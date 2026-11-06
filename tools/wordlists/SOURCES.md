# Word list sources (T23b, docs/PLAN_V0147.md §15) — licence evidence

## Source (gate: documented CC-BY-SA 4.0 compatibility — PASSED)

- Project: **FrequencyWords** (word frequency lists generated from OpenSubtitles)
- Repository: https://github.com/hermitdave/FrequencyWords
  - NOTE: the task suggested 'ArashRP/FrequencyWords'; that repository does not
    exist (HTTP 404, checked 2026-11-05). hermitdave/FrequencyWords is the
    canonical "FrequencyWords" project referenced by PLAN_V0147 §15.1.
- Licence statement (README.md, checked 2026-11-05):
    "License: MIT License for code.
     CC-by-sa-4.0 for content."
  → word lists = content = CC BY-SA 4.0; user approval for CC-BY-SA with
    attribution NOTICE: 2026-11-05 (docs/PLAN_V0147.md §12.1).
- Corpus origin (README): OpenSubtitles 2018, http://opus.nlpl.eu/OpenSubtitles2018.php
- Files used (raw, kept in this directory, NOT under app/):
    content/2018/de/de_full.txt  ("word<space>count", utf-8)
      https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_full.txt
      retrieved 2026-11-05, 1,157,685 lines,
      sha256 a5fc13f4efaaf70fd9226d11295fd3cd4c2264852ee1fad22c0abaf64acec8dc
    content/2018/en/en_full.txt
      https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_full.txt
      retrieved 2026-11-05, 1,656,996 lines,
      sha256 7fea67ab954e2c01df6c608c9826e594cf36f8823b3243554f88245fb75dc506
- Version pinning: repository has no releases/tags for content files; the raw
  content URLs above plus sha256 of the retrieved files are the version record.

## Build (deterministic; VKWL1 format documented in tools/build_wordlist.py)

  python3 tools/build_wordlist.py build de-de tools/wordlists/de_full.txt \
      --stop tools/wordlists/stop_de.txt --top 100000 \
      --out app/src/main/assets/wordlists/de-de.bin
  # de-de.bin 1,082,777 B plain (budget 1.5 MB; T23c stoplist rebuild — VK-7
  #     size correction REVIEW_V0147), sha256
  #   c19613ffa2ea98677d59e64efbcdd7905b458a87c206b33095c5b00baadc85b0
  python3 tools/build_wordlist.py build en-us tools/wordlists/en_full.txt \
      --stop tools/wordlists/stop_en.txt --top 150000 \
      --out app/src/main/assets/wordlists/en-us.bin
  # en-us.bin 704,333 B (gzip over budget, plain 1,368,835 B > 1.0 MB), sha256
  #   891bb79d1d9e2028d3a2b6e7d0442aa82b88457bd82e81b5cc463ed4c94b2889

Determinism check 2026-11-05: rebuilds are byte-identical (asserted by
WordListFormatTest.rebuildIsByteIdentical). T23c curation (2026-11-05): stop_de.txt
and stop_en.txt were extended with subtitle-junk/fragment forms that collided with
the corrector goldens (see testdata/typing_goldens headers + precheck_t23c.py);
both assets rebuilt with the same deterministic commands, shas updated above.

## Derived assets shipped in the APK (attribution NOTICE travels with them)

  app/src/main/assets/wordlists/de-de.bin   (top 100,000 forms, see SWEEP_de.md)
  app/src/main/assets/wordlists/en-us.bin   (top 150,000 forms, see SWEEP_en.md)
  app/src/main/assets/wordlists/NOTICE.txt  (CC BY-SA 4.0 attribution)

Nothing else under app/ derives from the word list data.

## T23b2 (2026-11-05, PLAN §15.2 Ergänzung) — typo-filter evaluation, NO asset change

`--typo-filter R --typo-filter-base B` was added to build_wordlist.py (longtail
entries at rank > B are dropped when they are restricted-edit-distance-1 of a
>= R-fold more frequent list word and not an umlaut transliteration of it).
Sweeps over levels 100k..250k x R in {100,200,400}:
tools/wordlists/SWEEP_de_filtered.md / SWEEP_en_filtered.md.

DECISION: keep the shipped assets unchanged (de-de top 100,000 plain; en-us top
150,000 gzip) — no probe metric improves at any filtered level and the deeper
lists demonstrably degrade the T23c corrector (longtail junk candidates,
umlaut-transliteration words blocking the umlaut goldens). Ratio caches
(tools/wordlists/typo_ratios_{de-de,en-us}.json) remain committed for future
re-evaluation; the shipped builds do NOT use --typo-filter.
