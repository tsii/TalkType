#!/usr/bin/env python3
"""Build deterministic VKWL1 binary word lists for TalkType (T23b, PLAN_V0147 S15).

Source: FrequencyWords (Hermit Dave), OpenSubtitles-2018-derived full lists,
"word<space>count" utf-8. Content licence CC BY-SA 4.0 (see NOTICE.txt +
SOURCES.md). No content outside tools/wordlists and the two APK assets.

Pipeline (deterministic):
  1. keep letter-only word forms (incl. umlauts / eszett), minimum length 2
  2. stoplist subtraction (case-insensitive, BEFORE the cut-off)
  3. case-insensitive dedup: the most frequent spelling stays (main form),
     ties broken by first occurrence (source is frequency-ordered)
  4. frequency sort: count desc, then word bytes asc (deterministic ties)
  5. top-N cut-off (T23b sweep: de 250k / en 150k baseline)

VKWL1 binary format (little-endian; entry index == frequency rank):
  bytes 0..4   magic b"VKWL1"
  byte  5      lang-code length L
  bytes 6..6+L layout language id, ASCII (e.g. b"de-de")
  u32 LE       entry count N
  N entries in frequency order (rank 0 = most frequent):
    u16 LE     byte length of the utf-8 word
    bytes      utf-8 word, case preserved

No compression while the plain blob stays under the size budget; otherwise the
WHOLE blob is gzipped with mtime=0 (byte-identical across rebuilds; the APK
compresses assets anyway, the budget counts the shipped asset bytes).

Sweep metric (S15.2): pure presence probe (edit-distance ranking = T23c):
  fix%   = lexicon corpus rows where the TYPED typo word is ABSENT from the
           list AND the EXPECTED word is PRESENT
  clean% = corrector-relevant words of the clean corpus rows PRESENT
           (FP% = 100 - clean%; must stay <= 1 %)

T23b2 typo filter (PLAN S15.2 Ergaenzung 2026-11-05, host-side): with
  --typo-filter R --typo-filter-base B, entries at frequency rank > B are
  DROPPED if they look like typos of a much more frequent list word g:
    edit distance 1, restricted to (a) substitution on keyboard NEIGHBOURS
    (same key incl. long-press alternates, or rect distance < 1.5 at row
    pitch 1.5x, from assets/layouts/<lang>.json), (b) one DOUBLED letter
    collapsed, (c) one omitted letter re-inserted (any reachable char),
  AND count(g)/count(f) >= R, AND f is NOT the exact German umlaut
  transliteration of g (ae/oe/ue/ss spelling of an umlaut word stays).
  Entries within rank <= B are never dropped. The best ratio per longtail
  word is cached in tools/wordlists/typo_ratios_<lang>.json (written by
  tools/wordlists/sweep_typo_filter.py; recomputed if absent/stale).

Usage:
  build_wordlist.py build <lang-id> <input.txt> --stop <stopfile> --top N \
      --out <file.bin> [--budget BYTES] [--typo-filter R] \
      [--typo-filter-base B] [--ratio-cache FILE]
  build_wordlist.py sweep <lang-id> <min> <max> <step> --corpus <tsv> \
      --stop <stopfile> [--budget BYTES]
"""

import argparse
import gzip
import hashlib
import re
import struct
import sys

MAGIC = b"VKWL1"
BUDGETS = {"de-de": 1_572_864, "en-us": 1_048_576}  # S15.2: 1.5 MB / 1.0 MB
WORD_RE = re.compile(r"[^\W\d_]+", re.UNICODE)  # letters only (unicode)


def fold(word):
    return word.lower()  # locale-independent, deterministic


def load_stoplist(path):
    stops = set()
    if path:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    stops.add(fold(line))
    return stops


def read_pairs(path):
    """Yields (word, count) in source (frequency desc) order."""
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            yield parts[0], int(parts[1])


def collect(path, stops):
    """Filtered + deduped entries as list[(word, count)], frequency desc."""
    best = {}  # fold -> (word, count)
    for word, count in read_pairs(path):
        if len(word) < 2 or not word.isalpha():
            continue
        f = fold(word)
        if f in stops:
            continue
        cur = best.get(f)
        if cur is None or count > cur[1]:
            best[f] = (word, count)
    entries = [(w, c) for w, c in best.values()]
    entries.sort(key=lambda e: (-e[1], e[0].encode("utf-8")))
    return entries


def pack(lang_id, entries):
    lang = lang_id.encode("ascii")
    out = bytearray(MAGIC)
    out.append(len(lang))
    out += lang
    out += struct.pack("<I", len(entries))
    for word, _count in entries:
        data = word.encode("utf-8")
        out += struct.pack("<H", len(data))
        out += data
    return bytes(out)


