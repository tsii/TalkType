#!/usr/bin/env python3
"""T22a: ONNX export of Qwen3-ASR (0.6B/1.7B) for the TalkType feasibility gate.

Exports three graphs per model, with a numerical parity check against the
HuggingFace transformers reference (fp32) before each export:

  encoder.onnx   mel [1,128,T] (T = 100*n chunks, all chunks valid) + additive
                 window mask [1,1,S,S] (S = 13*n) -> audio embeds [1,S,text_hidden]
                 (audio tower + multi-modal projector). Window semantics follow
                 the cu_seqlens path (n_window_infer=800 frames = 8 chunks = 104
                 tokens per attention window), expressed as a block mask.
  qwen_prefill.onnx  input_ids [1,L] + audio embeds [1,S,H] + additive causal
                 mask [1,1,L,L] -> logits [1,1,vocab] (last pos) + past_k/past_v
                 [layers,1,kv_heads,L,head_dim]
  qwen_step.onnx    token id [1,1] + past_k/past_v + position_ids [1,1] +
                 additive mask [1,1,1,P+1] -> logits [1,1,vocab] + new past

Deviation from the HF reference (documented in QWEN_ASR_CONTRACT.md): the RUNNER
pads raw audio with silence to whole seconds, so every mel chunk is fully valid;
the HF pipeline masks out mel padding instead. Our packed sequence therefore
contains 13 tokens per padded second (HF: valid frames only). Both are
deterministic; the contract goldens use THIS pipeline.

Usage: .venv-asr/bin/python tools/qwen_ref/export_qwen_onnx.py \
          --model-dir /home/dev/models/qwen-export/Qwen3-ASR-0.6B-hf \
          --out-dir /home/dev/models/qwen-export/out-0.6b
"""
import argparse
import hashlib
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from torch import nn

from transformers import Qwen3ASRForConditionalGeneration

NEG_INF = -1e9


def rms_norm(x, weight, eps):
    return weight * (x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + eps))


