#!/usr/bin/env python3
"""T25a: ONNX export of Qwen3 TEXT models (summarizer candidates A/B) — fp32 only.

Stages (skip-if-present, one subdir per stage, own EXPORT_LOG.txt):
  download  pinned HF files -> /home/dev/models/sum-export/<id>/raw/ (+PIN.json,
            every file logged with size + sha256)
  prefill   qwen_text_prefill.onnx: input_ids [1,L] + position_ids [1,L] +
            additive causal mask [1,1,L,L] -> logits [1,1,V] (last pos) +
            past_k/past_v [layers,1,kv_heads,L,head_dim]; parity vs HF fp32
  step      qwen_text_step.onnx: token [1,1] + past_k/past_v + position_ids [1,1]
            + mask [1,1,1,P+1] -> logits [1,1,V] + new past; parity = full
            greedy loop (ONNX prefill+step vs HF greedy): identical tokens AND
            logits rel-diff <= 1e-4 (SUM plan §3 gate row 1)
  report    writes <out>/export_report.json (sizes, shas, parities)

NO quantize / NO ExternalData fusion here — that is T25b. No audio branch ever:
this is a separate TEXT model, Qwen3-ASR must never be repurposed (plan §2).

RAM guard (plan §9 risk row): each export stage checks MemAvailable first
(B: >= 8 GB, A fp32: >= 24 GB) and prints NO-GO + exit 3 instead of OOM-crash.
Stage RSS is recorded from resource.getrusage; the intended caller wraps stages
in /usr/bin/time -v and appends that output to EXPORT_LOG.txt.

Usage: .venv-asr/bin/python tools/sum_ref/export_qwen_text_onnx.py \
          --candidate b --stage all
"""
import argparse
import hashlib
import json
import resource
import shutil
import sys
import time
from pathlib import Path

import numpy as np
import torch

NEG_INF = -1e9
OPSET = 17
VOCAB_MIN = 1000  # deterministic sample ids stay in [VOCAB_MIN, V-1000)

MODELS = {
    "b": {
        "hf_id": "Qwen/Qwen3-0.6B",
        "rev": "c1899de289a04d12100db370d81485cdf75e47ca",
        "out": "b-0.6b",
        "min_ram_fp32_gb": 8,
    },
    "a": {
        "hf_id": "Qwen/Qwen3-1.7B",
        "rev": "70d244cc86ccca08cf5af4e1e306ecf908b1ad5e",
        "out": "a-1.7b",
        "min_ram_fp32_gb": 24,
    },
}
FILES = [
    "config.json", "generation_config.json", "tokenizer.json",
    "tokenizer_config.json", "vocab.json", "merges.txt",
]
LOG = Path("/home/dev/Voice-Keyboard/tools/sum_ref/EXPORT_LOG.txt")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _log(stage: str, model_name: str, seconds: float, artifact: Path,
         note: str = "") -> None:
    import datetime
    rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    line = (f"{datetime.datetime.now().isoformat(timespec='seconds')} stage={stage} "
            f"model={model_name} seconds={seconds:.0f} artifact={artifact.name} "
            f"bytes={artifact.stat().st_size} sha256={sha256(artifact)} "
            f"rss_max_kb={rss_kb}{(' ' + note) if note else ''}")
    with open(LOG, "a") as f:
        f.write(line + "\n")
    print("LOG " + line)


def _log_plain(msg: str) -> None:
    import datetime
    with open(LOG, "a") as f:
        f.write(f"{datetime.datetime.now().isoformat(timespec='seconds')} {msg}\n")
    print("LOG " + msg)


def check_ram(min_gb: int, stage: str) -> None:
    avail = 0
    for line in open("/proc/meminfo"):
        if line.startswith("MemAvailable:"):
            avail = int(line.split()[1]) // (1024 * 1024)  # GB
    if avail < min_gb:
        print(f"NO-GO stage={stage}: need >={min_gb} GB MemAvailable, "
              f"have {avail} GB — refusing to start (RAM guard, plan §9). "
              f"Free memory (finish background Gradle) and re-run; this is NOT "
              f"a retry loop.", file=sys.stderr)
        sys.exit(3)
    print(f"RAM guard OK stage={stage}: MemAvailable {avail} GB >= {min_gb} GB")


