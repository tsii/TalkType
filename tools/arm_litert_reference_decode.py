#!/usr/bin/env python
"""R2: reference decoder, variant B (Arm parakeet-tdt litert) — corrected per /tmp/ptdt.cpp.

Case A confirmed: log_mel is ALIVE under ai-edge-litert (std 1.38 for a
sine) — the tflite_runtime measurement "constant -16,636" was an artifact of
the invoke interface. The Arm pre-pass (compute_global_feature_normalization,
ptdt.cpp:342) therefore runs as in the reference: Welford over the log_mel of
the NEUTRAL runs (mean=0/inv_std=1) of all windows, then
feature_mean=mean, feature_inv_std=1/(sqrt(var)+eps), var=m2/max(1,n-1).

Further reference semantics (ptdt.cpp):
- prepare_featurizer_inputs (:400): previous_sample = audio[start-1] (not 0)
- compute_features (:458): input_length = 9 + valid_feature_frames
- (:470): pre_encode_feature_cache from the last 9 valid frames
- decode_next_text_piece (:490): token=argmax(logits[:8193]),
  duration=argmax(logits[8193:8198]); blank⇒frame+=max(1,duration);
  token⇒emit+decoder_step; duration>0⇒frame+=duration otherwise
  symbols<10⇒stay on the frame.
- Encoder caches (outputs 2/3/4) are looped through as inputs 2/3/4
  (the Python signature runner does NOT manage states automatically — C++ does).
"""
import struct
import sys
import time
import numpy as np
from ai_edge_litert.interpreter import Interpreter

MODELS = "testdata/litert"
AUDIO = "/tmp/beckett.wav"
EPS = 1e-5

WINDOW_SAMPLES = 121_600
FRAME_HOP = 160
NUM_VALID_FRAMES = 760
FEAT_DIM = 128
CACHE_FRAMES = 9
VOCAB_SIZE = 8193
BLANK = 8192
MAX_SYMBOLS = 10
ENC_DIM = 1024


def sig(path, threads=4):
    it = Interpreter(model_path=path, num_threads=threads)
    it.allocate_tensors()
    return it.get_signature_runner("serving_default")


def read_float32_wav(path):
    d = open(path, "rb").read()
    assert d[:4] == b"RIFF" and d[8:12] == b"WAVE"
    pos, fmt, data = 12, None, None
    while pos + 8 <= len(d):
        cid, sz = d[pos:pos+4], struct.unpack("<I", d[pos+4:pos+8])[0]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", d[pos+8:pos+8+16])
        elif cid == b"data":
            data = d[pos+8:pos+8+sz]
        pos += 8 + sz + (sz & 1)
    tag, ch, sr, _br, _ba, bits = fmt
    assert tag == 3 and bits == 32 and ch == 1
    return np.frombuffer(data, dtype="<f4").copy(), sr


def load_vocab(path):
    d = open(path, "rb").read()
    count = struct.unpack("<H", d[:2])[0]
    assert count == VOCAB_SIZE
    pos, toks = 4, []
    while pos + 4 <= len(d) and len(toks) < count:
        ln = struct.unpack("<I", d[pos:pos+4])[0]
        toks.append(d[pos+4:pos+4+ln].decode("utf-8", errors="replace"))
        pos += 4 + ln
    return toks


