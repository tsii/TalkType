#!/usr/bin/env python3
"""I3 (PLAN_V0130 6.3 + Fable 4): cost curve of the whole_buffer decode.

Measures decode_ms (mel+encoder+TDT decode, how the app's partial path
decodes the COMPLETE buffer) for buffer lengths T = 5..300 s (mandatory)
plus 900 s (goal, only if the mandatory runs take < 10 min). Audio is
looped from testdata/hold/hold_korpus_180.wav + de_part1/2.wav and cut to
T s (rule 9: audio contents are NEVER printed, only numbers).

Variant: encoder-model.int8.onnx + decoder_joint-model.int8.onnx (int8) +
nemo128.onnx (fp32 mel) — identical to the APK export (MODEL_SHA256-pinned);
pattern: tools/reference_decode.py (TdtReference is imported, no code
duplicated).

Decision rule (PLAN 6.3): alpha = slope of decode_ms per audio second
(fit through the origin), W = 1500 ms / alpha (longest window with
decode <= 1.5 s). Background: the discrepancy C2 export 583 ms @ 11.9 s
(~49 ms/s, device) vs. user measurement 4102 ms @ ~8 s (~512 ms/s) — here:
the same variant on the host; document the deviation, never guess.

Usage: .venv-asr/bin/python tools/engine_cost_curve.py [--threads N]
Output: reports/engine_cost_curve.csv (T,decode_ms_med,rss_delta_mb) +
console lines alpha_ms_per_audio_s= / W_sek= / extrapolation 600/900 s.
"""
from __future__ import annotations

import argparse
import csv
import statistics
import sys
import time
from pathlib import Path

import numpy as np

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))
from reference_decode import DEFAULT_MODELS, TdtReference, load_waveform  # noqa: E402

SR = 16_000
TS_MANDATORY = [5, 10, 20, 30, 60, 90, 120, 180, 240, 300]
TS_GOAL = [900]
CSV_PATH = REPO / "reports" / "engine_cost_curve.csv"
POOL_FILES = [
    REPO / "testdata" / "hold" / "hold_korpus_180.wav",
    REPO / "testdata" / "de_part1.wav",
    REPO / "testdata" / "de_part2.wav",
]


def build_audio(target_s: int, pool: list[np.ndarray]) -> np.ndarray:
    """Loop T s of audio from the pool and cut it exactly (16 kHz mono)."""
    need = target_s * SR
    out = np.empty(need, dtype=np.float32)
    pos, i = 0, 0
    while pos < need:
        a = pool[i % len(pool)]
        i += 1
        take = min(len(a), need - pos)
        out[pos:pos + take] = a[:take]
        pos += take
    return out


def vmrss_mb() -> float:
    for line in Path("/proc/self/status").read_text().splitlines():
        if line.startswith("VmRSS:"):
            return int(line.split()[1]) / 1024.0
    return 0.0


def measure(ref: TdtReference, t_s: int, pool: list[np.ndarray], runs: int = 3) -> dict:
    audio = build_audio(t_s, pool)
    rss_before = vmrss_mb()
    decode_ms = []
    for _ in range(runs):
        r = ref.run(audio)  # text is ignored (rule 9)
        decode_ms.append((r["t_mel"] + r["t_enc"] + r["t_dec"]) * 1000.0)
    rss_after = vmrss_mb()
    del audio
    return {
        "t": t_s,
        "decode_ms_med": statistics.median(decode_ms),
        "decode_ms_runs": decode_ms,
        "rss_delta_mb": rss_after - rss_before,
    }


