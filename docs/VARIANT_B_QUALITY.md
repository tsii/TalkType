# Variant B (Arm LiteRT, dynamic int8) — German quality measurement

**Status:** 2026-10-28 · **Role:** measurement (no code/build changes)
**Thresholds (from `docs/E12_VARIANT_B_DESIGN.md` §94):** word identity
**≥ 95 %**, character edit distance **CED ≤ 2 %**, **no foreign-language
output** (a hard zero tolerance).

## Setup (reproducible, local)

| Element | Value |
|---|---|
| B reference | `tools/arm_litert_reference_decode.py` (unchanged; **only** the `AUDIO` path replaced per run, a copy under `/tmp/b_ref_<file>.py`) |
| Models | `testdata/litert/*.tflite` + `token_vocab.bin` |
| Runtime | `.venv-asr` `ai-edge-litert 2.3.0` (signature runner; deliberately **not** `tflite_runtime`) |
| Audio | `testdata/*.wav` (16 kHz int16) → for B converted to float32 (`/tmp/wav32/`, otherwise the reader refuses `tag==3`) |
| A reference | `tools/reference_decode.py` (ONNX Runtime 1.31.1, `.models/`) — on the same material |
| Raw data | `testdata/b_quality/*.b.txt` (B), `*.a.json` (A), `metrics.json` |

**Procedure per file:** one B decode (the whole audio, one resp. several
windows), one A decode (the whole audio), comparison against the §4.1 target
text.

## Result

| File | Words (B) | Word identity B | CED B | Language B | Anomaly |
|---|---|---|---|---|---|
| `de_smoke.wav` | 8 | **66.7 %** | 21.5 % | German | **start missing**: `gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.` (the first 6 words "So, jetzt bin ich" are missing) |
| `de_part1.wav` | 9 | **0.0 %** | 68.8 % | **ENGLISH** | `I'm not sure if you're still tile mystic cards.` — a complete failure |
| `de_part2.wav` | 8 | **87.5 %** | 4.5 % | German | only capitalization: `und nach einer Pause kommt der zweite Teil.` (substantively correct) |
| `de_pause.wav` | 11 | **46.7 %** | 38.0 % | German | start missing ("Das") **and** the second sentence wrong: `Und da feiert die Dreh.` instead of `Und nach einer Pause kommt der zweite Teil.` |
| **Average** | — | **50.2 %** | **33.2 %** | 1 of 4 English | — |

**Variant A (the same material, the same machine):** **100 % word identity,
0 % CED** on all four files (exactly §4.1) — identical with the device
values (`s1_realEngine_realWav` green).

## Verdict against the thresholds

| Criterion | Target | Actual (B) | Assessment |
|---|---|---|---|
| Word identity (average) | ≥ 95 % | **50.2 %** | **not passed** (off by a factor of 2) |
| CED (average) | ≤ 2 % | **33.2 %** | **not passed** (a factor of 16) |
| Foreign-language output | 0 cases | **1 of 4** (`de_part1` completely English) | **not passed** (a hard rule) |

**→ Not passed. Do not ship B in this form** — not even "only for English",
because the app is a German dictation tool with 15 layouts, and a silent
language switch inside the text is exactly the error class we excluded with
E5/`ASR_CONTRACT` §4.3.

## What could be an artifact of this (and what is not)

- **Systematic start loss (3 of 4 files):** the first words are missing
  (`de_smoke` −6 words, `de_pause` −1 word). That smells of the
  **priming/cache semantics** of the reference script (first
  windows/decoder warmup), not necessarily of a model weakness —
  `ANNAHME:`. A counter-check would be an offset test (preceding the audio
  with 0.5 s of silence) — **not** part of this measurement.
- **`de_part1` English:** here the start is *not* missing (9 words emitted),
  the text is substantively invented. That is **not** an artifact effect
  but a real language switch of the pipeline — the Arm chain was evaluated
  only on **LibriSpeech (English)** (a promotional line of the model card
  text).
