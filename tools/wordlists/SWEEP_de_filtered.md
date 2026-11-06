# T23b2 — Typo-filter sweep de (2026-11-05, PLAN §15.2 Ergänzung; HOST ONLY, no asset change)

Idea: go deeper than the 100k cut-off (up to 250k) and remove only
typo-suspect longtail entries.

## Filter math (build_wordlist.py --typo-filter R --typo-filter-base B)

An entry f at frequency rank > B (= 100k, the current de cut-off) is DROPPED if
a list word g exists (probe index = unfiltered top-250k) with

  - restricted edit distance 1 between fold(f) and g:
    (a) ONE substitution on keyboard neighbours (same key incl. long-press
        alternates, or key-rect centre distance < 1.5 at row pitch 1.5x —
        geometry from assets/layouts/de-de.json, like correct/NeighbourModel),
    (b) ONE doubled letter collapsed (Doppelbuchstabe), or
    (c) ONE omitted letter re-inserted (any reachable char),
  - count(g) / count(f) >= R, and
  - f is NOT the exact German umlaut transliteration of g
    (ae/oe/ue/ss renderings of an umlaut word stay in the list).

Entries within rank <= B are never dropped. Best ratio per longtail word is
cached in tools/wordlists/typo_ratios_de-de.json (4538 suspects ≥ 100x in the
rank-100k..250k zone; e.g. sontag→sonntag 525x, gte→gute 8383x,
warrten→warten 9485x — the corpus typos that re-entered at 250k in the T23b
sweep are all caught by the filter).

## Sweep (tools/wordlists/sweep_typo_filter.py)

Metrics per cell: probe fix% over the 32 lexicon rows (T23b presence probe),
clean coverage (81 words), Schutzmenge survival (239 expected-column words of
de.tsv/en.tsv/mix.tsv; 229–232 present per level, ALL survive in every cell),
absent typo forms (30/32 needed for the golden gates), asset size after the
budget-gzip decision.

| level | R | entries | fix% | clean% | survival | absent | asset B |
|-------|---|---------|------|--------|----------|--------|---------|
| 100k  | –   | 100 000 | 30/32 = 93.8 % | 100 % | 229/229 | 30/32 | 1 082 777 (plain) |
| 100k  | any | 100 000 | (filter has no longtail at this level — identical) |
| 150k  | 100 | 148 673 | 30/32 = 93.8 % | 100 % | 230/230 | 30/32 | 786 398 gz |
| 150k  | 200 | 149 038 | 30/32 = 93.8 % | 100 % | 230/230 | 30/32 | 787 731 gz |
| 150k  | 400 | 149 327 | 30/32 = 93.8 % | 100 % | 230/230 | 30/32 | 788 819 gz |
| 200k  | 100 | 197 139 | 30/32 = 93.8 % | 100 % | 230/230 | 30/32 | 1 045 479 gz |
| 200k  | 200 | 197 915 | 30/32 = 93.8 % | 100 % | 230/230 | 30/32 | 1 048 383 gz |
| 200k  | 400 | 198 529 | 30/32 = 93.8 % | 100 % | 230/230 | 30/32 | 1 050 600 gz |
| 250k  | 100 | 245 462 | 30/32 = 93.8 % | 100 % | 232/232 | 30/32 | 1 298 839 gz |
| 250k  | 200 | 246 659 | 30/32 = 93.8 % | 100 % | 232/232 | 30/32 | 1 303 157 gz |
| 250k  | 400 | 247 616 | 30/32 = 93.8 % | 100 % | 232/232 | 30/32 | 1 306 606 gz |

## Decision: STAY AT 100k — no asset change

The presence probe is IDENTICAL (93.8 % fix, 100 % clean, 100 % survival,
30/32 absent) at every filtered level — the filter reliably catches the
corpus typos, but no metric IMPROVES. The real T23c corrector prototype
(precheck_t23c.py against de-250k/R100 + en-200k/R100) however DEGRADES:

  - new wrong-fixes: jetzy→"jetty" (0.7), gte→"fte" (0.7), Gruse→"Grise"
    (0.7), fuer→"füer" (0.4) — longtail junk that is NOT distance-1 of a
    ≥100x more frequent word, so the filter cannot catch it;
  - morph goldens BREAK: "Fruehstueck" and "haettest" re-enter via the
    longtail and are PROTECTED by the umlaut-transliteration guard → they
    become in-list words → the umlaut corrections (Frühstück, hätte) are
    blocked (this is the guard working as specified — and exactly why a
    deeper list hurts);
  - "Teammeeting" becomes a list word at 250k → the compound splitter test
    premise (2-part decomposition) breaks (single-part dp result);
  - corrector fix-rate would drop from 30/32 (93.8 %) to ~27/32 (84 %) —
    below the ≥90 % gate of CorrectorGoldenTest.

Costs of switching would be: +215 KB asset (1.30 MB vs 1.08 MB), bigger RAM
HashMap, and the degradations above. Benefit: ~145k additional longtail word
forms (inflection depth) — demonstrably harmful in this corpus.

Everything stays as is: de-de.bin top 100 000 (sha256
c19613ffa2ea98677d59e64efbcdd7905b458a87c206b33095c5b00baadc85b0), en-us.bin
top 150 000 (sha256 891bb79d1d9e2028d3a2b6e7d0442aa82b88457bd82e81b5cc463ed4c94b2889).
The --typo-filter code and the ratio caches stay in tools/ (deterministic,
documented) for future re-evaluation with a curated longtail.
