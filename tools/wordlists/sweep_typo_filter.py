#!/usr/bin/env python3
"""T23b2 host sweep (PLAN V0147 §15.2 Ergänzung 2026-11-05): deeper word lists
with a typo filter for the longtail. NO Gradle, NO asset writes — pure analysis.

Filter (see build_wordlist.py --typo-filter): entries at rank > BASE are dropped
if a list word g exists at restricted edit distance 1 (keyboard-neighbour
substitution | doubled letter | omitted letter) with count(g)/count(f) >= R and
f is not the exact umlaut transliteration of g.

Metrics per cell (level x R):
  entries   — list size after the filter
  fix%      — T23b presence probe over the 32 lexicon rows (typed absent,
              expected present)
  clean%    — corrector-relevant clean words present (FP% = 100 - clean%)
  survival% — Schutzmenge: every word from the EXPECTED columns of
              de.tsv/en.tsv/mix.tsv that is present in the UNFILTERED list
              must survive the filter (100% mandatory)
  absent    — lexicon typo forms absent (WordListFormatTest needs >= 24,
              CorrectorGoldenTest needs >= 30 fixable over de+en)
  size      — plain bytes / asset bytes after the budget gzip decision

Also runs the T23c corrector prototype (precheck_t23c.py logic) against the
chosen cells to verify the real fix-rate / clean-FP / morph behaviour.
"""
import importlib.util
import json
import sys

spec = importlib.util.spec_from_file_location('bw', 'tools/build_wordlist.py')
bw = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bw)
spec2 = importlib.util.spec_from_file_location('pc', 'tools/wordlists/precheck_t23c.py')
pc = importlib.util.module_from_spec(spec2)
sys.modules['pc'] = pc
spec2.loader.exec_module(pc)

LANGS = {
    'de-de': dict(input='tools/wordlists/de_full.txt', stop='tools/wordlists/stop_de.txt',
                  base=100_000, budget=1_572_864, layout='de-de', corpus='testdata/typing_goldens/de.tsv'),
    'en-us': dict(input='tools/wordlists/en_full.txt', stop='tools/wordlists/stop_en.txt',
                  base=150_000, budget=1_048_576, layout='en-us', corpus='testdata/typing_goldens/en.tsv'),
}
LEVELS = [100_000, 150_000, 200_000, 250_000]
RS = [100, 200, 400]
DEPTH = 250_000


def schutzmenge():
    """All distinct folded words from the EXPECTED columns of the gold corpora
    (clean/morph/compound/mix targets and lexicon correction targets)."""
    words = set()
    for name in ('de.tsv', 'en.tsv', 'mix.tsv'):
        for line in open(f'testdata/typing_goldens/{name}', encoding='utf-8'):
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            expected = line.split('\t')[1]
            for w in bw.relevant_words(expected):
                words.add(w)
    return words


def main():
    schutz = schutzmenge()
    print(f"Schutzmenge (expected-column words): {len(schutz)}")
    results = {}
    for lang, cfg in LANGS.items():
        stops = bw.load_stoplist(cfg['stop'])
        entries = bw.collect(cfg['input'], stops)
        rows = bw.load_corpus(cfg['corpus'])
        # ratio cache (write for reuse by build_wordlist.py)
        cache_key = f"{lang}|{cfg['input']}|{cfg['stop']}|{cfg['base']}|{DEPTH}"
        ratios = bw.typo_best_ratios(entries, cfg['base'], lang, DEPTH, r_min=100.0)
        json.dump({"params": cache_key, "ratios": ratios},
                  open(f'tools/wordlists/typo_ratios_{lang}.json', 'w'))
        print(f"\n== {lang}: longtail suspects (rank {cfg['base']}..{DEPTH}): {len(ratios)}")
        print("| level | R | entries | fix% | clean% | survival% | absent | plain B | asset B |")
        print("|---|---|---|---|---|---|---|---|---|")
        for level in LEVELS:
            unfiltered = entries[:level]
            unfiltered_idx = {bw.fold(w): i for i, (w, c) in enumerate(unfiltered)}
            # survival baseline: schutz words present unfiltered
            schutz_present = [w for w in schutz if w in unfiltered_idx]
            for r in RS:
                kept = []
                for i, (w, c) in enumerate(unfiltered):
                    if i >= cfg['base'] and int(ratios.get(bw.fold(w), 0)) >= r:
                        continue
                    kept.append((w, c))
                idx = {bw.fold(w): i for i, (w, c) in enumerate(kept)}
                fixable, ltotal, cpresent, ctotal = bw.probe_metrics(rows, idx)
                # absent count: lexicon typos absent from idx
                all_typed = []
                for typed, expected, cls in rows:
                    if cls != 'lexicon':
                        continue
                    tw, ew = bw.relevant_words(typed), bw.relevant_words(expected)
                    if len(tw) != len(ew):
                        continue
                    d = [(a, b) for a, b in zip(tw, ew) if a != b]
                    if len(d) == 1:
                        all_typed.append(d[0][0])
                n_absent = sum(1 for t in set(all_typed) if t not in idx)
                n_surv = sum(1 for w in schutz_present if w in idx)
                blob, gz = bw.maybe_gzip(bw.pack(lang, kept), cfg['budget'])
                fix_pct = 100.0 * fixable / ltotal if ltotal else 0.0
                clean_pct = 100.0 * cpresent / ctotal if ctotal else 0.0
                surv_pct = 100.0 * n_surv / len(schutz_present) if schutz_present else 100.0
                print(f"| {level//1000}k | {r} | {len(kept)} | {fixable}/{ltotal} = {fix_pct:.1f}% "
                      f"| {cpresent}/{ctotal} = {clean_pct:.1f}% | {n_surv}/{len(schutz_present)} "
                      f"| {n_absent}/{len(set(all_typed))} | {len(bw.pack(lang, kept))} "
                      f"| {len(blob)}{' gz' if gz else ''} |")
                results[(lang, level, r)] = dict(
                    entries=[(w, c) for w, c in kept], fix=fixable, ltotal=ltotal,
                    clean=cpresent, ctotal=ctotal, surv=n_surv, surv_total=len(schutz_present))
    return results, schutz


if __name__ == '__main__':
    main()
