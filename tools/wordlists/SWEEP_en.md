# Sweep en (T23b, analog/kurz, docs/PLAN_V0147.md §15.2)

Command:
  python3 tools/build_wordlist.py sweep en-us 50000 250000 50000 \
      --corpus testdata/typing_goldens/en.tsv --stop tools/wordlists/stop_en.txt

| cutoff | entries | fix%        | clean%        | FP%  | plain B   | asset B        |
|--------|---------|-------------|---------------|------|-----------|----------------|
| 50k    | 50,000  | 2/2 = 100%  | 21/21 = 100%  | 0.0% | 449,574   | 449,574        |
| 100k   | 100,000 | 2/2 = 100%  | 21/21 = 100%  | 0.0% | 908,194   | 908,194        |
| 150k   | 150,000 | 2/2 = 100%  | 21/21 = 100%  | 0.0% | 1,368,835 | 704,333 (gz)   |
| 200k   | 200,000 | 2/2 = 100%  | 21/21 = 100%  | 0.0% | 1,834,710 | 934,372 (gz)   |
| 250k   | 250,000 | 2/2 = 100%  | 21/21 = 100%  | 0.0% | 2,306,114 | 1,159,973 (gz) |

## Decision

**en cut-off = 150,000** (plan baseline kept): the tiny en corpus (2 lexicon
rows, both typo forms absent at every cutoff) does not discriminate, so the
§15.2 baseline stays for inflection depth ("children", "mice", "went",
"better" all present from 50k on). Plain 1,368,835 B > 1.0 MB budget → whole
blob gzipped (mtime=0, deterministic), asset 704,333 B.

Asset: app/src/main/assets/wordlists/en-us.bin, 704,333 B (gzip),
sha256 6fc9634d27ca7d26d4d16a695729e9d683613771192ab9359badaa84fa19763d.