def rotate_half(x):
    x1, x2 = x[..., : x.shape[-1] // 2], x[..., x.shape[-1] // 2:]
    return torch.cat((-x2, x1), dim=-1)


def rope_cos_sin(position_ids, head_dim, base, dtype=torch.float32):
    inv_freq = 1.0 / (base ** (torch.arange(0, head_dim, 2, dtype=dtype) / head_dim))
    freqs = position_ids[:, :, None].float() * inv_freq[None, None, :]
    emb = torch.cat((freqs, freqs), dim=-1)
    return emb.cos(), emb.sin()


def apply_rope(q, k, cos, sin):
    # q,k: [B, H, S, D]; cos/sin: [B, S, D]
    cos, sin = cos.unsqueeze(1), sin.unsqueeze(1)
    return q * cos + rotate_half(q) * sin, k * cos + rotate_half(k) * sin


class EncoderExport(nn.Module):
    """Audio tower (static full-chunk packing) + projector, mask-based windows."""

    def __init__(self, model: Qwen3ASRForConditionalGeneration):
        super().__init__()
        self.at = model.model.audio_tower
        self.proj = model.model.multi_modal_projector
        self.n_window = self.at.config.n_window
        self.num_heads = self.at.config.encoder_attention_heads

    def forward(self, input_features, attn_mask):
        # input_features [1,128,T], T = n_chunks*2*n_window; attn_mask [1,1,S,S]
        at = self.at
        B, M, T = input_features.shape
        chunk_len = self.n_window * 2
        nc = T // chunk_len
        chunked = (
            input_features.view(B, M, nc, chunk_len)
            .permute(0, 2, 1, 3)
            .reshape(B * nc, 1, M, chunk_len)
        )
        x = F.gelu(at.conv2d1(chunked))
        x = F.gelu(at.conv2d2(x))
        x = F.gelu(at.conv2d3(x))
        tot, C, Fb, Ts = x.shape
        x = at.conv_out(x.permute(0, 3, 1, 2).contiguous().view(tot, Ts, C * Fb))
        x = x + at.positional_embedding.positional_embedding[:Ts].to(x.dtype)
        S = nc * Ts
        x = x.reshape(1, S, -1)
        scale = (at.config.d_model // self.num_heads) ** -0.5
        for layer in at.layers:
            res = x
            h = layer.self_attn_layer_norm(x)
            q = layer.self_attn.q_proj(h).view(1, S, self.num_heads, -1).transpose(1, 2)
            k = layer.self_attn.k_proj(h).view(1, S, self.num_heads, -1).transpose(1, 2)
            v = layer.self_attn.v_proj(h).view(1, S, self.num_heads, -1).transpose(1, 2)
            w = torch.matmul(q, k.transpose(-1, -2)) * scale + attn_mask
            w = torch.softmax(w, dim=-1)
            o = torch.matmul(w, v).transpose(1, 2).reshape(1, S, -1)
            x = res + layer.self_attn.out_proj(o)
            res = x
            h = layer.final_layer_norm(x)
            x = res + layer.fc2(F.gelu(layer.fc1(h)))
        x = at.ln_post(x)
        return self.proj(x)  # [1, S, text_hidden]


class DecoderLayersExport(nn.Module):
    """Qwen3 decoder stack with explicit past KV tensors (full attention only)."""

    def __init__(self, model: Qwen3ASRForConditionalGeneration):
        super().__init__()
        self.lm = model.model.language_model
        cfg = self.lm.config
        self.eps = cfg.rms_norm_eps
        self.head_dim = cfg.head_dim
        self.n_heads = cfg.num_attention_heads
        self.n_kv = cfg.num_key_value_heads
        self.rope_base = cfg.rope_parameters["rope_theta"]
        self.layers = self.lm.layers  # module list; weights reused

    def one_layer(self, layer, x, cos, sin, attn_mask, past_k, past_v):
        # x [B,S,H]; past_k/v [B,n_kv,P,hd] or None; attn_mask additive [B,1,S,P+S]
        attn = layer.self_attn
        B, S, H = x.shape
        res = x
        h = rms_norm(x, layer.input_layernorm.weight, self.eps)
        q = attn.q_norm(attn.q_proj(h).view(B, S, self.n_heads, self.head_dim)).transpose(1, 2)
        k = attn.k_norm(attn.k_proj(h).view(B, S, self.n_kv, self.head_dim)).transpose(1, 2)
        v = attn.v_proj(h).view(B, S, self.n_kv, self.head_dim).transpose(1, 2)
        q, k = apply_rope(q, k, cos, sin)
        group = self.n_heads // self.n_kv
        if group != 1:
            k = k.repeat_interleave(group, dim=1)
            v = v.repeat_interleave(group, dim=1)
        if past_k is not None:
            k = torch.cat((past_k, k), dim=2)
            v = torch.cat((past_v, v), dim=2)
        w = torch.matmul(q, k.transpose(-1, -2)) * (self.head_dim ** -0.5)
        if attn_mask is not None:
            w = w + attn_mask
        w = torch.softmax(w, dim=-1)
        o = torch.matmul(w, v).transpose(1, 2).reshape(B, S, -1)
        x = res + attn.o_proj(o)
        res = x
        h = rms_norm(x, layer.post_attention_layernorm.weight, self.eps)
        h = layer.mlp.down_proj(layer.mlp.act_fn(layer.mlp.gate_proj(h)) * layer.mlp.up_proj(h))
        x = res + h
        return x, k, v

    def stack(self, x, cos, sin, attn_mask, past_k, past_v):
        ks, vs = [], []
        for i, layer in enumerate(self.layers):
            pk = past_k[i] if past_k is not None else None
            pv = past_v[i] if past_v is not None else None
            x, k, v = self.one_layer(layer, x, cos, sin, attn_mask, pk, pv)
            ks.append(k)
            vs.append(v)
        x = rms_norm(x, self.lm.norm.weight, self.eps)
        return x, torch.stack(ks), torch.stack(vs)


class PrefillExport(nn.Module):
    def __init__(self, model: Qwen3ASRForConditionalGeneration):
        super().__init__()
        self.embed = model.model.language_model.embed_tokens
        self.stack = DecoderLayersExport(model)
        self.lm_head = model.lm_head
        self.audio_token_id = model.config.audio_token_id

    def forward(self, input_ids, audio_embeds, attn_mask, position_ids):
        # input_ids [1,L]; audio_embeds [1,S,H]; attn_mask [1,1,L,L] additive causal
        B, L = input_ids.shape
        x = self.embed(input_ids)  # [1,L,H]
        ph = (input_ids == self.audio_token_id)  # [1,L]
        idx = ph.long().cumsum(-1) - 1  # 0-based audio index per placeholder
        pad = audio_embeds.new_zeros(1, 1, audio_embeds.shape[-1])
        audio_ext = torch.cat((audio_embeds, pad), dim=1)  # [1,S+1,H]
        audio_at = audio_ext[:, idx].squeeze(0).squeeze(0)  # [L,H]
        x = torch.where(ph.unsqueeze(-1), audio_at, x)
        cos, sin = rope_cos_sin(position_ids, self.stack.head_dim, self.stack.rope_base)
        x, past_k, past_v = self.stack.stack(x, cos, sin, attn_mask, None, None)
        logits = self.lm_head(x[:, -1:, :])  # [1,1,V]
        return logits, past_k, past_v


class StepExport(nn.Module):
    def __init__(self, model: Qwen3ASRForConditionalGeneration):
        super().__init__()
        self.embed = model.model.language_model.embed_tokens
        self.stack = DecoderLayersExport(model)
        self.lm_head = model.lm_head

    def forward(self, input_ids, past_k, past_v, attn_mask, position_ids):
        # input_ids [1,1]; past_k/v [L_layers,1,n_kv,P,hd]; mask [1,1,1,P+1]; pos [1,1]
        pks = [past_k[i] for i in range(past_k.shape[0])]
        pvs = [past_v[i] for i in range(past_v.shape[0])]
        x = self.embed(input_ids)
        cos, sin = rope_cos_sin(position_ids, self.stack.head_dim, self.stack.rope_base)
        x, k, v = self.stack.stack(x, cos, sin, attn_mask, pks, pvs)
        logits = self.lm_head(x)  # [1,1,V]
        return logits, k, v


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def needs_flatten(path: Path) -> bool:
    """True if the model references per-tensor external data files instead of
    a single <name>.onnx.data."""
    import onnx
    m = onnx.load(str(path), load_external_data=False)
    want = path.name + ".data"
    for init in m.graph.initializer:
        for e in init.external_data:
            if e.key == "location" and e.value != want:
                return True
    return False


def flatten_and_dedupe(path: Path) -> Path:
    """torch.onnx.export writes ONE external file per initializer for >2GB graphs,
    which onnxruntime.quantization cannot read. Load the model (external data
    into memory), dedupe identical big initializers (tied weights; dims must
    match!), re-save with a single .onnx.data file and remove the per-tensor
    files. Returns the path of the quantization-ready model."""
    import onnx
    m = onnx.load(str(path))
    ext_files = set()
    for init in m.graph.initializer:
        for e in init.external_data:
            if e.key == "location":
                ext_files.add(e.value)
    sizes = {}
    for init in m.graph.initializer:
        n = 1
        for d in init.dims:
            n *= d
        sizes.setdefault((n, tuple(init.dims)), []).append(init.name)
    for (numel, dims), names in sorted(sizes.items()):
        if len(names) > 1 and numel > 10_000_000:
            # merge ONLY byte-identical weights (tied weights); same shape alone
            # is NOT sufficient (1.7B gate_proj of all layers share shape!)
            keep = names[0]
            keep_arr = None
            for cand in names[1:]:
                if keep_arr is None:
                    keep_arr = onnx.numpy_helper.to_array(
                        next(i for i in m.graph.initializer if i.name == keep))
                cand_arr = onnx.numpy_helper.to_array(
                    next(i for i in m.graph.initializer if i.name == cand))
                if not np.array_equal(keep_arr, cand_arr):
                    continue
                for node in m.graph.node:
                    for k, inp in enumerate(node.input):
                        if inp == cand:
                            node.input[k] = keep
                drop_me = cand
                keepers = [i for i in m.graph.initializer if i.name != drop_me]
                del m.graph.initializer[:]
                m.graph.initializer.extend(keepers)
                print(f"  dedupe {drop_me} -> {keep} ({numel} elems, byte-identical)")
    out2 = path
    onnx.save_model(
        m, str(out2), save_as_external_data=True, all_tensors_to_one_file=True,
        location=out2.name + ".data", size_threshold=1024,
    )
    for f in ext_files:
        p = path.parent / f
        if p.exists() and p.name != out2.name + ".data":
            p.unlink()
    return out2


def export(onnx_path, module, args_sample, input_names, output_names, dynamic_axes, opset):
    torch.onnx.export(
        module, args_sample, str(onnx_path),
        input_names=input_names, output_names=output_names,
        dynamic_axes=dynamic_axes, opset_version=opset,
        do_constant_folding=True, dynamo=False,
    )


def _log_line(stage: str, model_name: str, seconds: float, artifact: Path) -> None:
    import datetime
    line = (f"{datetime.datetime.now().isoformat(timespec='seconds')} stage={stage} model={model_name} "
            f"seconds={seconds:.0f} artifact={artifact.name} bytes={artifact.stat().st_size} "
            f"sha256={sha256(artifact)}")
    log = Path("/home/dev/Voice-Keyboard/tools/qwen_ref/EXPORT_LOG.txt")
    with open(log, "a") as f:
        f.write(line + "\n")
    print("LOG " + line)


def _load_model(model_dir: str) -> Qwen3ASRForConditionalGeneration:
    model = Qwen3ASRForConditionalGeneration.from_pretrained(
        model_dir, dtype=torch.float32, device_map="cpu"
    )
    model.eval()
    return model


def _build_encoder_inputs(cfg):
    nc = 3
    T = nc * 100
    S = nc * 13
    mel = torch.randn(1, 128, T) * 0.5 + 0.5
    win = 13 * (cfg.audio_config.n_window_infer // (cfg.audio_config.n_window * 2))
    mask = torch.full((1, 1, S, S), NEG_INF)
    start = 0
    while start < S:
        end = min(start + win, S)
        mask[:, :, start:end, start:end] = 0.0
        start = end
    return mel, mask, T, S


def stage_mel(model_dir: str, out: Path, force: bool) -> None:
    import numpy as np
    from transformers import AutoProcessor
    d = out / "mel"
    d.mkdir(parents=True, exist_ok=True)
    target = d / "mel_filters.npy"
    if target.exists() and not force:
        print("mel: present, skip")
        return
    t0 = time.time()
    proc = AutoProcessor.from_pretrained(model_dir)
    fe = proc.feature_extractor
    np.save(target, fe.mel_filters.astype(np.float32))
    import json
    (d / "mel_config.json").write_text(json.dumps({
        "num_mel_bins": fe.feature_size, "n_fft": fe.n_fft, "hop_length": fe.hop_length,
        "sampling_rate": fe.sampling_rate, "min_length": fe.min_length,
        "n_window": fe.n_window, "norm": "slaney", "mel_scale": "slaney",
        "max_frequency": 8000.0, "log10_clamp": 1e-10, "norm_max_minus": 8.0,
        "log_offset": 4.0, "log_div": 4.0,
    }))
    _log_line("mel", Path(model_dir).name, time.time() - t0, target)


def stage_encoder(model_dir: str, out: Path, force: bool, skip_parity: bool) -> None:
    d = out / "encoder"
    d.mkdir(parents=True, exist_ok=True)
    int8 = d / "encoder.int8.onnx"
    fp32 = d / "encoder.onnx"
    if int8.exists() and not force:
        print("encoder: int8 present, skip")
        return
    t0 = time.time()
    if not fp32.exists() or force:
        model = _load_model(model_dir)
        cfg = model.config
        enc = EncoderExport(model).eval()
        mel, mask, T, S = _build_encoder_inputs(cfg)
        with torch.no_grad():
            ours = enc(mel, mask)
            if not skip_parity:
                ref = model.model.get_audio_features(
                    input_features=mel, input_features_mask=torch.ones(1, T, dtype=torch.long),
                    return_dict=True,
                ).pooler_output.unsqueeze(0)
                diff = (ours - ref).abs().max().item()
                print(f"encoder parity max|diff| = {diff:.3e}")
                assert diff < 1e-3, "encoder parity FAILED"
        export(fp32, enc, (mel, mask),
               ["input_features", "attn_mask"], ["audio_embeds"],
               {"input_features": {2: "T"}, "attn_mask": {2: "S", 3: "S"}, "audio_embeds": {1: "S"}},
               17)
        del model, enc
        import gc; gc.collect()
    if needs_flatten(fp32):
        src = flatten_and_dedupe(fp32)
    else:
        src = fp32
    from onnxruntime.quantization import quantize_dynamic, QuantType
    quantize_dynamic(str(src), str(int8), weight_type=QuantType.QInt8)
    _log_line("encoder", Path(model_dir).name, time.time() - t0, int8)


def stage_prefill(model_dir: str, out: Path, force: bool, skip_parity: bool) -> None:
    d = out / "prefill"
    d.mkdir(parents=True, exist_ok=True)
    int8 = d / "qwen_prefill.int8.onnx"
    fp32 = d / "qwen_prefill.onnx"
    if int8.exists() and not force:
        print("prefill: int8 present, skip")
        return
    t0 = time.time()
    if not fp32.exists() or force:
        model = _load_model(model_dir)
        cfg = model.config
        audio_token = cfg.audio_token_id
        enc = EncoderExport(model).eval()
        mel, mask, T, S = _build_encoder_inputs(cfg)
        with torch.no_grad():
            audio_embeds = enc(mel, mask)
        pre = PrefillExport(model).eval()
        ids = [151644, 198, 151645, 198, 151644, 8701, 198]
        ids += [audio_token] * S
        ids += [151645, 198, 151644, 77091, 198]
        ids += [9866, 5441, 6338, 20165]  # approx 'language German<asr_text>' prefill
        input_ids = torch.tensor([ids], dtype=torch.long)
        L = input_ids.shape[1]
        causal = torch.triu(torch.full((1, 1, L, L), NEG_INF), diagonal=1)
        pos = torch.arange(L, dtype=torch.long)[None, :]
        with torch.no_grad():
            ours_l, ours_k, ours_v = pre(input_ids, audio_embeds, causal, pos)
            if not skip_parity:
                ref_out = model(
                    input_ids=input_ids, input_features=mel,
                    input_features_mask=torch.ones(1, T, dtype=torch.long),
                    attention_mask=torch.ones(1, L, dtype=torch.long),
                    position_ids=pos, use_cache=True,
                )
                d1 = (ours_l - ref_out.logits[:, -1:, :]).abs().max().item()
                print(f"prefill logits parity max|diff| = {d1:.3e}")
                assert d1 < 5e-3, "prefill parity FAILED"
        export(fp32, pre, (input_ids, audio_embeds, causal, pos),
               ["input_ids", "audio_embeds", "attn_mask", "position_ids"],
               ["logits", "past_k", "past_v"],
               {"input_ids": {1: "L"}, "audio_embeds": {1: "S"}, "attn_mask": {2: "L", 3: "L"},
                "position_ids": {1: "L"}, "past_k": {2: "L"}, "past_v": {2: "L"}},
               17)
        del model, enc, pre
        import gc; gc.collect()
    if needs_flatten(fp32):
        src = flatten_and_dedupe(fp32)
    else:
        src = fp32
    from onnxruntime.quantization import quantize_dynamic, QuantType
    quantize_dynamic(str(src), str(int8), weight_type=QuantType.QInt8)
    _log_line("prefill", Path(model_dir).name, time.time() - t0, int8)


def stage_step(model_dir: str, out: Path, force: bool, skip_parity: bool) -> None:
    d = out / "step"
    d.mkdir(parents=True, exist_ok=True)
    int8 = d / "qwen_step.int8.onnx"
    fp32 = d / "qwen_step.onnx"
    if int8.exists() and not force:
        print("step: int8 present, skip")
        return
    t0 = time.time()
    if not fp32.exists() or force:
        model = _load_model(model_dir)
        cfg = model.config
        audio_token = cfg.audio_token_id
        enc = EncoderExport(model).eval()
        mel, mask, T, S = _build_encoder_inputs(cfg)
        with torch.no_grad():
            audio_embeds = enc(mel, mask)
        pre = PrefillExport(model).eval()
        ids = [151644, 198, 151645, 198, 151644, 8701, 198]
        ids += [audio_token] * S
        ids += [151645, 198, 151644, 77091, 198]
        ids += [9866, 5441, 6338, 20165]
        input_ids = torch.tensor([ids], dtype=torch.long)
        L = input_ids.shape[1]
        causal = torch.triu(torch.full((1, 1, L, L), NEG_INF), diagonal=1)
        pos = torch.arange(L, dtype=torch.long)[None, :]
        with torch.no_grad():
            _, ours_k, ours_v = pre(input_ids, audio_embeds, causal, pos)
        step = StepExport(model).eval()
        tok = torch.tensor([[100]], dtype=torch.long)
        P = L
        step_pos = torch.tensor([[P]], dtype=torch.long)
        step_mask = torch.zeros(1, 1, 1, P + 1)
        with torch.no_grad():
            sl, _, _ = step(tok, ours_k, ours_v, step_mask, step_pos)
            if not skip_parity:
                ref_out = model(
                    input_ids=input_ids, input_features=mel,
                    input_features_mask=torch.ones(1, T, dtype=torch.long),
                    attention_mask=torch.ones(1, L, dtype=torch.long),
                    position_ids=pos, use_cache=True,
                )
                ref2 = model(
                    input_ids=tok, attention_mask=torch.ones(1, P + 1, dtype=torch.long),
                    position_ids=step_pos, past_key_values=ref_out.past_key_values, use_cache=True,
                )
                d2 = (sl - ref2.logits).abs().max().item()
                print(f"step logits parity max|diff| = {d2:.3e}")
                assert d2 < 5e-3, "step parity FAILED"
        export(fp32, step, (tok, ours_k, ours_v, step_mask, step_pos),
               ["input_ids", "past_k", "past_v", "attn_mask", "position_ids"],
               ["logits", "past_k_out", "past_v_out"],
               {"past_k": {3: "P"}, "past_v": {3: "P"}, "attn_mask": {3: "P"},
                "past_k_out": {3: "P1"}, "past_v_out": {3: "P1"}},
               17)
        del model, enc, pre, step
        import gc; gc.collect()
    if needs_flatten(fp32):
        src = flatten_and_dedupe(fp32)
    else:
        src = fp32
    from onnxruntime.quantization import quantize_dynamic, QuantType
    quantize_dynamic(str(src), str(int8), weight_type=QuantType.QInt8)
    _log_line("step", Path(model_dir).name, time.time() - t0, int8)


def main():

    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", default="/home/dev/models/qwen-export/Qwen3-ASR-0.6B-hf")
    ap.add_argument("--out-dir", default="/home/dev/models/qwen-export/out-0.6b")
    ap.add_argument("--stage", required=True,
                    choices=["mel", "encoder", "prefill", "step", "all"])
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--skip-parity", action="store_true")
    args = ap.parse_args()

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(0)
    stages = (["mel", "encoder", "prefill", "step"] if args.stage == "all" else [args.stage])
    for st in stages:
        if st == "mel":
            stage_mel(args.model_dir, out, args.force)
        elif st == "encoder":
            stage_encoder(args.model_dir, out, args.force, args.skip_parity)
        elif st == "prefill":
            stage_prefill(args.model_dir, out, args.force, args.skip_parity)
        elif st == "step":
            stage_step(args.model_dir, out, args.force, args.skip_parity)


if __name__ == "__main__":
    main()
