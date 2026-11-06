#!/usr/bin/env python3
"""T24a: inventory of duplicated initializers across the U2 ONNX graphs.

For each language (de/en) and each of the three int8 graphs
(encoder / decoder_init / decoder_step) this dumps EVERY initializer with
name, dims, dtype, nbytes, sha256 of its raw bytes and the ONNX nodes that
consume it, then groups byte-identical initializers ACROSS graphs (the merge
candidates). Output: reports/bart_merge_analysis_{lang}.json + stdout summary.

Usage:
  .venv-asr/bin/python tools/bart_ref/merge_analysis.py --lang en
  .venv-asr/bin/python tools/bart_ref/merge_analysis.py --lang de
"""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path

import onnx
from onnx import TensorProto

ROOT = Path("/home/dev/models/bart-export")
REPO = Path("/home/dev/Voice-Keyboard")

DT_SIZE = {TensorProto.FLOAT: 4, TensorProto.UINT8: 1, TensorProto.INT8: 1,
           TensorProto.UINT16: 2, TensorProto.INT16: 2, TensorProto.INT32: 4,
           TensorProto.INT64: 8, TensorProto.BOOL: 1, TensorProto.FLOAT16: 2,
           TensorProto.DOUBLE: 8}
DT_NAME = {v: k for k, v in vars(TensorProto).items()
           if isinstance(v, int) and not k.startswith("_")}
GRAPHS = ("encoder", "decoder_init", "decoder_step")


def raw_bytes(t) -> bytes:
    if t.raw_data:
        return t.raw_data
    # fallbacks (ints/floats lists) — none expected for these graphs
    from onnx import numpy_helper
    return numpy_helper.to_array(t).tobytes()


def graph_inventory(path: Path):
    m = onnx.load(str(path), load_external_data=True)
    consumers = defaultdict(list)
    for n in m.graph.node:
        for i in n.input:
            consumers[i].append(n.op_type)
    inits = []
    for t in m.graph.initializer:
        b = raw_bytes(t)
        inits.append({
            "name": t.name, "dims": list(t.dims),
            "dtype": DT_NAME.get(t.data_type, str(t.data_type)),
            "nbytes": len(b), "sha256": hashlib.sha256(b).hexdigest(),
            "consumers": sorted(set(consumers[t.name])),
        })
    return inits, len(m.graph.node)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True, choices=["de", "en"])
    args = ap.parse_args()
    lang = args.lang

    inv = {}
    n_nodes = {}
    for g in GRAPHS:
        p = ROOT / lang / g / f"{g}.int8.onnx"
        inv[g], n_nodes[g] = graph_inventory(p)

    # cross-graph duplicate groups (keyed by content sha, only >= 1 KiB)
    groups = defaultdict(list)
    for g in GRAPHS:
        for t in inv[g]:
            if t["nbytes"] >= 1024:
                groups[(t["sha256"], t["nbytes"])].append((g, t["name"], t["dims"]))
    dup = {k: v for k, v in groups.items() if len({x[0] for x in v}) >= 2}
    shared_bytes = sum(k[1] for k in dup)                       # stored once
    wasted_bytes = sum(k[1] * (len(v) - 1) for k, v in dup.items())  # removed copies

    per_graph = {g: {"n_initializers": len(inv[g]), "n_nodes": n_nodes[g],
                     "init_bytes": sum(t["nbytes"] for t in inv[g]),
                     "file_bytes": (ROOT / lang / g / f"{g}.int8.onnx").stat().st_size}
                 for g in GRAPHS}
    total_int8 = sum(per_graph[g]["file_bytes"] for g in GRAPHS)
    merged_estimate = total_int8 - wasted_bytes

    report = {
        "lang": lang, "per_graph": per_graph,
        "total_int8_bytes": total_int8,
        "merged_total_estimate_bytes": merged_estimate,
        "cross_graph_duplicate_groups": [
            {"sha256": k[0], "nbytes": k[1], "copies": v} for k, v in sorted(
                dup.items(), key=lambda kv: -kv[0][1])],
        "initializers": inv,
    }
    out = REPO / "reports" / f"bart_merge_analysis_{lang}.json"
    out.write_text(json.dumps(report, indent=2) + "\n")

    # stdout: the interesting tables
    print(f"=== {lang} int8 inventory ===")
    for g in GRAPHS:
        print(f"{g}: {per_graph[g]['n_initializers']} inits, "
              f"{per_graph[g]['init_bytes']/1e6:.1f} MB init bytes, "
              f"file {per_graph[g]['file_bytes']/1e6:.1f} MB, "
              f"{n_nodes[g]} nodes")
    print(f"total int8: {total_int8/1e6:.1f} MB")
    print(f"cross-graph duplicate groups (>=1 KiB): {len(dup)}, "
          f"shared-once bytes {shared_bytes/1e6:.1f} MB, "
          f"wasted copies {wasted_bytes/1e6:.1f} MB")
    print(f"merged total estimate: {merged_estimate/1e6:.1f} MB")
    print("\nlargest duplicate groups:")
    for k, v in sorted(dup.items(), key=lambda kv: -kv[0][1])[:20]:
        print(f"  {k[1]/1e6:7.2f} MB  sha={k[0][:12]}…  " +
              " | ".join(f"{g}:{n}{d}" for g, n, d in v))
    # tables of interest: embed + lm_head per graph
    print("\nembed/lm_head-relevant initializers per graph:")
    for g in GRAPHS:
        for t in inv[g]:
            if ("embed" in t["name"].lower() or t["dims"] and
                    max(t["dims"]) >= 32000):
                print(f"  {g:12s} {t['name']:40s} {t['dims']!s:20s} "
                      f"{t['dtype']:7s} {t['nbytes']/1e6:7.2f} MB "
                      f"consumers={t['consumers']} sha={t['sha256'][:12]}…")
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
