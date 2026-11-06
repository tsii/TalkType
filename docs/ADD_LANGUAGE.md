# Adding a keyboard language (runbook, since T23 / 0.1.47)

Keyboard scope is currently LIMITED to **de-de + en-us** (user command 2026-11-05):
everything else is greyed out in Setup and filtered out of the language cycle via
`LanguageAvailability.AVAILABLE`. This document is the checklist to enable language `X`.

## Real work (days, not minutes)
1. **Word list** `app/src/main/assets/wordlists/<x>.bin`
   - source: CC-BY-SA 4.0 frequency data (see tools/wordlists/SOURCES.md for the de/en
     provenance; new source => record URL + licence line in NOTICE.txt of that folder);
   - build: `tools/build_wordlist.py` (deterministic VKWL1 format; cut-off chosen by
     sweep on the golden corpus — target: max typo-fix rate with clean false-accepts
     0 %, typo-collision filter per SWEEP_*.md);
   - budget: <= 1.5 MB compressed; stoplist `tools/wordlists/stop_<x>.txt` curated
     (colloquial misspellings in subtitle corpora must NOT be accepted as words).
2. **Golden corpus** `testdata/typing_goldens/<x>.tsv` — >= 20 rows, class distribution
   per docs/PLAN_V0147.md §2 (neighbour/transposition/missing/doubled/umlaut/caps/
   spacing) + >= 8 clean controls + morph rows for the language's inflection classes.
   Gate: fix rate >= 75 % (correctable set), clean FP <= 1 %, in
   `CorrectorGoldenTest` — expectations are list-derived, not hand-wished.

## Quick steps (minutes)
3. Layout `assets/layouts/<x>.json` — already exists for all 15 (geometry checked by
   existing tests); verify the neighbour model loads: `NeighbourCostTest` pattern.
4. Endonym (`LanguageCycle.ENDONYMS`) and space-bar "all languages" label
   (`tools/labels/all_languages.tsv` -> `AllLanguagesLabel`) — both already contain all
   15 languages.
5. **Flip the switch:** add `<x>` to `LanguageAvailability.AVAILABLE` (ONE line), remove
   its grey-out reason from Setup copy, CHANGELOG entry.
6. Full test run + review + dist as usual.

Languages WITHOUT steps 1-2 keep: rule-layer correction only (capitals/spacing) and,
if made keyboard-available without a list, an honest "no word correction" notice —
the current policy keeps them unavailable until the list is real.
