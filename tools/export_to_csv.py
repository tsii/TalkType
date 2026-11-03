#!/usr/bin/env python3
"""AP4 — export parser/CSV appender (PLAN_C2_FABLE_R2 §1.3 step 11).

Reads a 🐞/debug_export text (key: value lines, HTML-escaped allowed),
extracts the C2 fields and appends a CSV row to reports/c2_runs.csv.
Rule 9: ONLY fields/numbers; no text/audio contents.

Usage: tools/export_to_csv.py <export-file> [--tag label] [--csv reports/c2_runs.csv]
"""
import argparse, csv, html, re, sys
from pathlib import Path

FIELDS = ["run_tag", "ts", "audio_s", "loud_s", "cap_blocks", "had_output", "output_chars",
          "hyp_count", "hyp_last_buffer_s", "decode_ms_max", "buffer_s", "last_error",
          "abort_reason", "mic_rel", "fgs_stopped_by", "status_res",
          "stop_cause", "start_src", "gate_at_stop", "gate_open_at_stop", "gate_foreign_resets",
          "owner_lost_at_s", "segment_finalizations", "max_pause_ms", "restart_inputs",
          "sel_unexpected", "first3s_peak_dbfs", "peak_dbfs", "ctl_failed", "dropped"]


def parse(text: str) -> dict:
    t = html.unescape(text).replace("&#10;", "\n")
    out = {}
    for line in t.splitlines():
        m = re.match(r"\s*([a-z0-9_]+)\s*[:=]\s*(.*)$", line)
        if m and m.group(1) in FIELDS:
            out[m.group(1)] = m.group(2).strip()
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("export_file")
    ap.add_argument("--tag", default="")
    ap.add_argument("--csv", default="reports/c2_runs.csv")
    a = ap.parse_args()
    raw = Path(a.export_file).read_text(errors="replace")
    rec = parse(raw)
    if "audio_s" not in rec:
        print("No export recognized (audio_s missing) — nothing appended.", file=sys.stderr)
        return 1
    rec["run_tag"] = a.tag
    rec["ts"] = __import__("datetime").datetime.now().isoformat(timespec="seconds")
    p = Path(a.csv)
    p.parent.mkdir(parents=True, exist_ok=True)
    new = not p.exists()
    with p.open("a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
        if new:
            w.writeheader()
        w.writerow(rec)
    print("appended:", {k: rec[k] for k in FIELDS if k in rec})
    return 0


if __name__ == "__main__":
    sys.exit(main())
