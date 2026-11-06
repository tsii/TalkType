#!/usr/bin/env python3
"""T24a: merge duplicated initializers across the U2 int8 graphs (de/en).

Fusion path (b) — ONNX graph rewrite via ExternalData (NOT a re-export):
every initializer >= 1 KiB whose raw bytes are byte-identical (sha256) in two
or more of the three graphs (encoder / decoder_init / decoder_step) is moved
ONCE into a language-shared binary blob and replaced in each graph by an
ExternalData reference (location/offset/length). Weights are therefore
byte-identical to the unfused export (verified in-script, per tensor), and the
graph structure (nodes, inputs, outputs, value_info) is untouched -> the merge
cannot change model behavior. Duplicate groups are identified by CONTENT, not
by name (the legacy tracer numbers MatMul initializers differently per graph).

Artifact layout (unfused export stays untouched; graphs flat at the root so
ExternalData locations stay INSIDE the model dir — onnx/ORT reject or dislike
".." references):
  /home/dev/models/bart-export/{lang}-merged/
    encoder.int8.onnx                    (external refs shared/…)
    decoder_init.int8.onnx
    decoder_step.int8.onnx
    shared/shared_weights.bin            (all deduplicated tensors, packed)
    tokenizer/…                          (copied 1:1 from {lang}/tokenizer)

Verification per language (asserted, fails loudly):
  1. reload of every merged graph resolves all external refs (onnx.load) and
     every initializer is array-identical to the unfused original;
  2. node list / graph inputs / outputs / value_info byte-identical
     (only initializer storage changed);
  3. onnx.checker passes on the loaded models.

Log: one stage=merge line per artifact file in tools/bart_ref/EXPORT_LOG.txt
(same format as export_bart_onnx.py, incl. tool versions).

Usage:
  .venv-asr/bin/python tools/bart_ref/merge_shared_initializers.py --lang en
  .venv-asr/bin/python tools/bart_ref/merge_shared_initializers.py --lang de --force
"""
import argparse
import datetime
import hashlib
import resource
import shutil
import sys
import time
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, numpy_helper

ROOT = Path("/home/dev/models/bart-export")
REPO = Path("/home/dev/Voice-Keyboard")
LOG = REPO / "tools/bart_ref/EXPORT_LOG.txt"
GRAPHS = ("encoder", "decoder_init", "decoder_step")
MIN_EXT_BYTES = 1024          # only deduplicate tensors >= 1 KiB
ALIGN = 1                     # exact packing, no padding


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def log_line(stage: str, lang: str, seconds: float, artifact: Path, note: str = "") -> None:
    line = (f"{datetime.datetime.now().isoformat(timespec='seconds')} stage={stage} lang={lang} "
            f"seconds={seconds:.0f} peak_rss_mb={peak_rss_mb():.0f} "
            f"artifact={artifact.name} bytes={artifact.stat().st_size} "
            f"sha256={sha256(artifact)}{' ' + note if note else ''}")
    with open(LOG, "a") as f:
        f.write(line + "\n")
    print("LOG " + line)


def load_graph(lang: str, g: str):
    return onnx.load(str(ROOT / lang / g / f"{g}.int8.onnx"), load_external_data=True)


def raw_bytes(t) -> bytes:
    if t.raw_data:
        return t.raw_data
    # scalars / small tensors may use typed data fields (e.g. float_data)
    return numpy_helper.to_array(t).tobytes()


def node_fingerprint(m) -> bytes:
    """Everything except initializers: nodes + graph IO + value_info."""
    fp = hashlib.sha256()
    for n in m.graph.node:
        fp.update(n.SerializeToString(deterministic=True))
    for io in (m.graph.input, m.graph.output, m.graph.value_info):
        for v in io:
            fp.update(v.SerializeToString(deterministic=True))
    return fp.digest()


