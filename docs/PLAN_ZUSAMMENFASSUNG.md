# PLAN ZUSAMMENFASSUNG — 0.1.48 / vc49: "Zusammenfassungs-Button" (on-device dictation summarizer)

Planner: oracle session, 2026-11-05. Status: PLAN ONLY — no code, no Gradle, no export, no git.
Repo language English; German titles allowed. Every figure is either **BELEGT** (cited) or **ZIEL** (target with measurement plan) or **~unverified** (planner has no web access — same discipline as `docs/PLAN_V0147.md` §13).

## 0. Ground truth this plan stands on (read before deciding anything)

- Expert side-note (user idea, `docs/PLAN_V0147.md:388-390`): ASR model useless for summarizing; **Qwen3-1.7B-Instruct int8 realistic as V2 feature** ("Strukturierung ja, abstraktive Zusammenfassung grenzwertig; 20–60 s per 3–5 min-Diktat; erst nach Telefon-Stichprobe evaluieren, **nie interaktiv**"). **Absorbed as the seed of this plan — no other lost summary-plan artifact exists in the repo** (grep `ZUSAMMENFASS|SUMMAR` over `docs/` hits only PLAN_V0147:388-390 and unrelated LAYOUT_AUDIT/AOSP_ROWKEYS noise).
- Hard ban: Qwen3-ASR is pure ASR and must NEVER be repurposed as a text model (`docs/PLAN_V0147.md` §1(d), §13(e); oracle note 2026-11-05).
- Rule 9 (privacy): suggestion-only, no content in logs/exports — codes only (`docs/PLAN_V0147.md` §0/§8; KONZEPT.md refinement T23, lines 539 ff).
- E9.1 (`docs/LANGUAGE_EMOJI_DECISIONS.md:21` ff): short tap / long-press only where established; **no dead click, no silent no-op** — an unavailable feature must never look available.
- Dictation contract: "hold = continuous dictation, no abort"; nothing may abort or auto-modify dictation text (KONZEPT §2.1, PLAN_V0147 §0).
- T23 replacement safety pattern to reuse: batch edit + suffix proof before/after + single-level undo (`docs/PLAN_V0147.md` §5, `correct/RegionReplacer.kt`).
- Export/quantize pipeline to reuse: `tools/qwen_ref/export_qwen_onnx.py` staged/skip-if-present pattern, opset 17, `quantize_dynamic(QInt8)`, ExternalData `flatten_and_dedupe` (byte-identity only!) (`docs/QWEN_ASR_CONTRACT.md` §2), and the BART §12 fusion to one shared blob (`docs/BART_CORRECT_CONTRACT.md` §12, en 179.3 MB / de 248.5 MB, −48 %/−41 %).
- Device KV/RAM lessons (all BELEGT, `docs/QWEN_ASR_CONTRACT.md` §6 T22-oom-fix/-fix2): Java float-array KV = Dalvik OOM; `allocateDirect` counts into the same 604 MB Dalvik pot; final architecture = **ORT-eigen memory** (each KV generation = open `OrtSession.Result`, zero-copy borrow, deterministic `result.close()`); segment hardcap `QwenAsrEngine.MAX_SEGMENT_AUDIO_S = 15` (`QwenAsrEngine.kt:196`) ⇒ two KV generations ≈ 156 MB native.
- ModelStore pattern: `ModelVariant` enum + `ModelManifest.forVariant/baseUrlFor` + sha-pinned `ModelFile` list + traversal-strict `isAllowedName`; **empty BASE_URL = card inert, fail-fast without network** — the one-line kill switch (`ModelManifest.kt`, `BASE_URL_QWEN = ""`).
- Emu gate precedent (`docs/PLAN_V0147.md` §18/§20/§20b): emulator-5566 (AVD vk-api36, x86_64 native) proves FUNCTION + relative latency; emulator overhead 3–7× vs host; **absolute judgement = mandatory phone spot sample**; withdrawal rule = one-line URL retraction (C3, `docs/PLAN_V0147.md` §20b) + orphaned-delete fix C2 (`SetupActivity.kt:392-399` pattern).
- Honest-label pattern C1 (`docs/PLAN_V0147.md` §20b): concrete measured warning copy on the Setup card, never vague.