# ---------------------------------------------------------------- download
def stage_download(cand: dict, out: Path, force: bool) -> Path:
    raw = out / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    pin = raw / "PIN.json"
    if pin.exists() and not force:
        print("download: PIN.json present, skip")
        return raw
    t0 = time.time()
    from huggingface_hub import hf_hub_download
    names = list(FILES)
    index = None
    try:  # model files: single safetensors or sharded index
        p = hf_hub_download(cand["hf_id"], "model.safetensors", revision=cand["rev"])
        names.append("model.safetensors")
    except Exception:
        p = hf_hub_download(cand["hf_id"], "model.safetensors.index.json",
                            revision=cand["rev"])
        index = json.loads(Path(p).read_text())
        names.append("model.safetensors.index.json")
        names += sorted(set(index["weight_map"].values()))
    for n in names:
        if n in ("vocab.json", "merges.txt") and not _exists_on_hub(cand, n):
            continue
        p = Path(hf_hub_download(cand["hf_id"], n, revision=cand["rev"]))
        dst = raw / n
        if not dst.exists() or force:
            shutil.copyfile(p, dst)
        _log_plain(f"download file={n} model={cand['hf_id']} bytes={dst.stat().st_size} "
                   f"sha256={sha256(dst)}")
    pin.write_text(json.dumps({"hf_id": cand["hf_id"], "revision": cand["rev"],
                               "files": names}, indent=1))
    total = sum(f.stat().st_size for f in raw.iterdir() if f.is_file() and f.name != "PIN.json")
    _log("download", cand["hf_id"], time.time() - t0, pin, note=f"total_bytes={total}")
    return raw


def _exists_on_hub(cand: dict, name: str) -> bool:
    from huggingface_hub import hf_hub_download
    try:
        hf_hub_download(cand["hf_id"], name, revision=cand["rev"])
        return True
    except Exception:
        return False


# ---------------------------------------------------------------- wrapper modules
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
    cos, sin = cos.unsqueeze(1), sin.unsqueeze(1)
    return q * cos + rotate_half(q) * sin, k * cos + rotate_half(k) * sin


class DecoderStack(torch.nn.Module):
    """Qwen3 decoder layers with explicit past KV tensors (pattern: qwen_ref)."""

    def __init__(self, lm, cfg):
        super().__init__()
        self.lm = lm
        self.eps = cfg.rms_norm_eps
        self.head_dim = cfg.head_dim
        self.n_heads = cfg.num_attention_heads
        self.n_kv = cfg.num_key_value_heads
        self.rope_base = getattr(cfg, "rope_theta", None) or cfg.rope_parameters["rope_theta"]
        self.layers = lm.layers

    def one_layer(self, layer, x, cos, sin, attn_mask, past_k, past_v):
        attn = layer.self_attn
        B, S, _H = x.shape
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


class PrefillExport(torch.nn.Module):
    def __init__(self, model, cfg):
        super().__init__()
        self.embed = model.model.embed_tokens
        self.stack = DecoderStack(model.model, cfg)
        self.lm_head = model.lm_head

    def forward(self, input_ids, attn_mask, position_ids):
        x = self.embed(input_ids)
        cos, sin = rope_cos_sin(position_ids, self.stack.head_dim, self.stack.rope_base)
        x, past_k, past_v = self.stack.stack(x, cos, sin, attn_mask, None, None)
        return self.lm_head(x[:, -1:, :]), past_k, past_v


