#!/usr/bin/env python3
"""U2-GATE: ONNX export of the seq2seq spelling-correction models (de/en).

Candidates (pinned revisions, see tools/bart_ref/EXPORT_LOG.txt):
  en: oliverguhr/spelling-correction-english-base @ 0e395835 (BART, base
      facebook/bart-base @ aadd2ab, both permissive: MIT model / Apache-2.0 base)
  de: oliverguhr/spelling-correction-german-base @ b3916256 (FLAN-T5, NOT BART
      despite the task title / model-card name "bart-base-spelling-de"; base
      google/flan-t5-base @ 7bcac57, both Apache-2.0)

Exports three graphs per language (hand-written wrappers over the HF weights,
legacy tracer, opset 17, fp32, then int8 dynamic quantization — same staged
pattern as tools/qwen_ref/export_qwen_onnx.py):

  encoder.onnx        input_ids [1,S] i64 -> last_hidden [1,S,768] f32
  decoder_init.onnx   decoder_start [1,1] i64 + encoder_hidden [1,S,768]
                      -> logits [1,1,V] + self_k/v [L,1,12,1,64]
                      + cross_k/v [L,1,12,S,64]   (KV cache WITH use_cache)
  decoder_step.onnx   input_ids [1,1] + position_ids [1,1] + self_k/v
                      [L,1,12,P,64] + cross_k/v -> logits + self_k/v_out
                      [L,1,12,P+1,64]           (cross cache is loop-invariant)

Each stage asserts numerical parity against the HuggingFace fp32 reference
(same inputs) BEFORE export: relative error <= 1e-4 on hidden states and
logits (<= 1e-5 relative on the KV tensors). One log line per stage in
tools/bart_ref/EXPORT_LOG.txt (time, stage, model, seconds, peak RSS,
artifact, bytes, sha256).

Usage:
  .venv-asr/bin/python tools/bart_ref/export_bart_onnx.py --lang en --stage all
  .venv-asr/bin/python tools/bart_ref/export_bart_onnx.py --lang de --stage all
"""
import argparse
import gc
import hashlib
import json
import math
import resource
import time
from pathlib import Path

import torch
import torch.nn.functional as F
from torch import nn
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

RAW = {
    "en": ("oliverguhr/spelling-correction-english-base",
           "0e3958355a09d2816ed2701fdc2f4471d46c320e"),
    "de": ("oliverguhr/spelling-correction-german-base",
           "b3916256af432f30dbaeb184a1c6b5be78b6f62f"),
}
OUT_ROOT = Path("/home/dev/models/bart-export")
LOG = Path("/home/dev/Voice-Keyboard/tools/bart_ref/EXPORT_LOG.txt")

# Relative-position buckets (T5), exact copy of transformers T5Attention.
# bidirectional=True for the encoder, False for the decoder self-attention.
NUM_BUCKETS, MAX_DISTANCE = 32, 128


def rel_bucket_bidirectional(rel):
    num_buckets = NUM_BUCKETS // 2
    buckets = (rel > 0).to(torch.long) * num_buckets
    rel = torch.abs(rel)
    max_exact = num_buckets // 2
    is_small = rel < max_exact
    large = max_exact + (torch.log(rel.float() / max_exact)
                        / math.log(MAX_DISTANCE / max_exact)
                        * (num_buckets - max_exact)).to(torch.long)
    large = torch.min(large, torch.full_like(large, num_buckets - 1))
    return buckets + torch.where(is_small, rel, large)


def rel_bucket_unidirectional(rel):
    rel = -torch.min(rel, torch.zeros_like(rel))
    max_exact = NUM_BUCKETS // 2
    is_small = rel < max_exact
    large = max_exact + (torch.log(rel.float() / max_exact)
                        / math.log(MAX_DISTANCE / max_exact)
                        * (NUM_BUCKETS - max_exact)).to(torch.long)
    large = torch.min(large, torch.full_like(large, NUM_BUCKETS - 1))
    return torch.where(is_small, rel, large)