## 1. Product decision (interaction flow)

**Decision: a dedicated summarize action that appears in the keyboard's status/correction row area AFTER a dictation commit, opening an in-IME proposal panel. Text changes only on explicit key press. Never during dictation, never automatically.**

- **Button location (recommended over alternatives):** the existing suggestion-row slot (statusRow reuse, 40–48 dp, established by T23 §6) shows a 📄-style "Summarize dictation" chip for ~60 s after a dictation commit that produced ≥ 30 words (ZIEL: threshold tuned in T25f, see §3 measurements). Rationale: it is *situational* — a persistent keyboard key would be a dead click most of the time (violates E9.1); long-press of the dictation key is impossible (hold = continuous dictation contract); 🌐 long-press is already taken. Alternatives rejected: persistent key (dead-click), 🌐 panel item (wrong mental model, not a language action), Setup-only (unusable per E9.1 argument against (b)).
- **Flow:**
  1. User dictates and releases → text committed as today (unchanged path, zero regression).
  2. If eligible (field type eligible like T23 §6 minus password/URI/numeric; dictation commit ≥ threshold words; set downloaded; summarizer card ON; active ASR variant = Parakeet §5; enough RAM) → chip appears in the row. If not eligible the chip **does not appear at all** (absence, not a disabled dead click; the reason lives on the Setup card — E9.1).
  3. Chip press → in-IME panel (E9.3-style, same window, keyboard stays open): source text length + estimated duration, progress (phase + elapsed s), then the summary in a scrollable read-only TextView. A running summarize is **cancellable** (panel X) — it is a background task, the no-abort contract protects the *dictation*, not this job.
  4. Result actions: **[Insert below]** (primary), **[Copy]** (secondary), **[Discard]**. Nothing is inserted, changed or moved without one of these presses; closing the panel with X changes nothing.
  5. **[Insert below]** = batch edit at cursor: insert summary as its own paragraph after the cursor position, with the T23 §5 safety protocol (suffix proof before and after, mismatch → refuse + counter code, never half-apply). **Undo**: the chip flips to one-level "Undo insert" until the next keystroke (exact T23 wand-undo pattern). **[Copy]** puts the summary on the clipboard (honest note on the card: the OS/other apps may sync clipboards — the keyboard itself never uploads).
  6. Dictation text in the field stays untouched until an Insert press; no auto-correct, no reformatting, no "improved" version of the user's text — ever.
- **Non-goals for 0.1.48:** no replace-selection, no auto-summarize, no scheduled/background summarization, no cloud, no summary history/persistence, no commands ("summarize in 3 bullets") — fixed output format only (see §3).

## 2. Model decision (on-device text-to-text)

All candidates are **decoder-only instruct LLMs exported by us to ONNX opset17 int8** (no ready ONNX exists, same as Qwen ASR — `docs/QWEN_ASR_CONTRACT.md` §1). No web access in this round ⇒ names/sizes/licenses marked ~unverified; **licence + exact size are a hard gate in T25a with license texts stored locally** (precedent `tools/bart_ref/LICENSES/`).

