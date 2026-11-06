# Sweep de (T23b, docs/PLAN_V0147.md §15.2) — cut-off tuning

Command:
  python3 tools/build_wordlist.py sweep de-de 100000 300000 50000 \
      --corpus testdata/typing_goldens/de.tsv --stop tools/wordlists/stop_de.txt

Metric (presence probe only; edit-distance ranking = T23c):
fix% = lexicon rows of de.tsv where the TYPED typo word is ABSENT and the
EXPECTED word PRESENT; clean% = corrector-relevant words of the clean rows
present (FP% = 100 − clean%, gate <= 1 %). 81 relevant clean words, 32
lexicon rows (must-not-touch shapes excluded, mirrors TypingRules §2).

| cutoff | entries | fix%          | clean%        | FP%  | plain B   | asset B         |
|--------|---------|---------------|---------------|------|-----------|-----------------|
| 100k   | 100,000 | 26/32 = 81.2% | 81/81 = 100%  | 0.0% | 1,082,649 | 1,082,649 (plain)|
| 150k   | 150,000 | 25/32 = 78.1% | 81/81 = 100%  | 0.0% | 1,665,605 | 791,204 (gz)    |
| 200k   | 200,000 | 24/32 = 75.0% | 81/81 = 100%  | 0.0% | 2,267,766 | 1,055,786 (gz)  |
| 250k   | 250,000 | 23/32 = 71.9% | 81/81 = 100%  | 0.0% | 2,882,385 | 1,314,918 (gz)  |
| 300k   | 300,000 | 20/32 = 62.5% | 81/81 = 100%  | 0.0% | 3,512,562 | 1,570,258 (gz)  |

## Decision

**de cut-off = 100,000** (was: 250,000 baseline). Reason: the only metric that
discriminates is fix%, and it FALLS monotonically with the cutoff — the
OpenSubtitles long tail contains colloquial/junk spellings that collide with
the corpus typos (at 250k additionally present: "gte", "sontag", "warrten").
100k maximises fix% (81.2%) at clean-FP 0.0%, and the plain blob stays under
the 1.5 MB budget (no gzip needed). Vocabulary probe: all §15.5 morph words
(ginge, gewichen, Häuser, hättest, gegangen, Straße, Grüße, Büro, …) and all 81
clean-corpus words are already present at 100k.

Not fixable at 100k (6/32, real-word collisions — T23c ambiguity guard / stoplist
territory): "wirf"→wird, "bud"→Bus, "schient"→scheint, "fur"→für,
"schon"→schön, "strasse"→Straße. Follow-up: stoplist curation (stop_de.txt,
§15.1: nich/gehts-class junk) may recover fix% at higher cutoffs; revisit with
T23c edit-distance goldens.

Asset: app/src/main/assets/wordlists/de-de.bin, 1,082,649 B plain,
sha256 c19613ffa2ea98677d59e64efbcdd7905b458a87c206b33095c5b00baadc85b0
(deterministic: rebuild byte-identical, see SOURCES.md).
