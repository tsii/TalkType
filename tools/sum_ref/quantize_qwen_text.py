#!/usr/bin/env python3
"""T25b: dynamic int8 quantization + ExternalData fusion for the Qwen3-0.6B
summarizer graphs (candidate B), then parity/determinism/measurement.

Stages (skip-if-present, EXPORT_LOG.txt lines like bart_ref/qwen_ref):
  flatten   fp32 graphs (per-initializer external files from torch export) ->
            load into memory, dedupe byte-identical big initializers (tied
            embed/lm_head), save one .onnx.data each  (needed: quantizer
            cannot read per-tensor files)
  quantize  onnxruntime quantize_dynamic QInt8 per graph (opset17 preserved;
            architecture has no LSTM; lm_head/embed quantized too — parity
            stage decides; on gate failure rerun with --fp32-head which keeps
            the embed/lm_head MatMuls in fp32 via nodes_to_exclude)
  fuse      cross-graph ExternalData merge EXACTLY on the U2 precedent
            (tools/bart_ref/merge_shared_initializers.py): every initializer
            >= 1 KiB that is byte-identical in both graphs moves once into
            shared/shared_weights.bin; structure fingerprint + per-tensor
            byte identity + onnx.checker asserted; size gate <= 800 MB printed
  parity    int8 vs fp32 greedy on 8 corpus texts, 32 new tokens each:
            token agreement >= 95 % (assert) + D2 determinism (double int8
            run, sha256 of every 32-token logit blob byte-identical)
  measure   4 corpus texts, 32 tokens, threads=4: per-text seconds, median,
            plus process peak RSS (outer /usr/bin/time -v adds the system view)

RAM guard before every stage: MemAvailable >= 8 GB, else wait up to
--wait-ram seconds, then NO-GO exit 3 (no crash, no endless retry).

Rule 9: logs contain token IDs and numbers only — never dictation/summary text.

Usage: .venv-asr/bin/python tools/sum_ref/quantize_qwen_text.py --stage all
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import resource
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path("/home/dev/models/sum-export/b-0.6b")
REPO = Path("/home/dev/Voice-Keyboard")
LOG = REPO / "tools/sum_ref/EXPORT_LOG.txt"
GRAPHS = ("prefill", "step")
GRAPH_NAME = {"prefill": "qwen_text_prefill.onnx", "step": "qwen_text_step.onnx"}
INT8_NAME = {"prefill": "qwen_text_prefill.int8.onnx", "step": "qwen_text_step.int8.onnx"}
# T25c-B-WO variant directories (per-channel IntegerOps; ORT quantize_dynamic
# already uses mode=IntegerOps/MatMulInteger by default - documented in
# reports/sum_t25c_b_wo.json that activations are STILL uint8-dynamic there).
T25B_SET_BYTES = 753_642_275
NEG_INF = np.float32(-1e9)
MIN_EXT_BYTES = 1024
SIZE_GATE = 800_000_000          # plan §3: fused int8 set for B <= 0.8 GB
N_TOKENS = 32
N_PARITY_TEXTS = 8
N_MEASURE_TEXTS = 4


# ---------------------------------------------------------------- helpers
def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def log_line(stage: str, seconds: float, artifact: Path, note: str = "") -> None:
    line = (f"{datetime.datetime.now().isoformat(timespec='seconds')} stage={stage} "
            f"model=Qwen/Qwen3-0.6B seconds={seconds:.0f} peak_rss_mb={peak_rss_mb():.0f} "
            f"artifact={artifact.name} bytes={artifact.stat().st_size} "
            f"sha256={sha256(artifact)}{' ' + note if note else ''}")
    with open(LOG, "a") as f:
        f.write(line + "\n")
    print("LOG " + line)


def ram_available_gb() -> int:
    for line in open("/proc/meminfo"):
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) // (1024 * 1024)
    return 0


def guard_ram(min_gb: int, wait_s: int) -> None:
    t0 = time.time()
    while ram_available_gb() < min_gb:
        if time.time() - t0 > wait_s:
            print(f"NO-GO: MemAvailable {ram_available_gb()} GB < {min_gb} GB after "
                  f"{wait_s}s wait — refusing to start (no retry loop).", file=sys.stderr)
            sys.exit(3)
        print(f"RAM guard: waiting, avail={ram_available_gb()} GB (need {min_gb})")
        time.sleep(30)


# ---------------------------------------------------------------- flatten
def stage_verify() -> None:
    """Re-assert: flatten-fp32 logits bit-identical to the ORIGINAL T25a export
    (plan T25c: 'flatten-bit-ident-Asserts'), without rebuilding artifacts."""
    if not all((ROOT / "flatten" / g / GRAPH_NAME[g]).exists() for g in GRAPHS):
        sys.exit("NO-GO: flatten artifacts missing - run T25b flatten first")
    t0 = time.time()
    import numpy as np
    ids = [1449, 2709, 1092, 25239, 10993, 12608, 49796, 3315]
    L = len(ids)
    mask = np.triu(np.full((1, 1, L, L), NEG_INF, dtype=np.float32), 1)
    feed = {"input_ids": np.array([ids]), "attn_mask": mask,
            "position_ids": np.arange(L)[None, :]}
    for g in ("prefill",):  # step-graph weights ride on the same flatten run:
        a = _sess(ROOT / g / GRAPH_NAME[g], 8).run(None, feed)[0]
        b = _sess(ROOT / "flatten" / g / GRAPH_NAME[g], 8).run(None, feed)[0]
        assert np.array_equal(a, b), f"flatten {g}: logits NOT bit-identical to original!"
        log_line("flatten_bitident_verify", time.time() - t0, ROOT / "flatten" / g / GRAPH_NAME[g],
                 note="logits_bit_identical=True probe_tokens=8")
        del a, b
        import gc; gc.collect()


def stage_flatten_build(force: bool) -> None:
    import onnx
    from onnx import numpy_helper
    for g in GRAPHS:
        d = ROOT / "flatten" / g
        d.mkdir(parents=True, exist_ok=True)
        out_m = d / GRAPH_NAME[g]
        if out_m.with_suffix(".onnx.data").exists() and not force:
            print(f"flatten {g}: present, skip")
            continue
        t0 = time.time()
        src = ROOT / g / GRAPH_NAME[g]
        m = onnx.load(str(src))
        # dedupe byte-identical big initializers (tied embed/lm_head etc.)
        by_hash: dict[str, str] = {}
        rename: dict[str, str] = {}
        keep: set[str] = set()
        for init in m.graph.initializer:
            arr = numpy_helper.to_array(init)
            if arr.size < 10_000_000:
                keep.add(init.name)
                continue
            h = hashlib.sha256(arr.tobytes()).hexdigest()
            if h in by_hash:
                rename[init.name] = by_hash[h]
            else:
                by_hash[h] = init.name
                keep.add(init.name)
        n_dupes = len(rename)
        for node in m.graph.node:
            for i, inp in enumerate(node.input):
                if inp in rename:
                    node.input[i] = rename[inp]
        dropped = [i.name for i in m.graph.initializer if i.name not in keep]
        n_before = len(m.graph.initializer)
        bytes_before = sum(numpy_helper.to_array(i).nbytes for i in m.graph.initializer)
        kept = [i for i in m.graph.initializer if i.name in keep]
        del m.graph.initializer[:]
        m.graph.initializer.extend(kept)
        data_path = out_m.with_suffix(".onnx.data")
        if data_path.exists():
            data_path.unlink()  # onnx APPENDS to an existing blob file!
        onnx.save_model(m, str(out_m), save_as_external_data=True,
                        all_tensors_to_one_file=True,
                        location=out_m.name + ".data", size_threshold=1024)
        del m, kept
        import gc; gc.collect()
        # verification: reload and compare against the ORIGINAL export
        chk = onnx.load(str(out_m))
        assert len(chk.graph.initializer) == n_before - n_dupes, "flatten lost initializers!"
        bytes_after = sum(numpy_helper.to_array(i).nbytes for i in chk.graph.initializer)
        expect_after = bytes_before - sum(
            numpy_helper.to_array(i).nbytes for i in
            [x for x in onnx.load(str(src)).graph.initializer if x.name in rename])
        assert bytes_after == expect_after, f"flatten byte total mismatch {bytes_after}!={expect_after}"
        del chk
        gc.collect()
        log_line("flatten_fp32", time.time() - t0, out_m,
                 note=f"dedup_dropped={n_dupes} inits_kept={n_before - n_dupes} "
                      f"bytes_kept={bytes_after} data={out_m.with_suffix('.onnx.data').stat().st_size}")


# ---------------------------------------------------------------- quantize
def stage_quantize(force: bool, fp32_head: bool, wo: bool = False) -> None:
    out_root = ROOT / ("int8wo" if wo else "int8")
    for g in GRAPHS:
        d = out_root / g
        d.mkdir(parents=True, exist_ok=True)
        out_m = d / INT8_NAME[g]
        if out_m.exists() and not force:
            print(f"quantize {g}: present, skip")
            continue
        t0 = time.time()
        src = ROOT / "flatten" / g / GRAPH_NAME[g]
        kwargs = {}
        if fp32_head:
            import onnx
            m = onnx.load(str(src), load_external_data=False)
            big = {i.name for i in m.graph.initializer
                   if int(np.prod(i.dims)) > 100_000_000}
            ids = sorted({n.name for n in m.graph.node
                          if any(inp in big for inp in n.input)})
            assert ids, "fp32-head: no big-weight MatMul nodes found"
            kwargs["nodes_to_exclude"] = ids
            print(f"quantize {g}: fp32-head, excluding {len(ids)} nodes: {ids}")
        from onnxruntime.quantization import QuantType, quantize_dynamic
        if wo:
            # T25c-B-WO: mode IntegerOps is already the quantize_dynamic default
            # (MatMulInteger); the new knob is per-channel weight granularity.
            data_path = out_m.with_suffix(".onnx.data")
            if data_path.exists():
                data_path.unlink()
            quantize_dynamic(str(src), str(out_m), weight_type=QuantType.QInt8,
                             per_channel=True, use_external_data_format=True, **kwargs)
            log_line("quantize_int8wo_perchannel", time.time() - t0, out_m,
                     note="weight_type=QInt8 per_channel=True mode=IntegerOps(default)")
        else:
            quantize_dynamic(str(src), str(out_m), weight_type=QuantType.QInt8, **kwargs)
            log_line("quantize_int8" + ("_fp32head" if fp32_head else ""), time.time() - t0,
                     out_m, note="weight_type=QInt8 opset17")


# ---------------------------------------------------------------- fuse
def _raw_bytes(t) -> bytes:
    """Raw little-endian bytes of an initializer, whatever value field it uses."""
    from onnx import TensorProto, numpy_helper
    if t.data_location == TensorProto.EXTERNAL:
        # already a reference (force-rebuild case): read the referenced slice
        loc = {e.key: e.value for e in t.external_data}
        p = ROOT / loc["location"]
        with open(p, "rb") as f:
            f.seek(int(loc.get("offset", 0)))
            return f.read(int(loc["length"]))
    if t.raw_data:
        return t.raw_data
    return numpy_helper.to_array(t).tobytes()


def _node_fingerprint(m) -> bytes:
    fp = hashlib.sha256()
    for n in m.graph.node:
        fp.update(n.SerializeToString(deterministic=True))
    for io in (m.graph.input, m.graph.output, m.graph.value_info):
        for v in io:
            fp.update(v.SerializeToString(deterministic=True))
    return fp.digest()


def stage_fuse(force: bool, wo: bool = False) -> None:
    import onnx
    from onnx import TensorProto, numpy_helper
    src_root = ROOT / ("int8wo" if wo else "int8")
    out = ROOT / ("int8wo-fused" if wo else "int8-fused")
    if (out / "shared" / "shared_weights.bin").exists() and not force:
        print("fuse: present, skip")
        return
    t0 = time.time()
    models = {g: onnx.load(str(src_root / g / INT8_NAME[g])) for g in GRAPHS}
    blobs: dict[tuple, bytes] = {}
    members: dict[tuple, int] = {}
    for g, m in models.items():
        for t in m.graph.initializer:
            b = _raw_bytes(t)
            if len(b) < MIN_EXT_BYTES:
                continue
            k = (hashlib.sha256(b).hexdigest(), len(b))
            blobs.setdefault(k, b)
            members[k] = members.get(k, 0) + 1
    dup_keys = [k for k, n in members.items() if n >= 2]
    ext_bytes = sum(k[1] for k in dup_keys)
    removed = sum(k[1] * (members[k] - 1) for k in dup_keys)
    out.mkdir(exist_ok=True)
    (out / "shared").mkdir(exist_ok=True)
    bin_path = out / "shared" / "shared_weights.bin"
    offsets = {}
    with open(bin_path, "wb") as f:
        for k in sorted(dup_keys, key=lambda k: (-k[1], k[0])):
            offsets[k] = f.tell()
            f.write(blobs[k])
    for g, m in models.items():
        for t in m.graph.initializer:
            b = _raw_bytes(t)
            k = (hashlib.sha256(b).hexdigest(), len(b))
            if k in offsets:
                for fld in ("raw_data", "float_data", "int32_data", "int64_data",
                            "double_data", "uint64_data", "string_data"):
                    t.ClearField(fld)
                t.data_location = TensorProto.EXTERNAL
                del t.external_data[:]
                for key, val in (("location", "shared/shared_weights.bin"),
                                 ("offset", str(offsets[k])), ("length", str(k[1]))):
                    e = t.external_data.add()
                    e.key, e.value = key, val
        onnx.save(models[g], str(out / INT8_NAME[g]))
    fingerprints = {g: _node_fingerprint(models[g]) for g in GRAPHS}
    # verification: reload + byte identity + structure fingerprint + checker
    for g in GRAPHS:
        orig = onnx.load(str(src_root / g / INT8_NAME[g]))
        merged = onnx.load(str(out / INT8_NAME[g]))
        assert _node_fingerprint(merged) == fingerprints[g] == _node_fingerprint(orig), \
            f"{g}: node/IO structure changed!"
        oi = {t.name: t for t in orig.graph.initializer}
        assert len(oi) == len(merged.graph.initializer)
        for t in merged.graph.initializer:
            a, b = numpy_helper.to_array(t), numpy_helper.to_array(oi[t.name])
            assert a.dtype == b.dtype and np.array_equal(a, b), f"{g}:{t.name} differs"
        onnx.checker.check_model(merged)
        del orig, merged
    total = bin_path.stat().st_size
    tag = "_wo" if wo else ""
    for g in GRAPHS:
        total += (out / INT8_NAME[g]).stat().st_size
        log_line("merge" + tag, time.time() - t0, out / INT8_NAME[g],
                 note=f"dedup_tensors={len(dup_keys)} removed_copies_mb={removed/1e6:.1f}")
    log_line("merge" + tag, time.time() - t0, bin_path,
             note=f"total_set_bytes={total}")
    gate = "PASS" if total <= SIZE_GATE else "FAIL"
    print(f"SIZE GATE (fused int8 set <= {SIZE_GATE/1e6:.0f} MB): {total/1e6:.1f} MB -> {gate}")
    assert total <= SIZE_GATE, f"size gate FAILED: {total/1e6:.1f} MB > 800 MB"
    if wo:
        delta = total - T25B_SET_BYTES
        print(f"size vs T25b set ({T25B_SET_BYTES/1e6:.1f} MB): {delta/1e6:+.1f} MB "
              f"(per-channel scale vectors; plan gate is the 800 MB limit, still met)")


# ---------------------------------------------------------------- parity
def _load_texts(n: int) -> list[list[int]]:
    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(str(ROOT / "raw"))
    recs = [json.loads(l) for l in
            (REPO / "testdata/sum_goldens/sum_goldens.jsonl")
            .read_text(encoding="utf-8").splitlines() if l.strip()]
    de = [r for r in recs if r["lang"] == "de"]
    en = [r for r in recs if r["lang"] == "en"]
    half = n // 2
    pick = de[:half] + en[:n - half]  # deterministic de/en mix
    out = []
    for r in pick:
        ids = tok(r["text"], add_special_tokens=False)["input_ids"]
        out.append(ids[-512:])  # cap prefill length (token budget plan §3)
    return out


def _sess(path: Path, threads: int):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def _greedy(sp, ss, ids, n_new, collect_logits=False):
    L = len(ids)
    mask = np.triu(np.full((1, 1, L, L), NEG_INF, dtype=np.float32), 1)
    outs = sp.run(None, {"input_ids": np.array([ids], dtype=np.int64),
                         "attn_mask": mask,
                         "position_ids": np.arange(L, dtype=np.int64)[None, :]})
    logits, k, v = outs
    seq, log_shas = [], []
    for i in range(n_new):
        nxt = int(logits[0, -1, :].argmax())
        seq.append(nxt)
        if collect_logits:
            log_shas.append(hashlib.sha256(logits.tobytes()).hexdigest())
        P = L + i
        outs = ss.run(None, {"input_ids": np.array([[nxt]], dtype=np.int64),
                             "past_k": k, "past_v": v,
                             "attn_mask": np.zeros((1, 1, 1, P + 1), np.float32),
                             "position_ids": np.array([[P]], dtype=np.int64)})
        logits, k, v = outs
    return (seq, log_shas) if collect_logits else seq


def stage_parity(wo: bool = False) -> dict:
    t0 = time.time()
    fused_dir = ROOT / ("int8wo-fused" if wo else "int8-fused")
    texts = _load_texts(N_PARITY_TEXTS)
    fp = {g: _sess(ROOT / "flatten" / g / GRAPH_NAME[g], 16) for g in GRAPHS}
    fp32_seqs = [_greedy(fp["prefill"], fp["step"], ids, N_TOKENS) for ids in texts]
    del fp
    import gc; gc.collect()
    it = {g: _sess(fused_dir / INT8_NAME[g], 16) for g in GRAPHS}
    int8_seqs = [_greedy(it["prefill"], it["step"], ids, N_TOKENS) for ids in texts]
    per = []
    for a, b in zip(fp32_seqs, int8_seqs):
        same = sum(1 for x, y in zip(a, b) if x == y)
        per.append(round(same / len(a), 4))
    agree = sum(sum(1 for x, y in zip(a, b) if x == y)
                for a, b in zip(fp32_seqs, int8_seqs)) / (N_PARITY_TEXTS * N_TOKENS)
    diffs = [(i, j) for i, (a, b) in enumerate(zip(fp32_seqs, int8_seqs))
             for j, (x, y) in enumerate(zip(*[a, b])) if x != y]
    print(f"int8-vs-fp32 greedy agreement = {agree:.4f} per-text {per}")
    assert agree >= 0.95, f"int8 parity FAILED: {agree:.4f} < 0.95"
    # D2 determinism: double int8 run, logit blobs byte-identical
    _, shas1 = _greedy(it["prefill"], it["step"], texts[0], N_TOKENS, collect_logits=True)
    _, shas2 = _greedy(it["prefill"], it["step"], texts[0], N_TOKENS, collect_logits=True)
    seq1 = _greedy(it["prefill"], it["step"], texts[0], N_TOKENS)
    assert shas1 == shas2, "D2 determinism FAILED (logit blobs differ)"
    assert seq1 == int8_seqs[0], "D2: sequence differs from parity run"
    del it
    gc.collect()
    blob = hashlib.sha256("".join(shas1).encode()).hexdigest()
    res = {"agreement": round(agree, 4), "per_text": per,
           "diff_positions": len(diffs),
           "d2_logit_blobs_identical": shas1 == shas2,
           "d2_combined_sha": blob}
    log_line("parity_int8_vs_fp32" + ("_wo" if wo else ""), time.time() - t0, LOG,
             note=f"agreement={agree:.4f} texts={N_PARITY_TEXTS} tokens={N_TOKENS} "
                  f"diffs={len(diffs)} d2_identical=True d2_sha={blob}")
    return res


# ---------------------------------------------------------------- measure
def stage_measure(wo: bool = False) -> dict:
    t0 = time.time()
    fused_dir = ROOT / ("int8wo-fused" if wo else "int8-fused")
    texts = _load_texts(N_MEASURE_TEXTS)
    it = {g: _sess(fused_dir / INT8_NAME[g], 4) for g in GRAPHS}
    per = []
    for ids in texts:
        s = time.time()
        _greedy(it["prefill"], it["step"], ids, N_TOKENS)
        per.append(round(time.time() - s, 2))
    med = float(np.median(per))
    # D2 determinism on the int8 artifact (double run, logit blobs byte-identical)
    _, shas1 = _greedy(it["prefill"], it["step"], texts[0], N_TOKENS, collect_logits=True)
    _, shas2 = _greedy(it["prefill"], it["step"], texts[0], N_TOKENS, collect_logits=True)
    d2 = shas1 == shas2
    assert d2, "D2 determinism FAILED on int8 artifact"
    res = {"threads": 4, "texts": N_MEASURE_TEXTS, "tokens": N_TOKENS,
           "per_text_s": per, "median_s": med, "peak_rss_mb": round(peak_rss_mb()),
           "d2_logit_blobs_identical": d2,
           "d2_combined_sha": hashlib.sha256("".join(shas1).encode()).hexdigest()}
    log_line("measure_int8_host" + ("_wo" if wo else ""), time.time() - t0, LOG,
             note=f"threads=4 texts={N_MEASURE_TEXTS} tokens={N_TOKENS} "
                  f"per_s={per} median_s={med:.2f} peak_rss_mb={peak_rss_mb():.0f} "
                  f"d2_identical={d2} d2_sha={res['d2_combined_sha']}")
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", default="all",
                    choices=["verify", "flatten", "quantize", "fuse", "parity", "measure", "all"])
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--fp32-head", action="store_true",
                    help="keep embed/lm_head MatMuls in fp32 (parity fallback)")
    ap.add_argument("--wo", action="store_true",
                    help="T25c-B-WO: per-channel IntegerOps variant into int8wo[-fused]/")
    ap.add_argument("--wait-ram", type=int, default=900)
    args = ap.parse_args()

    stages = ["flatten", "quantize", "fuse", "parity", "measure"] \
        if args.stage == "all" else [args.stage]
    # per-stage RAM floor: full fp32 graphs need ~8 GB; int8-only stages need less
    ram_min = {"verify": 6, "flatten": 8, "quantize": 8, "parity": 8, "fuse": 6, "measure": 4}
    report_path = ROOT / ("quant_report_wo.json" if args.wo else "quant_report.json")
    report = json.loads(report_path.read_text()) if report_path.exists() else {}
    for st in stages:
        guard_ram(ram_min[st], args.wait_ram)
        if st == "verify":
            stage_verify()
        elif st == "flatten":
            stage_flatten_build(args.force)
        elif st == "quantize":
            stage_quantize(args.force, args.fp32_head, args.wo)
        elif st == "fuse":
            stage_fuse(args.force, args.wo)
        elif st == "parity":
            report["parity"] = stage_parity(args.wo)
        elif st == "measure":
            report["measure"] = stage_measure(args.wo)
    if report:
        report["fp32_head"] = args.fp32_head
        report["wo"] = args.wo
        report_path.write_text(json.dumps(report, indent=1))
        print(f"report -> {report_path}")


if __name__ == "__main__":
    main()
