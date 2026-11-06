# T23b2 — Typo-filter sweep en (2026-11-05, PLAN §15.2 Ergänzung; HOST ONLY)

Filter as in SWEEP_de_filtered.md; typo-filter base = 150k (the current en
cut-off), probe depth 250k. Longtail suspects: 7429 (rank 150k..250k).

| level | R | entries | fix% | clean% | survival | asset B |
|-------|---|---------|------|--------|----------|---------|
| 150k  | – / any | 150 000 | 2/2 = 100 % | 21/21 = 100 % | 166/166 | 704 335 gz (current asset) |
| 200k  | 100 | 196 397 | 2/2 = 100 % | 21/21 = 100 % | 175/175 | 922 209 gz |
| 200k  | 200 | 197 154 | 2/2 = 100 % | 21/21 = 100 % | 175/175 | 924 843 gz |
| 200k  | 400 | 197 786 | 2/2 = 100 % | 21/21 = 100 % | 175/175 | 926 913 gz |
| 250k  | 100 | 242 571 | 2/2 = 100 % | 21/21 = 100 % | 179/179 | 1 134 939 gz — OVER 1.0 MB budget |
| 250k  | 200 | 244 146 | 2/2 = 100 % | 21/21 = 100 % | 179/179 | 1 140 389 gz — OVER budget |
| 250k  | 400 | 245 426 | 2/2 = 100 % | 21/21 = 100 % | 179/179 | 1 144 610 gz — OVER budget |

## Decision: STAY AT 150k — no asset change

No metric improves (2/2 fix, 100 % clean, 100 % survival everywhere); 250k is
over the 1.0 MB budget; 200k would add +218 KB for pure longtail that the de
side already showed to import corrector-hostile junk (see SWEEP_de_filtered.md
decision — the same class of degradation applies). en-us.bin stays top 150 000
(sha256 891bb79d1d9e2028d3a2b6e7d0442aa82b88457bd82e81b5cc463ed4c94b2889).
