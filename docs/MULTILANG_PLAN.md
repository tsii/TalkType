# Plan: multilingual typing across the language boundary (autocorrect
deferred)

**Status:** 2026-10-28 · **Role:** plan (no implementation, no build, no code
rework)
**User decision of the same day:** autocorrect/word suggestion will **not** be
implemented for now; this plan is fully prepared and will be worked on only
on request.

**Wish, verbatim:** type German **with English words in between** (and vice
versa) — **without** switching the language; with several selected languages,
typing *and* correction must cover **all chosen languages jointly** (all 15).

---

## 1. Decomposition into independent capabilities (the core)

The wish consists of **three** capabilities that technically have **nothing**
to do with each other. F1 is possible without a dictionary, F2 is not, F3 is a
different construction site.

### F1 — key offering for several languages simultaneously (**without a
dictionary, feasible today**)

Goal: the keyboard **permanently** offers all special characters of all chosen
languages — `ä ö ü ß` (de) **and** `é è ê à ç ù` (fr) **and** `ñ á é í ó ú`
(es) **and** `å æ ø` (sv/da), `ı ş ğ` (tr), `ё ы э` (ru), `ě š č ř` (cs),
`α β ς` (el) …

**Mechanics:** a **runtime merge** of the chosen assets into a *virtual
layout*:

- The letter order/geometry comes from the **primary layout**
  (`current_language`).
- The `alternates` of each key = the **union** of the `alternates` of that key
  across **all active languages** (same key, same position, e.g. `n` → `ñ ń`,
  `s` → `ß ś š`).
- Characters that are an **own** key in one active language become an
  **alternate** of the same key of the primary language in the virtual layout
  (e.g. `ü` from de is placed on `u` when primary = en).
- `symbols`/`symbolsShift` stay unchanged (the language is already neutral
  there).
- The **primary layout** continues to determine case mapping (`CaseMapper`
  per `caseLocale`) — a merged layout has **one** `caseLocale` (that of the
  primary language).

**Two hard rule conflicts the plan must solve (not hide):**

| Rule | Conflict | Solution in the plan |
|---|---|---|
| `LAYOUT_REQUIREMENTS` §1 rule 3: "max. **8** alternates per key" | the union across 15 languages can exceed 8 (e.g. `a`: `á à â ã å ä æ` …) | the rule applies to **assets** (which stay unchanged, ≤ 8). The **virtual** layout gets its own documented limit **12** + test "≤ 12, no duplicates, no key's own character" |
| §1 rule 3: alternates only **one** codepoint, NFC | `ı`/`i` (tr), `ß` (de), `ς` (el) are 1 codepoint each → ok; `ẞ` only in `shift` | stays fulfilled; the test checks NFC + `codePointCount == 1` |

**Globe semantics change:** in the future `🌐` switches **only** the primary
language (= letter arrangement), **not** the character offering and **not**
the dictation language (ASR stays `auto`, DESIGN §3). With only **one** active
language the behavior is exactly as today.

### F2 — word suggestion/completion across the language boundary (**needs a
dictionary — deferred**)

This is the actual "autocorrect across languages": completion, upper/lower
heuristics, "frequent words", typing-error correction over a **shared
vocabulary** of all active languages. Without a lexicon this is **not**
possible (no statistics, no candidates). It needs: an offline bundled or
downloaded dictionary per language, a joint candidate list, ranking, and a UI
that makes corrections visible (a suggestion bar) — plus a conflict rule with
ASR dictation (DESIGN §7.1 warns precisely about this: two correction
instances on the same text).

### F3 — ASR across language boundaries (**not** part of this plan)

Dictation stays `language=auto` (25 languages in the model, KONZEPT §2.5); a
German sentence with English words is possible today already — that is **not**
a typing capability. Delimitation only for clarity: F1/F2 concern exclusively
**typing/correcting**.

---

## 2. Concept fidelity

| Point | What the user wants | What `docs/KONZEPT.md` / the rules say | Decision/wording for the concept-fidelity table |
|---|---|---|---|
| **Dictionaries/autocorrect** | correction across several languages jointly | `KONZEPT.md` §7.1: "**No autocorrect and no learning dictionaries in version 1.**" · `reminders/ALWAYS.md` rule 9: "no autocorrect/dictionaries in v1" | **Deliberate, documented deviation (phase 2), date 2026-10-28, decided by the user themselves.** Entry: "*Multilingual word suggestion/correction (F2) extends the v1 statement 'no dictionaries': the user wants correction across all active languages. It will be implemented **offline only** (bundled/downloadable lexicon, no cloud service), **switchable off**, **without learning behavior** (no user history), and only after F1. Until then KONZEPT §7.1 applies unchanged.*" |
| **Resources** | 15 languages jointly | KONZEPT: offline, one signed sideload APK | lexicons **offline only**: either in the APK (size!) or via the existing `ModelStore` download (resume/hash). **No** cloud, no Google service, no telemetry learning |
| **F1 (character offering)** | wanted immediately | KONZEPT/DESIGN exclude only *autocorrect/dictionaries*, **not** the character offering | **No deviation** — F1 is pure layout logic and may be planned/built immediately |

---

## 3. Stage plan (test-first, ≤ 4 files, ≤ 2 builds per task)

