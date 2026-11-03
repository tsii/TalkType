# Next tasks (order)

**Status 2026-11-03 · v0.1.41 / versionCode 42 (TalkType) delivered (English UI, sanitized repo)** (11:00): repo translated DE→EN (docs + comments + assertion messages; UI stays German), new adaptive shield+waveform icon, T10 auto-space after dictation-ending punctuation; tests **613/0 + 578/0 = 1191/0**; next build **41 / 0.1.40**.md` P0/P1=0 Push YES; next build **41 / 0.1.40**.

1. **Phone evidence 0.1.38 (highest priority):** hold run 60–90 s (open — the field test
   ended with michold_cancel; tap chain 2× PASSED 22:30 /tmp/vk_phone_e3.log), 🐞 export
   (warm_hit/engine_load_ms); vc34 criteria remain (write=delta, no w1 corpse,
   start_ms<250). ADB port rotates — ask the user (<adb-port> most recently).
   Follow-ups: P2 anchor provenance (git), SegmentFinalizeTest lastflaky, P2-1 length guard,
   P2-2 coordinator @Volatile (docs/REVIEW_A5_W.md, docs/REVIEW_V0136.md).
2. **P2 report points (optional):** 3 of them in `docs/REVIEW_A1_A3_B.md` (stale comment,
   arming special case, redundant baseline setting).
3. **PUSH DONE (user approval 2026-11-03):** main `7d50174` — 0.1.32–0.1.38
   incl. reviews/docs/README image uploaded; rule 10 applies again from now.
4. Deferred items unchanged (MULTILANG, NPU/GPU, Variant B, fp16); old C2 export
   decision-tree work is superseded by the A4 diagnostics.

---
# Archive (status 0.1.31, done)

1. **C2 = dictation abort: OPEN (highest priority).** Longer recordings remain
   incomplete (cause not locally explained); the **variance is now measurable** via
   F42 fields `had_output`/`output_chars` (+ `first_loud_ms`, `first3s_*`, `fgs_stopped_by`,
   now `status_res`).
   Next evidence: **🐞 export #3 from the phone with 0.1.30-diag** — hold long, press 🐞,
   submit; evaluation according to the decision tree `docs/ANALYSE_DIKTAT_ABBRUCH_2.md`
   §9 (layers: `cap_blocks`/`loud_s` → `hyp_count` → commit → UI). Evidence points:
   `status_res`, **`fgs_stopped_by`** (with hold `up`), **hold evidence chain**.
   With 0.1.31 the **segmenter (F53)** is in: long recordings decode segmented
   (window ≤ 35 s, hypothesis latency ≤ ~1.4 s) — the export shows whether the layer was
   engine or control path. F43 auto-stop (2.5 s, suppressed during hold via F49), F45 FGS fix,
   F49–F53 are IN; windowed streaming (`KONZEPT` §5.1) remains **locked**
   until export #3 assigns the layer. The 0.1.15 evaluation rules (F21 mitigation/pacing
   via `decode_skips`/`decode_pace_ms`, quadratic re-decoding via
   `decode_ms_max`/`buffer_s`, `hyp_gap_ms`/`ctl_state` readings) remain valid as
   background. Basis: `docs/STREAMING_FREEZE_ANALYSE.md`.
2. **0.1.32 TODOs (build 33):** **`hint:<res-name>`** (StatusEvent.Hint code field —
   P2 follow-up from review A1–A3), remaining P2 follow-ups; **I5 hold 600-s run MUST run
   as the FIRST session after cold start** (emulator audio LAW, see item 5).
3. **F37 = clock injection in the coordinator** (follow-up from F35): the controller clock is
   **not injectable** → only with it does **Route B** (corpus + real engine,
   emulator/phone) run deterministically. Plan: `docs/KORPUS_DIKTAT_TESTPLAN.md` §5.
4. **Push only after user approval (rule 10):** `main` = `dc3b4f0`; **v0.1.14 … v0.1.31
   are not pushed** (reviews F35/F36/F38–F53: P0/P1 = 0, each push recommendation YES).
5. **Keep the emulator blocker documented:** `<device>` kills the IME process when
   loading `libonnxruntime.so` (SIGSEGV) → dictation not validatable there (also depends
   on F32-B); keys/gestures still checkable. Audio bridge remains **OFF**
   (`tools/emulator_audio_down.sh`). **Emulator audio LAW (0.1.31, binding for ALL
   audio/hold runs):** **only the FIRST app session after cold start delivers sound**
   (every further one: app stream −84 dBFS; tinycap/host OK; audioserver restart/`emu mic`
   ineffective) → long hold evidence only plannable as a **first session** (the 92-s
   evidence therefore not delivered; vc32 first session ran real: 93 characters, peak −17 dBFS,
   `cap_blocks` 280). Google onboarding: `messaging`/`gms`/`calendar`/`quicksearchbox`
   now `disabled-user`.
6. **Deferred, do not touch without user approval:** MULTILANG phase 1
   (`docs/MULTILANG_PLAN.md`), NPU/GPU (completed: not reachable), Variant B
   (rejected). fp16 **checked and rejected** (`docs/FP16_ABWEGUNG.md`: identical token IDs,
   but 1.90× download / 1.30× compute time / 2.13× start time / 4.2× memory).

## Operations (binding, `docs/CONTINGENCY_PLAN.md` §12/§13)
- ≤ 4 files / ≤ 2 builds / ≤ 10 min per coder task; instrumented test never in a coder task.
- No `sleep` in a turn — `schedule_task` (≥ 60 s) or max. 2× `wait_all(60)`.
- Gradle = global mutex; emulator exclusive; `claim_files` before multi-file tasks.
- ≤ 6 live subagents; slot recycling via `steer`; dismiss finished sessions.
- Measure liveness on the file system (mtime/`git status`), not on session output.
- Before every compaction: update `HANDOFF.md` + `state/progress.md` + `reports/NEXT.md`.
- **Push only after user approval (rule 10)**; `main` stands at `dc3b4f0`;
  **v0.1.14 … v0.1.31 are not pushed.**

## History (done, do not pick up again)
- "Build fully green again (157 tests)" (translated from German) → 726/0 (0.1.13) → 734/0 (0.1.14, superseded)
  → 818/0 (0.1.15, superseded) → 819/0 (F24d, superseded) → 828/0 (F24e, superseded) → … →
  897/0 (0.1.24, superseded) → (0.1.27: 475 debug, release measurement to follow, superseded) →
  935/0 (0.1.29, superseded) → 961/0 (0.1.30, superseded) → currently **995 / 0** (debug **513**
  per `app/build/test-results/testDebugUnitTest`, release **482** per `testReleaseUnitTest`).
- "DeepSeek review M4b + M5a" (translated from German) → completed (`REVIEW_STAGE4B`/`4C`/`6`).
- "M4c IME wiring", "M4d WAV test", "M5b setup UI", "M6 release", "M7 review" →
  **all implemented**; currently signed: release **v0.1.31** (SHA-256 `367165ea…`,
  diag `2ee37322…`); history incl. v0.1.30 (`34edb6d7…`), v0.1.29 (`1cdb1801…`),
  v0.1.14 (`cdb1606c…f462`).
- **D11/F15 key-press look** implemented and device-evidenced (seam `keyboard/KeySurface.kt`;
  delta 73/255 light / 65/255 dark, CapsLock accent `#4DB6AC`/`#80CBC4`, deviation 0 —
  orchestrator measurement, screenshot measurement outside the repo).
