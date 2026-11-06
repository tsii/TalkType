#!/usr/bin/env python3
"""T22f1 (PLAN_V0146 §6): golden fixture generator for the Qwen input chain
(asr/qwen/QwenInputs.kt) — HOST-ONLY documentation tool, no app code.

The mel math below is a VERBATIM MIRROR of tools/qwen_ref/run_qwen_ref.py
(compute_mel) — the pinned reference of docs/QWEN_ASR_CONTRACT.md §4.0/§4.1/§6.

Outputs (deterministic, re-runnable) into app/src/test/resources/qwen/:
  fixtures_mel.json   — inputs + expected log-mel:
                        small cases (silence 1 s, 440 Hz tone 0.5 s): COMPLETE
                        expected [128,T] float arrays (test tolerance 1e-6);
                        long cases (de_smoke first 2.8 s, de_pause first 3 s):
                        per-frame L2 norms + sha256 of the exact float32
                        reference (C-order little endian; test binds per-frame
                        L2 within 1e-4 — byte parity is reported, not required).
  fixtures_mask.json  — attention-mask block boundaries for S in 13*n, n=1,8,10,35.
  fixtures_prompt.json— prefix/suffix prompt token ids (contract §6) + full id
                        lists per S.
  mel_filters.npy + mel_config.json — verbatim copies of the pinned model
                        package files (sha256 cross-checked by the test against
                        ModelManifest.QWEN).
"""
import hashlib
import json
import platform
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import soundfile as sf
from tokenizers import Tokenizer

REPO = Path(__file__).resolve().parents[2]
EXPORT = Path("/home/dev/models/qwen-export/out-0.6b")
TOKENIZER = Path("/home/dev/models/qwen-export/Qwen3-ASR-0.6B-hf/tokenizer.json")
OUT_DIR = REPO / "app" / "src" / "test" / "resources" / "qwen"
AUDIO_TOKENS_PER_CHUNK = 13
WINDOW_TOKENS = 104  # 13 * n_window_infer/(2*n_window) = 13*800/100


