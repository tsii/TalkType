# PLAN V0.1.47 — T23 typing correction with magic-wand row (planner, 2026-11-05)
User mandate 2026-11-05: row ABOVE the keyboard (product, not debug): left = last typed
text with sensible correction, right = magic wand (replaces ladybug position); wand press
replaces the last typed text in the field by the shown correction; correction runs
continuously; DELTA: after a press, the next press only covers text typed since.
Independent of 0.1.46 (Qwen phone gate). Plan text by Opus 5.5 (read-only planner),
persisted verbatim by the orchestrator 2026-11-05.

## 0. Concept fidelity (refinement, user command 2026-11-05)
KONZEPT.md has NO autocorrect clause (grep = 0); the ban is ALWAYS.md rule 9
("no autocorrect/dictionaries in v1"). Refinement: NO automatic replacement ever — the
row is a SUGGESTION; text changes only on explicit wand press (user action, undoable).
Word lists are static, bundled, read-only; NO learning, NO user dictionary, NO
persistence of typed text. Rule 9 wording update needs user Go. Network: none (lists
ship as APK assets — after T20 the user may have revoked network).

## 1. Engine evaluation (decision)
(a) Neighbour graph + frequency word list (SymSpell-style, edit distance <=2,
    neighbour-weighted substitution costs from the layout JSON key rects). de+en lists
    ~0.4-0.6 MB each compressed (measure in T23b). Latency <5 ms/word. License MUST be
    redistribution-compatible — HARD GATE T23b (candidates unverified by planner).
(b) Rules without a word list: sentence-initial capital, after . ! ? ; double space;
    space before/after punctuation; "i"->"I" (en); doubled capital; repeated word.
    Works for all 15 (case-bearing scripts included). Zero size, zero lexicon risk.
(c) Small LM: deferred (0.1.5x upgrade candidate for dass/das-class errors).
(d) Qwen3-ASR decoder as text corrector: REJECTED (audio-conditioned ASR, second
    resident engine, 1.7 GB set, proven to invent text, rewrites instead of corrects).
DECISION: (b) all 15 languages + (a) de-de/en-us in 0.1.47; more languages = data-only.

## 2. Measurable quality target (release gate)
testdata/typing_goldens/{de,en}.tsv typed/expected/class. de: 40 typo + 20 clean;
en: 5 + 5. Classes: neighbour 35 %, transposition 15 %, missing 15 %, doubled 10 %,
umlaut/ß 10 %, caps 10 %, spacing 5 %. Metrics: word fix >= 75 % on typo words;
false-positive <= 1 % on clean; latency p95 < 10 ms per 20-word region (JVM).
Must-not-touch: digits, URLs/emails, ALLCAPS <=5, inner capitals, words in the list.

## 3. Correction semantics
Word tokens; last in-progress word held back unless region ends at wand press.
  DECISION 2026-11-05 (REVIEW_V0147 VK-5, Oracle): the hold-back is DELIBERATELY
  NOT in 0.1.47 — a hold-back without a closing separator would also suppress
  the completely typed final word ("...ist zu kalr" + wand — the MAIN use
  case). The residual risk stays minimal through VK-2 undo, VK-3 region-bound
  suggestions and the suffix proof; a 0.1.48 candidate only TOGETHER WITH a
  wand-press recompute (ends-at-wand semantics).
Accept only: word NOT in list, cost <= threshold, best freq >= k x runner-up
(ambiguity guard), length >= 3. Case preserved. No POS inference. List follows the
active layout language; no list -> rules only.

## 4. Typed-region tracking + delta (pure, JVM-testable)
TypedRegionTracker (analogous to OwnershipTracker/DictationOutputCounter): feeds text/
SPACE/BACKSPACE (buffer shrink; beyond buffer -> anchor reset)/ENTER; resets on field
start, wand press (DELTA), dictation commit, selection/cursor move, editor change.
Suffix proof via getTextBeforeCursor before display/replace; mismatch -> reset. Cap
500 chars (oldest dropped, anchor advances).

