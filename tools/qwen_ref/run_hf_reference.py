#!/usr/bin/env python3
"""Qwen3-ASR host reference (HuggingFace transformers backend).

Greedy, deterministic inference on the golden WAVs + 1 s silence.
Used in T22a to (a) verify the model works at all on CPU, (b) produce the
reference transcripts that the ONNX pipeline must reproduce bit-exactly.

Usage: .venv-asr/bin/python tools/qwen_ref/run_hf_reference.py [--model DIR] [--wav FILE]...
Writes a JSON report to stdout.
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch

from transformers import AutoProcessor, Qwen3ASRForConditionalGeneration

MODEL_DIR = "/home/dev/models/qwen-export/Qwen3-ASR-0.6B-hf"


def load_audio(path: str, sr: int = 16000) -> np.ndarray:
    data, s = sf.read(path, dtype="float32", always_2d=True)
    assert s == sr, f"{path}: expected {sr} Hz, got {s}"
    return data[:, 0]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=MODEL_DIR)
    ap.add_argument("--language", default="German")
    ap.add_argument("--max-new-tokens", type=int, default=128)
    ap.add_argument("--dtype", default="float32", choices=["float32", "bfloat16"])
    ap.add_argument("--wav", action="append")
    args = ap.parse_args()

    dtype = torch.float32 if args.dtype == "float32" else torch.bfloat16
    t0 = time.time()
    processor = AutoProcessor.from_pretrained(args.model)
    model = Qwen3ASRForConditionalGeneration.from_pretrained(
        args.model, dtype=dtype, device_map="cpu"
    )
    model.eval()
    load_s = time.time() - t0
    print(f"# model load: {load_s:.1f}s", file=sys.stderr)

    wavs = args.wav or [
        "/home/dev/Voice-Keyboard/testdata/de_part1.wav",
        "/home/dev/Voice-Keyboard/testdata/de_part2.wav",
        "/home/dev/Voice-Keyboard/testdata/de_pause.wav",
        "/home/dev/Voice-Keyboard/testdata/de_smoke.wav",
    ]
    # silence test: 1 s of near-silence (-60 dBFS noise floor, like our corpus)
    silence = (np.random.default_rng(0).normal(0, 1e-3, 16000)).astype(np.float32)
    cases = [(Path(w).name, load_audio(w), None) for w in wavs]
    cases.append(("silence_1s.wav", silence, None))

    results = []
    for name, audio, _ in cases:
        t1 = time.time()
        inputs = processor.apply_transcription_request(
            audio=audio, language=args.language, return_tensors="pt"
        )
        with torch.no_grad():
            out = model.generate(
                **inputs,
                max_new_tokens=args.max_new_tokens,
                do_sample=False,
                temperature=None, top_p=None, top_k=None,
            )
        gen_s = time.time() - t1
        raw = processor.decode(out[0, inputs["input_ids"].shape[-1]:], skip_special_tokens=True)
        parsed = processor.decode(out[0, inputs["input_ids"].shape[-1]:], return_format="parsed")
        results.append({
            "wav": name,
            "audio_s": round(len(audio) / 16000, 3),
            "raw": raw[0],
            "text": parsed.get("transcription", raw[0]),
            "language": parsed.get("language"),
            "gen_s": round(gen_s, 2),
            "n_new_tokens": int(out.shape[-1] - inputs["input_ids"].shape[-1]),
        })
        print(json.dumps(results[-1], ensure_ascii=False))

    print(json.dumps({"model": args.model, "load_s": round(load_s, 1),
                      "results": results}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
