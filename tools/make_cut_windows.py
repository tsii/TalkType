#!/usr/bin/env python3
"""Erzeugt die Kurzfenster-/Stille-/Rausch-Fixtures fuer die min_audio_s-Messung.

Aufruf:  .venv-asr/bin/python tools/make_cut_windows.py [--out testdata/cut]

Ergebnis (deterministisch, 16 kHz mono PCM16, exakt N Samples):
  <stem>_<dur>s.wav          Schnitt ab 0 s (Kopf des Materials)
  <stem>_t<off>_<dur>s.wav   Schnitt ab <off> s (mitten im Material)
  silence_<dur>s.wav          digitales Nullsignal
  noise_<dur>s.wav           weisses Rauschen, RMS = -50 dBFS (Mikrofon-Grundrauschen)

Die Messung selbst macht `tools/measure_min_audio.py` (Import von
`tools/reference_decode.py`, also derselbe korrigierte Decoder wie die Goldens).

Warum genau diese Fenster: `min_audio_s` ist die Schwelle, ab der die Pipeline
zum ersten Mal transkribiert — gemessen werden muss daher (a) wie viel Text ein
Fenster dieser Laenge ueberhaupt traegt, (b) ob ein Schnitt mitten im Material
schadet und (c) ob Stille/Rauschen dieser Laenge schon Halluzinationen erzeugt.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 16000
ROOT = Path(__file__).resolve().parent.parent

# (Quelle, Laenge in s) — jedes Fenster ab 0 s
HEAD_WINDOWS = [1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 6.0]
SOURCES = ["de_smoke", "de_pause"]
# Fenster, die mitten im Material beginnen (realistischer Streaming-Fall:
# der Chunk faengt nicht am Satzanfang an). Offset so gewaehlt, dass ein
# Wortanfang bzw. Satzanfang enthalten ist.
MID_WINDOWS = [("de_smoke", 2.0, [1.0, 2.0, 3.0]), ("de_pause", 6.0, [1.0, 2.0, 3.0])]
# Stille/Rauschen: hier zaehlt nur, ob Text erfunden wird (Halluzination).
# 0.3 s ist die in ASR_CONTRACT §4.3 behauptete Halluzinationslaenge, die
# uebrigen Werte liegen um die zu entscheidende Schwelle.
PROBE_DURATIONS = [0.3, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0]
# Zwei Rauschpegel: -50 dBFS (sehr leise, reines Grundrauschen) und -30 dBFS
# (laute Umgebung / Handmikrofon ohne Sprechsignal).
NOISE_RMS_DBFS = [-50.0, -30.0]
NOISE_SEED = 20261027


def load(path: Path) -> np.ndarray:
    data, sr = sf.read(str(path), dtype="float32", always_2d=False)
    if sr != SR:
        raise SystemExit(f"{path}: {sr} Hz, erwartet {SR} Hz")
    if data.ndim > 1:
        data = data.mean(axis=1)
    return np.ascontiguousarray(data, dtype=np.float32)


def write(path: Path, audio: np.ndarray) -> None:
    sf.write(str(path), np.clip(audio, -1.0, 1.0), SR, subtype="PCM_16")


def cut(audio: np.ndarray, offset_s: float, dur_s: float) -> np.ndarray:
    a = round(offset_s * SR)
    n = round(dur_s * SR)
    if a + n > audio.shape[0]:
        raise SystemExit(f"Fenster {offset_s}s+{dur_s}s passt nicht in {audio.shape[0] / SR:.3f}s")
    return np.ascontiguousarray(audio[a:a + n])


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--out", default=str(ROOT / "testdata" / "cut"))
    p.add_argument("--testdata", default=str(ROOT / "testdata"))
    args = p.parse_args(argv)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    srcs = {name: load(Path(args.testdata) / f"{name}.wav") for name in SOURCES}

    manifest: list[dict] = []

    for name, audio in srcs.items():
        for dur in HEAD_WINDOWS:
            win = cut(audio, 0.0, dur)
            fn = out / f"{name}_{dur:.1f}s.wav"
            write(fn, win)
            manifest.append({"file": fn.name, "source": name, "offset_s": 0.0,
                             "seconds": dur, "samples": int(win.shape[0]), "kind": "head"})

    for name, off, durs in MID_WINDOWS:
        for dur in durs:
            win = cut(srcs[name], off, dur)
            fn = out / f"{name}_t{off:.1f}_{dur:.1f}s.wav"
            write(fn, win)
            manifest.append({"file": fn.name, "source": name, "offset_s": off,
                             "seconds": dur, "samples": int(win.shape[0]), "kind": "mid"})

    for dur in PROBE_DURATIONS:
        n = round(dur * SR)
        fn = out / f"silence_{dur:.1f}s.wav"
        write(fn, np.zeros(n, dtype=np.float32))
        manifest.append({"file": fn.name, "source": "silence", "offset_s": 0.0,
                         "seconds": dur, "samples": n, "kind": "silence"})
        # (Rauschdateien entstehen direkt danach, s. Schleife unten.)
        for level in NOISE_RMS_DBFS:
            # gleicher Seed pro Laenge/Pegel -> reproduzierbar
            rng = np.random.default_rng(NOISE_SEED)
            noise = rng.standard_normal(n).astype(np.float32)
            rms = float(np.sqrt(np.mean(noise ** 2)))
            noise *= (10.0 ** (level / 20.0)) / rms
            fn = out / f"noise{int(abs(level))}dB_{dur:.1f}s.wav"
            write(fn, noise)
            manifest.append({"file": fn.name, "source": "noise", "offset_s": 0.0,
                             "seconds": dur, "samples": n, "kind": "noise",
                             "noise_rms_dbfs": level})

    (out / "manifest.json").write_text(
        json.dumps({"sr": SR, "noise_rms_dbfs": NOISE_RMS_DBFS, "noise_seed": NOISE_SEED,
                    "items": manifest}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    print(f"{len(manifest)} Fixtures in {out} geschrieben")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
