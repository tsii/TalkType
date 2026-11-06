#!/usr/bin/env python3
"""T22a: WER (difflib) of the Qwen3-ASR ONNX runs against the golden
reference texts from docs/ASR_CONTRACT.md §4.1 (Rev 3).

Parakeet reference: the golden texts ARE the Parakeet output
(docs/ASR_CONTRACT.md §4.1: "newly measured", token-exact) -> Parakeet
WER = 0 on this corpus by construction.

Usage: .venv-asr/bin/python tools/qwen_ref/wer_report.py <run.json> [<run2.json> ...]
"""
import difflib
import json
import re
import sys

REFS = {
    "de_part1.wav": "Das ist der erste Teil des Diktats.",
    "de_part2.wav": "Und nach einer Pause kommt der zweite Teil.",
    "de_pause.wav": "Das ist der erste Teil des Diktats. Und nach einer Pause kommt der zweite Teil.",
    "de_smoke.wav": "So, jetzt bin ich gespannt, ob die Diktierfunktion auf dem Telefon funktioniert.",
}


def norm(s: str) -> str:
    s = s.lower()
    s = re.sub(r"[^\w\s]", " ", s)
    return " ".join(s.split())


def wer(ref: str, hyp: str) -> tuple[float, int, int, int, int]:
    """WER via difflib opcodes: S+D+I over word lists."""
    r, h = ref.split(), hyp.split()
    sm = difflib.SequenceMatcher(a=r, b=h, autojunk=False)
    s = d = i = 0
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "replace":
            s += min(i2 - i1, j2 - j1)
            if i2 - i1 > j2 - j1:
                d += (i2 - i1) - (j2 - j1)
            else:
                i += (j2 - j1) - (i2 - i1)
        elif tag == "delete":
            d += i2 - i1
        elif tag == "insert":
            i += j2 - j1
    n = len(r)
    return (s + d + i) / n if n else 0.0, s, d, i, n


def main():
    for path in sys.argv[1:]:
        data = json.load(open(path))
        print(f"== {path} (precision={data['precision']})")
        total_n = total_err = 0
        for r in data["results"]:
            if r["wav"] not in REFS:
                continue
            ref = REFS[r["wav"]]
            w_raw, *_ = wer(ref, r["text"])
            w_n, s, d, i, n = wer(norm(ref), norm(r["text"]))
            total_n += n
            total_err += s + d + i
            print(f"  {r['wav']}: WER_raw={w_raw:.3f} WER_norm={w_n:.3f} "
                  f"(S={s} D={d} I={i} / N={n})  hyp={r['text']!r}")
        print(f"  corpus WER_norm = {total_err/total_n:.3f} ({total_err}/{total_n})")
        print(f"  Parakeet corpus WER = 0.000 (golden texts are Parakeet output, §4.1)")


if __name__ == "__main__":
    main()