| Criterion | **A. Qwen3-1.7B-Instruct int8** (primary) | **B. Qwen3-0.6B-Instruct int8** (fallback) | **C. SmolLM2-1.7B-Instruct int8** (own third candidate) |
|---|---|---|---|
| Download size int8 | ~1.6–2.0 GB (BELEGT neighbour: expert note "~1–1,5 GB" `PLAN_V0147:389`; Qwen-ASR 0.6B int8 set = 1.70 GB of which both decoder graphs 1.5 GB ⇒ 1.7B text model lands ≈ 1.6–1.9 GB even after §12 fusion) | ~0.65–0.75 GB | ~1.7–1.9 GB (~1.7 B params, int8 ≈ 1 B/param + vocab) |
| Device RAM while coexisting with resident ASR | ~2.0–2.6 GB engine RAM ZIEL (measure T25b host `ru_maxrss`; BELEGT comparator: Qwen-ASR 0.6B int8 = 2.50 GB host RSS incl. 3 sessions, `QWEN_ASR_CONTRACT` §9; text model needs no audio encoder ⇒ slightly less, but KV of a 1536-token context is bigger) | ~0.9–1.3 GB ZIEL | ~2.0–2.6 GB ZIEL (same class as A) |
| Summarization quality de+en | Best of the three at int8; expert: structuring good, abstractive "grenzwertig" (BELEGT side-note). At 0.6B abstraction degrades fast — that is why B is fallback, not first choice | Weak abstractive de; acceptable for bullet-structuring of clear dictations ZIEL (must pass the same goldens gate §3 — no free pass) | Trained with strong EN focus, de weaker than Qwen class (common assessment, ~unverified); its Apache-2.0 licence is the selling point |
| Licence | Qwen3 weights **~Apache-2.0** — verify + store text T25a (HARD GATE; ASR-class precedent: Qwen3-ASR apache-2.0 BELEGT `QWEN_ASR_CONTRACT` §1) | same | **~Apache-2.0** — verify T25a (HARD GATE) |
| Vocabulary / ORT-infra fit | **Excellent**: shares the Qwen3 vocab (151,936) — `QwenTokenizer.kt` and `tokenizer.json` pattern, prefill/step GQA graph shapes, NativeKv ORT-memory loop, RoPE/SiLU export wrappers all transfer 1:1 from `tools/qwen_ref` + `asr/qwen/`. Same 11.4 MB tokenizer file pattern. No audio inputs at all — a *separate* model, not a repurposed ASR | same as A | Needs own tokenizer + vocab_meta; graph machinery reusable (same wrapper skeleton), but every vocab/tokenizer assumption of `asr/qwen` must be re-derived — real extra work |