def run_featurizer(f, audio, start, n, mean, inv_std, cache, prev_sample):
    raw = np.zeros((1, WINDOW_SAMPLES), np.float32)
    raw[0, :n] = audio[start:start+n]
    sm = np.zeros((1, WINDOW_SAMPLES), np.float32)
    sm[0, :n] = 1.0
    valid = min(NUM_VALID_FRAMES, max(0, n // FRAME_HOP))
    fm = np.zeros((1, NUM_VALID_FRAMES, 1), np.float32)
    fm[0, :valid, 0] = 1.0
    out = f(args_0=raw, args_1=sm, args_2=fm,
            args_3=mean.astype(np.float32), args_4=inv_std.astype(np.float32),
            args_5=cache.astype(np.float32), args_6=np.array([prev_sample], np.float32))
    return out["output_0"], out["output_1"], valid  # input_features, log_mel, valid


def main():
    f = sig(f"{MODELS}/featurizer.tflite")
    enc = sig(f"{MODELS}/parakeet_tdt_nemo_encoder_cached_int8_dynamic_litert.tflite")
    dec = sig(f"{MODELS}/parakeet_tdt_nemo_decoder_step_int8_dynamic_litert.tflite")
    jnt = sig(f"{MODELS}/parakeet_tdt_nemo_joint_step_int8_dynamic_litert.tflite")
    vocab = load_vocab(f"{MODELS}/token_vocab.bin")

    audio, sr = read_float32_wav(AUDIO)
    audio = np.clip(audio, -1.0, 1.0)
    total = len(audio)
    print(f"audio {total} samples = {total/sr:.2f}s", file=sys.stderr)

    # ---- pre-pass (ptdt.cpp:342): Welford over the log_mel of the NEUTRAL runs ----
    mean = np.zeros((1, 1, FEAT_DIM), np.float32)
    inv_std = np.ones((1, 1, FEAT_DIM), np.float32)
    wmean = np.zeros(FEAT_DIM)
    m2 = np.zeros(FEAT_DIM)
    n_frames = 0
    off = 0
    zero_cache = np.zeros((1, CACHE_FRAMES, FEAT_DIM), np.float32)
    while off < total:
        remaining = total - off
        if remaining < FRAME_HOP:
            break
        n = min(WINDOW_SAMPLES, remaining)
        _, log_mel, valid = run_featurizer(f, audio, off, n, mean, inv_std, zero_cache, 0.0)
        for frame in range(valid):
            n_frames += 1
            c = float(n_frames)
            row = log_mel[0, frame]
            delta = row - wmean
            wmean += delta / c
            m2 += delta * (row - wmean)
        off += n
    if n_frames > 0:
        var = m2 / max(1, n_frames - 1)
        mean[0, 0] = wmean
        inv_std[0, 0] = 1.0 / (np.sqrt(var) + EPS)
    print(f"pre-pass: {n_frames} frames, mean={wmean[:3].round(3)}, inv_std={inv_std[0,0,:3].round(3)}", file=sys.stderr)

    # ---- streaming decode (ptdt.cpp:490 decode_next_text_piece) ----
    cache_ch = np.zeros((1, 24, 190, 1024), np.float32)
    cache_time = np.zeros((1, 24, 1024, 4), np.float32)
    cache_len = np.zeros((1,), np.int64)
    dec_h = np.zeros((2, 1, 640), np.float32)
    dec_c = np.zeros((2, 1, 640), np.float32)
    pre_cache = np.zeros((1, CACHE_FRAMES, FEAT_DIM), np.float32)
    joint_hidden = np.zeros((1, 1, 640), np.float32)

    tokens = []
    t0 = time.time()
    off = 0
    while off < total:
        n = min(WINDOW_SAMPLES, total - off)
        prev_sample = audio[off - 1] if off > 0 else 0.0
        feats, _, valid = run_featurizer(f, audio, off, n, mean, inv_std, pre_cache, prev_sample)
        # input_length = 9 + valid frames (ptdt.cpp:465)
        input_length = np.array([CACHE_FRAMES + valid], np.int64)
        enc_out = enc(args_0=feats.astype(np.float32), args_1=input_length,
                      args_2=cache_ch, args_3=cache_time, args_4=cache_len)
        pool = enc_out["output_0"]
        enc_len = int(enc_out["output_1"][0])
        cache_ch = enc_out["output_2"].copy()
        cache_time = enc_out["output_3"].copy()
        cache_len = enc_out["output_4"].copy()

        # pre_encode_feature_cache from the last 9 valid frames (ptdt.cpp:470)
        to_copy = min(CACHE_FRAMES, valid)
        pre_cache = np.zeros((1, CACHE_FRAMES, FEAT_DIM), np.float32)
        if to_copy > 0:
            src = CACHE_FRAMES + valid - to_copy
            pre_cache[0, CACHE_FRAMES - to_copy:] = feats[0, src:src + to_copy]

        # Greedy TDT (ptdt.cpp:490)
        final_frame = min(95, pool.shape[1], enc_len)
        fi, syms = 0, 0
        while fi < final_frame:
            frame = pool[:, fi:fi+1, :].astype(np.float32)
            lg = jnt(args_0=frame, args_1=joint_hidden.astype(np.float32))["output_0"]
            flat = lg[0, 0]
            token = int(np.argmax(flat[:VOCAB_SIZE]))
            duration = int(np.argmax(flat[VOCAB_SIZE:]))  # 5 extra columns (ptdt.cpp:538)
            if token == BLANK:
                fi += max(1, duration)
                syms = 0
                continue
            tokens.append(token)
            syms += 1
            d = dec(args_0=np.array([[token]], np.int64), args_1=dec_h, args_2=dec_c)
            joint_hidden = d["output_0"].astype(np.float32)  # decoder_hidden [1,1,640]
            dec_h = d["output_1"].copy()
            dec_c = d["output_2"].copy()
            if duration > 0:
                fi += duration
                syms = 0
            elif syms >= MAX_SYMBOLS:
                fi += 1
                syms = 0
        off += n

    wall = time.time() - t0
    text = "".join(vocab[i] for i in tokens)
    text = text.replace("▁", " ").replace("<pad>", "").replace("<unk>", "")
    print("token IDs:", tokens[:30], "..." if len(tokens) > 30 else "")
    print(f"text (first 140 characters): {text[:140]!r}")
    print(f"wall clock {wall:.2f}s / audio {total/sr:.2f}s → RTF={wall/(total/sr):.3f}")


if __name__ == "__main__":
    main()