class StepExport(torch.nn.Module):
    def __init__(self, model, cfg):
        super().__init__()
        self.embed = model.model.embed_tokens
        self.stack = DecoderStack(model.model, cfg)
        self.lm_head = model.lm_head

    def forward(self, input_ids, past_k, past_v, attn_mask, position_ids):
        pks = [past_k[i] for i in range(past_k.shape[0])]
        pvs = [past_v[i] for i in range(past_v.shape[0])]
        x = self.embed(input_ids)
        cos, sin = rope_cos_sin(position_ids, self.stack.head_dim, self.stack.rope_base)
        x, k, v = self.stack.stack(x, cos, sin, attn_mask, pks, pvs)
        return self.lm_head(x), k, v


def _load_model(raw: Path):
    from transformers import Qwen3ForCausalLM
    m = Qwen3ForCausalLM.from_pretrained(str(raw), dtype=torch.float32, device_map="cpu")
    m.eval()
    return m


def _sample_prompt(cfg) -> torch.Tensor:
    g = torch.Generator().manual_seed(0)
    ids = torch.randint(VOCAB_MIN, cfg.vocab_size - 1000, (1, 24), generator=g)
    return ids


def _rel(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.abs(a - b).max() / max(float(np.abs(b).max()), 1e-6))


def _export(onnx_path, module, args_sample, input_names, output_names, dyn):
    onnx_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = onnx_path.parent / (onnx_path.stem + "_raw.onnx")
    torch.onnx.export(
        module, args_sample, str(tmp),
        input_names=input_names, output_names=output_names,
        dynamic_axes=dyn, opset_version=OPSET,
        do_constant_folding=True, dynamo=False, external_data=True,
    )
    tmp.replace(onnx_path)  # stable names (also protects partial exports)
    for f in onnx_path.parent.glob(onnx_path.stem.removesuffix("_raw") + "*"):
        pass
    for f in onnx_path.parent.glob(tmp.name + "*"):
        f.rename(onnx_path.parent / f.name.replace(tmp.name, onnx_path.name))


def _ort(path: Path):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = min(16, torch.get_num_threads())
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


# ---------------------------------------------------------------- prefill
def stage_prefill(cand: dict, raw: Path, out: Path, force: bool, skip_parity: bool) -> dict:
    d = out / "prefill"
    fp32 = d / "qwen_text_prefill.onnx"
    if fp32.exists() and not force:
        print("prefill: fp32 present, skip")
        return {"path": str(fp32), "skipped": True,
                "bytes": fp32.stat().st_size, "sha256": sha256(fp32)}
    check_ram(cand["min_ram_fp32_gb"], "prefill")
    t0 = time.time()
    model = _load_model(raw)
    cfg = model.config
    pre = PrefillExport(model, cfg).eval()
    ids = _sample_prompt(cfg)
    L = ids.shape[1]
    causal = torch.triu(torch.full((1, 1, L, L), NEG_INF), diagonal=1)
    pos = torch.arange(L, dtype=torch.long)[None, :]
    parity = {}
    with torch.no_grad():
        ours_l, _, _ = pre(ids, causal, pos)
        if not skip_parity:
            ref = model(input_ids=ids, attention_mask=torch.ones(1, L, dtype=torch.long),
                        position_ids=pos, use_cache=False)
            rel = _rel(ours_l.numpy(), ref.logits[:, -1:, :].numpy())
            print(f"prefill logits parity rel = {rel:.3e}")
            assert rel <= 1e-4, f"prefill parity FAILED rel={rel:.3e} > 1e-4"
            parity = {"prefill_logits_rel": rel}
            del ref
    _export(fp32, pre, (ids, causal, pos),
            ["input_ids", "attn_mask", "position_ids"],
            ["logits", "past_k", "past_v"],
            {"input_ids": {1: "L"}, "attn_mask": {2: "L", 3: "L"},
             "position_ids": {1: "L"}, "past_k": {2: "L"}, "past_v": {2: "L"}},
            )
    del model, pre
    import gc; gc.collect()
    secs = time.time() - t0
    _log("prefill_fp32", cand["hf_id"], secs, fp32,
         note=f"parity_rel={parity.get('prefill_logits_rel', 'skipped')}")
    return {"path": str(fp32), "bytes": fp32.stat().st_size, "sha256": sha256(fp32),
            "seconds": secs, **parity}