## 5. Replacement via InputConnection (safety)
Preconditions: verified, no selection, dictation IDLE, corrected != buffer.
beginBatchEdit; deleteSurroundingText(utf16len, 0); commitText(corrected,1);
endBatchEdit; re-verify; mismatch -> counter log + reset. Anchor -> after corrected.
Undo one level until next keystroke (wand shows undo glyph). TYPE_NULL -> row off.

## 6. Row (UI)
Eligible: TYPE_CLASS_TEXT minus password/URI/email variations, minus NO_SUGGESTIONS,
minus numeric/datetime/NULL; Setup switch "Typing suggestions" default ON.
  CLARIFICATION 2026-11-05 (REVIEW_V0147 VK-6, Oracle): the exclusion list is
  FINAL: password (incl. web/visible), URI, e-mail (incl. web), numeric,
  phone, datetime, TYPE_NULL. POSTAL_ADDRESS and PERSON_NAME are
  DELIBERATELY IN (the user contract names no address exclusion); the
  review's "address" addendum is a USER OPEN POINT for 0.1.48.
Height: statusRow slot reuse (40-48 dp); keyboard ~that much lower in eligible fields;
VK-INSETS unaffected; row stable while eligible. Left TextView single line, ellipsize
START, changed words bold+underline; no suggestion -> dimmed, wand disabled. Right:
wand ImageButton 48x48 VECTOR (magic-wand emoji needs API 30, minSdk 29 — no emoji),
contentDescription "Apply correction"/"Undo correction". Debug keeps ladybug+status
left of wand. statusBandPx>0 in release flips long-press popups to T5 band mode —
regression tests both modes. RTL: firstStrong direction; one smoke test.

## 7. Periodicity
Worker single-thread: immediate on separator, else debounce 300 ms; generation counter
cancels stale; UI on main; region <= 500 chars bounds cost.

## 8. Privacy (rule 9)
No typed text in logs/exports/prefs/files. Debug fields: corr_rows_shown, corr_applied,
corr_undone, corr_verify_fail, corr_lang, corr_region_len_bucket.

## 9. TDD matrix (red first)
T23a TypingRulesTest (+goldens corpus first); T23b WordListFormatTest + licence NOTICE
(GATE); T23c NeighbourCostTest + CorrectorGoldenTest; T23d TypedRegionTrackerTest;
T23e ReplacementTest (fake IC seam); T23f Robolectric row matrix + wand vector +
popup band regression; T23g service seam (debounce, log-capture assert); T23h setup
switch + debug counters; T23i docs (concept fidelity, rule-9 wording after user Go,
CHANGELOG); T23j emu duty 5566 + vc48 + full tests + review + dist.

## 10. Tasks (<=4 files, claim_files, Gradle mutex)
T23a correct/TypingRules.kt + test + testdata/typing_goldens/{de,en}.tsv
T23b tools/build_wordlist.py + assets/wordlists/{de,en}.bin + NOTICE + test
T23c correct/NeighbourModel.kt + correct/WordCorrector.kt + golden test
T23d correct/TypedRegionTracker.kt + test
T23e correct/RegionReplacer.kt + test
T23f ImeRootView.kt row + res/drawable/ic_wand.xml + strings + Robolectric test
T23g VoiceKeyboardService.kt wiring
T23h SetupActivity switch + DebugInfoFormatter counters
T23i docs; T23j build/review/dist.

## 11. Risks
AutoSpace interaction (dictation resets region — test dictate-then-type-then-wand);
word-backspace beyond buffer -> anchor reset; apps rewriting text -> suffix proof,
worst case row empties; keyboard lower by row height (opt-out switch); false fixes of
names -> ambiguity guard + explicit wand + undo; word-list licence — hard gate T23b.

## 12. User questions RESOLVED (2026-11-05)
1. CC-BY-SA word list with attribution NOTICE: USER APPROVED -> T23b licence gate
   PASSED (NOTICE file mandatory).
2. Row height / keyboard ~40 dp lower + Setup switch default ON: approved via feature
   command itself.
3. Rule 9 wording refinement: documented as user command 2026-11-05 (suggestion-only,
   wand-press-only, no learning, no persistence).

## 13. Research: model as word-list replacement (user request 2026-11-05)
1. CC-BY-SA word-list source with attribution NOTICE in the APK — yes, or must T23b
   find a permissive source instead?