def maybe_gzip(blob, budget):
    """Plain while under budget, otherwise whole-blob gzip with mtime=0."""
    if budget is None or len(blob) <= budget:
        return blob, False
    return gzip.compress(blob, compresslevel=9, mtime=0), True


def build(lang_id, input_path, stop_path, top, out_path, budget,
          typo_filter=None, typo_base=100_000, depth=250_000, cache=None):
    stops = load_stoplist(stop_path)
    entries = collect(input_path, stops)
    dropped = 0
    if typo_filter:
        ratios = None
        if cache:
            key = f"{lang_id}|{input_path}|{stop_path}|{typo_base}|{depth}"
            ratios = load_ratio_cache(cache, key)
        if ratios is None:  # computed at r_min=100 so any R >= 100 works
            ratios = typo_best_ratios(entries, typo_base, lang_id, depth,
                                      r_min=100.0)
        kept = []
        for i, (w, c) in enumerate(entries[: top]):
            if i >= typo_base and int(ratios.get(fold(w), 0)) >= typo_filter:
                dropped += 1
                continue
            kept.append((w, c))
        entries = kept
    else:
        entries = entries[: top]
    blob, gz = maybe_gzip(pack(lang_id, entries), budget)
    with open(out_path, "wb") as f:
        f.write(blob)
    sha = hashlib.sha256(blob).hexdigest()
    print(f"{lang_id}: entries={len(entries)} dropped={dropped} "
          f"size={len(blob)} gzipped={gz} sha256={sha}")
    return blob


def relevant_words(text):
    """Corrector-relevant words: mirrors TypingRules must-not-touch (S2):
    no URL/e-mail tokens, no digits, no short ALLCAPS, no inner capitals."""
    words = []
    for token in text.split():
        if "://" in token or "@" in token:
            continue
        for w in WORD_RE.findall(token):
            if len(w) < 2 or not w.isalpha():
                continue
            if len(w) <= 5 and all(c.isupper() for c in w):
                continue
            if any(c.isupper() for c in w[2:]):
                continue
            if w[0].islower() and any(c.isupper() for c in w[1:]):
                continue
            words.append(fold(w))
    return words


def load_corpus(path):
    """typed/expected/class rows; '#' lines ignored (see typing_goldens)."""
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            typed, expected, cls = line.split("\t")
            rows.append((typed, expected, cls))
    return rows


def layout_positions(layout_id):
    """Key-rect positions from assets/layouts/<layout_id>.json (row pitch 1.5x,
    alternates share the host key) — same geometry as correct/NeighbourModel."""
    import json
    d = json.load(open(f"app/src/main/assets/layouts/{layout_id}.json"))
    pos = {}
    for lname in ("base", "shift"):
        for y, row in enumerate(d["layers"][lname]["rows"]):
            x = 0.0
            for k in row:
                if k.get("kind") != "text":
                    x += k.get("width", 1.0)
                    continue
                w = k.get("width", 1.0)
                p = (x + w / 2.0, 1.5 * y)
                for c in [k["value"]] + list(k.get("alternates", [])):
                    pos.setdefault(c.lower(), p)
                x += w
    return pos


def keyboard_neighbours(layout_id):
    """char -> (neighbour chars incl. same key, full reachable alphabet)."""
    import math
    pos = layout_positions(layout_id)
    nbrs = {}
    for a, pa in pos.items():
        s = set()
        for b, pb in pos.items():
            if b == a:
                continue
            if math.dist(pa, pb) < 1.5:  # same key (0.0) or adjacent
                s.add(b)
        nbrs[a] = s
    return nbrs, set(pos.keys())


UMLAUT_TRANSLIT = {"\u00e4": "ae", "\u00f6": "oe", "\u00fc": "ue", "\u00df": "ss"}


def translit_umlauts(w):
    for u, a in UMLAUT_TRANSLIT.items():
        w = w.replace(u, a)
    return w


def typo_best_ratios(entries, base, layout_id, depth, r_min=100.0):
    """Best non-umlaut count(g)/count(f) over restricted distance-1 pairs,
    for entries at rank index >= base (longtail) up to `depth`.
    Returns {fold(f): int(best ratio)} for ratios >= r_min."""
    nbrs, alphabet = keyboard_neighbours(layout_id)
    index = {fold(w): c for (w, c) in entries[:depth]}
    out = {}
    for i in range(base, min(depth, len(entries))):
        w, cf = entries[i]
        f = fold(w)
        best = 0.0
        L = len(f)
        cands = set()
        for j in range(L):
            for nb in nbrs.get(f[j], ()):  # (a) neighbour substitution
                if nb != f[j]:
                    cands.add(f[:j] + nb + f[j + 1:])
            if j + 1 < L and f[j] == f[j + 1]:  # (b) doubled letter collapsed
                cands.add(f[:j] + f[j + 1:])
        for j in range(L + 1):  # (c) omitted letter re-inserted
            for ch in alphabet:
                cands.add(f[:j] + ch + f[j:])
        cands.discard(f)
        for g in cands:
            cg = index.get(g)
            if cg is None or cg <= cf:
                continue
            ratio = cg / cf
            if ratio < r_min:
                continue
            if translit_umlauts(g) == f:  # exact umlaut transliteration: keep
                continue
            if ratio > best:
                best = ratio
        if best >= r_min:
            out[f] = int(best)
    return out


