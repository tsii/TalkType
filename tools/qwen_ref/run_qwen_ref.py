#!/usr/bin/env python3
"""T22a: ORT reference runner for the exported Qwen3-ASR ONNX pipeline.

Runs the pinned 3-graph pipeline (encoder -> prefill -> greedy step decode)
with onnxruntime (host CPU) on the golden WAVs + silence tests, measures
RTF and prints a JSON report. Greedy only -> deterministic.

Pipeline contract (see docs/QWEN_ASR_CONTRACT.md):
  raw 16 kHz mono -> pad to full seconds (silence) -> log-mel 128 (numpy,
  same math as Qwen3ASRFeatureExtractor, melbank from mel/mel_filters.npy)
  -> encoder.int8.onnx (+ block window mask) -> prefill.int8.onnx
  -> step.int8.onnx greedy until <|im_end|>/<|endoftext|> or cap.

Usage:
  .venv-asr/bin/python tools/qwen_ref/run_qwen_ref.py \
      --export-dir /home/dev/models/qwen-export/out-0.6b \
      --model-dir /home/dev/models/qwen-export/Qwen3-ASR-0.6B-hf
"""
import argparse
import json
import os
import resource
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf
from tokenizers import Tokenizer

NEG_INF = -1e9
EOS = (151643, 151645)
AUDIO_TOKENS_PER_CHUNK = 13
CHUNK_FRAMES = 100  # 2 * n_window, 1 s of audio
WINDOW_TOKENS = 104  # 13 * n_window_infer/(2*n_window) = 8 chunks


def load_wav(path: str) -> np.ndarray:
    data, sr = sf.read(path, dtype="float32", always_2d=True)
    assert sr == 16000, f"{path}: {sr} Hz"
    return data[:, 0]