2. Keyboard ~40 dp lower in eligible text fields, Setup switch default ON — ok?
3. Rule 9 wording update in ALWAYS.md needs explicit user Go (until then T23a/c-without
   -assets/d/e may still run).

Method (planner, NO web access -> every model name/size/latency UNVERIFIED, marked ~):
phone factors ORT 1.31.1 CPU int8 ARM, ASR engine (Parakeet ~863 MB) resident in
parallel; region = 20 words ~ 30 subword tokens; baseline = word list ~0.4-0.6 MB/lang,
<10 ms/region, zero rewrite risk.

| Class | Candidates (~unverified) | int8 size | RAM | Latency/20w | Quality | Rewrite risk | Fit WordCorrector(word,lang)->cand |
|---|---|---|---|---|---|---|---|
| (a) seq2seq GEC | t5-small 60M en, mT5-small 300M, BART-base spelling fine-tunes (oliverguhr/de-en, ~140M) | 60-300 MB | 150-600 MB | 0.5-1.5 s | typos+some grammar | MEDIUM-HIGH rewrites order/style; diff-guard needed | poor (sentence-level) |
| (b) CSC/detection | BERT-tiny/mini classifiers 4-30M, GECToR 125M en | 4-500 MB | 20-500 MB | 5-200 ms | DETECTION only -> candidates still need a list | LOW | partial (OOV check only) |
| (c) n-gram/char LM | word bigram top-1M 2-5 MB/lang; 3-gram 20-100 MB; char5 5-20 MB | = size (mmap) | <5 ms lookups | context ranking (das/dass); no inventing | NONE | GOOD as scorer behind the list; does NOT replace vocab |
| (d) fastText/embeddings | cc.de/en.300 quantized 10-100 MB | 10-100 MB | 5-20 ms | semantic neighbours != spelling neighbours (Haus->Wohnung) | MEDIUM wrong-word | poor |
| (e) Qwen3-ASR decoder text-only | our 0.6B export | 1.7 GB unless set present | ~2.5 GB | 1-5 s | ASR objective, text-only UNTESTED (prefill REQUIRES audio_embeds per §4.2/§6) | HIGH (silence-invention §5) | BAD |

(e) fair re-check: text-only path needs S=0 audio scatter (graph survival unverified);
even if it runs it is an ASR fine-tune without correction objective; slot conflict:
second resident 2.5 GB engine or per-press load (seconds). A general instruct-LLM of the
same size avoids audio but keeps rewrite/hallucination/RAM problems. REJECTED with
contract evidence.

VERDICT: no class replaces the list cheaper — (b)(c)(d) still need a vocabulary;
(a)(e) are 100-3000x larger, 50-500x slower, rewrite-risk contradicts
"correct without rewriting". APK ethics: lists (~1 MB de+en) stay in the APK; ANY model
would go through the ModelStore OPTIONAL-download path (never APK) and needs network
BEFORE the T20 revoke.
DECISION (i): keep word list for 0.1.47, T23a-j unchanged. Upgrade path:
- U1 (0.1.5x): word-bigram context scorer 2-5 MB/lang from the SAME CC-BY-SA corpus
  (NOTICE extended); interface `CandidateScorer` behind WordCorrector — build T23c with
  that seam from day one (cheap forward-compat).
- U2 (0.1.5x+, user Go): optional TEXT set BART-base spelling de/en (~140-180 MB int8),
  export via tools/qwen_ref staged pattern; runs ONLY on wand press while dictation IDLE,
  unloads after 60 s; output accepted only if word-level diff = pure substitutions
  (else fall back to list result); requires explicit user refinement of the
  one-engine-resident rule + own opt-in download.
- NEVER: Qwen-ASR decoder as text corrector; fastText; models inside the APK.
Gate for any upgrade: T23 golden corpus — fix rate up AND false-positive <=1 % AND
p95 <300 ms/region on the phone.