def merge(lang: str, force: bool) -> None:
    out = ROOT / f"{lang}-merged"
    if (out / "shared" / "shared_weights.bin").exists() and not force:
        print(f"{lang}-merged present, skip (use --force to rebuild)")
        return
    if out.exists():
        shutil.rmtree(out)
    t0 = time.time()

    models = {g: load_graph(lang, g) for g in GRAPHS}

    # --- find cross-graph duplicate groups by content (sha256, length)
    blobs = {}          # (sha, nbytes) -> bytes  (canonical content store)
    members = {}        # (sha, nbytes) -> {graph: [tensor_proto, ...]}
    for g, m in models.items():
        for t in m.graph.initializer:
            b = raw_bytes(t)
            if len(b) < MIN_EXT_BYTES:
                continue
            k = (hashlib.sha256(b).hexdigest(), len(b))
            blobs.setdefault(k, b)
            members.setdefault(k, {}).setdefault(g, []).append(t)
    dup_keys = [k for k, m in members.items() if len(m) >= 2]
    ext_bytes = sum(k[1] for k in dup_keys)
    saved_bytes = sum(k[1] * (sum(len(v) for v in members[k].values()) - 1)
                      for k in dup_keys)
    print(f"{lang}: {len(members)} unique tensors >= {MIN_EXT_BYTES} B, "
          f"{len(dup_keys)} duplicated across graphs, "
          f"{ext_bytes/1e6:.1f} MB in shared blob, "
          f"{saved_bytes/1e6:.1f} MB copies removed")

    # --- pack shared blob (deterministic order: by size desc, then sha)
    shared_dir = out / "shared"
    shared_dir.mkdir(parents=True)
    bin_path = shared_dir / "shared_weights.bin"
    loc = "shared/shared_weights.bin"
    offsets = {}
    with open(bin_path, "wb") as f:
        for k in sorted(dup_keys, key=lambda k: (-k[1], k[0])):
            offsets[k] = f.tell()
            f.write(blobs[k])
    assert bin_path.stat().st_size == ext_bytes

    # --- rewrite duplicated initializers as ExternalData references
    def to_external(t: onnx.TensorProto, k) -> None:
        t.data_location = TensorProto.EXTERNAL
        t.ClearField("raw_data")
        del t.external_data[:]
        for key, val in (("location", loc),
                         ("offset", str(offsets[k])),
                         ("length", str(k[1]))):
            e = t.external_data.add()
            e.key, e.value = key, val

    n_ext = 0
    for g, m in models.items():
        for t in m.graph.initializer:
            b = raw_bytes(t) if t.raw_data else None
            if b is None or len(b) < MIN_EXT_BYTES:
                continue
            k = (hashlib.sha256(b).hexdigest(), len(b))
            if k in offsets:
                to_external(t, k)
                n_ext += 1
        d = out
        onnx.save(m, str(d / f"{g}.int8.onnx"))
    print(f"{lang}: externalized {n_ext} initializer references into {bin_path}")

    # --- copy tokenizer 1:1 (same sha256 as stage=tokenizer)
    shutil.copytree(ROOT / lang / "tokenizer", out / "tokenizer")

    # --- verification 1+2: reload, per-tensor byte identity + structure identity
    for g in GRAPHS:
        orig = load_graph(lang, g)
        merged = onnx.load(str(out / f"{g}.int8.onnx"), load_external_data=True)
        assert node_fingerprint(merged) == node_fingerprint(orig), \
            f"{g}: node/IO structure changed!"
        onnx.checker.check_model(merged)
        orig_init = {t.name: t for t in orig.graph.initializer}
        assert len(orig_init) == len(merged.graph.initializer)
        for t in merged.graph.initializer:
            o = orig_init[t.name]
            assert list(t.dims) == list(o.dims) and t.data_type == o.data_type
            a = numpy_helper.to_array(t)
            b = numpy_helper.to_array(o)
            assert a.shape == b.shape and a.dtype == b.dtype
            assert np.array_equal(a, b), f"{g}:{t.name} bytes differ after merge!"
        del orig, merged

    # --- log (same format as export stages)
    note = (f"tools=onnx{onnx.__version__}/ort"
            f"{__import__('onnxruntime').__version__}/"
            f"py{sys.version_info.major}.{sys.version_info.minor} "
            f"dedup_tensors={len(dup_keys)} removed_copies_mb={saved_bytes/1e6:.1f} "
            f"src_sha=content-identical")
    for g in GRAPHS:
        log_line("merge", lang, time.time() - t0, out / f"{g}.int8.onnx", note)
    log_line("merge", lang, time.time() - t0, bin_path, note)

    total = sum((out / f"{g}.int8.onnx").stat().st_size for g in GRAPHS) \
        + bin_path.stat().st_size
    print(f"{lang}-merged total int8: {total/1e6:.1f} MB "
          f"(unfused {sum((ROOT/lang/g/f'{g}.int8.onnx').stat().st_size for g in GRAPHS)/1e6:.1f} MB)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True, choices=["de", "en"])
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    merge(args.lang, args.force)


if __name__ == "__main__":
    main()
