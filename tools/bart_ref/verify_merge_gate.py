#!/usr/bin/env python3
"""T24a merge gate: prove the FUSED int8 pipeline is behavior-identical to the
UNFUSED int8 pipeline (U2-GATE goldens, same 20 sentences per language).

Gate criteria (both must PASS, per language):
  1. Word identity fused vs unfused = 100 % — greedy token-id sequences and
     decoded texts identical for all 20 goldens (a merge that only dedups
     byte-identical initializers cannot change behavior; this proves it).
  2. ORT logit-path parity fused vs unfused <= 1e-4 rel (encoder hidden,
     decoder_init logits, one decoder_step logits per golden; expected 0.0 —
     the weights are byte-identical by construction, verified per tensor in
     merge_shared_initializers.py).

Also measures the fused-pipeline RTF on the 20-word region (context only).

Output: reports/bart_u2_merge_{lang}.json (+ stdout summary, exit 1 on FAIL).

Usage:
  .venv-asr/bin/python tools/bart_ref/verify_merge_gate.py --lang en
  .venv-asr/bin/python tools/bart_ref/verify_merge_gate.py --lang de
"""
import argparse
import json
import os
import statistics
import sys
import time
from pathlib import Path

import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_bart_ref import OnnxRunner, load_goldens, rel_err

ROOT = Path("/home/dev/models/bart-export")
REPO = Path("/home/dev/Voice-Keyboard")


def graph_files(d: Path, fused: bool):
    names = ("encoder", "decoder_init", "decoder_step")
    if fused:
        return {n: (d / f"{n}.int8.onnx") for n in names} | \
               {"shared_weights.bin": d / "shared" / "shared_weights.bin"}
    return {n: (d / n / f"{n}.int8.onnx") for n in names}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True, choices=["de", "en"])
    ap.add_argument("--threads", type=int, default=min(16, os.cpu_count() or 8))
    ap.add_argument("--runs", type=int, default=20)
    args = ap.parse_args()
    lang = args.lang

    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(str(ROOT / lang / "tokenizer"))
    meta = json.loads((ROOT / lang / "tokenizer" / "vocab_meta.json").read_text())
    prefix = meta["input_prefix"]

    unfused = OnnxRunner(lang, "int8", args.threads)                    # ROOT/lang
    fused = OnnxRunner(lang, "int8", args.threads,
                       model_dir=str(ROOT / f"{lang}-merged"))          # merged dir

    goldens = load_goldens(lang)
    results, gate_ok = [], True
    max_enc = max_init = max_step = 0.0
    for g in goldens:
        ids = tok(prefix + g["input"], return_tensors="pt")["input_ids"].numpy()
        out_unfused = unfused.greedy(ids)
        out_fused = fused.greedy(ids)
        text_u = tok.decode(out_unfused, skip_special_tokens=True)
        text_f = tok.decode(out_fused, skip_special_tokens=True)
        same_ids = out_unfused == out_fused
        same_text = text_u == text_f
        # logit-path parity fused vs unfused (probe: enc hidden, init, step 1)
        eh_u, il_u, sl_u = unfused.logits_probe(ids)
        eh_f, il_f, sl_f = fused.logits_probe(ids)
        max_enc = max(max_enc, rel_err(eh_f, eh_u))
        max_init = max(max_init, rel_err(il_f, il_u))
        max_step = max(max_step, rel_err(sl_f, sl_u))
        # word identity fused vs unfused (words of the corrected output)
        w_u, w_f = text_u.split(), text_f.split()
        n = max(len(w_u), len(w_f))
        hits = sum(1 for a, b in zip(w_u, w_f) if a == b) if len(w_u) == len(w_f) else 0
        results.append({
            "input": g["input"], "class": g["class"],
            "unfused_ids": out_unfused, "fused_ids": out_fused,
            "same_token_ids": same_ids, "same_text": same_text,
            "unfused_text": text_u, "fused_text": text_f,
            "word_hits": hits, "word_total": n,
        })
        gate_ok = gate_ok and same_ids and same_text
        if not (same_ids and same_text):
            print(f"MISMATCH {g['input']!r}: unfused={text_u!r} fused={text_f!r}")

    word_hits = sum(r["word_hits"] for r in results)
    word_total = sum(r["word_total"] for r in results)
    word_identity = word_hits / word_total if word_total else 1.0

    # RTF on the 20-word region (context only, fused pipeline)
    region = {"de": ("correct: ich wollte dir noch schreiben dass wir morgen "
                     "früh um sieben am Hauptbahnhof treffen und dann zusammen "
                     "mit dem Zug nach Leipzig fahren weil dort das erste "
                     "Konzert schon um neun beginnt"),
              "en": ("i wanted to let you know that we are meeting tomorrow "
                     "morning at seven at the central station and then taking "
                     "the train to boston together because the first concert "
                     "starts already at nine")} [lang]
    ids = tok(region, return_tensors="pt")["input_ids"].numpy()
    fused.greedy(ids)  # warmup
    times = []
    for _ in range(args.runs):
        t0 = time.perf_counter()
        fused.greedy(ids)
        times.append(time.perf_counter() - t0)
    median = statistics.median(times)

    sizes = {name: p.stat().st_size
            for name, p in graph_files(ROOT / f"{lang}-merged", fused=True).items()}
    total_fused = sum(sizes.values())
    total_unfused = sum(p.stat().st_size for p in graph_files(ROOT / lang, False).values())

    logit_gate = max(max_enc, max_init, max_step) <= 1e-4
    word_gate = (word_identity == 1.0) and gate_ok
    report = {
        "lang": lang, "ort": ort.__version__, "threads": args.threads,
        "goldens": len(results),
        "gate_word_identity_fused_vs_unfused": round(word_identity, 4),
        "gate_identical_token_ids": sum(r["same_token_ids"] for r in results),
        "gate_identical_texts": sum(r["same_text"] for r in results),
        "logit_parity_fused_vs_unfused_rel": {
            "encoder": max_enc, "decoder_init": max_init, "decoder_step": max_step},
        "logit_gate_pass": logit_gate, "word_gate_pass": word_gate,
        "fused_rtf_20word_region_s_median": round(median, 4),
        "rtf": round(median / 7.0, 4),
        "sizes_bytes": sizes,
        "fused_total_mb": round(total_fused / 1e6, 1),
        "unfused_total_mb": round(total_unfused / 1e6, 1),
        "sentences": results,
    }
    out = REPO / "reports" / f"bart_u2_merge_{lang}.json"
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "sentences"},
                     indent=2, ensure_ascii=False))
    print(f"wrote {out}")
    if not (logit_gate and word_gate):
        print("MERGE GATE FAILED")
        sys.exit(1)
    print("MERGE GATE PASSED (word identity 100%, logits identical)")


if __name__ == "__main__":
    main()
