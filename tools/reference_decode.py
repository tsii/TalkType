#!/usr/bin/env python3
"""Referenz-Decoder fuer parakeet-tdt-0.6b-v3 (ONNX int8) — Golden-Test-Erzeuger.

Zweck: unabhaengig von der Android-Implementierung beweisen, wie der TDT-Greedy-
Decode fuer den gepinnten Export auszusehen hat, und Golden-Text fuer Test-WAVs
erzeugen.  Aufruf:  .venv-asr/bin/python tools/reference_decode.py [audio.wav]

Verifizierter Tensorvertrag (gemessen 2026-10-27, onnxruntime 1.31.1):
  nemo128.onnx
    waveforms      float32 [B, N]        (16 kHz roh, [-1,1])
    waveforms_lens int64   [B]
    -> features      float32 [B, 128, T]
    -> features_lens int64   [B]
  encoder-model.int8.onnx
    audio_signal float32 [B, 128, T]
    length       int64   [B]
    -> outputs         float32 [B, 1024, T/8]
    -> encoded_lengths int64   [B]
  decoder_joint-model.int8.onnx
    encoder_outputs float32 [B, 1024, T]     (Nutzung: T = 1 Frame pro Schritt)
    targets         int32   [B, 1]
    target_length   int32   [B]
    input_states_1  float32 [2, B, 640]
    input_states_2  float32 [2, B, 640]
    -> outputs          float32 [B, T, U, 8198]   (T = Encoderframes, U = Labels,
                                                   hier immer [B, 1, 1, 8198])
    -> prednet_lengths  int32   [B]
    -> output_states_1  float32 [2, B, 640]
    -> output_states_2  float32 [2, B, 640]

8198 = 8193 Vokabeln (0..8192, <blk> = 8192) + 5 Durationklassen {0,1,2,3,4}.

KORREKTUREN gegenueber der ersten Fassung (2026-10-27, Review):
1. FEHLER: Die PredNet-States wurden bei *jedem* Schritt uebernommen, auch bei
   Blank.  Referenz (istupakov/onnx-asr, `_AsrWithTransducerDecoding._decoding`,
   und NeMo TDT-Greedy) ist: der State wird NUR bei einem emittierten Nicht-Blank
   fortgeschrieben; bei Blank wird derselbe State erneut gefuettert.  Auswirkung
   messbar: de_pause verlor "nach", de_part1 zerfiel in "ersteil ... Dikt Arts".
   Jetzt referenzkonform.
2. FEHLER: MAX_SYMBOLS_PER_FRAME war als globales Limit (10 * frames Tokens)
   implementiert und nur im dur==0-Zweig geprueft -> pro Frame konnten bis zu
   10*frames Symbole entstehen.  Jetzt echter Zaehler pro Encoderframe, bei
   Erreichen Zwangsvorschub +1 Frame (identisch zur Referenz).
3. FEHLER: Sondertokens wurden nicht gefiltert; <unk>/<|nospeech|>/<|pnc|> usw.
   waeren als Klartext im Ergebnis gelandet.  Jetzt gefiltert ueber das
   Token-Muster (siehe is_special) — NICHT ueber "id < N", weil die Ziffern
   "0".."9" als IDs 234..243 mitten zwischen den Sondertokens liegen.
4. Robustheit: Waveform-/SR-Pruefung ohne `assert` (asserts verschwinden unter
   `python -O`), Fehlermeldungen statt Tracebacks, Schutz gegen leeres Audio,
   NaN/Inf, sehr langes Audio (> --max-seconds), encoded_lengths > Tensorbreite
   und Endlosschleifen; Modelle relativ zum Repo (nicht zum CWD); Threads
   konfigurierbar; Detok exakt wie die Referenz (Regex statt nur strip()).
   Die Detok-Regel aendert fuer die vorhandenen Golden-WAVs nichts (geprueft).

Referenz-Greedy (1 Frame pro Schritt, "t" = Encoderframe-Index):
    state1 = state2 = 0; last = <blk>; t = 0; emitted_this_frame = 0
    while t < frames:
        logits, s1', s2' = dec(enc[:, :, t], last, state1, state2)
        token = argmax(logits[0:T:0, 0:U:0, 0:VOCAB_SIZE])   # 8193
        dur   = argmax(logits[...][VOCAB_SIZE:])             # 5
        if token != <blk>:
            state1, state2 = s1', s2'      # NUR hier
            emit(token); last = token; emitted_this_frame += 1
        if dur > 0:                       # gilt auch bei Blank (Silence-Skip)
            t += dur; emitted_this_frame = 0
        elif token == <blk> or emitted_this_frame >= MAX_SYMBOLS_PER_FRAME:
            t += 1; emitted_this_frame = 0
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf

BLANK_ID = 8192
VOCAB_SIZE = 8193          # 0..8192, inkl. <blk>
NUM_DURATIONS = 5
MAX_SYMBOLS_PER_FRAME = 10  # == max_tokens_per_step (config.json setzt ihn nicht -> Default 10)

# Sondertokens sind "<...>"-Strings (0..233, 244..273) plus <blk>; die Ziffern
# "0".."9" (IDs 234..243) sind regulaeres Vokabular und duerfen NICHT gefiltert werden.
SPECIAL_TOKEN_RE = re.compile(r"^<[^<>]*>$")
# Detokenisierung wie onnx-asr (`_AsrWithDecoding.DECODE_SPACE_PATTERN`): fuehrendes
# Leerzeichen, Leerzeichen vor Satzzeichen und vor dem Wortende entfernen.
SPACE_RE = re.compile(r"\A\s|\s\B|(\s)\b")

DEFAULT_MODELS = Path(__file__).resolve().parent.parent / ".models"
DEFAULT_AUDIO = Path(__file__).resolve().parent.parent / "testdata" / "de_smoke.wav"


def die(msg: str, code: int = 2) -> None:
    print(f"reference_decode: {msg}", file=sys.stderr)
    raise SystemExit(code)


def load_vocab(path: Path) -> dict[int, str]:
    if not path.is_file():
        die(f"vocab.txt nicht gefunden: {path} (--models angeben)")
    table: dict[int, str] = {}
    with path.open(encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            if not line:
                continue
            tok, _, idx = line.rpartition(" ")
            # Format: "<token> <id>"; Token kann nicht-leere Zeichenketten wie "<|de|>" sein
            try:
                table[int(idx)] = tok
            except ValueError:
                die(f"vocab.txt Zeile {lineno} unlesbar: {line!r}")
    if BLANK_ID not in table:
        die(f"vocab.txt ohne <blk> (id {BLANK_ID}) — falsches Vokabular?")
    if len(table) != VOCAB_SIZE:
        print(
            f"reference_decode: WARNUNG vocab-Groesse {len(table)} != {VOCAB_SIZE}",
            file=sys.stderr,
        )
    return table


def is_special(token: str) -> bool:
    return SPECIAL_TOKEN_RE.match(token) is not None


def load_waveform(path: Path, expected_sr: int = 16000) -> np.ndarray:
    if not path.is_file():
        die(f"Audio nicht gefunden: {path}")
    try:
        data, sr = sf.read(str(path), dtype="float32", always_2d=False)
    except (RuntimeError, OSError, ValueError) as exc:  # soundfile/LibsndfileError, IO-Fehler
        die(f"Audio nicht lesbar: {path}: {exc}")
    if data.size == 0:
        die(f"Audio ist leer: {path}")
    audio = data.mean(axis=1) if data.ndim > 1 else data
    audio = np.asarray(audio, dtype=np.float32).ravel()
    if sr != expected_sr:
        die(f"{path}: {sr} Hz, erwartet {expected_sr} Hz "
            f"(z. B. ffmpeg -i in -ar 16000 -ac 1 out.wav)")
    if not np.all(np.isfinite(audio)):
        die(f"{path}: nicht-finite Samples (NaN/Inf) im Audio")
    return audio


class TdtReference:
    def __init__(self, models: Path, threads: int) -> None:
        self.vocab = load_vocab(models / "vocab.txt")
        so = ort.SessionOptions()
        so.intra_op_num_threads = max(1, threads)
        so.inter_op_num_threads = 1
        so.log_severity_level = 3
        self._so = so
        self._models = models
        self.mel = self._session("nemo128.onnx")
        self.enc = self._session("encoder-model.int8.onnx")
        self.dec = self._session("decoder_joint-model.int8.onnx")

    def _session(self, name: str) -> ort.InferenceSession:
        path = self._models / name
        if not path.is_file():
            die(f"Modell fehlt: {path} (siehe docs/TECH_FACTS.md §2)")
        return ort.InferenceSession(str(path), self._so, providers=["CPUExecutionProvider"])

    def detokenize(self, ids: list[int]) -> str:
        # Sondertokens vor der ▁-Ersetzung ausfiltern (ihr Name enthaelt kein ▁,
        # aber die Pruefung soll am Original-Token erfolgen).
        tokens = [self.vocab[i] for i in ids if i in self.vocab and not is_special(self.vocab[i])]
        text = "".join(tokens).replace("\u2581", " ")
        return SPACE_RE.sub(lambda m: " " if m.group(1) else "", text).strip()

    def run(self, audio: np.ndarray, max_symbols_per_frame: int = MAX_SYMBOLS_PER_FRAME):
        t0 = time.perf_counter()
        feats, feat_len = self.mel.run(None, {
            "waveforms": audio[np.newaxis, :],
            "waveforms_lens": np.array([audio.shape[0]], dtype=np.int64),
        })
        t_mel = time.perf_counter() - t0

        t0 = time.perf_counter()
        enc_out, enc_len = self.enc.run(None, {"audio_signal": feats, "length": feat_len})
        t_enc = time.perf_counter() - t0

        # encoded_lengths kann bei manchen Exporten/Praezisionen die Tensorbreite
        # ueberschreiten -> clampen, sonst leerer Slice (Invalid rank).
        frames = max(0, min(int(enc_len[0]), int(enc_out.shape[2])))
        if frames == 0:
            return {"tokens": [], "times": [], "steps": 0, "frames": 0, "mel_frames": int(feats.shape[2]),
                    "t_mel": t_mel, "t_enc": t_enc, "t_dec": 0.0, "text": "", "truncated": False}

        state1 = np.zeros((2, 1, 640), dtype=np.float32)
        state2 = np.zeros((2, 1, 640), dtype=np.float32)
        last_label = BLANK_ID          # TDT startet mit <blk>
        tokens: list[int] = []
        times: list[int] = []

        t, steps, emitted = 0, 0, 0
        guard = 4 * frames * (max_symbols_per_frame + 1) + 1000  # theoretische Obergrenze
        truncated = False
        t0 = time.perf_counter()
        while t < frames:
            frame = np.ascontiguousarray(enc_out[:, :, t:t + 1])
            logits, _plen, new1, new2 = self.dec.run(None, {
                "encoder_outputs": frame,
                "targets": np.array([[last_label]], dtype=np.int32),
                "target_length": np.array([1], dtype=np.int32),
                "input_states_1": state1,
                "input_states_2": state2,
            })
            steps += 1
            flat = logits[0, 0, 0]                      # [8198]
            token = int(np.argmax(flat[:VOCAB_SIZE]))
            dur = int(np.argmax(flat[VOCAB_SIZE:VOCAB_SIZE + NUM_DURATIONS]))

            if token != BLANK_ID:
                # Reference: PredNet-State NUR nach Nicht-Blank fortschreiben.
                state1, state2 = new1, new2
                tokens.append(token)
                times.append(t)
                last_label = token
                emitted += 1

            if dur > 0:
                # gilt auch bei Blank: Durations sind hier auch fuer Silence
                # bedeutungstragend (Skip); t += 1 bei Blank liefert messbar
                # schlechtere Texte (siehe docs/ASR_CONTRACT.md §3).
                t += dur
                emitted = 0
            elif token == BLANK_ID or emitted >= max_symbols_per_frame:
                t += 1                                  # Blank bzw. Symbolbudget: immer Fortschritt
                emitted = 0

            if steps > guard:
                truncated = True
                print(f"reference_decode: WARNUNG Decode-Guard nach {steps} Schritten "
                      f"(frames={frames}) — Ergebnis unvollstaendig", file=sys.stderr)
                break
        t_dec = time.perf_counter() - t0

        return {
            "tokens": tokens, "times": times, "steps": steps, "frames": frames,
            "mel_frames": int(feats.shape[2]), "t_mel": t_mel, "t_enc": t_enc,
            "t_dec": t_dec, "text": self.detokenize(tokens), "truncated": truncated,
        }


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="TDT-Referenzdecoder (Golden-Test-Erzeuger)")
    p.add_argument("audio", nargs="?", default=str(DEFAULT_AUDIO), help="16-kHz-mono-WAV")
    p.add_argument("--models", default=str(DEFAULT_MODELS), help="Verzeichnis mit ONNX+vocab.txt")
    p.add_argument("--threads", type=int, default=min(8, len(__import__("os").sched_getaffinity(0))),
                   help="intra_op_num_threads (Default: min(8, verfuegbare Kerne))")
    p.add_argument("--max-symbols-per-frame", type=int, default=MAX_SYMBOLS_PER_FRAME,
                   help="max_tokens_per_step des Exports (Default 10)")
    p.add_argument("--max-seconds", type=float, default=600.0,
                   help="harte Obergrenze fuer die Audiolaenge (Default 600 s)")
    p.add_argument("--json", action="store_true", help="Maschinenlesbare Ausgabe inkl. Timestamps")
    return p.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    path = Path(args.audio)
    audio = load_waveform(path)
    dur_s = audio.shape[0] / 16000.0
    if dur_s > args.max_seconds:
        die(f"Audio {dur_s:.1f}s > --max-seconds {args.max_seconds:.0f}s; "
            f"Referenzdecoder arbeitet bewusst whole-buffer (siehe ASR_CONTRACT §4)")

    engine = TdtReference(Path(args.models), args.threads)
    res = engine.run(audio, args.max_symbols_per_frame)

    total = res["t_mel"] + res["t_enc"] + res["t_dec"]
    rtf = total / dur_s if dur_s else float("nan")
    if args.json:
        print(json.dumps({
            "audio": str(path), "audio_seconds": round(dur_s, 3),
            "frames": res["frames"], "mel_frames": res["mel_frames"], "steps": res["steps"],
            "mel_s": round(res["t_mel"], 3), "encoder_s": round(res["t_enc"], 3),
            "decoder_s": round(res["t_dec"], 3), "rtf": round(rtf, 4),
            "tokens": len(res["tokens"]), "truncated": res["truncated"],
            "times_s": [round(0.08 * t, 3) for t in res["times"]],
            "token_ids": res["tokens"], "text": res["text"],
        }, ensure_ascii=False))
    else:
        print(f"# audio={dur_s:.2f}s frames={res['frames']} steps={res['steps']} "
              f"mel={res['t_mel']:.2f}s enc={res['t_enc']:.2f}s dec={res['t_dec']:.2f}s rtf={rtf:.3f}")
        print(f"# tokens={len(res['tokens'])}")
        print(res["text"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
