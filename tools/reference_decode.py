#!/usr/bin/env python3
"""Reference decoder for parakeet-tdt-0.6b-v3 (ONNX int8) — golden test generator.

Purpose: prove, independently of the Android implementation, how the TDT
 greedy decode must look for the pinned export, and produce golden text for
test WAVs.  Usage:  .venv-asr/bin/python tools/reference_decode.py [audio.wav]

Verified tensor contract (measured 2026-10-27, onnxruntime 1.31.1):
  nemo128.onnx
    waveforms      float32 [B, N]        (16 kHz raw, [-1,1])
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
    -> outputs          float32 [B, T, U, 8198]   (T = encoder frames, U = labels,
                                                   here always [B, 1, 1, 8198])
    -> prednet_lengths  int32   [B]
    -> output_states_1  float32 [2, B, 640]
    -> output_states_2  float32 [2, B, 640]

8198 = 8193 vocabulary entries (0..8192, <blk> = 8192) + 5 duration classes {0,1,2,3,4}.

CORRECTIONS versus the first version (2026-10-27, review):
1. BUG: the PredNet states were adopted at *every* step, including blanks.
   Reference (istupakov/onnx-asr, `_AsrWithTransducerDecoding._decoding`,
   and NeMo TDT greedy) is: the state is advanced ONLY on an emitted non-blank;
   on blank the same state is fed again.  Impact measurable: de_pause lost
   "nach", de_part1 broke apart into "ersteil ... Dikt Arts".
   Now reference-conformant.
2. BUG: MAX_SYMBOLS_PER_FRAME was implemented as a global limit (10 * frames
   tokens) and only checked in the dur==0 branch -> up to 10*frames symbols
   could arise per frame.  Now a real counter per encoder frame; on reaching
   it a forced advance of +1 frame (identical to the reference).
3. BUG: special tokens were not filtered; <unk>/<|nospeech|>/<|pnc|> etc.
   would have landed as plain text in the result.  Now filtered via the
   token pattern (see is_special) — NOT via "id < N", because the digits
   "0".."9" as IDs 234..243 lie right among the special tokens.
4. Robustness: waveform/SR check without `assert` (asserts disappear under
   `python -O`), error messages instead of tracebacks, protection against
   empty audio,
   NaN/Inf, very long audio (> --max-seconds), encoded_lengths > tensor width
   and infinite loops; models relative to the repo (not to the CWD); threads
   configurable; detok exactly like the reference (regex instead of only strip()).
   The detok rule changes nothing for the existing golden WAVs (checked).

Reference greedy (1 frame per step, "t" = encoder frame index):
    state1 = state2 = 0; last = <blk>; t = 0; emitted_this_frame = 0
    while t < frames:
        logits, s1', s2' = dec(enc[:, :, t], last, state1, state2)
        token = argmax(logits[0:T:0, 0:U:0, 0:VOCAB_SIZE])   # 8193
        dur   = argmax(logits[...][VOCAB_SIZE:])             # 5
        if token != <blk>:
            state1, state2 = s1', s2'      # NUR hier
            emit(token); last = token; emitted_this_frame += 1
        if dur > 0:                       # also applies on blank (silence skip)
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
VOCAB_SIZE = 8193          # 0..8192, incl. <blk>
NUM_DURATIONS = 5
MAX_SYMBOLS_PER_FRAME = 10  # == max_tokens_per_step (config.json does not set it -> default 10)

# special tokens are "<...>" strings (0..233, 244..273) plus <blk>; the digits
# "0".."9" (IDs 234..243) are regular vocabulary and must NOT be filtered.
SPECIAL_TOKEN_RE = re.compile(r"^<[^<>]*>$")
# detokenization like onnx-asr (`_AsrWithDecoding.DECODE_SPACE_PATTERN`): remove
# leading spaces, spaces before punctuation and before the word end.
SPACE_RE = re.compile(r"\A\s|\s\B|(\s)\b")

DEFAULT_MODELS = Path(__file__).resolve().parent.parent / ".models"
DEFAULT_AUDIO = Path(__file__).resolve().parent.parent / "testdata" / "de_smoke.wav"


def die(msg: str, code: int = 2) -> None:
    print(f"reference_decode: {msg}", file=sys.stderr)
    raise SystemExit(code)


def load_vocab(path: Path) -> dict[int, str]:
    if not path.is_file():
        die(f"vocab.txt not found: {path} (specify --models)")
    table: dict[int, str] = {}
    with path.open(encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            if not line:
                continue
            tok, _, idx = line.rpartition(" ")
            # Format: "<token> <id>"; the token can be non-empty strings like "<|de|>"
            try:
                table[int(idx)] = tok
            except ValueError:
                die(f"vocab.txt line {lineno} unreadable: {line!r}")
    if BLANK_ID not in table:
        die(f"vocab.txt without <blk> (id {BLANK_ID}) — wrong vocabulary?")
    if len(table) != VOCAB_SIZE:
        print(
            f"reference_decode: WARNING vocab size {len(table)} != {VOCAB_SIZE}",
            file=sys.stderr,
        )
    return table


def is_special(token: str) -> bool:
    return SPECIAL_TOKEN_RE.match(token) is not None


def load_waveform(path: Path, expected_sr: int = 16000) -> np.ndarray:
    if not path.is_file():
        die(f"audio not found: {path}")
    try:
        data, sr = sf.read(str(path), dtype="float32", always_2d=False)
    except (RuntimeError, OSError, ValueError) as exc:  # soundfile/LibsndfileError, IO error
        die(f"audio unreadable: {path}: {exc}")
    if data.size == 0:
        die(f"audio is empty: {path}")
    audio = data.mean(axis=1) if data.ndim > 1 else data
    audio = np.asarray(audio, dtype=np.float32).ravel()
    if sr != expected_sr:
        die(f"{path}: {sr} Hz, expected {expected_sr} Hz "
            f"(e. g. ffmpeg -i in -ar 16000 -ac 1 out.wav)")
    if not np.all(np.isfinite(audio)):
        die(f"{path}: non-finite samples (NaN/Inf) in the audio")
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
            die(f"model missing: {path} (see docs/TECH_FACTS.md §2)")
        return ort.InferenceSession(str(path), self._so, providers=["CPUExecutionProvider"])

    def detokenize(self, ids: list[int]) -> str:
        # filter special tokens before the ▁ replacement (their name contains no ▁,
        # but the check should happen on the original token).
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

        # encoded_lengths can exceed the tensor width for some exports/precisions
        # -> clamp, otherwise empty slice (invalid rank).
        frames = max(0, min(int(enc_len[0]), int(enc_out.shape[2])))
        if frames == 0:
            return {"tokens": [], "times": [], "steps": 0, "frames": 0, "mel_frames": int(feats.shape[2]),
                    "t_mel": t_mel, "t_enc": t_enc, "t_dec": 0.0, "text": "", "truncated": False}

        state1 = np.zeros((2, 1, 640), dtype=np.float32)
        state2 = np.zeros((2, 1, 640), dtype=np.float32)
        last_label = BLANK_ID          # TDT starts with <blk>
        tokens: list[int] = []
        times: list[int] = []

        t, steps, emitted = 0, 0, 0
        guard = 4 * frames * (max_symbols_per_frame + 1) + 1000  # theoretical upper bound
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
                # Reference: advance the PredNet state ONLY after a non-blank.
                state1, state2 = new1, new2
                tokens.append(token)
                times.append(t)
                last_label = token
                emitted += 1

            if dur > 0:
                # also applies on blank: durations are meaning-bearing for silence
                # here too (skip); t += 1 on blank yields measurably
                # worse texts (see docs/ASR_CONTRACT.md §3).
                t += dur
                emitted = 0
            elif token == BLANK_ID or emitted >= max_symbols_per_frame:
                t += 1                                  # blank resp. symbol budget: always progress
                emitted = 0

            if steps > guard:
                truncated = True
                print(f"reference_decode: WARNING decode guard after {steps} steps "
                      f"(frames={frames}) — result incomplete", file=sys.stderr)
                break
        t_dec = time.perf_counter() - t0

        return {
            "tokens": tokens, "times": times, "steps": steps, "frames": frames,
            "mel_frames": int(feats.shape[2]), "t_mel": t_mel, "t_enc": t_enc,
            "t_dec": t_dec, "text": self.detokenize(tokens), "truncated": truncated,
        }


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="TDT reference decoder (golden test generator)")
    p.add_argument("audio", nargs="?", default=str(DEFAULT_AUDIO), help="16 kHz mono WAV")
    p.add_argument("--models", default=str(DEFAULT_MODELS), help="directory with ONNX+vocab.txt")
    p.add_argument("--threads", type=int, default=min(8, len(__import__("os").sched_getaffinity(0))),
                   help="intra_op_num_threads (default: min(8, available cores))")
    p.add_argument("--max-symbols-per-frame", type=int, default=MAX_SYMBOLS_PER_FRAME,
                   help="max_tokens_per_step of the export (default 10)")
    p.add_argument("--max-seconds", type=float, default=600.0,
                   help="hard upper bound for the audio length (default 600 s)")
    p.add_argument("--json", action="store_true", help="machine-readable output incl. timestamps")
    return p.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    path = Path(args.audio)
    audio = load_waveform(path)
    dur_s = audio.shape[0] / 16000.0
    if dur_s > args.max_seconds:
        die(f"audio {dur_s:.1f}s > --max-seconds {args.max_seconds:.0f}s; "
            f"the reference decoder deliberately works whole-buffer (see ASR_CONTRACT §4)")

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
