#!/usr/bin/env python3
"""Creates the short-window/silence/noise fixtures for the min_audio_s measurement.

Usage:  .venv-asr/bin/python tools/make_cut_windows.py [--out testdata/cut]

Result (deterministic, 16 kHz mono PCM16, exactly N samples):
  <stem>_<dur>s.wav          cut from 0 s (head of the material)
  <stem>_t<off>_<dur>s.wav   cut from <off> s (in the middle of the material)
  silence_<dur>s.wav         digital zero signal
  noise_<dur>s.wav           white noise, RMS = -50 dBFS (microphone noise floor)

The measurement itself is done by `tools/measure_min_audio.py` (import of
`tools/reference_decode.py`, i.e. the same corrected decoder as the goldens).

Why exactly these windows: `min_audio_s` is the threshold from which the pipeline
transcribes for the first time — so the measurement must determine (a) how much text
a window of this length carries at all, (b) whether a cut in the middle of the material
harms, and (c) whether silence/noise of this length already produces hallucinations.
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

# (source, length in s) — every window from 0 s
HEAD_WINDOWS = [1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 6.0]
SOURCES = ["de_smoke", "de_pause"]
# windows that start in the middle of the material (more realistic streaming case:
# the chunk does not start at the sentence start). Offset chosen so that a
# word start or sentence start is contained.
MID_WINDOWS = [("de_smoke", 2.0, [1.0, 2.0, 3.0]), ("de_pause", 6.0, [1.0, 2.0, 3.0])]
# silence/noise: here the only thing that matters is whether text is invented (hallucination).
# 0.3 s is the hallucination length asserted in ASR_CONTRACT §4.3, the
# remaining values lie around the threshold to be decided.
PROBE_DURATIONS = [0.3, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0]
# two noise levels: -50 dBFS (very quiet, pure noise floor) and -30 dBFS
# (loud environment / handheld microphone without a speech signal).
NOISE_RMS_DBFS = [-50.0, -30.0]
NOISE_SEED = 20261027


def load(path: Path) -> np.ndarray:
    data, sr = sf.read(str(path), dtype="float32", always_2d=False)
    if sr != SR:
        raise SystemExit(f"{path}: {sr} Hz, expected {SR} Hz")
    if data.ndim > 1:
        data = data.mean(axis=1)
    return np.ascontiguousarray(data, dtype=np.float32)


def write(path: Path, audio: np.ndarray) -> None:
    sf.write(str(path), np.clip(audio, -1.0, 1.0), SR, subtype="PCM_16")


def cut(audio: np.ndarray, offset_s: float, dur_s: float) -> np.ndarray:
    a = round(offset_s * SR)
    n = round(dur_s * SR)
    if a + n > audio.shape[0]:
        raise SystemExit(f"window {offset_s}s+{dur_s}s does not fit into {audio.shape[0] / SR:.3f}s")
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
        # (noise files are created directly afterwards, see the loop below.)
        for level in NOISE_RMS_DBFS:
            # same seed per length/level -> reproducible
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
    print(f"wrote {len(manifest)} fixtures to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
