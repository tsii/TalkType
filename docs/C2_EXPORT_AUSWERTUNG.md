# C2 export analysis — 🐞 from 0.1.15 (debug), user's phone api 37, dictation ~20 s, aborted

**Method:** read only; every claim below backed with file:line (HEAD worktree,
2026-10-30/31). RULE 9: only numbers, keys, codes, paths — no content.

## 0. Actual numbers (export, 30 lines = header + 29 KEYS)

rtf 0.15 · first_hyp_ms 2320 · decode_ms_max/p95 **583** · buffer_s **11.9** = audio_s
**11.9** · ctl_state **IDLE** · ctl_failed **0** · dropped **0** · hyp_count **6** ·
hyp_gap_ms **7323** · decode_pace_ms 2000 · decode_skips 1 · start_ms 55 · starting 0 ·
peak_pct 13 · peak_dbfs −17.6 · permissions record=yes **notif=no**.

## 1) Quadratic C2 cause: not evidenced in THIS export

`window()` decodes the whole buffer on each run (`dictation/DictationController.kt:416`
`PcmWindow(pcm.copyOf(totalSamples), sampleRateHz)`). But the numbers contradict a
continuous rise up to the abort:

- The last (11.9-s) decode took **583 ms** ⇒ effective processing ratio at the end
  ≈ **49 ms per audio second** (0.15 cumulative with `processingMs/audioMs`,
  `debug/DebugInfoCollector.kt:61-64`).
- Continued linearly, the decode reaches the 1-s mark only at ≈ **20 s of audio** —
  **after** the abort point (11.9 s). Quadratic re-decoding would therefore still
  have been below the visible threshold here.
- Consistency: 6 hypotheses at a 2-s cadence (chunk 2000, pace 2000, 2.0-s partial
  guard `DictationController.kt:243ff`) = hypotheses at ≈ 2/4/6/8/10/12 s —
  exactly 6. **The final was missing** (otherwise hyp_count 7).

**Conclusion 1:** The 583 ms prove that the C2 cause (quadratic window work) grows
slowly on this device, but is **not** the cause of this run's abort. Either the abort
came too early (11.9 s) for the visible root, or the abort lies elsewhere — the exit
analysis (point 2) shows: elsewhere.

## 2) All exit paths of a running session, evaluated against "11.9 s + IDLE + failed 0 + dropped 0 + hyp 6"

Decisive code fact: **`pcm`/`totalSamples`/decode stats are cleared ONLY on the next
`start()`** (`DictationController.kt:217-237`), never in `stop()`/`abort()`/`fail()`.
`buffer_s` is the **last measured decode state** (`DictationController.kt:130,161,
183-191`) — not "unprocessed remainder". That is the basis for the distinction:

| # | Exit path (file:line) | Expected export pattern | Fits? |
|---|---|---|---|
| E1 | Mic-up (UP) → `stopDictationIfRunning()` → `controller.stop()` with **final** (`ime/VoiceKeyboardService.kt:504,511,566`; `DictationController.kt:316-358`) | IDLE, **hyp_count 7** (final counts via `noteHypothesis`), possibly ctl_failed | **RULED OUT** — hyp 6 |
| E2 | `fail()` paths (ASR_ERROR, EDITOR_SYNC_TIMEOUT …) `DictationController.kt:405-410` | **ctl_failed 1** | **RULED OUT** — failed 0 |
| E3 | **Mic hold CANCEL** (ACTION_CANCEL) → `MicHoldAction.ABORT` → `abortDictation()` (`VoiceKeyboardService.kt:291,467,687-725`; `ime/MicToggle.kt:43-50`) | IDLE, failed 0, dropped 0, buffer/stats intact, **no `last_error`** | **Candidate 1 — fits completely** |
| E4 | **Editor/app switch** → `onStartInput` → `onEditorChanged()` → `abort()` (`VoiceKeyboardService.kt:307-318`; `DictationController.kt:376-384`) | like E3 | **Candidate 2 — fits completely** |
| E5 | **AudioRecord system stop** (`audio/AudioRecordSource.kt:129-132` `ERR_SYSTEM_STOP`/`ERR_INVALID_OP`) → `errorListener` → `coordinator.abort()` (`ime/DictationCoordinator.kt:146-149`) | like E3, **but `last_error` = "audioSource:systemStop…"** | **Candidate 3 — only if `last_error` is populated** |
| E6 | IME disappears (back/tab/window) → `onFinishInputView`/`onWindowHidden` → `stopDictationIfRunning()` (`VoiceKeyboardService.kt:327-340`) | like E1 (final) → hyp 7 | **RULED OUT** (except OOM in the final, see E9) |
| E7 | `onDestroy`/`shutdown` (process/IME death) → `coordinator.abort()+shutdown()` (`VoiceKeyboardService.kt:344-353`; `DictationCoordinator.kt:445-477`) | IDLE, buffer intact — **but afterwards `coordinator = null`**: the collector would show all `coordinator?` fields empty (`DebugInfoCollector.kt:83-108`) | **RULED OUT** — in the export all fields are live-populated ⇒ the process was alive at export time |
| E8 | maxDuration 300 s (`AudioRecordSource.kt:38,108-110`) | unreachable at 11.9 s; + `last_error` | **RULED OUT** (confirms the "clarified" entry in HANDOFF) |
| E9 | OOM in decode (feedAudio/final) → `abort()` + rethrow, worker catch → `last_error = "oom:…"`, `NoFinal` (`DictationController.kt:271-276,336-339`; `DictationCoordinator.kt:296-312,384`) | IDLE, failed 0, buffer intact, **`last_error` = oom** | **very unlikely** (decode 583 ms, model 670 MB, p95=max=583 → no pressure) — can only be checked via `last_error` |
| E10 | Start watchdog "abandoned" (deadline 15 s, `VoiceKeyboardService.kt:455-464`) | would abort, but `starting`/`start_ms` (55) show a clean start | **RULED OUT** |
| E11 | FGS without POST_NOTIFICATIONS (`notif=no`) | see point 4 | **not an abort candidate** |
| E12 | Language switch during dictation (F25) | only a pending note, no abort (`VoiceKeyboardService.kt:1022ff`) | **RULED OUT** |
| E13 | C-2b "controller IDLE + active → abort" (`DictationCoordinator.kt:352-357`) | presupposes that `fail()` ran before → failed 1 | **RULED OUT** (consistent with E2) |

**Result:** The export signs an **abort without final and without an error flag**.
Exactly three indistinguishable paths remain: **E3 (mic CANCEL)**, **E4 (editor
switch)**, **E5 (audio system stop)** — and E5 can be separated immediately with the
**already existing** field `last_error` (`DebugInfoFormatter.kt:111`): if `last_error`
was `-` in the export (the excerpt does not quote the field), E3/E4 remain.
**Note for the user:** the "mid-dictations that went well" were ordinary mic-up runs
(E1); the breakage ran over a CANCEL/switch event type, not over an ASR error.

## 3) `hyp_gap_ms 7323`

`hyp_gap_ms` = time since the last **produced** hypothesis (`DictationController.kt:
117,193-195,412-414`). With a 2-s cadence, the 6th hypothesis was at ≈ 11.9 s; 7.3 s
of lead time before the export ⇒ **export ≈ 0.5–1 s after the abort**, last text at
≈ **4.6–5 s** (± export delay, formula: `t_last = 11.9 + 7.3 − t_export_before_abort`).
That matches the user perception "text stopped coming from ~12 s" (session ended at
~12 s; but the last partial changes were already ~5 s old — from ~5 s, decode results
did arrive, but presumably without editor changes due to commit policy/delta typer:
`hyp_count` counts produced hypotheses, not guaranteed changed editor content).

## 4) `permissions notif=no` (Android 13+)

The project handling is deliberately like this: `POST_NOTIFICATIONS` is only
**optional** — `ime/DictationPermissions.kt:16-27` (REQUIRED = only `RECORD_AUDIO`;
optionals do not grant permission) with the live error note "FGS runs per
CaptureForegroundService even without it". `capture/CaptureForegroundService.kt:24`
confirms: "Without `POST_NOTIFICATIONS`, the service still runs (notification
invisible)". The manifest declares the permission (`AndroidManifest.xml:19`).
Consequence: **cosmetic** (no visible recording indication; the OS's system mic
indicator remains). **Not an abort candidate** — and E7 additionally shows that no
FGS/process death took place.

