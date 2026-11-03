# Measurement comparison — change → gain (measured numbers only)

**Purpose:** make it comparable in the end which change brought which gain.
**Discipline:** exclusively the delivered, evidenced measurements; no guessing,
no estimating — where no measurement exists, it says **not measured** / **not
collected**. RULE 9: numbers, keys, geometry, paths only — no content.

**Versions/context:** 0.1.14 = build 15, 0.1.15 = build 16, 0.1.16 = build
17, 0.1.17 = build 18 (`app/build.gradle.kts:14-15`). Emulator = <device>
(API 36 AVD), phone = the user's device, api 37.

---

## A) C2 dictation (user's phone, 🐞 exports)

| Change | before (0.1.14 era) | after (0.1.15, with F21) | Gain | Evidence source |
|---|---|---|---|---|
| F21 cost-aware decode cadence | rtf **0.44**; first hypothesis after **5,339 ms** | rtf **0.15**; decode_ms_max **583**; decode_ms_p95 **583**; buffer_s = audio_s = **11.9** | rtf ≈ **−66 %** (0.44 → 0.15) | 🐞 export 0.1.14 era (HANDOFF "C2") / 🐞 export #1 0.1.30 (docs/C2_EXPORT_AUSWERTUNG.md §0) |
| F21 mitigation, effect in export #1 | — | hyp_count **6**; hyp_gap_ms **7,323**; ctl_failed **0**; dropped **0**; state **IDLE** | **no measurable C2 gain in the abort case**: break at 11.9 s **without finale and without an error flag** (abort path, not C2) | 🐞 export #1; analysis docs/C2_EXPORT_AUSWERTUNG.md §1/§2 |
| F21 cadence values `decode_pace_ms` / `decode_skips` | — | fields **present** in export #1, values **not individually evaluated** | not individually evaluated | 🐞 export #1 (field list docs/DEBUG_INFO_FIELDS.md) |
| Quadratic cause `window()` (DictationController.kt:416) | visible as 5,339 ms first hypothesis | 583 ms at 11.9 s buffer (≈ 49 ms per audio second) — growth still **below threshold** | **gain not measured** (the abort came first); fix candidate still open | analysis §1 |

## B) Optics of a pressed key (D11/F15, emulator, channel delta of 255)

| Change | before | after | Gain | Evidence source |
|---|---|---|---|---|
| Key press surface (pressed key) | light **27** / dark **21** | light **73** / dark **65** | delta ≈ **×2.7** (light 27→73), ≈ **×3.1** (dark 21→65) | orchestrator emulator measurement (docs/DESIGN_STOCK_LOOK.md, log in HANDOFF "D11/F15") |
| CapsLock accent surface | — | `#4DB6AC` (light) / `#80CBC4` (dark), deviation **0** | target value exactly met | ditto |

## C) Microphone (user's phone)