# --- mirror of run_qwen_ref.py (contract §4.0) — do NOT diverge ---
def compute_mel(audio: np.ndarray, melbank: np.ndarray) -> np.ndarray:
    n = max(len(audio), 8000)  # min_length
    n = ((n + 15999) // 16000) * 16000  # full 1 s chunks
    wav = np.zeros(n, dtype=np.float32)
    wav[: len(audio)] = audio
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


def window_blocks(s: int) -> list:
    blocks, start = [], 0
    while start < s:
        end = min(start + WINDOW_TOKENS, s)
        blocks.append([start, end])
        start = end
    return blocks


def prompt_prefix_suffix(tk: Tokenizer) -> tuple:
    def build(n_audio):
        s = ("<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>"
             + "<|audio_pad|>" * n_audio
             + "<|audio_end|><|im_end|>\n<|im_start|>assistant\nlanguage German<asr_text>")
        return tk.encode(s, add_special_tokens=False).ids

    ids2 = build(2)
    first = ids2.index(151676)
    last = len(ids2) - 1 - ids2[::-1].index(151676)
    prefix, suffix = ids2[:first], ids2[last + 1:]
    for probe in (1, 5, 130):
        assert build(probe) == prefix + [151676] * probe + suffix, "decomposition broken"
    return prefix, suffix


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    melbank = np.load(EXPORT / "mel" / "mel_filters.npy")
    assert melbank.shape == (201, 128) and melbank.dtype == np.float32
    mel_cfg = json.loads((EXPORT / "mel" / "mel_config.json").read_text())
    assert (mel_cfg["n_fft"], mel_cfg["hop_length"], mel_cfg["sampling_rate"]) == (400, 160, 16000)

    def read_mono(path: Path) -> np.ndarray:
        data, sr = sf.read(path, dtype="float32", always_2d=True)
        assert sr == 16000
        return data[:, 0]

    smoke_all = read_mono(REPO / "testdata" / "de_smoke.wav")
    pause_all = read_mono(REPO / "testdata" / "de_pause.wav")

    def long_case(label: str, raw: np.ndarray) -> dict:
        mel = compute_mel(raw, melbank)[0]  # [128, T]
        norms = [float(np.linalg.norm(mel[:, t])) for t in range(mel.shape[1])]
        return {
            "label": label,
            "input": [float(x) for x in raw],
            "n_frames": int(mel.shape[1]),
            "frame_l2": norms,
            "float32_sha256": hashlib.sha256(mel.tobytes()).hexdigest(),
        }

    def small_case(label: str, raw: np.ndarray) -> dict:
        mel = compute_mel(raw, melbank)[0]
        return {
            "label": label,
            "input": [float(x) for x in raw],
            "n_frames": int(mel.shape[1]),
            "expected": mel.tolist(),  # 128 rows x T frames (band-major, C-order)
        }

    tone_t = np.arange(8000)
    tone = (0.5 * np.sin(2 * np.pi * 440.0 * tone_t / 16000.0)).astype(np.float32)
    cases_mel = [
        long_case("smoke_2_8s", smoke_all[: int(2.8 * 16000)]),
        long_case("pause_3_0s", pause_all[: 48_000]),
        small_case("silence_1s", np.zeros(16000, dtype=np.float32)),
        small_case("tone_440hz_0_5s", tone),
    ]
    by = {c["label"]: c for c in cases_mel}
    assert by["smoke_2_8s"]["n_frames"] == 300  # 2.8 s pads up to 3.0 s (contract §4.0)
    assert by["pause_3_0s"]["n_frames"] == 300
    assert by["silence_1s"]["n_frames"] == 100
    assert by["tone_440hz_0_5s"]["n_frames"] == 100  # 0.5 s pads up to a full 1.0 s
    assert len(by["silence_1s"]["expected"]) == 128

    meta_mel = {
        "reference": "tools/qwen_ref/run_qwen_ref.py compute_mel (QWEN_ASR_CONTRACT §4.0)",
        "mel_filters": str(EXPORT / "mel" / "mel_filters.npy"),
        "mel_filters_sha256": sha256_of(EXPORT / "mel" / "mel_filters.npy"),
        "mel_config": str(EXPORT / "mel" / "mel_config.json"),
        "mel_config_sha256": sha256_of(EXPORT / "mel" / "mel_config.json"),
        "wavs": [str(REPO / "testdata" / "de_smoke.wav"), str(REPO / "testdata" / "de_pause.wav")],
        "tone_formula": "0.5*sin(2*pi*440*t/16000), t=0..7999, float32",
        "tolerance_small_abs": 1e-6,
        "tolerance_long_frame_l2_rel": 1e-4,
        "float32_sha_note": "sha256 over mel[128,T] float32 C-order LE; test binds L2, parity reported",
        "numpy": np.__version__,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }

    cases_mask = []
    for n in (1, 8, 10, 35):
        s = AUDIO_TOKENS_PER_CHUNK * n
        cases_mask.append({"s": s, "window_tokens": WINDOW_TOKENS, "blocks": window_blocks(s)})
    assert cases_mask[3]["blocks"] == [[0, 104], [104, 208], [208, 312], [312, 416], [416, 455]]

    tk = Tokenizer.from_file(str(TOKENIZER))
    prefix, suffix = prompt_prefix_suffix(tk)
    cases_prompt = []
    for n in (1, 8, 10, 35):
        s = AUDIO_TOKENS_PER_CHUNK * n
        ids = prefix + [151676] * s + suffix
        cases_prompt.append({"s": s, "length": len(ids), "ids": ids})
        assert len(ids) == 18 + s

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "fixtures_mel.json").write_text(
        json.dumps({"meta": meta_mel, "cases": cases_mel}, indent=1) + "\n", encoding="utf-8")
    (OUT_DIR / "fixtures_mask.json").write_text(
        json.dumps({
            "meta": {"reference": "QWEN_ASR_CONTRACT §4.1 window_mask; 104er blocks, rest window at the end",
                     "neg_inf": -1e9, "generated_at": meta_mel["generated_at"]},
            "cases": cases_mask}, indent=1) + "\n", encoding="utf-8")
    (OUT_DIR / "fixtures_prompt.json").write_text(
        json.dumps({
            "meta": {"reference": "QWEN_ASR_CONTRACT §6 (HF-processor-verified in T22a)",
                     "tokenizer": str(TOKENIZER), "tokenizer_sha256": sha256_of(TOKENIZER),
                     "generated_at": meta_mel["generated_at"]},
            "prefix": prefix, "audio_pad_id": 151676, "suffix": suffix,
            "cases": cases_prompt}, indent=1) + "\n", encoding="utf-8")
    # verbatim copies of the pinned model package files (small: 103 KB + 260 B)
    (OUT_DIR / "mel_filters.npy").write_bytes((EXPORT / "mel" / "mel_filters.npy").read_bytes())
    (OUT_DIR / "mel_config.json").write_bytes((EXPORT / "mel" / "mel_config.json").read_bytes())

    for c in cases_mel:
        print(f"mel {c['label']}: frames={c['n_frames']}")
    print(f"mask cases: {[c['s'] for c in cases_mask]}")
    print(f"prompt prefix({len(prefix)}) suffix({len(suffix)}) lengths: {[c['length'] for c in cases_prompt]}")
    print("done:", OUT_DIR)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