**DECISION: A as primary, B as contractual fallback, C evaluated in T25a only if A and B both fail the quality gate** (C's only edge is licence cleanliness; vocab/infra fit is strictly worse than the Qwen line). Export A's prefill/step exactly per Qwen contract §2 (staged script `tools/sum_ref/export_qwen_text_onnx.py`, own subdirs per stage, byte-identity dedupe only), then **fuse embed+lm_head duplication via BART §12 ExternalData** (BELEGT −48 %; the Qwen contract §3 "Optimierungshinweis" already earmarks exactly this saving ≈ 155 MB for 0.6B).

**Never** (carried over, non-negotiable): Qwen3-ASR decoder as text engine; models inside the APK; any cloud path.

## 3. Host GATE (T25a/T25b — export first, no Gradle)

Gate semantics = U2/Qwen precedent: the GATE runs and produces a contract doc (`docs/SUM_MODEL_CONTRACT.md`) **before** any app code is written.

| Gate item | Threshold | Status | Measurement plan |
|---|---|---|---|
| Numerical parity wrapper-ONNX vs HF fp32 | logits rel ≤ 1e-4 | ZIEL (BELEGT comparators: Qwen 3.05e-05; BART ≤1.4e-6) | assert in export script (pattern `export_qwen_onnx.py`) |
| int8 vs HF-greedy output identity | ≥ 95 % on goldens (diffs must be pure substitutions where applicable — BART gate precedent 97.2 %/100 %) | ZIEL | `tools/sum_ref/run_sum_ref.py` over all goldens |
| Determinism | byte-identical output on repeated greedy runs | ZIEL | double-run diff, T25b |
| Quality (goldens) | `testdata/sum_goldens/{de,en}.tsv`: ≥ 12 de + 8 en real-length dictations (450–900 words) + human reference; metrics: (i) fixed output format honored 100 % (5 bullets + 1 headline), (ii) **entity-fabrication = 0** (every named entity/number in summary must appear in source — automatic checker), (iii) human adequacy mean ≥ 3.5/5, (iv) not a copy-paste: summary length 10–25 % of source | ZIEL | checker script committed; human scoring by 2 raters, recorded in the contract |
| Hallucination probes | 6 adversarial goldens (trap names/numbers not in text) → **0 critical fabrications** | ZIEL (BELEGT motivation: Qwen-ASR silence hallucination `QWEN_ASR_CONTRACT` §5 — same guard family applies: output guards, cap, stop-seq) | part of goldens, class=trap |
| Host RTF / duration | 600-word dictation: prefill+decode ≤ 60 s int8, threads=16 (BELEGT comparators: Qwen-ASR 0.6B decode 22–35 tok/s on this host; expert estimate 20–60 s/3–5 min dictat BELEGT-note) | ZIEL | `reports/sum_t25b_{model}.json` per sentence, med/p95 |
| Size fused int8 set | **≤ 1.6 GB** (A) / ≤ 0.8 GB (B) after §12-style ExternalData fusion | ZIEL (BELEGT method: BART −48 %; Qwen §3 hint) | measured bytes, sha-pinned |
| Token budget | input cap 1024 tokens, `max_new_tokens` 256, stop on EOS + "\n\n"; longer dictations → **chunked map-reduce with hard 3-chunk ceiling, else "text too long" code** (no unbounded loops — KONZEPT §3.1 lesson) | design contract | scripted in T25b harness + T25e engine test |
| Coexistence RAM headroom | engine must load+run with Parakeet resident inside: **freeBytes ≥ 2.4 GB** required before load (ZIEL, refinement of U2's 400 MB guard `PLAN_V0147` §17.1), phone-measured in T25j | ZIEL | debug field `sum_free_mb` (number, not content — rule 9) |

GATE outcomes: **GO A** → build chain on A. **A fails quality** → run gate on B (same table, no lowered thresholds). **A+B fail quality** → feature slips to 0.1.5x, no app work happens (sunken-cost stop). **A/B fail only size** → ship the smaller that passes or accept with explicit download-size copy (C1-condition precedent `PLAN_V0147` §19).

## 4. Device GATE

1. **Emu functional GATE (emulator-5566, AVD vk-api36, x86_64 native — never arm64-only APK, `PLAN_V0147` §18):** instrumented `SumGoldenTest`: full flow on the 4 shortest goldens; asserts: schema honored, entity-guard holds, insert/undo/discard behaviours, cancellation mid-run, engine never loaded while dictation non-IDLE. Latency recorded (phases: load/prefill/decode/total) with **relative criterion** emu `total_ms ≤ 180 000` for a 450-word golden (ZIEL; BELEGT: emu is 3–7× host, `PLAN_V0147` §20) and decode tok/s logged. Memory: no Dalvik OOM, ORT-eigen KV only (BELEGT pattern), native_kb before/after in the test log.
2. **Phone spot sample — PFLICHT at next device access** (T25k, mirrors C3 of §20b): run one 3-min dictation, note `sum_total_s`, `sum_peak_mb`. **Withdrawal rule: total > 120 s for a typical 3-min dictation, OR any OOM/crash in the chain, OR ≥ 1 fabricated entity in the field-visible test → `BASE_URL_SUM = ""` immediately, no open card in later releases until measured RTF/quality pass again. Reversal = one commit.**
3. Until the phone sample exists the card ships with the honest C1-style label (§6) and the feature is marked experimental.

## 5. Engine toolbox + coexistence matrix

**Reuse, do not rebuild:** ORT 1.16→ note: Android ORT 1.31.1 identical on host and app (`build.gradle.kts:131`, BELEGT `QWEN_ASR_CONTRACT` header); `asr/qwen/QwenGraphs.kt` NativeKv pattern (KV = open `OrtSession.Result`s, inputs borrow previous generation's direct views, deterministic `close()` — never a Java float-array KV, never `allocateDirect` > 0 MB of our own); `QwenTokenizer.kt` (same vocab for candidates A/B); `OnnxSessions.kt` session setup; single background worker (T23 pattern `corrWorker`, `VoiceKeyboardService.kt:491`); generator: **greedy argmax only**, `max_new_tokens=256`, stop on EOS + "\n\n" + schema-complete detector, repeat-guard (KONZEPT §3.5/ASR contract guards), thread cap 4 on device (BART condition C2 lesson).

**Coexistence matrix (binding policy — dictation always wins; ASR engine is NEVER aborted or preempted):**

| Situation | ASR Parakeet | ASR Qwen3-ASR (if variant active) | U2/BART (T24, when built) | Summarizer |
|---|---|---|---|---|
| Idle, field eligible, no recent dictation | resident (warm, today's behaviour) | resident | not loaded | IDLE |
| Any key typed (correction session) | resident | resident | preheat per T24 rules (unchanged) | IDLE — chip never shown without dictation commit |
| Dictation non-IDLE (hold/rec/finalizing) | **loading/working — untouchable** | **untouchable** | never starts (`PLAN_V0147` §17.1 stays) | **never loads/never runs**; a queued/running summarize is soft-cancelled, see below |
| Right after commit, chip shown | resident | resident | may unload on 60 s idle rule (unchanged) | IDLE |
| Chip pressed → summarize load/run | **stays resident** (Parakeet ≈ 0.7 GB set, `ModelManifest.ONNX` sum ≈ 671 MB files; ~1 GB RAM class) | **conflict → feature unavailable** (see rule 2) | **forced unload at summarize load-request** (frees the 420–510 MB, BELEGT `BART_CORRECT_CONTRACT` §9; typing correction falls back to word list — a graceful degradation, documented on the card) | LOADING→READY→run→ **UNLOAD 30 s after last panel close** (ZIEL; U2 uses 60 s, we are 5× bigger) |
| Dictation key pressed WHILE summarize runs | keeps working (no shared worker starvation: summarize runs on its own worker, yields the ASR thread — ASR threads untouched) | n/a | n/a | **soft-cancel**: finish current token, close all KV results, engine→UNLOADING; dictation proceeds uninterrupted; panel shows "cancelled" |
| Summarizer READY + user starts summarize again | resident | — | stays unloaded until next keypress preheat | READY (no reload within the idle window) |

Rules:
1. **Summarizer loads only when**: dictation IDLE, ASR not mid-load, summarizer card ON, set downloaded, eligible field, RAM guard passed (≥ 2.4 GB free ZIEL), **and active ASR variant = ONNX_CPU/LITERT_CPU**. State machine identical to U2: IDLE/LOADING/READY/UNLOADING, no double-load.
2. **Qwen3-ASR + Summarizer are mutually exclusive by policy** (BELEGT: Qwen-ASR worst case 2.6–3.1 GB device RAM, `QWEN_ASR_CONTRACT` §6 T22-oom-fix; + summarizer ≈ 2–2.6 GB exceeds any sane API-36 per-app headroom). Effect: with the Qwen dictation variant active the chip never appears and the Setup card states the reason (one honest line, not a dead click). This is also future-proof: if 0.1.48 ships before Qwen's phone-GATE resolves, nothing couples.
3. Nothing in this feature may slow the first key-press path: no eager loading; load starts on chip press only (unlike U2's preheat — we are multi-GB; preheating 2 GB on a keystroke would be negligent).

## 6. Setup card (opt-in, honest copy)

- New opt-in card "Dictation summary (experimental)" in SetupActivity, **default OFF**, never auto-download (DownloadPolicy RAM gate: device RAM ≥ 8 GB — same gate as Qwen; disk space gate reuses ~2× set-size reserve rule).
- Honest C1-label pattern (mandatory, concrete numbers filled from the contract at card-unlock time):
  *"Optional download (~1.8 GB). Runs fully on this device. On our test emulator a 3-minute dictation took about X seconds — on your phone it may be clearly slower. Needs the standard dictation model to be selected (not Qwen3-ASR). Summaries can be wrong — always read before inserting. NOT yet measured on your phone."*
- C2-lesson applied from day one: the Delete button stays visible whenever files are READY, **also when the card is locked** via empty BASE_URL (no 2 GB hostage).
- One-line kill switch: `BASE_URL_SUM = ""` in `ModelManifest` (identical semantics to `BASE_URL_QWEN`, documented: "not enabled yet", fail-fast without network).

## 7. Privacy (rule 9)

- **User-initiated only**: every summarize run traces to one chip press. No background inference, no telemetry.
- **Text never leaves the device**: no network call in any summarize code path; download is model files only (ModelStore, sha-pinned). Clipboard insert is OS-level and disclosed on the card.
- **Logs/export: codes and numbers only** (precedent VK-SESS / corr_* fields): `sum_state`, `sum_runs`, `sum_inserts`, `sum_undone`, `sum_discards`, `sum_cancelled`, `sum_guard_rejects`, `sum_schema_rejects`, `sum_src_bucket` (0-300/300-700/700+ words), `sum_total_s`, `sum_free_mb`, `sum_engine_variant`. **Never** the dictation text, the summary text, or any substring, in logcat, 🐞 export, prefs or files. Source text lives in memory only and is cleared on panel close (no persistence anywhere).
- Summary text inserted into the field follows the dictation-output rule set: it is normal committed text from the user's point of view, with the suffix-proof safety protocol around it.

## 8. Task chain T25a…T25k (each ≤ 4 files / ≤ 150 lines / ≤ 2 build rounds)

```
T25a  (host, NO gradle)  licence+size check A/B/C (license texts -> tools/sum_ref/LICENSES/);
      export A prefill/step opset17 fp32 (staged script tools/sum_ref/export_qwen_text_onnx.py,
      own subdir per stage) -> parity asserts vs HF. Files: export script, vocab_meta, EXPORT_LOG.
      Gate interim: parity <=1e-4 + licence PASS.
T25b  (host)             quantize int8 + ExternalData fusion (BART §12 method) + run_sum_ref.py
      + goldens testdata/sum_goldens/{de,en}.tsv (write corpus first) + checker (entities/schema)
      + reports/sum_t25b_a.json -> draft docs/SUM_MODEL_CONTRACT.md.
      Gate interim: §3 table rows 1-7 for A. <=2 rounds.
T25c  (host, conditional)  same pipeline for fallback B (or C) — ONLY if T25b fails quality/size.
      Gate interim: contract finalized GO for exactly one model id e.g. `qwen3-1.7b-instruct-int8`.
--- app chain opens only after contract GO (U2/T24 precedent) ---
T25d  ModelVariant.SUM_TEXT + ModelManifest.SUM (pinned list) + BASE_URL_SUM="" +
      baseUrlFor branch + DownloadPolicy branch + test (empty-URL inertness; whitelist).
T25e  sum/SumEngine.kt (ORT prefill+step greedy loop, NativeKv reuse, stop-seq, cap 256,
      schema guard, chunker <=3) + SumEngineTest (fake sessions).
T25f  sum/SumPolicy.kt (chip visibility: pure decision fn; Parakeet-only rule; thresholds) +
      SumChip row slot in ImeRootView + Robolectric (chip appears only after commit>=N words;
      absent with Qwen variant; absent non-IDLE).
T25g  sum/SummaryPanel.kt (in-IME panel, progress, result, Insert/Copy/Discard/X) + strings.
T25h  service wiring: worker, state machine IDLE/LOADING/READY/UNLOADING, dictation-priority
      soft-cancel, U2 forced-unload hook, RAM guard + VoiceKeyboardService seams + tests.
T25i  SumInserter.kt (batch edit + suffix proof + undo, reuses RegionReplacer pattern) + test.
T25j  Setup card (opt-in, honest copy from contract, delete-while-locked per C2) +
      debug fields (all §7 codes) + docs/SUM_MODEL_CONTRACT.md finalize.
T25k  Emu duty 5566 (SumGoldenTest §4.1) + vc49 full tests + review + dist + CHANGELOG.
      Phone sample T25k2 when device available (withdrawal rule §4.2).
Order: a->b->(c)->d->e->f->g->h->i->j->k. Claim_files + Gradle mutex as always.
T25a/b/c need no Gradle; d..j are app tasks; k builds.
```
Emu duty is PFLICHT in T25k; no card-unlock commit without the emu log in `reports/`.

## 9. Risks + rollback

| Risk | Mitigation |
|---|---|
| Device latency far above estimate (emu 3-7× lesson) | non-interactive design + cancellable + phone-withdrawal rule (§4.2); card stays "experimental" until phone sample |
| int8 quality collapse at 1.7B | host parity/identity gates BEFORE app code; fallback chain A→B→slip (T25c stop rule) |
| OOM (three-engine stack) | coexistence matrix §5 (mutual exclusion with Qwen-ASR, U2 forced unload, RAM guard), ORT-eigen KV only (BELEGT Dalvik 604 MB pot), max_new_tokens + chunk ceiling |
| Hallucinated summary believed as fact | schema+entity guards (§3), "always read before inserting" card copy, insert-never-automatic, summary visibly labelled in panel |
| Insert corrupts field (apps rewriting text) | T23 §5 protocol: suffix proof pre/post, refuse-on-mismatch, single-level undo |
| Export RAM failure on this host (BELEGT: 1.7B ASR decoder export died at 137/OOM, `QWEN_ASR_CONTRACT` §7) | text model fp32 ≈ 6.9 GB (A) may still exceed the loaded host → mitigation order: (1) export via bf16→fp32 layer-wise writer, (2) run T25a for B first as pipeline proof (0.6B proven-sized), (3) host with ≥ 24 GB free. **Explicit go/no-go checkpoint after first fp32 export stage.** |
| Licence unverified | hard gate T25a, local license texts, NOTICE if any CC/BY element appears; NO candidate ships on "~" |
| Rollback (whole feature) | one line: `BASE_URL_SUM = ""` → card inert; files remain but are Delete-able (C2); `SUM_TEXT` variant structurally inert; chip policy returns false → zero UI residue |

## 10. Open decisions for the user (max 3, with recommendation)

1. **Chip placement** — (a) status/suggestion-row chip after dictation commit **(recommended)**; (b) persistent row key; (c) panel item under 🌐 long-press. Recommendation: (a) — only (a) avoids dead clicks (E9.1) and ties the affordance to the moment a summary is meaningful.
2. **Primary result action** — (a) "Insert below" batch-edit with undo + secondary Copy **(recommended)**; (b) Copy only (max safety, more fiddly for the user). Recommendation: (a); the T23 §5 protocol + undo make it as safe as the wand, and Copy-only would make the feature a curiosity.
3. **If the 1.7B fails the device GATE but 0.6B passes only "structuring" goldens** — (a) ship 0.6B with a stricter card label ("outlines/bullets only, not free-text summaries"); (b) slip the whole feature to 0.1.5x **(recommended as default)**; (c) try candidate C. Recommendation: (b) unless the 0.6B quality gate is *fully* green including traps — a half-working summarizer damages the product's core trust ("text is mine, tools are honest"); ship nothing over a 3.5/5 adequacy gate.

## 11. ENTSCHIEDEN 2026-11-05 (oracle sa-f91b, binding for T25x)

1. **D1 = (a)** — situational chip in the suggestion/status row after a dictation commit
   (>= ~30 words). Rationale: E9.1 no-dead-click (a persistent key is dead most of the time);
   the dictation contract blocks repurposing the mic hold; a chip absent when ineligible is
   absence, not a dead click.
2. **D2 = (a)** — primary "Insert below" (batch edit, suffix proof pre/post, single-level undo,
   T23 §5 pattern) + secondary Copy with the honest clipboard note on the card. Copy-only would
   make the feature a curiosity; every text change still requires an explicit press (rule 9).
3. **D3 = (b) slip as DEFAULT** if the 1.7B fails the device GATE and the 0.6B only passes
   structuring. (a) shipping 0.6B is allowed ONLY on full green of every §3 row incl. the six
   adversarial traps AND a passed phone spot sample, with the stricter label. (c) candidate C is
   considered only in the HOST quality branch (§2), never as a device-GATE escape.
4. **D4 = mutual exclusion stays; turn-taking REJECTED.** RAM alone would allow turn-taking
   (peak ~3.1 GB vs ~5.7 GB sum), but the dictation contract decides: turn-taking means the NEXT
   hold pays a 1.7 GB cold reload of Qwen-ASR in the press path (no preheat possible for
   multi-GB), and unlike U2 there is NO acceptable fallback under Qwen-ASR — silent fall-back to
   Parakeet would be a disallowed model switch mid-use. Qwen's own phone GATE is unresolved;
   coupling two experimental multi-GB engines before it resolves is double risk. Re-open only if
   (1) Qwen phone GATE resolved with RTF < 1 AND (2) a reload-cheap ASR-resident schema exists.
   Reversible by one policy line in SumPolicy (T25f).

All four decisions are congruent with the planner's recommendations (no dissent).