def compute_mel(audio: np.ndarray, melbank: np.ndarray) -> np.ndarray:
    """Mirror of Qwen3ASRFeatureExtractor._torch_extract_fbank_features
    for 1-D audio, WITHOUT the feature extractor's mel padding (we pad the
    raw audio to full seconds instead; see contract deviation note)."""
    n = max(len(audio), 8000)  # min_length
    n = ((n + 15999) // 16000) * 16000  # full 1 s chunks
    wav = np.zeros(n, dtype=np.float32)
    wav[: len(audio)] = audio
    # torch.stft(center=True, pad_mode='reflect'), n_fft=400, hop=160
    padded = np.pad(wav, 200, mode="reflect")
    n_fft, hop = 400, 160
    n_frames = n // hop  # 1 + n//hop frames, last one dropped
    window = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n_fft) / n_fft)
    mags = np.empty((1 + n_fft // 2, n_frames), dtype=np.float32)
    for t in range(n_frames):
        frame = padded[t * hop : t * hop + n_fft] * window
        spec = np.fft.rfft(frame)
        mags[:, t] = (spec.real**2 + spec.imag**2)
    mel = melbank.T @ mags  # [128, T]
    log_spec = np.log10(np.maximum(mel, 1e-10))
    log_spec = np.maximum(log_spec, log_spec.max() - 8.0)
    log_spec = (log_spec + 4.0) / 4.0
    return log_spec.astype(np.float32)[None]  # [1,128,T]


def window_mask(S: int) -> np.ndarray:
    m = np.full((1, 1, S, S), NEG_INF, dtype=np.float32)
    start = 0
    while start < S:
        end = min(start + WINDOW_TOKENS, S)
        m[:, :, start:end, start:end] = 0.0
        start = end
    return m


def build_prompt_ids(tk: Tokenizer, n_audio: int) -> list[int]:
    s = (
        "<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>"
        + "<|audio_pad|>" * n_audio
        + "<|audio_end|><|im_end|>\n<|im_start|>assistant\nlanguage German<asr_text>"
    )
    return tk.encode(s, add_special_tokens=False).ids


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--export-dir", default="/home/dev/models/qwen-export/out-0.6b")
    ap.add_argument("--model-dir", default="/home/dev/models/qwen-export/Qwen3-ASR-0.6B-hf")
    ap.add_argument("--precision", choices=["int8", "fp32"], default="int8")
    ap.add_argument("--max-new-tokens", type=int, default=128)
    ap.add_argument("--threads", type=int, default=0)
    ap.add_argument("--verify-prompt", action="store_true",
                    help="compare prompt ids against HF processor once")
    ap.add_argument("--wav", action="append")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    ed = Path(args.export_dir)
    sub = {"enc": "encoder", "pre": "prefill", "step": "step"}
    names = {
        "enc": f"encoder{'.int8' if args.precision=='int8' else ''}.onnx",
        "pre": f"qwen_prefill{'.int8' if args.precision=='int8' else ''}.onnx",
        "step": f"qwen_step{'.int8' if args.precision=='int8' else ''}.onnx",
    }
    so = ort.SessionOptions()
    if not args.threads:
        try:
            args.threads = len(os.sched_getaffinity(0))
        except AttributeError:
            args.threads = os.cpu_count()
    so.intra_op_num_threads = args.threads
    sess = {
        k: ort.InferenceSession(str(ed / sub[k] / p), so, providers=["CPUExecutionProvider"])
        for k, p in names.items()
    }
    tk = Tokenizer.from_file(str(Path(args.model_dir) / "tokenizer.json"))
    melbank = np.load(str(ed / "mel" / "mel_filters.npy"))

    wavs = args.wav or [
        "/home/dev/Voice-Keyboard/testdata/de_part1.wav",
        "/home/dev/Voice-Keyboard/testdata/de_part2.wav",
        "/home/dev/Voice-Keyboard/testdata/de_pause.wav",
        "/home/dev/Voice-Keyboard/testdata/de_smoke.wav",
    ]
    cases = [(Path(w).name, load_wav(w)) for w in wavs]
    cases.append(("silence_1s_zero.wav", np.zeros(16000, dtype=np.float32)))
    cases.append(("silence_1s_noise60db.wav",
                  np.random.default_rng(0).normal(0, 1e-3, 16000).astype(np.float32)))

    results = []
    for name, audio in cases:
        t0 = time.perf_counter()
        mel = compute_mel(audio, melbank)
        n_chunks = mel.shape[2] // CHUNK_FRAMES
        S = n_chunks * AUDIO_TOKENS_PER_CHUNK
        t_enc0 = time.perf_counter()
        audio_embeds = sess["enc"].run(None, {
            "input_features": mel, "attn_mask": window_mask(S)})[0]
        t_enc = time.perf_counter() - t_enc0

        ids = build_prompt_ids(tk, S)
        input_ids = np.asarray([ids], dtype=np.int64)
        L = len(ids)
        causal = np.triu(np.full((1, 1, L, L), NEG_INF, dtype=np.float32), 1)
        pos = np.arange(L, dtype=np.int64)[None, :]
        t_pre0 = time.perf_counter()
        logits, past_k, past_v = sess["pre"].run(None, {
            "input_ids": input_ids, "audio_embeds": audio_embeds,
            "attn_mask": causal, "position_ids": pos})
        t_pre = time.perf_counter() - t_pre0

        generated = []
        tok = int(np.argmax(logits[0, -1]))
        t_steps = 0.0
        P = L
        capped = False
        while len(generated) < args.max_new_tokens:
            if tok in EOS:
                break
            generated.append(tok)
            m = np.zeros((1, 1, 1, P + 1), dtype=np.float32)
            spos = np.asarray([[P]], dtype=np.int64)
            tin = np.asarray([[tok]], dtype=np.int64)
            ts0 = time.perf_counter()
            logits, past_k, past_v = sess["step"].run(None, {
                "input_ids": tin, "past_k": past_k, "past_v": past_v,
                "attn_mask": m, "position_ids": spos})
            t_steps += time.perf_counter() - ts0
            P += 1
            tok = int(np.argmax(logits[0, -1]))
        else:
            capped = True
        total = time.perf_counter() - t0
        audio_s = len(audio) / 16000
        text = tk.decode(generated, skip_special_tokens=True)
        r = {
            "wav": name, "audio_s": round(audio_s, 3), "text": text,
            "n_tokens": len(generated), "capped": capped,
            "mel_frames": int(mel.shape[2]), "audio_tokens": S, "prompt_len": L,
            "t_enc_s": round(t_enc, 3), "t_prefill_s": round(t_pre, 3),
            "t_steps_s": round(t_steps, 3), "t_total_s": round(total, 3),
            "rtf": round(total / audio_s, 3),
            "tok_per_s": round(len(generated) / t_steps, 1) if t_steps else None,
        }
        results.append(r)
        print(json.dumps(r, ensure_ascii=False))

    report = {
        "export_dir": str(ed), "precision": args.precision,
        "ort_version": ort.__version__,
        "threads": args.threads,
        "max_new_tokens": args.max_new_tokens, "results": results,
        "peak_rss_mb": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024,
    }
    if args.verify_prompt:
        from transformers import AutoProcessor
        proc = AutoProcessor.from_pretrained(args.model_dir)
        ref = proc.apply_transcription_request(
            audio=cases[0][1], language="German", return_tensors="pt")
        mine = build_prompt_ids(tk, S_of(cases[0][1], melbank))
        report["prompt_verify"] = {
            "hf_len": int(ref["input_ids"].shape[-1]), "ours_len": len(mine),
            "equal": list(ref["input_ids"][0].tolist()) == mine}
    if args.out:
        Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=1))
        print(f"# written {args.out}")


def S_of(audio, melbank):
    mel = compute_mel(audio, melbank)
    return (mel.shape[2] // CHUNK_FRAMES) * AUDIO_TOKENS_PER_CHUNK


if __name__ == "__main__":
    main()