# ---------------------------------------------------------------- step
def stage_step(cand: dict, raw: Path, out: Path, force: bool, skip_parity: bool) -> dict:
    d = out / "step"
    fp32 = d / "qwen_text_step.onnx"
    if fp32.exists() and not force:
        print("step: fp32 present, skip")
        return {"path": str(fp32), "skipped": True,
                "bytes": fp32.stat().st_size, "sha256": sha256(fp32)}
    check_ram(cand["min_ram_fp32_gb"], "step")
    pre_onnx = out / "prefill" / "qwen_text_prefill.onnx"
    if not pre_onnx.exists():
        sys.exit(f"NO-GO: {pre_onnx} missing — run --stage prefill before --stage step")
    t0 = time.time()
    model = _load_model(raw)
    cfg = model.config
    step = StepExport(model, cfg).eval()
    ids = _sample_prompt(cfg)
    L = ids.shape[1]
    N_NEW = 8
    causal = torch.triu(torch.full((1, 1, L, L), NEG_INF), diagonal=1)
    pos = torch.arange(L, dtype=torch.long)[None, :]
    with torch.no_grad():
        _, k0, v0 = PrefillExport(model, cfg).eval()(ids, causal, pos)
    tok = torch.tensor([[int(ids[0, -1])]], dtype=torch.long)  # feed last prompt token
    P = L
    s_pos = torch.tensor([[P]], dtype=torch.long)
    s_mask = torch.zeros(1, 1, 1, P + 1)
    with torch.no_grad():
        sl, _, _ = step(tok, k0, v0, s_mask, s_pos)
        if not skip_parity:
            ref = model(input_ids=ids, attention_mask=torch.ones(1, L, dtype=torch.long),
                        position_ids=pos, use_cache=True)
            ref2 = model(input_ids=tok, attention_mask=torch.ones(1, P + 1, dtype=torch.long),
                         position_ids=s_pos, past_key_values=ref.past_key_values, use_cache=True)
            rel = _rel(sl.numpy(), ref2.logits.numpy())
            print(f"step logits parity rel = {rel:.3e}")
            assert rel <= 1e-4, f"step parity FAILED rel={rel:.3e} > 1e-4"
            del ref, ref2
    _export(fp32, step, (tok, k0, v0, s_mask, s_pos),
            ["input_ids", "past_k", "past_v", "attn_mask", "position_ids"],
            ["logits", "past_k_out", "past_v_out"],
            {"past_k": {3: "P"}, "past_v": {3: "P"}, "attn_mask": {3: "P"},
             "past_k_out": {3: "P1"}, "past_v_out": {3: "P1"}},
            )
    parity = {"step_logits_rel": rel} if not skip_parity else {}
    # full greedy-loop parity: ORT prefill+step vs HF greedy (token IDs only — rule 9)
    if not skip_parity:
        parity.update(_greedy_parity(raw, pre_onnx, fp32, ids, N_NEW))
    del model, step, k0, v0
    import gc; gc.collect()
    secs = time.time() - t0
    _log("step_fp32", cand["hf_id"], secs, fp32,
         note=";".join(f"{k}={v:.3e}" for k, v in parity.items() if isinstance(v, float))
         + f" greedy_match={parity.get('greedy_tokens_identical')}")
    return {"path": str(fp32), "bytes": fp32.stat().st_size, "sha256": sha256(fp32),
            "seconds": secs, **parity}


