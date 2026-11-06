#!/usr/bin/env python3
"""U2-GATE runner: parity + RTF evidence for the exported spelling-correction
models (de/en, ONNX int8 vs HuggingFace reference).

Measures per language (testdata/bart_goldens/{lang}.tsv, 20 sentences):
  1. HF reference generation, PINNED greedy GenerationConfig (identical to the
     ONNX runner contract) + HF pipeline defaults (context only).
  2. ONNX greedy (encoder -> decoder_init -> decoder_step KV loop), int8 AND
     fp32, ORT 1.31.1 CPU (== app version).
  3. Word identity HF-greedy vs ONNX-int8 (target >= 95%), diffs classified
     (substitution only / insertion / deletion).
  4. Logit-path parity fp32-ORT vs HF (max rel err, target <= 1e-4):
     encoder hidden states + decoder_init logits + one decoder_step per golden.
  5. RTF: end-to-end correction of a 20-word region (int8), 20 runs,
     median/p95 wall time + peak RSS delta.

Output: reports/bart_u2_{lang}.json (+ stdout summary).

Usage:
  .venv-asr/bin/python tools/bart_ref/run_bart_ref.py --lang de
  .venv-asr/bin/python tools/bart_ref/run_bart_ref.py --lang en --threads 16
"""
import argparse
import json
import os
import resource
import statistics
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

ROOT = Path("/home/dev/models/bart-export")
REPO = Path("/home/dev/Voice-Keyboard")
MAX_NEW_TOKENS = 64


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def load_goldens(lang: str):
    rows = []
    for line in (REPO / "testdata/bart_goldens" / f"{lang}.tsv").read_text().splitlines():
        if line.startswith("#") or not line.strip():
            continue
        typo, clean, cls = line.split("\t")
        rows.append({"input": typo, "clean": clean, "class": cls})
    return rows


class OnnxRunner:
    def __init__(self, lang: str, quant: str, threads: int, model_dir: str | None = None):
        # model_dir=None -> unfused layout ROOT/{lang}/{graph}/{graph}.{suffix}
        # (T24a: pass the {lang}-merged dir for the fused pipeline)
        d = Path(model_dir) if model_dir else ROOT / lang
        suffix = {"int8": "int8.onnx", "fp32": "onnx"}[quant]
        if model_dir is None:
            paths = {g: d / g / f"{g}.{suffix}" for g in ("encoder", "decoder_init", "decoder_step")}
        else:
            paths = {g: d / f"{g}.{suffix}" for g in ("encoder", "decoder_init", "decoder_step")}
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        prov = ["CPUExecutionProvider"]
        self.enc = ort.InferenceSession(str(paths["encoder"]), so, prov)
        self.ini = ort.InferenceSession(str(paths["decoder_init"]), so, prov)
        self.stp = ort.InferenceSession(str(paths["decoder_step"]), so, prov)
        meta = json.loads((d / "tokenizer" / "vocab_meta.json").read_text())
        self.prefix = meta["input_prefix"]
        self.start_id = meta["decoder_start_token_id"]
        self.eos = meta["eos_token_id"]

    def encode(self, input_ids):
        return self.enc.run(None, {"input_ids": input_ids})[0]

    def greedy(self, input_ids) -> list:
        enc_h = self.encode(input_ids)
        feeds = {"decoder_start_ids": np.array([[self.start_id]], dtype=np.int64),
                 "encoder_hidden": enc_h}
        logits, sk, sv, ck, cv = self.ini.run(None, feeds)
        out = []
        for pos in range(MAX_NEW_TOKENS):
            tok = int(np.argmax(logits[0, -1]))
            if tok == self.eos:
                break
            out.append(tok)
            logits, sk, sv = self.stp.run(
                None, {"input_ids": np.array([[tok]], dtype=np.int64),
                       "position_ids": np.array([[pos + 1]], dtype=np.int64),
                       "self_k": sk, "self_v": sv, "cross_k": ck, "cross_v": cv})
        return out

    def logits_probe(self, input_ids):
        """(enc_h, init_logits, step_logits) for the parity check."""
        enc_h = self.encode(input_ids)
        logits, sk, sv, ck, cv = self.ini.run(
            None, {"decoder_start_ids": np.array([[self.start_id]], dtype=np.int64),
                   "encoder_hidden": enc_h})
        tok = int(np.argmax(logits[0, -1]))
        s_logits, _, _ = self.stp.run(
            None, {"input_ids": np.array([[tok]], dtype=np.int64),
                   "position_ids": np.array([[1]], dtype=np.int64),
                   "self_k": sk, "self_v": sv, "cross_k": ck, "cross_v": cv})
        return enc_h, logits, s_logits