## 14. Correction language mode (user addendum 2026-11-05; planner text, persisted verbatim)
### 14.1 Modes
| Mode | Pref (vk_layout) | Accept word if … | Candidates from | Space label |
|---|---|---|---|---|
| CURRENT (default) | corr_lang_mode=current | in list of current_language | that list | endonym as today |
| ALL_ACTIVE | corr_lang_mode=all | in ANY list of active languages | all active lists | "All languages" in DEVICE display language (§14.4) |
🌐 short tap still cycles layouts (unchanged). In ALL_ACTIVE the space bar shows the
mode label; the layout endonym moves to the correction row as a small prefix tag
("DE ·") so the layout stays identifiable.
### 14.2 Toggle (decision)
Primary: LONG-PRESS 🌐 (>=400 ms, char-menu hold threshold) -> 2-cell LongPressMenu
[<endonym current>] [All languages], slide-select; one active language -> disabled cell +
hint "Only one language active". Secondary: Setup switch (same pref) + one-line shortcut
hint. NOT swipe/long-press space (gesture conflicts). Non-IDLE: allowed (text-only).
Coder check: does KeyboardView arm long-press on ACTION keys today? If not, T23k adds it
for `language` only.
### 14.3 ALL_ACTIVE semantics
Lists loaded per active language that HAS one (0.1.47: de-de,en-us; 1-3 MB each, <=5
probes/word). Accept: in any list (caseLocale-aware) OR compound-valid (§15.3). Edit
costs: neighbour graph of the DISPLAYED layout; unreachable chars flat substitution;
de umlaut rules for de candidates only. Ranking: cost, then current-layout language, then
frequency RANK within own list (raw counts not comparable). Cross-language ambiguity:
different languages, cost Δ<0.5 and rank ratio <3 -> no suggestion. List-less active
languages: words containing chars ONLY on such layouts (derived from layout JSON char
sets) are skipped; else processed (wand-only + undo bound damage). Setup availability
line: "Word correction available for: Deutsch, English (US); other: punctuation/capitals
only." Universal rules always; language rules gated per §14.3. Case via CaseMapper
caseLocale; row textDirection=firstStrong.
### 14.4 "All languages" label in device language
Device locale = Resources.getSystem().configuration.locales[0] (app UI stays EN).
Technique: Kotlin table AllLanguagesLabel keyed by BCP-47 subtag for the 15 languages +
en; fallback EN "All languages". NOT values-<lang> (single-string folders trigger lint
MissingTranslation error; partial-l10n trap). Pattern = existing LanguageCycle.ENDONYMS.
Translations via translator role (rule 1), <=14 chars for the space key at 12 sp.
Limits: locales outside the 15 show EN; regional variants map by language subtag
(pt-BR->pt); re-resolve on onStartInputView/config change.
### 14.5 Tests added
LanguageModePolicyTest (default CURRENT; ALL_ACTIVE with 1 language == CURRENT; any-list
accept; tie-break current; cross-lang ambiguity -> null; list-less char guard; en "i"
matrix). AllLanguagesLabelTest (15+fallback, <=14 chars, pt-BR->pt, ja->EN). Robolectric:
🌐 long-press popup + pref persist; single-language hint; short tap cycles (regression);
space label switches; Setup mirrors. Golden corpus + class=mix rows (mixed de/en;
expected values fixed FROM the list, not hand-wished).

## 15. Morphology, list size, licence (user question 2026-11-05)
### 15.1 Answer
Frequency lists (FrequencyWords/OpenSubtitles, CC-BY-SA 4.0, NOTICE — licence gate
PASSED by user) are WORD-FORM lists: conjugated/declined/participles/comparatives are
own entries. Inflection is covered by LIST DEPTH, not rules. Real gaps: (1) productive
de COMPOUNDS -> splitter (§15.3); (2) rare valid forms under the cut-off + spoken-corpus
register gap; (3) lists contain colloquial misspellings (nich, gehts) -> stoplist.
### 15.2 Size decision
de **100k forms (1.08 MB)** SWEEP RESULT 2026-11-05 (SWEEP_de.md: fix% monoton fallend
  81->63 %, 250k laesst Tipp-Kollisionen 'gte/sontag/warrten' durch; 100k = max fix% bei
  clean-FP 0 %, alle Morph-Testwoerter vorhanden); plan-baseline 250k VERWORFEN; en **150k (~0.8 MB)**. Final cut-off TUNED
