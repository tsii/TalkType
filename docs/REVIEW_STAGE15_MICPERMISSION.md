# Review Stage 15 — Microphone permission (V16a) + orchestrator's own changes (LevelPeak, MicCaptureTest)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-29
**Subject:** `DictationPermissions` (RECORD_AUDIO-only), `LevelPeak` (+ test), `DictationCoordinator.noteLevel`, `VoiceKeyboardService.levelListener`, `MicCaptureTest`. **No build/emulator** (orchestrator: 288/0; MicCaptureTest 3/3 on <device>).

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| M-01 | **P2** | `MicCaptureTest.kt:87-103` (m2) | **Thresholds too lax — a pure noise floor passes m2 without injected sound.** RMS ≥ 200 / peak ≥ 1000 match the plan; but with the device at 65% volume the measured actual is peak ~19,800 / RMS ~1,900 — i.e. ~10–20× above the threshold. A microphone hiss (peak 1,500) or a faint foreign signal would pass the "much too quiet" assertion even though the target sound never arrived. m2 therefore only proves "not silence", not "the sound arrived". | `assertTrue("RMS $rms unter 200…", rms >= 200)`, `peak >= 1000`; the comment cites the actual ~19,800/1,900. | Raise the thresholds toward the actual values (e.g. RMS ≥ 800, peak ≥ 6,000) or assert a band `1,000..32,000`, so that only the actual sound (not noise) passes. |
| M-02 | **P2** | `VoiceKeyboardService.kt:363-366` + `DictationCoordinator` (`coordinator` field) | **`coordinator` is not `@Volatile` but is read from the capture thread.** The `levelListener` lambda runs on the AudioRecordSource capture thread and reads `coordinator`; it is written on the main/IME thread (`buildCoordinator()`/`onDestroy`). Cross-thread without volatile = visibility race. The consequence is **only** trivial (a stale `coordinator` → `noteLevel` on a float; null → no-op; no crash, no effect on the dictation text), but it is technically an unsynchronized diagnostic access. A late-running callback after `release()`/`onDestroy` is harmless (float update, no resource access). | `levelListener = { rms, _ -> coordinator?.noteLevel(rms) }`; `private var coordinator: DictationCoordinator?`. | Declare `coordinator` as `@Volatile` (or route the level channel explicitly through the coordinator instead of via the service field reference). |
| M-03 | Note | `LevelPeak.kt:20-26` (`note`) | **Read-modify-write on `@Volatile max` is not atomic — ok for a diagnostic counter.** `if (rms > max) max = …` is not a CAS; two concurrent `note()` calls could lose an update. In practice single-writer (only the capture thread calls `noteLevel`), and a lost maximum only changes the displayed percentage. **Not** a P1; viable as a documented single-writer assumption. `@Volatile` guarantees visibility of the last set value. | `@Volatile private var max`; `note()` check-then-set. | Optional: add a KDoc "called only from the capture thread (single-writer)"; otherwise it remains a conscious diagnostic trade-off. |
| M-04 | ✓ | `LevelPeak.kt:22-24,29` | **Clamp/range correct.** Linear 0..1 source: `>1f → 1f` (defensive), `≤0`/`NaN` ignored (the peak keeps the last genuine max), `percent = max*100`→`coerceIn(0,100)`. No crash on odd RMS values. | `if (rms > max) max = if (rms > 1f) 1f else rms`. | — |
| M-05 | ✓ | `DictationCoordinator.kt:97` (`Peak=`) | **Rule 9 upheld.** `diagnosticsLine` carries only an integer maximum value (0..100 %), no samples, no time series, no content. `val levelPeak` + `noteLevel()` as the only setter is clean encapsulation (the `max` field stays private). | `"…Peak=${levelPeak.percent()}"`. | — |
| V16-01 | ✓ | `DictationPermissions.kt:20-30` + grep | **V16a separation clean.** `REQUIRED_IN_ORDER = [RECORD_AUDIO]`, `OPTIONAL_IN_ORDER = [POST_NOTIFICATIONS]`; `nextStep`/`isReady` ignore the optional list. A `grep POST_NOTIFICATIONS` over `app/src` evidences: only the manifest declaration, the SetupActivity request (once, optional), comments — **no** place where `POST_NOTIFICATIONS` blocks dictation. The old state (dictation never started when the notification permission was missing) was in fact the live bug; per `CaptureForegroundService` the FGS runs even without it. | `REQUIRED_IN_ORDER=listOf(RECORD_AUDIO)`; `isReady` via `nextStep`. | — |
| V16-02 | Note | `SetupActivity.kt:193` (`requiredPermissions`) | **Name/marker behavior inconsistent (cosmetic).** `requiredPermissions()` still contains the optional `POST_NOTIFICATIONS` (despite the "required" name) and `evaluatePermissions` may write a `denied_forever_` marker for it — both harmless (the marker is read only for RECORD_AUDIO), but misleading. | `list.add(Manifest.permission.POST_NOTIFICATIONS)` in `requiredPermissions()`. | Rename the method to `requestablePermissions()` and do not carry the optional marker as `denied_forever`. |
| M-06 | Note | `MicCaptureTest.kt:37-55` (m1) | **m1 is a device proof, not an app proof.** It checks a raw `AudioRecord` (state/recordingState), without the app permission flow (`pm grant` happens externally via the orchestrator script) and without `AudioRecordSource`. It thereby evidences §1.3 "the real microphone path delivers", but **not** "the app handles RECORD_AUDIO correctly" (that is covered by `DictationPermissionsTest`). No deficiency for the purpose, but it must not be counted as an app permission proof. | Raw `AudioRecord` directly; no `checkSelfPermission`. | Optionally clarify in the test KDoc: "device proof, the permission flow is tested by DictationPermissionsTest". |

---

## Operational risk (device selection) — recommendation

`-Pandroid.testDevice=<device>` is **not** an AGP property → it was ignored; `connectedDebugAndroidTest` ran on **both** connected devices. **The most reliable pinning for this project:**

1. **`ANDROID_SERIAL=<device> ./gradlew …connectedDebugAndroidTest`** (adb selects via the environment variable; zero configuration) — primary recommendation.
2. **AGP managed devices** (`testOptions.managedDevices.devices { … }` with a named AVD) — robust for CI, but requires an AVD definition.
3. Direct `adb -s <serial> shell am instrument` (already used for MicCaptureTest) — simplest for singleton runs.

## Verdict

**ready for release** — 0 P0, 0 P1, 2 P2 (M-01 lax m2 thresholds, M-02 `coordinator` not `@Volatile`), the rest notes. V16a (RECORD_AUDIO-only) is correct; the LevelPeak diagnostic channel is rule-9 clean and single-writer capable.

**Not checked:** live behavior of the m2 thresholds against pure emulator noise (no device run), the `levelListener` thread schedule across multiple dictation sessions, the real `POST_NOTIFICATIONS` request in SetupActivity on API 33+.

## Top-3

1. **M-01 (P2)** — Move the m2 thresholds near the actual values (RMS ≥ 800 / peak ≥ 6,000) or a band, so that a noise floor does not count as "sound arrived".
2. **M-02 (P2)** — `coordinator` `@Volatile` (capture-thread read vs. main-thread write).
3. **M-06/V16-02 (Note)** — Clarify the m1 purpose in the KDoc; rename `requiredPermissions()` + do not carry the optional notification marker as `denied_forever`.