def rel_err(a, b):
    a, b = np.asarray(a, dtype=np.float64), np.asarray(b, dtype=np.float64)
    return float(np.abs(a - b).max() / max(np.abs(b).max(), 1e-12))


def norm_words(text):
    return [w.strip(".,!?;:‘’'\"").lower() for w in text.split()]


def word_diff(hf_text, onnx_text):
    a, b = hf_text.split(), onnx_text.split()
    if len(a) == len(b):
        subs = [(i, a[i], b[i]) for i in range(len(a)) if a[i] != b[i]]
        return subs, [], []
    # different lengths: simple positional alignment
    subs, dels = [], []
    for i in range(max(len(a), len(b))):
        wa = a[i] if i < len(a) else None
        wb = b[i] if i < len(b) else None
        if wa != wb:
            if wa is None:
                subs.append((i, "<none>", wb))
            elif wb is None:
                dels.append((i, wa))
            else:
                subs.append((i, wa, wb))
    return subs, dels, []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True, choices=["de", "en"])
    ap.add_argument("--threads", type=int, default=min(16, os.cpu_count() or 8))
    ap.add_argument("--runs", type=int, default=20)
    args = ap.parse_args()
    lang = args.lang

    import torch
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, GenerationConfig

    torch.manual_seed(0)
    raw = str(ROOT / "raw" / lang)
    tok = AutoTokenizer.from_pretrained(raw)
    model = AutoModelForSeq2SeqLM.from_pretrained(raw, dtype=torch.float32,
                                                  attn_implementation="eager")
    model.eval()
    meta = json.loads((ROOT / lang / "tokenizer" / "vocab_meta.json").read_text())
    prefix = meta["input_prefix"]
    gc = GenerationConfig(
        decoder_start_token_id=meta["decoder_start_token_id"],
        eos_token_id=meta["eos_token_id"], pad_token_id=meta["pad_token_id"],
        max_new_tokens=MAX_NEW_TOKENS, num_beams=1, do_sample=False,
        no_repeat_ngram_size=0)  # PINNED greedy, identical to ONNX runner

    def pipeline_default(text):
        """What the removed text2text-generation pipeline did: generate with
        the model's own default generation config (config.json values)."""
        with torch.no_grad():
            ids = tok(text, return_tensors="pt")
            return tok.batch_decode(
                model.generate(**ids, max_length=256),
                skip_special_tokens=True)[0]

    onnx_int8 = OnnxRunner(lang, "int8", args.threads)
    onnx_fp32 = OnnxRunner(lang, "fp32", args.threads)  # fp32 graphs

    goldens = load_goldens(lang)
    results = []
    word_hits, word_total = 0, 0
    max_enc_err = max_init_err = max_step_err = 0.0
    for g in goldens:
        text = prefix + g["input"]
        ids = tok(text, return_tensors="pt")
        input_ids = ids["input_ids"].numpy()
        # --- HF pinned greedy
        with torch.no_grad():
            hf = model.generate(**ids, generation_config=gc)
        hf_text = tok.batch_decode(hf, skip_special_tokens=True)[0]
        # --- HF model defaults (context; = removed text2text pipeline)
        pip = pipeline_default(text)
        # --- ONNX int8 + fp32 greedy
        onnx_ids = onnx_int8.greedy(input_ids)
        onnx_text = tok.decode(onnx_ids, skip_special_tokens=True)
        fp32_ids = onnx_fp32.greedy(input_ids)
        fp32_text = tok.decode(fp32_ids, skip_special_tokens=True)
        # --- logit-path parity fp32-ORT vs HF
        with torch.no_grad():
            out = model(input_ids=ids["input_ids"],
                        decoder_input_ids=torch.tensor([[meta["decoder_start_token_id"]]]),
                        use_cache=True)
            ref_enc = model.get_encoder()(input_ids=ids["input_ids"]).last_hidden_state
            ref_init = out.logits
            ref_step = model(
                input_ids=ids["input_ids"],
                decoder_input_ids=torch.tensor([[int(np.argmax(ref_init[0, -1]))]]),
                past_key_values=out.past_key_values, use_cache=True).logits
        enc_h, init_l, step_l = onnx_fp32.logits_probe(input_ids)
        max_enc_err = max(max_enc_err, rel_err(enc_h, ref_enc.numpy()))
        max_init_err = max(max_init_err, rel_err(init_l, ref_init.numpy()))
        max_step_err = max(max_step_err, rel_err(step_l, ref_step.numpy()))
        # --- word identity HF-greedy vs ONNX-int8
        subs, dels, _ = word_diff(hf_text, onnx_text)
        a, b = hf_text.split(), onnx_text.split()
        n = max(len(a), len(b))
        word_hits += n - len(subs) - len(dels)
        word_total += n
        same_fp32 = fp32_text == onnx_text
        clean_onnx = norm_words(onnx_text) == norm_words(g["clean"])
        clean_hf = norm_words(hf_text) == norm_words(g["clean"])
        # vor/nach-Zaehlung (token + words), silence-equivalent check
        n_in_tok = int(ids["input_ids"].shape[1])
        n_out_tok = len(onnx_ids)
        silent_eq = onnx_text.strip() == g["input"].strip()
        results.append({
            "input": g["input"], "class": g["class"], "clean": g["clean"],
            "hf_greedy": hf_text, "hf_pipeline": pip,
            "onnx_int8": onnx_text, "onnx_fp32": fp32_text,
            "int8_identical_to_fp32": same_fp32,
            "diffs_vs_hf": {"substitutions": subs, "deletions": dels},
            "clean_ok_int8": clean_onnx, "clean_ok_hf": clean_hf,
            "n_input_tokens": n_in_tok, "n_output_tokens": n_out_tok,
            "n_words_in": len(g["input"].split()), "n_words_out": len(b),
            "identical_in_out": silent_eq,
        })

    word_identity = word_hits / word_total if word_total else 0.0

    # --- RTF: correction of a 20-word region (int8)
    region = {"de": ("correct: ich wollte dir noch schreiben dass wir morgen "
                     "früh um sieben am Hauptbahnhof treffen und dann zusammen "
                     "mit dem Zug nach Leipzig fahren weil dort das erste "
                     "Konzert schon um neun beginnt"),
              "en": ("i wanted to let you know that we are meeting tomorrow "
                     "morning at seven at the central station and then taking "
                     "the train to boston together because the first concert "
                     "starts already at nine")} [lang]
    ids = tok(region, return_tensors="pt")["input_ids"].numpy()
    onnx_int8.greedy(ids)  # warmup
    times = []
    rss0 = peak_rss_mb()
    for _ in range(args.runs):
        t0 = time.perf_counter()
        onnx_int8.greedy(ids)
        times.append(time.perf_counter() - t0)
    rss1 = peak_rss_mb()
    median = statistics.median(times)
    p95 = sorted(times)[int(0.95 * (len(times) - 1))]

    sizes = {p.name: p.stat().st_size
             for sub in ("encoder", "decoder_init", "decoder_step")
             for p in sorted((ROOT / lang / sub).glob("*.int8.onnx"))}
    total_int8 = sum(sizes.values())

    report = {
        "lang": lang, "threads": args.threads, "ort": ort.__version__,
        "transformers": __import__("transformers").__version__,
        "torch": torch.__version__,
        "goldens": len(results), "max_new_tokens": MAX_NEW_TOKENS,
        "word_identity_hf_vs_int8": round(word_identity, 4),
        "word_total": word_total,
        "logit_parity_rel_err": {"encoder": max_enc_err,
                                 "decoder_init": max_init_err,
                                 "decoder_step": max_step_err},
        "int8_identical_to_fp32_texts": sum(r["int8_identical_to_fp32"] for r in results),
        "clean_word_match_int8": sum(r["clean_ok_int8"] for r in results),
        "clean_word_match_hf": sum(r["clean_ok_hf"] for r in results),
        "identical_in_out_count": sum(r["identical_in_out"] for r in results),
        "rtf_20word_region_s": {"median": round(median, 4), "p95": round(p95, 4),
                                "runs": args.runs,
                                "assumed_typing_window_s": 7.0},
        "rtf": round(median / 7.0, 4),
        "peak_rss_mb": round(rss1, 0), "rss_delta_mb": round(rss1 - rss0, 0),
        "int8_sizes_bytes": sizes, "int8_total_mb": round(total_int8 / 1e6, 1),
        "sentences": results,
    }
    out = REPO / "reports" / f"bart_u2_{lang}.json"
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "sentences"},
                     indent=2, ensure_ascii=False))
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