by T23b sweep (100k..300k; max fix rate with clean-FP <=1 %; sweep table committed).
Stoplist <=200 curated entries per language (tools/wordlists/stop_<lang>.txt; static,
no learning — rule 9 safe). Test budgets: de <=1.5 MB, en <=1.0 MB (was 700 KB).
### 15.3 Compound splitter (de only, pure Kotlin)
word = w1 (+linking s|es|n|en|er|e|∅) + … + wk, k<=4, parts >=3 chars and in list
(head = last part, case-folded); DP over <=40 chars, <1 ms. ACCEPTANCE ONLY (never
candidate generation). Unknown but splittable -> no suggestion.
### 15.4 Rejected
Hunspell/LO dicts (GPL — beyond user's CC-BY-SA approval, needs separate Go);
HFST/SMOR-class FST (size/licence/native toolchain); own suffix generator
(over-accepts typos ending -en/-st).
### 15.5 Golden corpus extension (T23c)
class=morph de15/en5: clean inflected UNCHANGED (ginge, gewichen, Häuser, des Hauses,
hättest; children, mice, went, better) AND typo'd inflected fix TO the inflected form
(gegnagen->gegangen, Hääuser->Häuser, chidlren->children, gose->goes).
class=compound de8: valid compounds unchanged; typo INSIDE compound -> NO suggestion in
0.1.47 (expected=input, documented limitation). Metrics now include morph+compound rows.

## 10b. Task changes (2026-11-05 addenda)
T23b + sweep script + stoplists + NOTICE (CC-BY-SA 4.0) + budgets de<=1.5MB/en<=1.0MB.
T23c2 correct/CompoundSplitter.kt + test. NEW T23k correct/LanguageModePolicy.kt +
correct/AllLanguagesLabel.kt + tests (labels via translator role FIRST). NEW T23l
KeyboardView 🌐 long-press + ALL_ACTIVE space label + service pref wiring + Robolectric
(sequential after T23g, both claim VoiceKeyboardService.kt). T23h + Setup switch
"Correct in all active languages" + availability line.
Order: T23a → T23b → T23c/T23c2 → T23d → T23e → T23k → T23f → T23g → T23l → T23h → T23i → T23j.

### 14.6 Precision after user addendum 2026-11-05 (globe, anglicisms, default scope)
(1) GLOBUS SHORT TAP — ONE CYCLE, BOTH MODES (decision): CURRENT: short tap = next
active layout (unchanged), correction language follows the layout; ALL_ACTIVE: short tap
= next active layout (unchanged), key positions + neighbour graph follow, row prefix tag
("DE ·"/"EN ·") changes, space keeps the mode label. NO second correction-language
cycle: ALL_ACTIVE uses all lists at once (nothing to cycle); CURRENT's layout IS the
correction language. Rejected variant C ("All languages" as extra globe-cycle stop):
mixes two dimensions; layout change only by leaving the mode. USER CONFIRMED 2026-11-05: one cycle (variant C declined).
(2) ANGLICISMS (ALL_ACTIVE): (a) MERGE candidates by case-folded surface BEFORE ranking
(de "Meeting" + en "meeting" = ONE candidate, best rank wins) — cross-language brake
applies only to DIFFERENT surface forms, so anglicism typos are not blocked by their own
twin; (b) case from input token ("Meeitng"->"Meeting", "meeitng"->"meeting"), no noun
capitalisation inference; (c) no anglicising (Projetk->Projekt cost 1 beats project
cost 2); (d) mixed compounds: splitter parts from ANY active list language, split also
at "-"; acceptance only; (e) same-surface conflicts (Mial -> Mail|mal, both cost 1) ->
brake, no suggestion. CURRENT=de: common anglicisms usually live in the de subtitle list
(Meeting, Job, Team, Update); golden documents both.
GOLDEN class=mix >=12 rows with mode column (cur|all): Meeitng->Meeting; Jbo->Job;
Udpate->Update; Dwonload->Download; Taem->Team; Projetk->Projekt (no anglicising);
Feedbakc->Feedback; Homeofice->Homeoffice; "gecancelt" unchanged (accepted Denglish);
Teammeeting unchanged (mixed compound); Mial unchanged (brake); clean control;
Team-Meetnig->Team-Meeting (hyphen split). "list-derived" expectations fixed by a
list-presence PRECHECK in T23b and committed; test fails if expected form absent from
the lists. Mix rows count into fix >=75 % / clean FP <=1 % (mode=all).
(3) DEFAULT SCOPE de+en ONLY (deliberate): lists only de-de/en-us; other 13 languages
rule layer only (list-less-char guard stays). Default active languages are already
de-de,en-us -> out-of-box ALL_ACTIVE = de+en. A new language becomes list-capable via
assets/wordlists/<lang>.bin + NOTICE line + testdata/typing_goldens/<lang>.tsv
(>=20 rows, §2 metrics green) — WordListRegistry discovers by asset name, NO code
change. Setup availability line reads from the registry.
§10 changes: T23b + WordListRegistry auto-discovery (+ test 'only de+en present') +
list-presence precheck generating mix-column 'list-derived'; T23c + merge-before-brake +
mix corpus >=13 rows with mode column + tests for merge/brake/anglicising; T23c2 parts
from any active list in ALL_ACTIVE + hyphen split; T23l regression 'short tap = layout
switch in BOTH modes, prefix tag follows, space keeps mode label'.

## 16. Keyboard scope limited to de+en (user command 2026-11-05) + new-language runbook
USER DECISION: until further notice the KEYBOARD offers only de-de + en-us. All other
13 languages are GREYED OUT in the Setup language menu (not removable-silent: disabled
row + one-line reason "coming soon", tap shows hint, never a silent no-op E9.1).
- Single source of truth: correct/../LanguageAvailability AVAILABLE = {de-de, en-us}
  (pure Kotlin set; NOT hard-coded in UI). Consumed by: Setup menu (grey+reason),
  LanguageCycle (active set filtered through availability; active languages that become
  unavailable drop out of the cycle transparently; current_language falls back to first
  available; first-run default already de+en).
- Correction layer UNCHANGED (already de+en only, §14.6(3)) — this restriction is about
  the keyboard offering, and it also makes ALL_ACTIVE = de+en trivially true for now.
- Setup note line explains WHY (word correction quality) + points to future additions.
- TASK T23m (after T23g/T23l, same files area): LanguageAvailability.kt + test +
  SetupActivity grey-out + cycle filter + Robolectric (grey rows not clickable, cycle
  drops unavailable, fallback). docs/ADD_LANGUAGE.md shipped.
- RUNBOOK docs/ADD_LANGUAGE.md (what it takes to add language X): (1) layout asset
  already exists assets/layouts/<x>.json (geometry tests green); (2) word list
  assets/wordlists/<x>.bin via tools/build_wordlist.py (CC-BY-SA source + NOTICE line +
  stoplist curation + sweep table, budget <=1.5 MB); (3) testdata/typing_goldens/<x>.tsv
  >= 20 rows (§2 class mix) with metrics fix>=75 % / FP<=1 % green incl. morph rows;
  (4) endonym (exists) + AllLanguagesLabel entry (exists, <=14 chars); (5) neighbour
  model auto-derives from layout rects (no code); (6) ADD <x> TO LanguageAvailability
  AVAILABLE (ONE LINE) + delete grey-out reason for it + CHANGELOG. Steps 2-3 are the
  real work; 1/4/5/6 are minutes.

## 17. U2 implemented NOW (user command 2026-11-05) + preheat-on-keypress
USER COMMAND: build U2 (optional BART spelling-correction set) already in this cycle.
Load+warm-up AUTOMATICALLY when the user presses any keyboard key EXCEPT Enter and the
dictation key. This command also carries the two U2 decisions of §13 (one-engine rule
relaxed for an on-demand TEXT model alongside the resident ASR engine; own opt-in
download).
### 17.1 Lifecycle (decision)
- Trigger: first text-committing key of an editing session (eligible field, correction
  switch ON, set downloaded) -> single background load+warm-up (one dummy 8-word
  inference) on the existing worker; repeated keys never double-load (state machine:
  IDLE/LOADING/READY/UNLOADING).
- Never starts while dictation is non-IDLE or the ASR engine is mid-load (RAM guard
  freeBytes >= 400 MB, else retry on next keypress up to 3x then wait for next session).
- UNLOAD 60 s idle (per §13) or on onStartInputView into an ineligible field; wand press
  while LOADING -> correction row shows list-result immediately and U2 result when READY
  (never blocks the row).
- Output guard per §13: accepted ONLY if word-level diff vs input = pure substitutions
  (no insert/delete/reorder) AND wand-press-only like list suggestions; else fall back.
### 17.2 Tasks (T24 chain, after T23 chain merges; export gate FIRST, no gradle needed)
T24a U2-GATE host: export BART-base spelling de/en (candidate oliverguhr/
spelling-correction-*-base; licence+gated-repo check) -> ONNX enc/dec int8 + parity
<=1e-4 rel on 20 gold sentences/lang + RTF host + docs/BART_CORRECT_CONTRACT.md.
T24b ModelVariant TEXT sets (bart-spelling-de/en-int8) via ModelStore + manifest pinning
+ download policy (RAM/disk gate, opt-in in Setup card, never auto-download).
T24c BartCorrectEngine (ORT; encoder + greedy KV decode loop; substitution guard).
T24d CorrectorSeam: WordCorrector -> list result NOW, U2 result async upgrade of the
same row; preheat state machine in service (trigger EXCLUDING enter/dictation keys).
T24e Setup card + debug fields (u2_state, u2_upgrades, u2_guard_rejects) + docs/CHANGELOG.
Gate for shipping the card: BART_CORRECT_CONTRACT Go + golden-corpus upgrade metrics
(fix% up, FP<=1%, p95 <300 ms phone-later) — same rules as §13.

## 18. Phone gate replaced by emulator (user command 2026-11-05)
User cannot attach a phone now. Gate moves to emulator-5566 (AVD vk-api36, x86_64 image
confirmed in config.ini; debug builds ship x86_64 ABIs — the past ORT SIGSEGV was the
arm64-ONLY diag APK under ARM translation, plain debug must run native x86_64).
Procedure: start 5566 -> push qwen set flat + sha cross-check -> install 0.1.46
diag-debug (NOT arm64Only!) + androidTest APK -> am instrument QwenGoldenTest.
GATE SEMANTICS CHANGED (documented): emulator proves FUNCTION + relative latency
profile (phase breakdown enc/prefill/decode, vk_qwen_rtf); ABSOLUTE 10 s criterion is
replaced by: emulator total_ms <= 10000 AND decode tok/s recorded; final phone spot
check deferred to next physical phone access (Qwen card ships 'experimental' flag
unchanged). If the x86_64-native debug build ALSO SIGSEGVs, environment is confirmed
broken -> report, keep card locked.

## 19. U2-GATE executed 2026-11-05 -> GO mit Bedingungen (docs/BART_CORRECT_CONTRACT.md)
- FOUNDING CORRECTION: oliverguhr DE model is a FLAN-T5-base fine-tune (NOT BART; the
  HF name is misleading). EN = bart-base fine-tune. Both Apache-2.0, ungated, seq2seq
  with use_cache -> engine must support T5 AND BART decoder shapes. DE input REQUIRES
  prefix "correct: " (without it: measured degeneration loops).
- PARITY: wrapper-vs-HF 0.0 all stages; ORT-logit paths <=1.4e-6; word-identity int8
  vs HF-greedy: en 107/107 = 100 %, de 104/107 = 97.2 % (>=95 % target); ALL diffs are
  pure substitutions (guard-compatible), 2 cases in reports/bart_u2_de.json.
- HOST RTF int8 20-word region: en med 0.26 s / p95 0.38; de med 0.42 / p95 0.49;
  ONNX RAM 420-510 MB. SIZES: en 346 MB, de 422 MB — OVER the 200 MB target (embed +
  lm_head duplicated across 3 graphs).
- CONDITIONS for T24b/T24c: (C1) merge/embed-share decoder graphs BEFORE shipping set
  (target en ~215 / de ~259 MB) or accept with explicit download-size copy in Setup;
  (C2) phone-thread RAM measurement (threads=4) before card unlock; (C3) quality vs
  real dictation goldens; optional single-model candidate
  oliverguhr/spelling-correction-multilingual-base (MIT) to evaluate for U3.

## 20. Qwen-Emu-Tor ERGEBNIS 2026-11-05 (emulator-5566, native x86_64, APK 979f4a86)
FUNKTION GRUEN: QwenGoldenTest 7/7 (4 Gold-Texte unter 0,85-Latte + Stille exakt '' +
Guards). Durchlauf der Tor-Kaskade: 8/16-Koepfe-Bug (Tensor-Durchreiche-Fix) ->
Dalvik-OOM (NativeKv: ORT-eigener Speicher, 2 Generationen, ~156MB natives bei 15s-Cap)
-> Segment-Obergrenze 15s (Coordinator segmentFinalizeMs=15000 fuer Qwen, Parakeet 35s
unveraendert). 10s-Fenster: total 5,06s (enc 3,31 / pre 0,44 / dec 1,26; 19 tok = 15
tok/s). 35s-Fenster: 3 Segmente, total 40,4s (enc 13,8 / pre 1,7 / dec 12,0; 86 tok =
7,1 tok/s) -> RTF 1,16; KRITERIUM total_ms<=10000 NOTERLEUDT auf dem Emulator
(Vergleichsmarke Host-Python gleiche Hardware: RTF 0,13-0,35 — Emulator-Overhead
~3-7x; Telefon真实-Lage ungemessen). Memory sauber: native_kb vor/nur 6,0->14,0 MB,
Java-Heap konstant. ENTSCHEIDUNG der Karten-Freigabe bei Opus (Lauf sa-neu): (A) 0.1.46
ohne Qwen-Freischaltung (Karte bleibt 'not available', Infrastruktur committen) oder
(B) Karte frei mit ehrlicher 'langsamer, experimentell'-Beschriftung (Qwen ist expliziter
Opt-in, Parakeet bleibt Default+empfohlen; Nachte-Diktat-Risiko traegt nur der
Umsteller) + Telefon-Stichprobe bei naechstem Geraet-Zugang.

## 20b. ORACLE-ENTSCHEIDUNG 2026-11-05: B (Karte freigeben) mit Bedingungen
Reversibilitaets-Argument: B ist per BASE_URL_QWEN='' faktisch ein-Zeilen-reversibel
(Karte 'not available yet', Models bleiben liegen, Nutzer loescht selbst); A ist es nicht
(kein Upload = keine Telefon-Daten je). Emu-Notleuderung RTF 1,16 = Emu-Overhead 3-7x
gegen Host-Python 0,13-0,35 auf derselben CPU — kein Modellurteil. Nachtvertrag intakt
(ModelSwitchPolicy nie Abort; Parakeet Default). Bedingungen:
(C1) Karten-Warnlabel KONKRET (EN-UI): 'Slower than real time on our test device (RTF 1.2
     measured); NOT yet measured on your phone — it may be much slower. Experimental.'