def main() -> None:
    p = argparse.ArgumentParser(description="whole_buffer decode cost curve (I3)")
    p.add_argument("--threads", type=int,
                   default=min(8, len(__import__("os").sched_getaffinity(0))),
                   help="intra_op_num_threads (default: min(8, cores))")
    p.add_argument("--models", default=str(DEFAULT_MODELS))
    p.add_argument("--runs", type=int, default=3, help="median over N runs per T")
    p.add_argument("--skip-goal", action="store_true",
                   help="skip the 900-s goal run (after host OOM: the 900-s buffer "
                        "needed >12 GB RSS and was killed by the container OOM)")
    args = p.parse_args()

    print("# variant: int8 encoder + int8 decoder + fp32 mel "
          "(encoder-model.int8.onnx / decoder_joint-model.int8.onnx / nemo128.onnx) — "
          "identical to the APK export", flush=True)
    print(f"# threads={args.threads} runs/T={args.runs} "
          f"ort={__import__('onnxruntime').__version__}", flush=True)

    ref = TdtReference(Path(args.models), args.threads)
    pool = [load_waveform(f) for f in POOL_FILES]
    print(f"# audio pool: {[f.name for f in POOL_FILES]} "
          f"(loop->cut to T s)", flush=True)

    t0 = time.perf_counter()
    results = []
    for t_s in TS_MANDATORY:
        r = measure(ref, t_s, pool, args.runs)
        results.append(r)
        runs = ",".join(f"{x:.0f}" for x in r["decode_ms_runs"])
        print(f"T={r['t']:>4}s decode_ms_med={r['decode_ms_med']:8.1f} "
              f"runs=[{runs}] rss_delta_mb={r['rss_delta_mb']:+7.1f} "
              f"elapsed={time.perf_counter() - t0:7.1f}s", flush=True)

    # the 900-s goal only if the mandatory runs stayed under 10 min and
    # --skip-goal is not set (host finding 2026-11-01: 900-s buffer => OOM at
    # ~12.2 GB anon RSS, container OOM kill — documented as a measurement point).
    if args.skip_goal:
        print("# 900-s goal skipped (--skip-goal; earlier run: "
              "OOM kill at ~12.2 GB RSS)", flush=True)
    elif time.perf_counter() - t0 < 600.0:
        for t_s in TS_GOAL:
            r = measure(ref, t_s, pool, args.runs)
            results.append(r)
            runs = ",".join(f"{x:.0f}" for x in r["decode_ms_runs"])
            print(f"T={r['t']:>4}s decode_ms_med={r['decode_ms_med']:8.1f} "
                  f"runs=[{runs}] rss_delta_mb={r['rss_delta_mb']:+7.1f} "
                  f"elapsed={time.perf_counter() - t0:7.1f}s (goal)", flush=True)
    elif time.perf_counter() - t0 >= 600.0:
        print("# 900-s goal skipped (mandatory runs >= 10 min)", flush=True)

    # slope: fit through the origin (decode(0)=0 is by construction).
    xs = np.array([r["t"] for r in results], dtype=np.float64)
    ys = np.array([r["decode_ms_med"] for r in results], dtype=np.float64)
    alpha = float((xs * ys).sum() / (xs * xs).sum())
    w_sek = 1500.0 / alpha if alpha > 0 else float("inf")
    max_ok = max((r["t"] for r in results if r["decode_ms_med"] <= 1500.0),
                 default=0)

    CSV_PATH.parent.mkdir(parents=True, exist_ok=True)
    with CSV_PATH.open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["T", "decode_ms_med", "rss_delta_mb"])
        for r in results:
            w.writerow([r["t"], f"{r['decode_ms_med']:.1f}",
                        f"{r['rss_delta_mb']:.1f}"])
    print(f"# csv: {CSV_PATH}", flush=True)

    print(f"alpha_ms_per_audio_s={alpha:.1f}", flush=True)
    print(f"W_sek={w_sek:.1f}", flush=True)
    print(f"# largest MEASURED T with decode<=1500ms: {max_ok}s", flush=True)
    print(f"extrapolation_decode_600s_s={alpha * 600 / 1000.0:.1f}", flush=True)
    print(f"extrapolation_decode_900s_s={alpha * 900 / 1000.0:.1f}", flush=True)
    # discrepancy reference points (numbers only):
    print("# reference: device C2 export ~49 ms/s (583 ms @ 11.9 s); "
          "user measurement ~512 ms/s (4102 ms @ ~8 s)", flush=True)


if __name__ == "__main__":
    main()