### Phase 1 — F1: character offering across the language set (released,
autocorrect-free)

| M | Goal | Test FIRST | Files | ETA |
|---|---|---|---|---|
| **M1.1** | merge core as a pure function | `LayoutMergerTest`: (a) primary de + active en → the `n` alternates contain `ñ`; (b) primary en + active de → the key `u` carries `ü`; (c) ≤ **12** alternates, no duplicates, no own character; (d) NFC + 1 codepoint; (e) **one** asset alone ⇒ result **identical** to the asset (regression) | `layouts/LayoutMerger.kt` (new, ~120 lines), `app/src/test/.../layouts/LayoutMergerTest.kt` | 3 h |
| **M1.2** | merge in the loading process | `LayoutRepositoryTest`: `loadMerged(activeIds, primary)` delivers a virtual asset; with 1 language identical to `load()`; unknown IDs are ignored | `layouts/LayoutRepository.kt`, `layouts/LayoutMerger.kt` | 1.5 h |
| **M1.3** | wiring + globe semantics | `MultilangWiringTest` (instrumented): switching the primary language changes the letter arrangement, **keeps** the special characters of the other active languages; `active_languages` unchanged | `ime/VoiceKeyboardService.kt`, `keyboard/KeyboardView.kt` | 2 h |
| **M1.4** | shift layer of the merge | `LayoutMergerShiftTest`: uppercase forms of the merged alternates (only characters **with** an uppercase form, rule D3 from `LAYOUT_REQUIREMENTS`), `ß→ẞ`, the `σ/ς→Σ` exception | `layouts/LayoutMerger.kt` | 2 h |
| **M1.5** | visual check + docs | extend `LAYOUT_REQUIREMENTS` §1 rule 3 with "applies to assets; virtual layout ≤ 12"; emulator: open 2×, field change | `docs/LAYOUT_REQUIREMENTS.md` | 1 h |

**Phase 1 total: ~9.5 h** (3 tasks of ≤ 4 files each; builds: 2× `test`, 1×
instrumented).

### Phase 2 — F2: correction across the language set (**deliberately
deferred**)

| M | Goal | Test FIRST | Files | ETA |
|---|---|---|---|---|
| **M2.0** | decision template (lexicon source, size, license, off-switch) | docs + `LexiconSourceTest` (format only) | `docs/LEXICON_DECISION.md` (new) | 2 h |
| **M2.1** | lexicon loader + index (offline, packed) | `LexiconTest`: load, lookup, memory < X MB | `lexicon/Lexicon.kt`, `lexicon/LexiconFormat.kt` | 4 h |
| **M2.2** | candidates across **all** active languages (union, no ranking) | `CandidateUnionTest`: de+en delivers both word lists, deduplicated, sorted by language | `lexicon/CandidateProvider.kt` | 3 h |
| **M2.3** | suggestion bar (display only, **no** auto-replacement) | `SuggestionBarTest`: appears, selecting inserts, **no** automatic intervention | `keyboard/SuggestionBar.kt`, `keyboard/KeyboardView.kt` | 4 h |
| **M2.4** | optional: ranking/learning — **only** with explicit user approval | `NoLearningTest`: without approval **no** persistence of user words | `lexicon/Ranking.kt` | 3 h |

**Phase 2 total: ~16 h**, of which alone 2 h decision + 4 h lexicon. **Not**
before F1, not without the concept-fidelity line from §2.

---

## 4. Privacy assessment (F2)

- **No dictionary downloads from the network** in the basic variant: lexicons
  come **in the APK** (small languages) or via the **existing model
  downloader** (`ModelStore` with resume/SHA-256, one host, one consent). No
  calls to third parties.
- **No learning behavior:** no user history, no frequency counting, no
  "recently used" — with that the privacy promise (rule 9) stays intact, and
  the deviation concerns only "dictionary present", not "user data
  collected".
- **Switchable off:** a toggle in setup ("word suggestions") — default
  **off** until the user activates it. With that today's state (KONZEPT §7.1)
  is the default.
- **Order of magnitude `ASSUMPTION:`** lexicon per language 0.3–2 MB
  compressed (frequency list top 20k–50k); 15 languages ≈ 5–20 MB —
  acceptable next to 671 MB of model, but it is **APK growth** or a second
  download.

---

## 5. Schedule overview

| Phase | Content | ETA | Dependency |
|---|---|---|---|
| **1 (F1)** | character offering across all active languages | **9.5 h** (3–4 tasks) | none — **can start immediately** |
| **2a (F2 docs)** | lexicon decision + concept-fidelity entry | 2 h | user approval for dictionaries |
| **2b (F2 code)** | lexicon + suggestion bar, **no** auto-replacement | ~14 h | 2a |
| **2c (optional)** | ranking/learning | 3 h | explicit approval; otherwise **never** |

## ASSUMPTION lines

- `ASSUMPTION: The union of the alternates stays ≤ 12 per key if at most 4`
  `languages are active simultaneously; with more, the truncation test`
  `(M1.1c) applies and documents the limit in the UI.`
- `ASSUMPTION: Lexicon sizes 0.3–2 MB per language (top 20k–50k) — not`
  `measured.`
- `ASSUMPTION: F1 needs no schema change (no 5th layer); the merge creates a`
  `virtual asset in memory.`