(C2) VOR Freischaltung: Orphaned-Delete-Fix — SetupActivity.kt:392-399: Delete-Button
     sichtbar wenn Dateien READY, AUCH bei locked card (BASE_URL-Rueckzug darf 1,7 GB
     nicht UI-loeschfrei hints).
(C3) Telefon-Stichprobe naechster Geraetezugang PFLICHT: vk_qwen_rtf notieren; RTF>=1
     dauerhaft ODER Stille-Halluzination im Feld -> BASE_URL_Rueckzug sofort, keine freie
     Karte in spaeteren Releases bis RTF<1 belegt. Rueckholpfad = ein Commit.
NAechstes: C1+C2 Bau (T22-Session), Volltests, dann NUTZER-GO fuer commit/push (T22
zuerst, separat!) + Server-Upload + BASE_URL_QWEN (T23-WIP bleibt unberuehrt im Baum).
Side-Notiz Experte (Nutzeridee Diktat->Zusammenfassung): ASR-Modell dafuer unbrauchbar;
Qwen3-1.7B-Instruct int8 als V2-Feature realistisch (Strukturierung ja, abstraktive
Zusammenfassung grenzwertig; 20-60s per 3-5min-Diktat; erst nach Telefon-Stichprobe
evaluieren, nie interaktiv).