| Change | Value | Interpretation | Evidence source |
|---|---|---|---|
| Level/routing (no change since F13) | peak_dbfs **−22.0**; peak_pct **7**; route **internal**; src **VOICE_RECOGNITION** | **in order** — mic level is no longer a suspect (AudioSource experiment dropped) | 🐞 export (user's phone); "clarified" in HANDOFF |

## D) Footer F24e (emulator, de-de, 0.1.16)

| Change | before | after | Gain | Evidence source |
|---|---|---|---|---|
| Unified footer + period menu | 14 layouts with `‹ ›` cursor keys, de-de without them (transitional inconsistency, review note) | view 1080×576; keyboard top edge y=1698; footer y=2202; **period rect <706,432,789,576>** (center 747, width 83 px); popup-open **cells=5** left=384 top=264; cell centers 468/594/720/846/972; popup zone left=384 w=696 | 15/15 layouts uniform (Σ 13), cursor actions as menu cells 4/5 instead of direct keys | orchestrator emulator measurement 0.1.16; target design docs/LAYOUT_REQUIREMENTS.md §2.3 |

## E) F27 stock geometry (emulator, de-de, 0.1.17 debug, 16:44–16:45)

| Change | before (0.1.16) | after (0.1.17) | Gain | Evidence source |
|---|---|---|---|---|
| Row grid of the letter rows | r0 **93.9 px** (1080/11.5); r1/r2 **120 px** (1080/9) — **non-uniform** | all three rows **exactly 90 px** (VK-HIT: z <450,0,540,144>, a <135,144,225,288> with a 1.5u margin left, b <585,288,675,432>) | uniform stock 90-px grid (before/after geometry cross-check) | orchestrator emulator measurement 16:44–16:45 |
| Space key | **498 px** (6u) | **582 px** (7u): rect <290,432,872,576> | **+84 px / +16.9 %** area | ditto |
| Period + mic | period **166 px**, mic **166 px** (D10 rework) | period **83 px** (1u: 872–955), mic **125 px** (1.5u: 955–1080) directly beside it | narrow period (1u), mic directly to the right — stock cut | ditto |
| Popup flip | (previous popup position not measured separately) | r1 key (a): popup-open **cells=6** left=0 **top=0** (clamped at the top edge), zone left=0 w=833; row-0 keys open **below** (flip) | flip behavior measured; quantitative row saving **not measured** | ditto |

## F) Test inventory (unit)

| Change | before | after | Gain | Evidence source |
|---|---|---|---|---|
| F24e–F27 task chain (debug suite) | **419/0** (0.1.14) → 425/0 → 427/0 → 429/0 → 430/0 → 431/0 → 432/0 (F26a+b) → 437/0 (F27a1/a2/b) → **440/0** (F27d, 16:34) | **440/0** (build 18, RUN 1, 2026-10-31 16:48) | **+21 tests** vs. 0.1.14, consistently 0 failures | /tmp/vk_tests.txt; app/build/test-results/testDebugUnitTest |
| Release suite | 399/0 (0.1.15) | **420/0** (50 files), 2026-10-31 16:48 | +21 vs. 0.1.15 | /tmp/vk_tests.txt (RUN 1) |
| Engine latency/quality gains | — | — | **not measured** (see H) | — |

## G) APK sizes (bytes, release / diag-debug)

| Build | Release | diag-debug | Growth per shape | Evidence source |
|---|---|---|---|---|
| 0.1.15 | 40,343,852 | 102,386,730 | — | dist list (HANDOFF/PROJECT_STATE) |
| 0.1.16 | 40,345,748 | 102,388,918 | release **+1,896**; diag **+2,188** | dist measurement (orchestrator) |
| 0.1.17 | 40,346,420 | 102,390,034 | release **+672** (vs. 0.1.16); diag **+1,116** (vs. 0.1.16) | dist measurement (orchestrator) |

## H) Not measurable (documented, not glossed over)

| Topic | Status | Reason |
|---|---|---|
| Engine/dictation run on <device> | **not verifiable** | SIGSEGV in `libonnxruntime.so` at load time (dlopen/call_constructors) → engine latency and quality gains provable **only via phone exports** (A); all emulator numbers here (B/D/E) are at the keyboard/geometry and optics level |
| C2 gain on longer (> 20 s) sessions | **not measured** | export #1 broke off at 11.9 s (abort path, docs/C2_EXPORT_AUSWERTUNG.md); follow-up measurement with `abort_reason`/`hyp_last_buffer_s` pending |
| Dictation abort cause | **open (3 candidates)** | mic CANCEL / editor switch / audio system stop — differentiation only via `last_error` + new fields (docs/C2_EXPORT_AUSWERTUNG.md §2/§5) |

---
**Evidence sources overall:** 🐞 exports (0.1.14 era, export #1 from 30.10.),
orchestrator emulator measurements (D11/F15, F24e 0.1.16, F27 0.1.17
16:44–16:45), `/tmp/vk_tests.txt` (RUN 1, 31.10. 16:48),
`app/build/test-results/`, `app/build.gradle.kts:14-15`,
`docs/C2_EXPORT_AUSWERTUNG.md`, `docs/LAYOUT_REQUIREMENTS.md` §2.3, dist
list.

## I) Dictation hardening (F28–F31b, 0.1.18 → 0.1.24)

| Change | Effect (measured values only) | Evidence |
|---|---|---|
| F28b1 **stall watchdog**: no capture blocks for > **3000 ms** → error `stall` (instead of an endless "dictation") | threshold 3000 ms; trigger verified in test case b (silence from the start) and c (dropouts 3.5 s → exactly one error) | `ime/DictationCoordinator.kt:73` (`STALL_LIMIT_MS`), `app/src/testDebug/…/ime/FakeAudioScenarioTest.kt` (cases b/c) |
| F31 **silence detection**: blocks keep flowing, but no loud block for > 3000 ms → error `silence` + new message + 🐞 field **`loud_s`** | loud threshold **`SILENCE_RMS = 0.01` (≈ −40 dBFS)**; the separation "no blocks → `stall`" vs. "blocks without signal → `silence`" verified (cases e/g) | `ime/DictationCoordinator.kt:100/337-338`, `debug/DebugInfoFormatter.kt:134/194`, `res/values/strings.xml:57` (`dictation_error_no_signal`) |
| F31b **`everLoud` latch**: `silence` fires only as long as no loud block ever arrived — **no false aborts on speech pauses** (review P1) | latch set/gate verified in test case f (silent→loud, no error); P1 thereby closed | `ime/DictationCoordinator.kt:218/300/330-332`; `docs/REVIEW_F28_F31.md` (P1 → closed) |
| F31b **`FGS_READY_TIMEOUT_MS` 2 s → 4 s** (cold-start deadline for the FGS handshake) | constant **4000 ms** | `ime/VoiceKeyboardService.kt:149` |
| F31b **layout gates on individual values** (space 6.5 / mic 2 / period 1, exactly one "@" per layer, ru-ru without an @ key, r2[0] shift = 1) | **3 new tests** (in the 464/0 run) | `FooterAcrossAssetsTest` / `LayoutCompletenessTest` (orchestrator statement) |
| Test inventory across the hardening (0.1.15 → 0.1.24) | debug **419 → 464**, release **399 → 433**, consistently **0 failures** (total 818 → 897) | `app/build/test-results/` (sum of the result XMLs, status 0.1.24); for F27/lead-in see section F |
| Review **F28–F31** | originally **P0=0/P1=1/P2=3** → after F31b **P0=0 · P1=0 · P2=1** (open: popup clip/`isFooterRow` edge case); verdict **pushable: YES** | `docs/REVIEW_F28_F31.md` (incl. "addendum F31b") |
| Emulator engine (a framework condition for all dictation tests on <device>) | **not measured/fixable**: SIGSEGV in `libonnxruntime.so` blocks real decode runs; fake-engine tests run independently of it | `docs/PROJECT_STATE.md` "environment", `docs/EMULATOR_DIKTAT_HARNESS.md` §1 |