- **`de_pause` sentence 2 wrong:** after the 4-s pause the context breaks
  ("Und da feiert die Dreh."). Plausibly the same cache/window effect;
  regardless, the result is unusable.

## Consequence for the delivery

1. **B stays out** (v0.1.x and v0.2) as long as the three gates are not
   reached.
2. A **second measurement run with an offset variant** (a silence prefix, so
   the start is not lost) is cheap (10 min) and would clarify whether the
   50.2 % come from the priming effect alone. `ANNAHME:` even then
   `de_part1` would remain English → the gate stays broken.
3. **A is and remains the reference** (100 % / 0 % CED, identical on device
   and desktop).
4. The **engine switch infrastructure** (S0–S2 from
   `MODEL_VARIANT_SWITCH_MEMO.md`) is sensible independently of that, but
   without a shipped B variant it is only scaffolding.

## ASSUMPTION lines

- `ANNAHME: The start loss is a priming/cache artifact of the reference`
  `script — why: it occurs identically in 3 of 4 files, and de_part2 (3.1 s)`
  `is almost error-free despite the same length. Not verified (the offset`
  `test is missing).`
- `ANNAHME: The RTF values from the script (0.014–0.039 on x86) are not an`
  `ARM benchmark — only the text quality is the statement here.`

---

## Start-loss test (R4, 2026-10-28) — priming hypothesis checked

**Hypothesis:** the systematic start loss comes from **window/cache
priming** (first window from sample 0, an empty
`pre_encode_feature_cache`, `previous_sample = 0`) — not from the model.

**Setup:** the same script, only `AUDIO` replaced; audio in `/tmp/wav32/`
as float32. Variants per file: **(A)** unchanged, **(B)** 0.4 s of silence
prepended (6,400 zero samples), **(C)** the first 0.4 s cut off. Raw data:
`testdata/b_quality/*_pre.b.txt`, `*_cut.b.txt`, `prefix_test.json`.

| File | Variant | Words | Word identity | CED | Text |
|---|---|---|---|---|---|
| `de_smoke` | A (R3) | 8 | 66.7 % | 21.5 % | `gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.` |
| `de_smoke` | **B +0.4 s silence** | 8 | **66.7 %** | **21.5 %** | **identical to A** |
| `de_smoke` | **C −0.4 s** | 8 | **66.7 %** | **21.5 %** | **identical to A** |
| `de_part1` | A (R3) | 9 | 0.0 % | 68.8 % | `I'm not sure if you're still tile mystic cards.` (English) |
| `de_part1` | **B +0.4 s silence** | 2 | 28.6 % | 62.9 % | `des Diktats.` (**German**, but wrong content) |
| `de_part1` | **C −0.4 s** | 4 | 0.0 % | 68.6 % | `and the five years.` (**English**) |

### Finding 1 — the start loss is **not** priming

`de_smoke` delivers **the same** text in **all three** variants (66.7 % /
21.5 %). Neither 0.4 s of silence before nor 0.4 s less changes a single
character. The missing words "So, jetzt bin ich" are thus **inherent to the
model/pipeline** and not explainable by sample-0 priming. Hypothesis
**refuted**.

### Finding 2 — `de_part1` flips language with 0.4 s of silence

Without a prefix and with −0.4 s: **English** (`I'm not sure…`,
`and the five years.`). With +0.4 s of silence: **German**, but
substantively wrong (`des Diktats.`, 2 of 7 words). That is **worse** than
a stable failure: on short, sentence-like German the pipeline is
**language-unstable** — 400 ms of leading silence decide between German and
English. Such a path is not shippable for a dictation tool (a silent
language switch in the text = exactly the error class from E5/§4.3).

### Consequence

- The **priming explanation is dead**; the start loss remains a real
  deficiency of B.
- The average gates stay broken (50.2 % / 33.2 %), and the language
  instability is an **additional, independent** reason for exclusion.
- **B will not be shipped.** A further debug cycle on the Arm reference
  chain would only make sense if Arm/Google delivered German evaluation
  data for this variant — not as app integration work.
