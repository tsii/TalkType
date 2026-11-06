#!/usr/bin/env python3
"""T25b: offline checker for summarizer outputs (no model, no network).

Checks (plan docs/PLAN_ZUSAMMENFASSUNG.md §3 quality rows 1-2 + §8 T25b):
  schema      non-empty, 3-6 sentences (de/en abbreviation-aware),
              language of the summary == expected lang (stopword+script detector)
  fabrication every digit sequence and every capitalized name token of the
              summary must occur in the source. Normalization: thousands
              separators (1.240 ~ 1240), decimal comma (17,5 ~ 17.5),
              hyphen compounds checked whole AND per part, case-tolerant only
              when the lowercased word itself occurs in the source.
  length      summary/source chars > 0.60 -> flag "too_long" (YELLOW warning,
              not a FAIL; copy-paste guard, plan §3 row iv)

Verdict: PASS (no fabrication, schema ok) / FAIL(list of reasons).

Self-test modes:
  --goldens FILE   every reference must PASS vs its own source (JSONL:
                   id/lang/text/reference/entities)
  --traps FILE     traps.tsv columns id/lang/source/summary/expected
                   (expected = PASS|FAIL)
Rule 9: this module never writes logs; caller may persist results in
reports/*.json (user text there is allowed, unlike logcat/EXPORT_LOG).

Usage:
  .venv-asr/bin/python tools/sum_ref/check_summary.py --goldens testdata/sum_goldens/sum_goldens.jsonl --traps testdata/sum_goldens/traps.tsv
  .venv-asr/bin/python tools/sum_ref/check_summary.py --text src.txt --summary sum.txt --lang de
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- sentence split
_ABBR = (r"z\.\s?B\.|u\.\s?a\.|usw\.|etc\.|ca\.|Nr\.|No\.|Dr\.|Prof\.|bzw\."
         r"|vgl\.|ff\.|d\.h\.|i\.A\.|z\.Zt\.|Inc\.|Ltd\.|Jr\.|Sr\.|St\.|vs\.|Mr\.|Ms\.")


def split_sentences(s: str) -> list[str]:
    s = s.strip()
    if not s:
        return []
    protected = re.sub(_ABBR, lambda m: m.group(0).replace(".", "\x00"), s)
    parts = re.split(r"(?<=[.!?])\s+", protected)
    return [p.replace("\x00", ".").strip() for p in parts if p.strip()]


# ---------------------------------------------------------------- language id
_DE = {"der", "die", "das", "und", "ist", "nicht", "von", "auf", "mit", "für",
       "den", "dem", "des", "ein", "eine", "einer", "zu", "auch", "sind",
       "war", "waren", "werden", "wir", "sie", "es", "im", "am", "um", "an",
       "bei", "nach", "über", "aus", "noch", "schon", "nur", "aber", "oder",
       "wird", "hat", "haben", "dass", "dann", "hier", "heute", "morgen"}
_EN = {"the", "and", "is", "of", "to", "in", "for", "on", "with", "that",
       "are", "was", "were", "not", "this", "from", "we", "they", "it", "as",
       "be", "have", "has", "had", "will", "would", "about", "there", "their",
       "then", "today", "tomorrow", "but", "or", "can", "should", "said"}

# function words that may start a summary sentence without being an entity
_CAP_OK = {"der", "die", "das", "ein", "eine", "einer", "einen", "dem", "den",
           "des", "dem", "als", "auch", "am", "an", "im", "in", "mit", "nach",
           "seit", "von", "zu", "zur", "zum", "über", "unter", "vor", "bei",
           "ohne", "dabei", "dazu", "ferner", "außerdem", "zusätzlich", "hierzu",
           "darin", "danach", "statt", "zudem", "ebenso", "somit", "sowie", "dann",
           "the", "a", "an", "at", "on", "in", "of", "and", "after", "before",
           "during", "with", "without", "from", "this", "these", "that", "both",
           "each", "every", "all", "any", "no", "nor", "so", "if", "when", "while",
           "just", "even", "still", "again", "once", "because", "since",
           "he", "she", "they", "we", "who", "which", "what", "where", "how",
           "not", "both", "each", "every", "all", "it", "this", "these", "those"}


def detect_lang(s: str) -> str:
    words = set(re.findall(r"[a-zäöüß]+", s.lower()))
    de = len(words & _DE) + (2 if ("ß" in s or re.search(r"[äöü]", s)) else 0)
    en = len(words & _EN)
    if de > en:
        return "de"
    if en > de:
        return "en"
    return "unknown"


# ---------------------------------------------------------------- numbers
_NUM = re.compile(r"\d+(?:[.,]\d+)*%?")


def _num_variants(tok: str) -> set[str]:
    t = tok.rstrip("%")
    out = {t, tok}
    if re.fullmatch(r"\d{1,3}(?:\.\d{3})+(?:[.,]\d+)?", t):
        out.add(t.replace(".", ""))
    if "," in t:
        out.add(t.replace(",", "."))
    out.add(re.sub(r"[.,]", "", t) if re.fullmatch(r"[\d.,]+", t) else t)
    for v in list(out):
        if "," in v:
            out.add(v.replace(",", "."))
    return out


def num_tokens(s: str) -> list[str]:
    return _NUM.findall(s)


def num_ok(summary: str, source: str) -> list[str]:
    """Digit sequences of the summary missing from the source."""
    src = set()
    for t in num_tokens(source):
        src |= _num_variants(t)
    bad = []
    for t in num_tokens(summary):
        if not (_num_variants(t) & src):
            bad.append(t)
    return bad


# ---------------------------------------------------------------- names / caps
_NAME = re.compile(r"[A-ZÄÖÜ][0-9A-Za-zÄÖÜäöüß\-']*[0-9A-Za-zÄÖÜäöüß]|[A-ZÄÖÜ]")


def _norm_word(w: str) -> str:
    return w.strip("„“\"'()[]:;.,!?«»").replace("\u2019", "'")


def name_ok(summary: str, source: str) -> list[str]:
    """Capitalized tokens of the summary not backed by the source.

    A token is grounded if (a) it occurs in the source's capitalized set,
    (b) its lowercase form occurs among source words, or with morphological
    tolerance (>=4 chars prefix / >=5 chars suffix overlap, covers German
    plural/case variants), (c) each hyphen part is grounded, or (d) it splits
    into two grounded halves (German compounds like Zwetschgen+Torte).
    """
    src_caps = {_norm_word(w) for w in _NAME.findall(source)}
    src_lower = set(re.findall(r"[a-zäöüß]+", source.lower()))

    def simple(w: str) -> bool:
        if w in src_caps or w.lower() in src_lower:
            return True
        lw = w.lower()
        if len(lw) >= 4 and any(sw.startswith(lw) or lw.startswith(sw) for sw in src_lower):
            return True
        if len(lw) >= 5 and any(sw.endswith(lw) for sw in src_lower):
            return True
        return False

    def grounded(w: str) -> bool:
        w = _norm_word(w)
        if simple(w):
            return True
        parts = [p for p in w.split("-") if len(p) >= 2]
        if parts and all(simple(p) for p in parts):
            return True
        for i in range(3, len(w) - 2):
            if simple(w[:i]) and simple(w[i:]):
                return True
        return False

    bad = sorted({_norm_word(w) for w in _NAME.findall(summary)
                  if w.lower() not in _CAP_OK and not grounded(w)})
    return bad


# ---------------------------------------------------------------- main check
def check(text: str, summary: str, lang: str) -> dict:
    reasons: list[str] = []
    warns: list[str] = []
    sents = split_sentences(summary)
    if not summary.strip():
        reasons.append("empty")
    if not (3 <= len(sents) <= 6):
        reasons.append(f"schema:sentences={len(sents)} (need 3-6)")
    if any(len(s) < 15 for s in sents):
        reasons.append("schema:short_sentence_fragment")
    got = detect_lang(summary)
    if got != lang:
        reasons.append(f"lang:got={got} want={lang}")
    fab_num = num_ok(summary, text)
    if fab_num:
        reasons.append("fabrication:numbers=" + ",".join(fab_num))
    fab_name = name_ok(summary, text)
    if fab_name:
        reasons.append("fabrication:names=" + ",".join(fab_name))
    ratio = (len(summary) / max(len(text), 1))
    if ratio > 0.60:
        warns.append(f"too_long:ratio={ratio:.2f}")
    return {"verdict": "FAIL" if reasons else "PASS", "reasons": reasons,
            "warnings": warns, "sentences": len(sents),
            "length_ratio": round(ratio, 3)}


# ---------------------------------------------------------------- selftests
def run_goldens(path: Path) -> dict:
    fails = []
    n = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        res = check(r["text"], r["reference"], r["lang"])
        n += 1
        if res["verdict"] != "PASS":
            fails.append({"id": r["id"], "reasons": res["reasons"]})
        if any("too_long" in w for w in res["warnings"]):
            fails.append({"id": r["id"], "reasons": ["reference too_long"]})
    return {"goldens": n, "failures": fails}


def run_traps(path: Path) -> dict:
    rows = [l.split("\t") for l in
            path.read_text(encoding="utf-8").splitlines() if l.strip()]
    hdr, rows = rows[0], rows[1:]
    fails, npass, nfail = [], 0, 0
    for row in rows:
        d = dict(zip(hdr, row))
        res = check(d["source"], d["summary"], d["lang"])
        exp = d["expected"].upper()
        if exp == "FAIL":
            nfail += 1
        else:
            npass += 1
        if res["verdict"] != exp:
            fails.append({"id": d["id"], "expected": exp,
                          "got": res["verdict"], "reasons": res["reasons"]})
    return {"traps_total": len(rows), "traps_expect_fail": nfail,
            "traps_expect_pass": npass, "mismatches": fails}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--goldens", type=Path)
    ap.add_argument("--traps", type=Path)
    ap.add_argument("--text", type=Path)
    ap.add_argument("--summary", type=Path)
    ap.add_argument("--lang", choices=["de", "en"])
    args = ap.parse_args()

    if args.text:
        res = check(args.text.read_text(encoding="utf-8"),
                    args.summary.read_text(encoding="utf-8"), args.lang)
        print(json.dumps(res, ensure_ascii=False))
        return 0 if res["verdict"] == "PASS" else 1

    rc = 0
    report = {}
    if args.goldens:
        report.update(run_goldens(args.goldens))
    if args.traps:
        report.update(run_traps(args.traps))
    if not report:
        ap.print_help()
        return 2
    bad = report.get("failures", []) + report.get("mismatches", [])
    print(json.dumps(report, ensure_ascii=False, indent=1))
    if bad:
        print(f"SELFTEST FAILED ({len(bad)} problems)", file=sys.stderr)
        rc = 1
    else:
        print("SELFTEST OK")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