def load_ratio_cache(path, expect_key):
    """Cache format: {"params": <key>, "ratios": {...}} — None if absent/stale."""
    import json
    try:
        d = json.load(open(path, encoding="utf-8"))
        if d.get("params") == expect_key:
            return d["ratios"]
    except (OSError, ValueError):
        pass
    return None


def ratio_cache_path(lang_id):
    return f"tools/wordlists/typo_ratios_{lang_id}.json"


def probe_metrics(rows, folded_index):
    """fix% over lexicon rows + clean% over clean rows (presence probe)."""
    lexicon_fixable = 0
    lexicon_total = 0
    clean_present = 0
    clean_total = 0
    seen_clean = set()
    for typed, expected, cls in rows:
        if cls == "lexicon":
            t_words = relevant_words(typed)
            e_words = relevant_words(expected)
            if len(t_words) != len(e_words):
                continue  # not a single-word typo row
            diffs = [(t, e) for t, e in zip(t_words, e_words) if t != e]
            if len(diffs) != 1:
                continue
            typo, correct = diffs[0]
            lexicon_total += 1
            if typo not in folded_index and correct in folded_index:
                lexicon_fixable += 1
        elif cls == "clean":
            for w in relevant_words(expected):
                if w in seen_clean:
                    continue
                seen_clean.add(w)
                clean_total += 1
                if w in folded_index:
                    clean_present += 1
    return lexicon_fixable, lexicon_total, clean_present, clean_total


def sweep(lang_id, low, high, step, corpus_path, stop_path, budget):
    stops = load_stoplist(stop_path)
    entries = collect(_input_for(lang_id), stops)
    rows = load_corpus(corpus_path)
    print(f"| cutoff | entries | fix% | clean% | FP% | plain B | asset B |")
    print(f"|---|---|---|---|---|---|---|")
    for top in range(low, high + 1, step):
        subset = entries[:top]
        blob, gz = maybe_gzip(pack(lang_id, subset), budget)
        folded_index = {fold(w): i for i, (w, _c) in enumerate(subset)}
        fixable, ltotal, cpresent, ctotal = probe_metrics(rows, folded_index)
        fix_pct = 100.0 * fixable / ltotal if ltotal else 0.0
        clean_pct = 100.0 * cpresent / ctotal if ctotal else 0.0
        print(f"| {top//1000}k | {len(subset)} | {fixable}/{ltotal} = {fix_pct:.1f}% "
              f"| {cpresent}/{ctotal} = {clean_pct:.1f}% | {100-clean_pct:.1f}% "
              f"| {len(pack(lang_id, subset))} | {len(blob)}{' (gz)' if gz else ''} |")


def _input_for(lang_id):
    return {"de-de": "tools/wordlists/de_full.txt", "en-us": "tools/wordlists/en_full.txt"}[lang_id]


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build")
    b.add_argument("lang_id")
    b.add_argument("input")
    b.add_argument("--stop", required=True)
    b.add_argument("--top", type=int, required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--budget", type=int, default=None)
    b.add_argument("--typo-filter", type=int, default=None,
                   help="T23b2: drop longtail typo suspects at ratio >= R")
    b.add_argument("--typo-filter-base", type=int, default=100_000,
                   help="rank from which entries are typo-suspect (default 100k)")
    b.add_argument("--depth", type=int, default=250_000,
                   help="probe depth for the ratio computation (default 250k)")
    b.add_argument("--ratio-cache", default=None,
                   help="JSON cache of longtail best ratios (default "
                        "tools/wordlists/typo_ratios_<lang>.json)")

    s = sub.add_parser("sweep")
    s.add_argument("lang_id")
    s.add_argument("low", type=int)
    s.add_argument("high", type=int)
    s.add_argument("step", type=int)
    s.add_argument("--corpus", required=True)
    s.add_argument("--stop", required=True)
    s.add_argument("--budget", type=int, default=None)

    a = p.parse_args()
    budget = a.budget if a.budget is not None else BUDGETS.get(a.lang_id)
    if a.cmd == "build":
        cache = a.ratio_cache or ratio_cache_path(a.lang_id)
        build(a.lang_id, a.input, a.stop, a.top, a.out, budget,
              typo_filter=a.typo_filter, typo_base=a.typo_filter_base,
              depth=a.depth, cache=cache)
    else:
        sweep(a.lang_id, a.low, a.high, a.step, a.corpus, a.stop, budget)


if __name__ == "__main__":
    main()