def _greedy_parity(raw: Path, pre_onnx: Path, step_onnx: Path, ids: torch.Tensor,
                   n_new: int) -> dict:
    L = ids.shape[1]
    # ONNX path
    sess_p, sess_s = _ort(pre_onnx), _ort(step_onnx)
    mask = np.triu(np.full((1, 1, L, L), NEG_INF, dtype=np.float32), 1)
    feed = {"input_ids": ids.numpy(), "attn_mask": mask,
            "position_ids": np.arange(L, dtype=np.int64)[None, :]}
    outs = sess_p.run(None, feed)
    logits, k, v = outs[0], outs[1], outs[2]
    onnx_seq, onnx_logits = [], [logits[0, -1, :].copy()]  # logits that pick token i
    for i in range(n_new):
        nxt = int(logits[0, -1, :].argmax())
        onnx_seq.append(nxt)
        P = L + i
        outs = sess_s.run(None, {
            "input_ids": np.array([[nxt]], dtype=np.int64), "past_k": k, "past_v": v,
            "attn_mask": np.zeros((1, 1, 1, P + 1), dtype=np.float32),
            "position_ids": np.array([[P]], dtype=np.int64)})
        logits, k, v = outs[0], outs[1], outs[2]
        if i < n_new - 1:
            onnx_logits.append(logits[0, 0, :].copy())
    # HF path (same greedy loop, HF cache)
    model = _load_model(raw)
    hf_seq, ref_logits = [], []
    with torch.no_grad():
        out = model(input_ids=ids, attention_mask=torch.ones(1, L, dtype=torch.long),
                    position_ids=torch.arange(L, dtype=torch.long)[None, :], use_cache=True)
        cache = out.past_key_values
        ref_logits.append(out.logits[0, -1, :].numpy())
        for i in range(n_new):
            nxt = int(out.logits[0, -1, :].argmax())
            hf_seq.append(nxt)
            out = model(input_ids=torch.tensor([[nxt]]),
                        attention_mask=torch.ones(1, L + i + 1, dtype=torch.long),
                        position_ids=torch.tensor([[L + i]]),
                        past_key_values=cache, use_cache=True)
            cache = out.past_key_values
            if i < n_new - 1:
                ref_logits.append(out.logits[0, -1, :].numpy())
    rels = [_rel(a, b) for a, b in zip(onnx_logits, ref_logits)]
    max_rel = max(rels)
    print(f"greedy loop parity: identical={onnx_seq == hf_seq} "
          f"logits_rel_max={max_rel:.3e} (per-step {[f'{r:.2e}' for r in rels]})")
    assert onnx_seq == hf_seq, f"greedy token mismatch ONNX={onnx_seq} HF={hf_seq}"
    assert max_rel <= 1e-4, f"greedy logits parity FAILED rel={max_rel:.3e} > 1e-4"
    del model, cache
    import gc; gc.collect()
    return {"greedy_tokens_identical": onnx_seq == hf_seq,
            "greedy_logits_rel_max": max_rel,
            "greedy_onnx_ids": onnx_seq, "greedy_hf_ids": hf_seq}
# ---------------------------------------------------------------- report
def stage_report(cand: dict, out: Path, parts: dict) -> None:
    rep = {"candidate": cand["hf_id"], "revision": cand["rev"], "stages": parts,
           "opset": OPSET, "scope": "T25a fp32 only (int8+fusion = T25b)"}
    p = out / "export_report.json"
    p.write_text(json.dumps(rep, indent=1))
    print(f"report -> {p}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidate", required=True, choices=["a", "b"])
    ap.add_argument("--stage", default="all", choices=["download", "prefill", "step", "all"])
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--skip-parity", action="store_true")
    args = ap.parse_args()
    cand = MODELS[args.candidate]
    out = Path("/home/dev/models/sum-export") / cand["out"]
    out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(0)
    parts = {}
    stages = ["download", "prefill", "step"] if args.stage == "all" else [args.stage]
    raw = out / "raw"
    for st in stages:
        if st == "download":
            stage_download(cand, out, args.force)
        elif st == "prefill":
            parts["prefill"] = stage_prefill(cand, raw, out, args.force, args.skip_parity)
        elif st == "step":
            parts["step"] = stage_step(cand, raw, out, args.force, args.skip_parity)
    if args.stage == "all":
        stage_report(cand, out, parts)


if __name__ == "__main__":
    main()
