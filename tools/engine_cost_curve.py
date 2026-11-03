#!/usr/bin/env python3
"""I3 (PLAN_V0130 6.3 + Fable 4): Kostenkurve des whole_buffer-Decodes.

Misst decode_ms (Mel+Encoder+TDT-Decode, wie der Partial-Pfad der App den
VOLLSTANDIGEN Puffer dekodiert) fuer Pufferlaengen T = 5..300 s (Pflicht)
plus 900 s (Ziel, nur wenn die Pflichtlaeufe < 10 min dauern). Audio wird
aus testdata/hold/hold_korpus_180.wav + de_part1/2.wav gelooped und auf T s
zugeschnitten (Regel 9: Audio-Inhalte werden NIE ausgegeben, nur Zahlen).

Variante: encoder-model.int8.onnx + decoder_joint-model.int8.onnx (int8) +
nemo128.onnx (fp32-Mel) — identisch zum APK-Export (MODEL_SHA256-gepinnt);
Muster: tools/reference_decode.py (TdtReference wird importiert, kein Code
dupliziert).

Entscheidungsregel (PLAN 6.3): alpha = Steigung decode_ms pro Audio-Sekunde
(Fit durch den Ursprung), W = 1500 ms / alpha (laengstes Fenster mit
decode <= 1,5 s). Klargrund: Diskrepanz C2-Export 583 ms @ 11,9 s
(~49 ms/s, Geraet) vs. Nutzer-Messung 4102 ms @ ~8 s (~512 ms/s) — hier:
gleiche Variante auf dem Host; Abweichung dokumentieren, nie raten.

Aufruf: .venv-asr/bin/python tools/engine_cost_curve.py [--threads N]
Ausgabe: reports/engine_cost_curve.csv (T,decode_ms_med,rss_delta_mb) +
Konsolenzeilen alpha_ms_per_audio_s= / W_sek= / Extrapolation 600/900 s.
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
    """T s Audio aus dem Pool loopen und exakt zuschneiden (16 kHz mono)."""
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
        r = ref.run(audio)  # Text wird ignoriert (Regel 9)
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
    p = argparse.ArgumentParser(description="whole_buffer-Decode-Kostenkurve (I3)")
    p.add_argument("--threads", type=int,
                   default=min(8, len(__import__("os").sched_getaffinity(0))),
                   help="intra_op_num_threads (Default: min(8, Kerne))")
    p.add_argument("--models", default=str(DEFAULT_MODELS))
    p.add_argument("--runs", type=int, default=3, help="Median ueber N Laeufe je T")
    p.add_argument("--skip-goal", action="store_true",
                   help="900-s-Ziellauf auslassen (nach Host-OOM: 900-s-Puffer "
                        "brauchte >12 GB RSS und wurde vom Container-OOM gekillt)")
    args = p.parse_args()

    print("# Variante: int8-Encoder + int8-Decoder + fp32-Mel "
          "(encoder-model.int8.onnx / decoder_joint-model.int8.onnx / nemo128.onnx) — "
          "identisch zum APK-Export", flush=True)
    print(f"# threads={args.threads} runs/T={args.runs} "
          f"ort={__import__('onnxruntime').__version__}", flush=True)

    ref = TdtReference(Path(args.models), args.threads)
    pool = [load_waveform(f) for f in POOL_FILES]
    print(f"# audio pool: {[f.name for f in POOL_FILES]} "
          f"(loop->T s zugeschnitten)", flush=True)

    t0 = time.perf_counter()
    results = []
    for t_s in TS_MANDATORY:
        r = measure(ref, t_s, pool, args.runs)
        results.append(r)
        runs = ",".join(f"{x:.0f}" for x in r["decode_ms_runs"])
        print(f"T={r['t']:>4}s decode_ms_med={r['decode_ms_med']:8.1f} "
              f"runs=[{runs}] rss_delta_mb={r['rss_delta_mb']:+7.1f} "
              f"elapsed={time.perf_counter() - t0:7.1f}s", flush=True)

    # Ziel 900 s nur, wenn die Pflichtlaeufe unter 10 min blieben und nicht
    # --skip-goal gesetzt ist (Host-Befund 2026-11-01: 900-s-Puffer => OOM bei
    # ~12,2 GB anon-RSS, Container-OOM-Kill — als Messpunkt dokumentiert).
    if args.skip_goal:
        print("# Ziel 900 s uebersprungen (--skip-goal; frueherer Lauf: "
              "OOM-Kill bei ~12,2 GB RSS)", flush=True)
    elif time.perf_counter() - t0 < 600.0:
        for t_s in TS_GOAL:
            r = measure(ref, t_s, pool, args.runs)
            results.append(r)
            runs = ",".join(f"{x:.0f}" for x in r["decode_ms_runs"])
            print(f"T={r['t']:>4}s decode_ms_med={r['decode_ms_med']:8.1f} "
                  f"runs=[{runs}] rss_delta_mb={r['rss_delta_mb']:+7.1f} "
                  f"elapsed={time.perf_counter() - t0:7.1f}s (Ziel)", flush=True)
    elif time.perf_counter() - t0 >= 600.0:
        print("# Ziel 900 s uebersprungen (Pflichtlaeufe >= 10 min)", flush=True)

    # Steigung: Fit durch den Ursprung (decode(0)=0 ist konstruiert).
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
    print(f"# groesstes GEMESSENES T mit decode<=1500ms: {max_ok}s", flush=True)
    print(f"extrapolation_decode_600s_s={alpha * 600 / 1000.0:.1f}", flush=True)
    print(f"extrapolation_decode_900s_s={alpha * 900 / 1000.0:.1f}", flush=True)
    # Diskrepanz-Referenzpunkte (nur Zahlen):
    print("# referenz: geraet C2-Export ~49 ms/s (583 ms @ 11,9 s); "
          "nutzermessung ~512 ms/s (4102 ms @ ~8 s)", flush=True)


if __name__ == "__main__":
    main()
