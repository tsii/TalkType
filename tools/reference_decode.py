#!/usr/bin/env python3
"""Referenz-Decoder für parakeet-tdt-0.6b-v3 (ONNX int8) — Golden-Test-Erzeuger.

Zweck: unabhängig von der Android-Implementierung beweisen, wie der TDT-Greedy-
Decode für den gepinnten Export auszusehen hat, und Golden-Text für Test-WAVs
erzeugen. Aufruf:  .venv-asr/bin/python tools/reference_decode.py <audio.wav>

Verifizierter Tensorvertrag (gemessen 2026-10-27, onnxruntime 1.31.1):
  nemo128.onnx
    waveforms      float32 [B, N]        (16 kHz roh, [-1,1])
    waveforms_lens int64   [B]
    -> features      float32 [B, 128, T]
    -> features_lens int64   [B]
  encoder-model.int8.onnx
    audio_signal float32 [B, 128, T]
    length       int64   [B]
    -> outputs         float32 [B, 1024, T/8]
    -> encoded_lengths int64   [B]
  decoder_joint-model.int8.onnx
    encoder_outputs float32 [B, 1024, 1]     (ein Frame pro Schritt)
    targets         int32   [B, 1]
    target_length   int32   [B]
    input_states_1  float32 [2, B, 640]
    input_states_2  float32 [2, B, 640]
    -> outputs          float32 [B, 1, 1, 8198]   (8193 Symbole + 5 Durations)
    -> prednet_lengths  int32   [B]
    -> output_states_1  float32 [2, B, 640]
    -> output_states_2  float32 [2, B, 640]

8198 = 8193 Vokabeln (0..8192, <blk> = 8192) + 5 Durationklassen {0,1,2,3,4}.
"""
import sys, time
import numpy as np
import onnxruntime as ort
import soundfile as sf

MODELS = ".models"
BLANK = 8192
VOCAB_SIZE = 8193
NUM_DURATIONS = 5
MAX_SYMBOLS_PER_FRAME = 10

so = ort.SessionOptions()
so.intra_op_num_threads = 8
so.inter_op_num_threads = 1
so.log_severity_level = 3


def load_vocab(path):
    table = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                continue
            # Format: "<token> <id>", Token kann Leerzeichen enthalten
            tok, _, idx = line.rpartition(" ")
            table[int(idx)] = tok
    return table


def detokenize(ids, vocab):
    out = []
    for i in ids:
        t = vocab.get(i, "")
        out.append(t)
    return "".join(out).replace("\u2581", " ").strip()


def main(wav):
    src = sf.read(wav, dtype="float32", always_2d=False)
    audio, sr = src[0], src[1]
    if audio.ndim > 1:
        audio = audio.mean(1)
    assert sr == 16000, f"erwartet 16 kHz, ist {sr}"
    audio = audio.astype(np.float32)[None, :]

    mel = ort.InferenceSession(f"{MODELS}/nemo128.onnx", so, providers=["CPUExecutionProvider"])
    enc = ort.InferenceSession(f"{MODELS}/encoder-model.int8.onnx", so, providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(f"{MODELS}/decoder_joint-model.int8.onnx", so, providers=["CPUExecutionProvider"])
    vocab = load_vocab(f"{MODELS}/vocab.txt")

    t0 = time.time()
    feats, feat_len = mel.run(None, {
        "waveforms": audio,
        "waveforms_lens": np.array([audio.shape[1]], dtype=np.int64),
    })
    t_mel = time.time() - t0

    t0 = time.time()
    enc_out, enc_len = enc.run(None, {
        "audio_signal": feats,
        "length": feat_len,
    })
    t_enc = time.time() - t0

    frames = int(enc_len[0])
    state1 = np.zeros((2, 1, 640), dtype=np.float32)
    state2 = np.zeros((2, 1, 640), dtype=np.float32)
    last_label = BLANK  # TDT startet mit blank
    tokens = []
    times = []

    t0 = time.time()
    t, steps = 0, 0
    while t < frames:
        frame = enc_out[:, :, t:t + 1]
        logits, _, state1, state2 = dec.run(None, {
            "encoder_outputs": frame,
            "targets": np.array([[last_label]], dtype=np.int32),
            "target_length": np.array([1], dtype=np.int32),
            "input_states_1": state1,
            "input_states_2": state2,
        })
        flat = logits[0, 0, 0]           # [8198]
        token = int(np.argmax(flat[:VOCAB_SIZE]))
        dur = int(np.argmax(flat[VOCAB_SIZE:]))
        steps += 1
        if token != BLANK and token < 8192:
            tokens.append(token)
            times.append(t)
            last_label = token
            # Nicht-blank: Duration 0 erlaubt mehrere Labels pro Frame
            t += max(dur, 0)
            if dur == 0:
                # Schutz gegen Endlosschleife bei wiederholtem Non-Blank-0
                if len(tokens) > MAX_SYMBOLS_PER_FRAME * frames:
                    break
        else:
            t += max(dur, 1)             # Blank: immer mind. 1 Frame Fortschritt
    t_dec = time.time() - t0

    text = detokenize(tokens, vocab)
    dur_s = audio.shape[1] / 16000
    print(f"# audio={dur_s:.2f}s frames={frames} steps={steps} "
          f"mel={t_mel:.2f}s enc={t_enc:.2f}s dec={t_dec:.2f}s rtf={(t_mel+t_enc+t_dec)/dur_s:.3f}")
    print(f"# tokens={len(tokens)}")
    print(text)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "testdata/de_smoke.wav")
