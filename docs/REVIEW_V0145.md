# REVIEW v0.1.45 (vc46) — reviewer: DeepSeek V4 Pro 0813 (cortecs), 2026-11-04 14:0x UTC
Scope: `git diff 52d2493` + untracked (AppVersion, NetworkInvitePolicy, setup card,
T21a bridges, T21b permission change). Plan: `docs/PLAN_V0145.md` (incl. §T21a
field protocol). Full tests at review time: debug 673/0, release 638/0 (=1311/0).

## Verdict: **P0 = 0, P1 = 0 — push GO** (P2 ×3 below)

Highlights verified by reviewer:
- T20b ShadowIntent proof is a real `ShadowApplication.getNextStartedActivity()`
  capture (action `android.settings.APPLICATION_DETAILS_SETTINGS`, data
  `package:de.voicekeyboard`) + persist `net_invite_shown_for` + card GONE;
  no fake in-app "revoked" state. Honest privacy copy (user-side revoke only).
- T21b: `requiredForApi` now `RECORD_AUDIO` only at all API levels; inverted F28c
  tests carry the 2026-11-04 measurement reference; manifest keeps POST_NOTIFICATIONS
  (download progress); `permPostNotifications` export field intact.
- T21a bridges are debug-only (FLAG_DEBUGGABLE AND Settings.Global==1); release
  path unreachable; no content logs (rule 9 clean).
- DownloadPolicy never had metered/WLAN gating (grep-confirmed) — nothing was lost.

## Findings (all P2 — report/optional; disposition by orchestrator)
| ID | Sev | Where | Finding | Disposition |
|---|---|---|---|---|
| VK-1 | P2 | AndroidManifest.xml | Comment claimed ACCESS_NETWORK_STATE "removed"; it is still merged in from the onnxruntime AAR (code never uses it). | **FIXED** pre-build: comment made honest (kept in place; `tools:node remove` rejected as noise). |
| VK-2 | P2 | strings.xml | `dictation_error_permission_notification` unreferenced AND carried the T21b-refuted claim ("system can stop the recording"). | **FIXED**: string deleted (grep: no `R.string.` reference); historical note lives in tests/plan doc. |
| VK-3 | P2 | DictationPermissions.kt / VoiceKeyboardService.kt | Notif-gate bridge (`notifGateBypassed`/`bypassNotifGate`) became inert after T21b (filters a set that no longer contains notif). Silent-guard bridge stays functional and is still needed for measurement. | **KEPT as documented measurement tech** (debug-only, harmless no-op; removal = follow-up cleanup candidate, does not block). |

## Post-review builds (final for 0.1.45/vc46)
- app-release.apk sha256 `082fa82f2c8e787a8f2e45861c70bbbc71814853d8b388430a443cfafb8ff258` (2026-11-04 14:11:56 UTC)
- app-debug.apk (diag) sha256 `42e3e9db263869afa4ab2cca6bf572c8387c94a85dae874b6319c93580bdcc52` (2026-11-04 14:12:01 UTC)

## Emu duty program (emulator-5566, debug build; recorded by orchestrator)
- Version head line: `TalkType 0.1.45 (46) · debug` (uiautomator text, matches dumpsys versionName 0.1.45) — PASS
- E2 network switch: absent; no 'Allow network' row — PASS
- T20b card: shown once after READY; button opened system App-Details
  (`com.android.settings/.spa.SpaActivity`, header 'TalkType'); afterwards card
  permanently hidden (setup dump: no setup_net_invite) — PASS
- Keyboard VK-HIT smoke: space/p/enter live (footer geometry 1080x576, pad=126,
  VK-INSETS ev=apply vis=126 ign=63 tap=126) — PASS
- Device note: this AVD intermittently ghosted the IME window (gms ANR dialogs and
  an app ANR under swiftshader/RAM pressure); standard remedy tts->vk re-switch.
  Not a 0.1.45 code path (0.1.45 touches no KeyboardView code).
