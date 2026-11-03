# Review Stage 5 — Model download (dataSync FGS, controller, setup UI) + M5b

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Subject:** `model/{ModelStore,ModelDownloader,DownloadController,ModelDownloadService,DownloadPolicy,…}`, `SetupActivity` + layout/strings, manifest (INTERNET back in, two FGS). **Gradle lock held → no build** (pure reading + assessment of the orchestrator's empirical findings).

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| S5-1 (R-3) | **P2** | `SetupActivity.kt:176-179` + `strings.xml:19` | **Network switch OFF ⇒ message "network error" blames the connection.** With `DownloadPolicy.Decision.NetworkDisabled` (the user turned the switch off themselves, or it was turned off automatically after READY), `setup_error_network` is shown ("Check the connection…"). That is misleading: the connection is (usually) fine, the switch is OFF. | `DownloadPolicy.decide`: `!networkEnabled → NetworkDisabled`; `SetupActivity.kt:178` `stateView.text = getString(R.string.setup_error_network)`. | New string `setup_network_disabled` ("Network access is disabled — enable the switch for downloads.") and use it for `NetworkDisabled`. |
| S5-2 | P2 | `InputConnectionSink.kt:49-62` | **`onEditExecutor` uses `latch.await()` without a timeout — deadlock when called from the main thread.** The block is posted to the `MAIN_EXECUTOR` (`Handler(main).post`) and then `latch.await()` blocks synchronously. If a caller ever invokes `replaceBeforeCursor`/`textBeforeCursor` from the main thread (e.g. a future keyboard commit path), `await()` blocks the main looper, the posted block can never run ⇒ deadlock/ANR. Not reached today (the sink is only called by the ASR worker on `Dispatchers.Default`), but the invariant ("EVERY InputConnection operation runs on this executor") turns the sink into a main-thread semaphore. | Line 52 `editExecutor.execute { try { r=block() } finally { latch.countDown() } }`; line 62 `latch.await()` (no timeout). | Reentrancy guard: if already on the main looper, execute `block` directly inline; otherwise `await(timeout, …)` with fail-fast (`EditorWriteException("Editor-Edit lief in Timeout")`). |
| S5-3 | P2 | `VoiceKeyboardService.kt` `onMicDown` ("Start fehlgeschlagen") | **"Start fehlgeschlagen" is not differentiated.** A device without a microphone, an audio init error and `startFailed` collapse onto the same message; `coordinator.lastError` (`audioInit:…`, `startFailed`) is not translated into user-facing text. The orchestrator's finding ("no microphone ⇒ start failed, no crash") confirms: functionally clean, but the user does not know whether the device, the permission (which is checked beforehand) or the audio subsystem is at fault. | `updateStatus("Start fehlgeschlagen")` without evaluating `c.lastError`. | Map three classes: (1) "No microphone found" (AudioRecord device missing/routes null), (2) "Audio start failed" (`startFailed`/`audioInit`), (3) "Model not ready" (already separate). Map the `lastError` code → string key. |
| S5-4 | Note | `strings.xml:29` vs. manifest `:9-10` | **"…the app switches off network access for itself" should be phrased more precisely.** `INTERNET`/`ACCESS_NETWORK_STATE` remain **permanently** in the manifest; "switching off" is only the app-internal flag `network_for_downloads` (gates the download start), not an OS permission. The following sentence ("completely revoke the network permission in the Android settings") corrects this — hence no hard falsehood, but the phrasing "for itself" is technically vague (the app in fact has no other network use afterwards: telemetry provider removed). | Manifest lines 9-10 permanent; `SetupActivity.networkFlagKey()` consumed only in `DownloadPolicy.decide`. | Sharpen the text: "…the app disables its download switch automatically; no further network access takes place." |
| S5-5 | Note | `ModelDownloadService.kt:48` (`observer` static) | **Static `observer` reference holds the activity (lambda capture) — leak-prone.** Between `onCreate` (`observer = { … runOnUiThread { render } }`) and `onDestroy` (`observer = null`) the companion `@Volatile observer` holds the activity context. Rotation calls `onDestroy` ⇒ release ✓; but a process kill of the activity without `onDestroy` holds it unintentionally. No current leak, but a fragile pattern. | `SetupActivity.onDestroy` → `observer = null`; `ModelDownloadService.observer` static, captures `this`. | Weak reference (`WeakReference`) or a `Flow`/bound service instead of a static callback; additionally secure via `onFinish` rather than purely `onDestroy`. |
| S5-6 | Note | `ModelDownloadService.kt:78` (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`) | **`dataSync` type unguarded for API<34 with `minSdk=29`.** `startForeground(…, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` and `android:foregroundServiceType="dataSync"` are API-34 concepts; on Android 10–13 (minSdk 29) the type `0x100` is unknown and can throw an `IllegalArgumentException`/`SecurityException` at start. The emulator is API 36 → unnoticed. | `ModelDownloadService.kt:78` `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` without a `Build.VERSION.SDK_INT >= 34` guard; manifest `:62` `foregroundServiceType="dataSync"`. | On `SDK_INT < 34`, set the type to an existing fallback (e.g. `FOREGROUND_SERVICE_TYPE_MANIFEST`=0) or use `startForeground(id, notif)` (2-arg). |

---

## Point-by-point answers

1. **dataSync FGS:** `onTimeout` → `controller.cancel(); stopSelf()` ✓ (6-h/24-h limit, TECH_FACTS §4.1); `startForeground` immediately in `onStartCommand` ✓; `START_NOT_STICKY` + null intent→`stopSelf` ✓ (no auto restart); on activity destruction the download continues in the service (notification-only) — **desired** (the download must not couple to the UI activity; no privacy conflict, no content in the notification). Compatibility gap API<34 (S5-6).
2. **dataSync + API 36:** a one-time user download (~90 s, far below 6 h) is compatible with the `dataSync` time limit; `onTimeout` exists anyway. The only limitation = S5-6 (older devices).
3. **DownloadController/service singleton:** double START safeguarded (`running` AtomicBoolean in `DownloadController.start()`, no-op on Downloading/Verifying/Ready) ✓; the listener aggregator correctly separates `controller.listener` (notification + observer) and the static `observer` (UI); no overwriting ✓. Leak risk S5-5.
4. **`denied_forever` marker (misclick risk):** **CORRECT, no P1.** The concern "first denial ⇒ already `false`" is inverted: after the **first** denial, `shouldShowRequestPermissionRationale` returns **true** (the marker is *not* set); only after a **second** denial or a set "never ask again" (API 11+ behaves that way on double denial) does it return `false` ⇒ marker + settings path. Exactly then the dialog no longer appears anyway; the settings button (`setup_open_settings`, set VISIBLE in `refreshUi` via `anyPermissionDeniedForever()`) is reachable. Residual risk (inconspicuous): device policies can deliver `false` without user action — not action-relevant.
5. **Privacy text vs. reality:** INTERNET+ACCESS_NETWORK_STATE permanently in the manifest, but "no telemetry" is true (provider removed). "Switching off network for itself" = app flag, not a permission — the following sentence corrects it. S5-4 for precision.
6. **Strings:** `setup_error_network` misleading when the network is off (S5-1/R-3); `setup_error_cancelled/hash/space/fgs_blocked/denied_forever` are accurate.

---

## Assessment of the orchestrator's findings

- **R-3 (P2):** confirmed and evidenced in code (`NetworkDisabled → setup_error_network`, SetupActivity.kt:178). The correct message = its own string, e.g. `setup_network_disabled`: "Network access is disabled. Enable the switch 'Netzwerk für Downloads erlauben' to download the model."
- **`denied_forever`:** correct (see point 4) — the settings button is in fact visible in the "BERECHTIGUNGEN ERTEILEN" state as soon as the marker has been written (`anyPermissionDeniedForever()` → VISIBLE).
- **No microphone ⇒ "Start fehlgeschlagen":** not differentiated enough (S5-3). Three classes distinguishable for the user: (1) no microphone device, (2) audio start/init error, (3) model/permission (already separate).
- **Cancel button** (`setup_download_cancel`, SetupActivity.kt:146 → `ACTION_CANCEL`): `onStartCommand` → `controller?.cancel()` → `stopSelf()`; `DownloadController.cancel()` calls `currentOp?.cancel()` → `ModelDownloader.cancel()` sets `@Volatile cancelled` → the read loop throws `ModelError.Cancelled` → `State.Cancelled`. Visibility: `cancelButton` VISIBLE only on Downloading/Verifying (render), GONE on Idle/Cancelled/Failed/Ready ✓. Reentrancy on double tap: `cancel()` is idempotent (`currentOp?.cancel()` throws no state), `stopSelf()` multiple times is harmless. No finding.

---

## Verdict

**Release for merge** — **0 P0, 0 P1.** The download runs empirically correctly (671 MB, small→large, `.part`/`.etag`, atomic activation, exact hashes, FGS cleanly stopped, network automatically off). The five findings are P2s/notes (UX precision and latent robustness); none blocks the merge.

## Residual risks

1. S5-2: deadlock if the `EditorSink` is ever driven from the main thread (not the case today).
2. S5-6: the `dataSync` FGS can throw at start on Android < 14 (minSdk 29) — symptomless on the API-36 reference device (GrapheneOS).
3. S5-5: static activity callback — released under normal lifecycle changes, but a fragile path.

## Top-5 actions

1. **S5-1 (R-3)** — Introduce a dedicated string `setup_network_disabled` and show it for `NetworkDisabled`.
2. **S5-2** — Reentrancy guard/timeout in `onEditExecutor` (`if (mainLooper) block() else await(timeout)+fail-fast`).
3. **S5-3** — Differentiate "Start fehlgeschlagen" by `coordinator.lastError` (no microphone / audio init / model).
4. **S5-6** — Guard `FOREGROUND_SERVICE_TYPE_DATA_SYNC` behind `SDK_INT >= 34` (fallback for older devices).
5. **S5-4/S5-5** — Sharpen the privacy text; defuse the `observer` via `WeakReference`/`Flow`.