def rms_norm(x, weight, eps):
    var = x.to(torch.float32).pow(2).mean(-1, keepdim=True)
    return weight * (x * torch.rsqrt(var + eps))


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def _log_line(stage: str, lang: str, seconds: float, artifact: Path, note: str = "") -> None:
    import datetime
    line = (f"{datetime.datetime.now().isoformat(timespec='seconds')} stage={stage} lang={lang} "
            f"seconds={seconds:.0f} peak_rss_mb={peak_rss_mb():.0f} "
            f"artifact={artifact.name} bytes={artifact.stat().st_size} "
            f"sha256={sha256(artifact)}{' ' + note if note else ''}")
    with open(LOG, "a") as f:
        f.write(line + "\n")
    print("LOG " + line)


def _load_model(model_dir: str):
    model = AutoModelForSeq2SeqLM.from_pretrained(
        model_dir, dtype=torch.float32, attn_implementation="eager")
    model.eval()
    return model


# --------------------------------------------------------------------------
# BART (en) — post-LN transformer, learned positional embeddings (offset 2)
# --------------------------------------------------------------------------

class BartEncoderExport(nn.Module):
    def __init__(self, model):
        super().__init__()
        enc = model.model.encoder
        self.embed = model.model.shared          # tied embed_tokens
        self.pos = enc.embed_positions           # BartLearnedPositionalEmbedding
        self.ln_emb = enc.layernorm_embedding
        self.layers = enc.layers
        self.n_heads = model.config.encoder_attention_heads
        self.scale = (model.config.d_model // self.n_heads) ** -0.5

    def one_layer(self, layer, x):
        B, S, _ = x.shape
        sa = layer.self_attn
        q = sa.q_proj(x).view(B, S, self.n_heads, -1).transpose(1, 2)
        k = sa.k_proj(x).view(B, S, self.n_heads, -1).transpose(1, 2)
        v = sa.v_proj(x).view(B, S, self.n_heads, -1).transpose(1, 2)
        w = torch.softmax(torch.matmul(q, k.transpose(-1, -2)) * self.scale, dim=-1)
        o = torch.matmul(w, v).transpose(1, 2).reshape(B, S, -1)
        x = layer.self_attn_layer_norm(x + sa.out_proj(o))
        h = layer.fc2(F.gelu(layer.fc1(x)))
        return layer.final_layer_norm(x + h)

    def forward(self, input_ids):  # [1,S] -> [1,S,768]
        x = self.embed(input_ids) + self.pos(input_ids)
        x = self.ln_emb(x)
        for layer in self.layers:
            x = self.one_layer(layer, x)
        return x


def _bart_decoder_common(model):
    """Shared per-layer step math. h [B,1,768]; returns (new_self_k, new_self_v)."""
    cfg = model.config
    n_heads = cfg.decoder_attention_heads
    scale = (cfg.d_model // n_heads) ** -0.5

    def self_attn(layer, h, past_k, past_v):
        B, S, _ = h.shape  # S == 1
        sa = layer.self_attn
        q = sa.q_proj(h).view(B, S, n_heads, -1).transpose(1, 2)
        k = sa.k_proj(h).view(B, S, n_heads, -1).transpose(1, 2)
        v = sa.v_proj(h).view(B, S, n_heads, -1).transpose(1, 2)
        if past_k is not None:
            k = torch.cat((past_k, k), dim=2)
            v = torch.cat((past_v, v), dim=2)
        w = torch.softmax(torch.matmul(q, k.transpose(-1, -2)) * scale, dim=-1)
        o = torch.matmul(w, v).transpose(1, 2).reshape(B, S, -1)
        x = layer.self_attn_layer_norm(h + sa.out_proj(o))
        return x, k, v

    def cross_attn(layer, h, cross_k, cross_v):
        B, S, _ = h.shape
        ca = layer.encoder_attn
        q = ca.q_proj(h).view(B, S, n_heads, -1).transpose(1, 2)
        w = torch.softmax(torch.matmul(q, cross_k.transpose(-1, -2)) * scale, dim=-1)
        o = torch.matmul(w, cross_v).transpose(1, 2).reshape(B, S, -1)
        return layer.encoder_attn_layer_norm(h + ca.out_proj(o))

    def ffn(layer, x):
        h = layer.fc2(F.gelu(layer.fc1(x)))
        return layer.final_layer_norm(x + h)

    return self_attn, cross_attn, ffn


class BartDecoderInitExport(nn.Module):
    """First decode step: decoder_start token + full cross-attn KV computed."""

    def __init__(self, model):
        super().__init__()
        dec = model.model.decoder
        self.embed = model.model.shared
        self.pos_weight = dec.embed_positions.weight  # lookup idx = position + 2
        self.ln_emb = dec.layernorm_embedding
        self.layers = dec.layers
        self.lm_head = model.lm_head
        self.logits_bias = model.final_logits_bias  # [1,V], zeros for bart-base
        self.self_attn, self.cross_attn, self.ffn = _bart_decoder_common(model)

    def forward(self, decoder_start_ids, encoder_hidden):
        # decoder_start_ids [1,1] i64 (position 0), encoder_hidden [1,S,768]
        pos = F.embedding(torch.zeros_like(decoder_start_ids) + 2, self.pos_weight)
        x = self.ln_emb(self.embed(decoder_start_ids) + pos)
        sks, svs, cks, cvs = [], [], [], []
        for layer in self.layers:
            x, k, v = self.self_attn(layer, x, None, None)
            B, S, _ = encoder_hidden.shape
            ca = layer.encoder_attn
            ck = ca.k_proj(encoder_hidden).view(B, S, -1, 64).permute(0, 2, 1, 3).contiguous()
            cv = ca.v_proj(encoder_hidden).view(B, S, -1, 64).permute(0, 2, 1, 3).contiguous()
            x = self.cross_attn(layer, x, ck, cv)
            x = self.ffn(layer, x)
            sks.append(k); svs.append(v); cks.append(ck); cvs.append(cv)
        logits = self.lm_head(x) + self.logits_bias
        return (logits, torch.stack(sks), torch.stack(svs),
                torch.stack(cks), torch.stack(cvs))


class BartDecoderStepExport(nn.Module):
    def __init__(self, model):
        super().__init__()
        dec = model.model.decoder
        self.embed = model.model.shared
        self.pos_weight = dec.embed_positions.weight
        self.ln_emb = dec.layernorm_embedding
        self.layers = dec.layers
        self.lm_head = model.lm_head
        self.logits_bias = model.final_logits_bias
        self.self_attn, self.cross_attn, self.ffn = _bart_decoder_common(model)

    def forward(self, input_ids, position_ids, self_k, self_v, cross_k, cross_v):
        # input_ids [1,1], position_ids [1,1] (= past length P),
        # self_k/v [L,1,H,P,hd], cross_k/v [L,1,H,S,hd]
        pos = F.embedding(position_ids + 2, self.pos_weight)
        x = self.ln_emb(self.embed(input_ids) + pos)
        sks, svs = [], []
        for i, layer in enumerate(self.layers):
            x, k, v = self.self_attn(layer, x, self_k[i], self_v[i])
            x = self.cross_attn(layer, x, cross_k[i], cross_v[i])
            x = self.ffn(layer, x)
            sks.append(k); svs.append(v)
        logits = self.lm_head(x) + self.logits_bias
        return logits, torch.stack(sks), torch.stack(svs)


# --------------------------------------------------------------------------
# T5 (de, flan-t5-base) — pre-LN RMSNorm blocks, relative position bias,
# no qk scaling, gated-gelu FFN, decoder output scaled by d_model**-0.5
# --------------------------------------------------------------------------

class T5EncoderExport(nn.Module):
    def __init__(self, model):
        super().__init__()
        enc = model.encoder
        self.embed = enc.embed_tokens  # tied to shared.weight
        self.blocks = enc.block
        self.final_ln = enc.final_layer_norm
        self.eps = model.config.layer_norm_epsilon
        self.rel_emb = self.blocks[0].layer[0].SelfAttention.relative_attention_bias

    def forward(self, input_ids):  # [1,S] -> [1,S,768]
        S = input_ids.shape[1]
        x = self.embed(input_ids)
        pos = torch.arange(S, dtype=torch.long)
        rel = pos[None, :] - pos[:, None]
        bias = self.rel_emb(rel_bucket_bidirectional(rel)).permute(2, 0, 1).unsqueeze(0)
        for block in self.blocks:
            sa = block.layer[0]
            h = rms_norm(x, sa.layer_norm.weight, self.eps)
            B, S_ = h.shape[0], h.shape[1]
            q = sa.SelfAttention.q(h).view(B, S_, 12, 64).transpose(1, 2)
            k = sa.SelfAttention.k(h).view(B, S_, 12, 64).transpose(1, 2)
            v = sa.SelfAttention.v(h).view(B, S_, 12, 64).transpose(1, 2)
            w = torch.softmax(torch.matmul(q, k.transpose(-1, -2)) + bias, dim=-1)
            o = sa.SelfAttention.o(torch.matmul(w, v).transpose(1, 2).reshape(B, S_, -1))
            x = x + o
            ff = block.layer[-1]
            h = rms_norm(x, ff.layer_norm.weight, self.eps)
            f = ff.DenseReluDense.wo(
                ff.DenseReluDense.act(ff.DenseReluDense.wi_0(h)) * ff.DenseReluDense.wi_1(h))
            x = x + f
        return rms_norm(x, self.final_ln.weight, self.eps)


def _t5_decoder_common(model):
    cfg = model.config
    eps = cfg.layer_norm_epsilon

    def self_attn(block, x, bias_row, past_k, past_v):
        sa = block.layer[0]
        h = rms_norm(x, sa.layer_norm.weight, eps)
        a = sa.SelfAttention
        B, S, _ = h.shape  # S == 1
        q = a.q(h).view(B, S, 12, 64).transpose(1, 2)
        k = a.k(h).view(B, S, 12, 64).transpose(1, 2)
        v = a.v(h).view(B, S, 12, 64).transpose(1, 2)
        if past_k is not None:
            k = torch.cat((past_k, k), dim=2)
            v = torch.cat((past_v, v), dim=2)
        w = torch.softmax(torch.matmul(q, k.transpose(-1, -2)) + bias_row, dim=-1)
        o = a.o(torch.matmul(w, v).transpose(1, 2).reshape(B, S, -1))
        return x + o, k, v

    def cross_attn(block, x, cross_k, cross_v):
        ca = block.layer[1]
        h = rms_norm(x, ca.layer_norm.weight, eps)
        a = ca.EncDecAttention
        B, S, _ = h.shape
        q = a.q(h).view(B, S, 12, 64).transpose(1, 2)
        w = torch.softmax(torch.matmul(q, cross_k.transpose(-1, -2)), dim=-1)
        o = a.o(torch.matmul(w, cross_v).transpose(1, 2).reshape(B, S, -1))
        return x + o

    def ffn(block, x):
        ff = block.layer[-1]
        h = rms_norm(x, ff.layer_norm.weight, eps)
        f = ff.DenseReluDense.wo(
            ff.DenseReluDense.act(ff.DenseReluDense.wi_0(h)) * ff.DenseReluDense.wi_1(h))
        return x + f

    return self_attn, cross_attn, ffn


class T5DecoderInitExport(nn.Module):
    def __init__(self, model):
        super().__init__()
        dec = model.decoder
        self.embed = dec.embed_tokens
        self.blocks = dec.block
        self.final_ln = dec.final_layer_norm
        self.rel_emb = self.blocks[0].layer[0].SelfAttention.relative_attention_bias
        self.lm_head = model.lm_head  # UNTIED (checkpoint carries its own weights)
        self.out_scale = model.config.d_model ** -0.5 if getattr(
            model.config, "scale_decoder_outputs", True) else 1.0
        self.eps = model.config.layer_norm_epsilon
        self.self_attn, self.cross_attn, self.ffn = _t5_decoder_common(model)

    def forward(self, decoder_start_ids, encoder_hidden):
        # decoder_start_ids [1,1] (position 0), encoder_hidden [1,S,768]
        B, S, _ = encoder_hidden.shape
        x = self.embed(decoder_start_ids)
        # query position 0, single key at position 0 -> rel 0 -> bucket 0
        bias_row = self.rel_emb(torch.zeros(1, 1, dtype=torch.long))  # [1,1,12]
        bias_row = bias_row.permute(2, 0, 1).unsqueeze(0)              # [1,12,1,1]
        sks, svs, cks, cvs = [], [], [], []
        for block in self.blocks:
            x, k, v = self.self_attn(block, x, bias_row, None, None)
            ca = block.layer[1].EncDecAttention
            ck = ca.k(encoder_hidden).view(B, S, 12, 64).permute(0, 2, 1, 3).contiguous()
            cv = ca.v(encoder_hidden).view(B, S, 12, 64).permute(0, 2, 1, 3).contiguous()
            x = self.cross_attn(block, x, ck, cv)
            x = self.ffn(block, x)
            sks.append(k); svs.append(v); cks.append(ck); cvs.append(cv)
        x = rms_norm(x, self.final_ln.weight, self.eps)
        logits = self.lm_head(x * self.out_scale)
        return (logits, torch.stack(sks), torch.stack(svs),
                torch.stack(cks), torch.stack(cvs))


class T5DecoderStepExport(nn.Module):
    def __init__(self, model):
        super().__init__()
        dec = model.decoder
        self.embed = dec.embed_tokens
        self.blocks = dec.block
        self.final_ln = dec.final_layer_norm
        self.rel_emb = self.blocks[0].layer[0].SelfAttention.relative_attention_bias
        self.lm_head = model.lm_head
        self.out_scale = model.config.d_model ** -0.5 if getattr(
            model.config, "scale_decoder_outputs", True) else 1.0
        self.eps = model.config.layer_norm_epsilon
        self.self_attn, self.cross_attn, self.ffn = _t5_decoder_common(model)

    def forward(self, input_ids, position_ids, self_k, self_v, cross_k, cross_v):
        # input_ids [1,1]; position_ids [1,1] = P (absolute query position);
        # self_k/v [L,1,12,P,64]; cross_k/v [L,1,12,S,64]
        K = self_k.shape[3] + 1
        mem = torch.arange(K, dtype=torch.long)
        rel = mem - position_ids.flatten()          # [P+1]
        buckets = rel_bucket_unidirectional(rel)    # [P+1]
        bias = self.rel_emb(buckets)                # [P+1,12]
        bias = bias.permute(1, 0).view(1, 12, 1, K)  # [1,12,1,P+1]
        x = self.embed(input_ids)
        sks, svs = [], []
        for i, block in enumerate(self.blocks):
            x, k, v = self.self_attn(block, x, bias, self_k[i], self_v[i])
            x = self.cross_attn(block, x, cross_k[i], cross_v[i])
            x = self.ffn(block, x)
            sks.append(k); svs.append(v)
        x = rms_norm(x, self.final_ln.weight, self.eps)
        logits = self.lm_head(x * self.out_scale)
        return logits, torch.stack(sks), torch.stack(svs)


# --------------------------------------------------------------------------
# Stages
# --------------------------------------------------------------------------

def _export(onnx_path, module, args_sample, input_names, output_names, dynamic_axes):
    torch.onnx.export(
        module, args_sample, str(onnx_path),
        input_names=input_names, output_names=output_names,
        dynamic_axes=dynamic_axes, opset_version=17,
        do_constant_folding=True, dynamo=False,
    )


def _quant(src: Path, dst: Path):
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8)


def _rel_err(a, b):
    return (a - b).abs().max().item() / b.abs().max().item()


def stage_raw(lang: str, force: bool) -> Path:
    raw_dir = OUT_ROOT / "raw" / lang
    raw_dir.mkdir(parents=True, exist_ok=True)
    repo, rev = RAW[lang]
    meta = raw_dir / "PIN.json"
    if not meta.exists():
        from huggingface_hub import snapshot_download
        snapshot_download(repo_id=repo, revision=rev, local_dir=str(raw_dir),
                          ignore_patterns=["runs/*", "*.bin", "*.h5", "*.msgpack",
                                           "*.ot", "*.onnx", "onnx/*", "*.tflite"])
        meta.write_text(json.dumps({"repo": repo, "revision": rev}, indent=2) + "\n")
        _log_line("raw", lang, 0, raw_dir / "model.safetensors",
                  note=f"repo={repo} revision={rev} (snapshot pinned)")
    else:
        print(f"raw[{lang}]: pinned snapshot present, skip")
    return raw_dir


def stage_tokenizer(lang: str, raw_dir: Path, out: Path, force: bool) -> None:
    d = out / "tokenizer"
    d.mkdir(parents=True, exist_ok=True)
    tok_json = d / "tokenizer.json"
    if tok_json.exists() and not force:
        print("tokenizer: present, skip")
        return
    t0 = time.time()
    tok = AutoTokenizer.from_pretrained(str(raw_dir))
    tok.save_pretrained(str(d))  # writes tokenizer.json + tokenizer_config.json
    cfg = json.loads((raw_dir / "config.json").read_text())
    meta = {
        "arch": cfg["architectures"][0],
        "tokenizer_class": tok.__class__.__name__,
        "vocab_size": len(tok),
        "bos_token_id": tok.bos_token_id,
        "eos_token_id": tok.eos_token_id,
        "pad_token_id": tok.pad_token_id,
        "decoder_start_token_id": cfg.get("decoder_start_token_id"),
        "input_prefix": "correct: " if lang == "de" else "",
    }
    (d / "vocab_meta.json").write_text(json.dumps(meta, indent=2) + "\n")
    _log_line("tokenizer", lang, time.time() - t0, tok_json)


def _hf_cache_tensors(past):
    sk = torch.stack([l.keys for l in past.self_attention_cache.layers])
    sv = torch.stack([l.values for l in past.self_attention_cache.layers])
    ck = torch.stack([l.keys for l in past.cross_attention_cache.layers])
    cv = torch.stack([l.values for l in past.cross_attention_cache.layers])
    return sk, sv, ck, cv


def stage_encoder(lang: str, model_dir: Path, out: Path, force: bool) -> None:
    d = out / "encoder"
    d.mkdir(parents=True, exist_ok=True)
    int8 = d / "encoder.int8.onnx"
    fp32 = d / "encoder.onnx"
    if int8.exists() and not force:
        print("encoder: int8 present, skip")
        return
    t0 = time.time()
    if not fp32.exists() or force:
        model = _load_model(str(model_dir))
        ids = torch.randint(100, 500, (1, 23))
        wrapper = (BartEncoderExport(model) if model.config.model_type == "bart"
                   else T5EncoderExport(model)).eval()
        with torch.no_grad():
            ours = wrapper(ids)
            if model.config.model_type == "bart":
                ref = model.model.encoder(input_ids=ids).last_hidden_state
            else:
                ref = model.encoder(input_ids=ids).last_hidden_state
            err = _rel_err(ours, ref)
            print(f"encoder parity max rel err = {err:.3e}")
            assert err <= 1e-4, "encoder parity FAILED"
        _export(fp32, wrapper, (ids,), ["input_ids"], ["last_hidden"],
                {"input_ids": {1: "S"}, "last_hidden": {1: "S"}})
        del model, wrapper
        gc.collect()
    _quant(fp32, int8)
    _log_line("encoder", lang, time.time() - t0, int8)


def _init_step_parity_and_export(lang, model_dir, out, stage_name, wrapper_cls, force):
    """Common staged export for decoder_init and decoder_step."""
    d = out / stage_name
    d.mkdir(parents=True, exist_ok=True)
    int8 = d / f"{stage_name}.int8.onnx"
    fp32 = d / f"{stage_name}.onnx"
    if int8.exists() and not force:
        print(f"{stage_name}: int8 present, skip")
        return
    t0 = time.time()
    if not fp32.exists() or force:
        model = _load_model(str(model_dir))
        is_bart = model.config.model_type == "bart"
        start_id = model.config.decoder_start_token_id
        enc_ids = torch.randint(100, 500, (1, 23))
        with torch.no_grad():
            if is_bart:
                enc_h = model.model.encoder(input_ids=enc_ids).last_hidden_state
            else:
                enc_h = model.encoder(input_ids=enc_ids).last_hidden_state
        wrapper = wrapper_cls(model).eval()
        if stage_name == "decoder_init":
            start = torch.tensor([[start_id]], dtype=torch.long)
            with torch.no_grad():
                ours = wrapper(start, enc_h)
                from transformers.modeling_outputs import BaseModelOutput
                ref = model(input_ids=enc_ids, decoder_input_ids=start,
                            encoder_outputs=BaseModelOutput(last_hidden_state=enc_h),
                            use_cache=True)
                ref_kv = _hf_cache_tensors(ref.past_key_values)
                err = _rel_err(ours[0], ref.logits)
                print(f"decoder_init logits parity max rel err = {err:.3e}")
                assert err <= 1e-4, "decoder_init logits parity FAILED"
                for name, a, b in zip(("self_k", "self_v", "cross_k", "cross_v"),
                                     ours[1:], ref_kv):
                    e = _rel_err(a, b)
                    print(f"  kv {name} rel err = {e:.3e}")
                    assert e <= 1e-5, f"kv {name} parity FAILED"
            _export(fp32, wrapper, (start, enc_h),
                    ["decoder_start_ids", "encoder_hidden"],
                    ["logits", "self_k", "self_v", "cross_k", "cross_v"],
                    {"encoder_hidden": {1: "S"}, "cross_k": {3: "S"},
                     "cross_v": {3: "S"}})
        else:  # decoder_step
            step_cls = wrapper_cls  # same class handles step via wrapper_cls=...StepExport
            start = torch.tensor([[start_id]], dtype=torch.long)
            init_cls = (BartDecoderInitExport if is_bart else T5DecoderInitExport)
            init = init_cls(model).eval()
            with torch.no_grad():
                _, sk, sv, ck, cv = init(start, enc_h)
                P = sk.shape[3]
                tok = torch.tensor([[123]], dtype=torch.long)
                pos = torch.tensor([[P]], dtype=torch.long)
                ours = step_cls(model).eval()(tok, pos, sk, sv, ck, cv)
                from transformers.modeling_outputs import BaseModelOutput
                ref1 = model(input_ids=enc_ids, decoder_input_ids=start,
                             encoder_outputs=BaseModelOutput(last_hidden_state=enc_h),
                             use_cache=True)
                ref2 = model(decoder_input_ids=tok,
                             encoder_outputs=BaseModelOutput(last_hidden_state=enc_h),
                             past_key_values=ref1.past_key_values, use_cache=True)
                err = _rel_err(ours[0], ref2.logits)
                print(f"decoder_step logits parity max rel err = {err:.3e}")
                assert err <= 1e-4, "decoder_step logits parity FAILED"
                ref_kv = _hf_cache_tensors(ref2.past_key_values)
                for name, a, b in zip(("self_k", "self_v"), ours[1:], ref_kv[:2]):
                    e = _rel_err(a, b)
                    print(f"  kv {name} rel err = {e:.3e}")
                    assert e <= 1e-5, f"kv {name} parity FAILED"
            _export(fp32, step_cls(model).eval(), (tok, pos, sk, sv, ck, cv),
                    ["input_ids", "position_ids", "self_k", "self_v",
                     "cross_k", "cross_v"],
                    ["logits", "self_k_out", "self_v_out"],
                    {"self_k": {3: "P"}, "self_v": {3: "P"},
                     "cross_k": {3: "S"}, "cross_v": {3: "S"},
                     "self_k_out": {3: "P1"}, "self_v_out": {3: "P1"}})
        del model, wrapper
        gc.collect()
    _quant(fp32, int8)
    _log_line(stage_name, lang, time.time() - t0, int8)


def stage_decoder_init(lang, model_dir, out, force):
    def cls(model):
        return (BartDecoderInitExport if model.config.model_type == "bart"
                else T5DecoderInitExport)(model)
    _init_step_parity_and_export(lang, model_dir, out, "decoder_init", cls, force)


def stage_decoder_step(lang, model_dir, out, force):
    def cls(model):
        return (BartDecoderStepExport if model.config.model_type == "bart"
                else T5DecoderStepExport)(model)
    _init_step_parity_and_export(lang, model_dir, out, "decoder_step", cls, force)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True, choices=["de", "en"])
    ap.add_argument("--stage", default="all",
                    choices=["raw", "tokenizer", "encoder", "decoder_init",
                             "decoder_step", "all"])
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    out = OUT_ROOT / args.lang
    out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(0)

    raw_dir = stage_raw(args.lang, args.force)
    stages = (["raw", "tokenizer", "encoder", "decoder_init", "decoder_step"]
              if args.stage == "all" else [args.stage])
    for st in stages:
        if st == "raw":
            pass  # done above
        elif st == "tokenizer":
            stage_tokenizer(args.lang, raw_dir, out, args.force)
        elif st == "encoder":
            stage_encoder(args.lang, raw_dir, out, args.force)
        elif st == "decoder_init":
            stage_decoder_init(args.lang, raw_dir, out, args.force)
        elif st == "decoder_step":
            stage_decoder_step(args.lang, raw_dir, out, args.force)


if __name__ == "__main__":
    main()