## 5) Proposal: 2 new 🐞 fields (fastest narrowing)

1. **`abort_reason`** (String, default `-`) — **where:** `ime/DictationCoordinator.kt`,
   `abort()` (line 445) gets a `reason` parameter, set at the 4 callers:
   `abortDictation()` (`VoiceKeyboardService.kt:691`, origin: mic CANCEL →
   `reason = "michold_cancel"`) / `onEditorChanged()` (`DictationController.kt:376`,
   → `editor_change`) / `errorListener` (`DictationCoordinator.kt:146`, →
   `audio_error:<code>`) / `shutdown()` (`DictationCoordinator.kt:470`, →
   `ime_destroy`). Additionally, write the `StopOutcome` branch in `stop()`
   (`DictationCoordinator.kt:362-390`) as well (`final` / `no_final:<code>` /
   `idle_noop`). Pass through `debug/DebugInfoCollector.kt` (next to line 94) +
   1 line in `DebugInfoFormatter.KEYS` (30th field, catalog `docs/DEBUG_INFO_FIELDS.md`).
   **With this one field, the next breakage is unambiguous in an export.**
2. **`hyp_last_buffer_s`** (Double) — **where:** `DictationController.applyPartial`/
   `noteHypothesis` (lines 300-315, 412-414) stores the `lastDecodeBufferSeconds`
   value of the **last accepted** hypothesis; collector passes it through like
   `buffer_s`. Separates "text stopped at 4.6 s" from "session died at 4.6 s" and
   shows whether the late hypotheses still triggered editor changes (commit policy
   behavior).

(`last_error` needs **no** new field — already exists, `DebugInfoFormatter.kt:111`;
it suffices to read it in the next 🐞 analysis.)

## 6) Recommended countermeasure level

**(a) only re-instrumentation** — 1–2 🐞 runs with `abort_reason` +
`hyp_last_buffer_s` (each ≈ 1 coder assignment ≤ 4 files: coordinator, controller,
collector, formatter/catalog). **Rationale:** the three remaining candidates
(E3/E4/E5) are UI/audio lifecycle events with **already controlled** abort paths
(no silent death, no growing channel) — the actual "bug" is that the abort was
**invisible** (the status row only showed it as a silent IDLE change). Only when
`abort_reason` names the same candidate in 2 runs is a fix provable.

**(b) concrete fix candidate: not yet** — any file:line would be pure speculation
(E3 → CANCEL tolerance on the hold gesture? E4 → editor-switch behavior? E5 →
audio-source hardening?) and would shoot at the wrong target.

**(c) windowed streaming remains unnecessary** — rationale with this export's
numbers: decode 583 ms at an 11.9-s buffer (≈ 49 ms per audio second); 1-s threshold
at ≈ 20 s of audio; `rtf 0.15`, p95 = max (no outliers); F21 cadence held (1 skip).
The quadratic cause is real, but on api-37 hardware only relevant for significantly
longer sessions — as long as the abort originates from point 2, `KONZEPT` §5.1
would be a rebuild against an unreached problem (and stays blocked until (a)
proves the C2 share in longer runs: the trigger would then be
`decode_ms_max > 1000` + rising `buffer_s`).

---
**Sources:** `dictation/DictationController.kt` (start 217, feedAudio 243, stop 316,
abort 367, onEditorChanged 376, fail 405, window 416), `ime/DictationCoordinator.kt`
(errorListener 146, start 152, consumer catch 296-357, stop 362, lastStopOutcome 404,
abort 445, shutdown 470), `ime/VoiceKeyboardService.kt` (onStartInput 307,
onFinishInputView 327, onDestroy 344, watchdog 455, onMicUp 504,
stopDictationIfRunning 566, abortDictation 687, onMicHoldEnd 708, LANGUAGE 979),
`ime/MicToggle.kt` (29-50), `audio/AudioRecordSource.kt` (38, 108, 129),
`ime/DictationPermissions.kt` (16-27), `capture/CaptureForegroundService.kt` (24, 67),
`debug/DebugInfoCollector.kt` (61, 83-108), `debug/DebugInfoFormatter.kt` (108-117).
