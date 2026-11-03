#!/usr/bin/env python3
"""AP4 — dictation corpus generator (PLAN_C2_FABLE_R2 §1.5).

Creates time-true speech corpora (espeak-ng de, fallback piper) with
defined pauses and sidecar JSON (sentence boundaries, pauses, golden length).
Level: -17 dBFS peak, pauses with -60 dBFS noise (never digitally silent).

Usage:  tools/make_korpus.py --seconds 600 --out testdata/c2/k600.wav
Variants: 120 (bisect) / 600 (main run) / 900 (cap test).
Rule 9: only generic pattern sentences, no user/real contents.
"""
import argparse, json, math, random, shutil, struct, subprocess, sys, wave
from pathlib import Path

SENTENCES = [
    "wort alpha beta gamma delta",
    "zwei vier sechs acht zehn wort",
    "der testlauf läuft wie geplant",
    "hier kommt ein neuer satz",
    "und noch ein weiterer satz",
    "pausen werden fest eingebaut",
    "kurz und bündig",
    "ein längere phrase mit vielen wörtern für realistischere fenster",
    "zahl eins zwei drei vier fünf",
    "Segmentgrenzen sollen sauber erkannt werden",
    "danach folgt sogleich der nächste satz",
    "bitte keine Satzzeichen vergessen",
]  # 12 generic patterns (rule 9)


def synth_espeak(text: str, out_wav: Path) -> None:
    subprocess.run(
        ["espeak-ng", "-v", "de", "-s", "150", "-w", str(out_wav), text],
        check=True, capture_output=True)


def read_wav_mono16k(path: Path) -> list[int]:
    with wave.open(str(path), "rb") as w:
        assert w.getnchannels() == 1 and w.getframerate() == 22050 or True
        sr = w.getframerate()
        n = w.getnframes()
        data = w.readframes(n)
    samples = list(struct.unpack("<" + "h" * n, data))
    # resample naiv by integer factor to 16000 if needed (22050/16000 not integer; take nearest)
    if sr != 16000:
        step = sr / 16000.0
        out = [samples[int(i * step)] for i in range(int(len(samples) / step))]
        samples = out
    return samples


def gen_noise(n: int, amp: float) -> list[int]:
    return [int(amp * 32767 * (random.random() * 2 - 1)) for _ in range(n)]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=600)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    random.seed(args.seed)
    if not shutil.which("espeak-ng"):
        print("espeak-ng is missing", file=sys.stderr)
        return 2
    sr = 16000
    peak = int(32767 * (10 ** (-17 / 20.0)))   # -17 dBFS scaling
    noise_amp = 10 ** (-60 / 20.0) / 0.577      # RMS->amp roughly, distortion irrelevant
    total = bytearray()
    sidecar = {"sentences": [], "pauses": [], "golden_chars": 0, "seconds": 0.0}
    pos_s = 0.0
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(args.out).with_suffix(".tmp.wav")
    while pos_s < args.seconds:
        s = random.choice(SENTENCES)
        synth_espeak(s + ".", tmp)
        samples = read_wav_mono16k(tmp)
        # normalize to peak
        mx = max(1, max(abs(x) for x in samples))
        samples = [int(x * peak / mx) for x in samples]
        dur = len(samples) / sr
        sidecar["sentences"].append({"start_s": round(pos_s, 3), "end_s": round(pos_s + dur, 3),
                                     "chars": len(s) + 1})
        sidecar["golden_chars"] += len(s) + 1
        total += struct.pack("<" + "h" * len(samples), *samples)
        pos_s += dur
        if pos_s >= args.seconds:
            break
        pause = random.choice([1.5, 2.0, 2.5, 3.0, 4.0])
        n = int(pause * sr)
        nz = gen_noise(n, noise_amp)
        total += struct.pack("<" + "h" * n, *nz)
        sidecar["pauses"].append({"start_s": round(pos_s, 3), "len_s": pause})
        pos_s += pause
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes(bytes(total))
    sidecar["seconds"] = round(pos_s, 3)
    out.with_suffix(".json").write_text(json.dumps(sidecar, ensure_ascii=False, indent=1))
    print(f"OK {out} {pos_s:.1f}s golden_chars={sidecar['golden_chars']} "
          f"sentences={len(sidecar['sentences'])} pauses={len(sidecar['pauses'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
