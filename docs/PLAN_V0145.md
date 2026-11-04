# PLAN V0.1.45 — T19 version label, T20 network-revoke invite, T21 notifications (planner: Opus 5.5, 2026-11-04)
User mandate: version visible to the user; network permission NOT managed in-app (system
deep-link invite after download, no nagging); decide POST_NOTIFICATIONS by measurement.

## T19 version label
Place: SetupActivity header, one line under setup_title (only launcher target; IME has no
release status row; a full About screen is overkill).
Rule: pure `versionLabel(name, code, debug)` -> "TalkType 0.1.45 (46)", debug appends
" · debug"; source via PackageManager, ONE shared reader `AppVersion.read(context)` also
used by DebugInfoCollector (no second read path).
Files: new ime/AppVersion.kt; SetupActivity.kt; setup_activity.xml; DebugInfoCollector.kt.
TDD-red-first: (1) AppVersionTest JVM: release/debug formats, empty name -> "TalkType ? (46)".
(2) Robolectric SetupActivity: view exists, shows packageInfo name+code.
Emu: open Setup, screenshot; text == dumpsys versionName; both variants.

## T20 network-revoke invite
Baseline: E2 in-app network switch (SetupActivity 44-51,78-79,148; auto-off at READY
281-285 DownloadPolicy.shouldTurnNetworkOff); manifest comment "E2 stay permanently" REVIDIED.
Rule: (1) INTERNET stays in manifest (download needs it; user revokes at SYSTEM level).
ACCESS_NETWORK_STATE: check processReleaseManifest (ORT AAR may add it anyway); remove via
tools:node="remove" only if unused by our code. (2) in-app switch REMOVED; DownloadPolicy
decides from REAL network state (no net -> known "netz" error). (3) trigger: transition to
DownloadController.State.Ready in a run that downloaded/verified AND
prefs["net_invite_shown_for"] != modelSetId (stable sha256-of-manifest hash); cold start
READY+never-shown shows once; "Later" and button both set shown_for. (4) UI: one card in
Setup, button opens ACTION_APPLICATION_DETAILS_SETTINGS package:<pkg> (fallback
ACTION_SETTINGS); text path honestly: GrapheneOS App-Info -> Permissions -> Network ->
Don't allow; stock Pixel has no full revoke (only background data). No notification, no
follow-up. (5) suppress card if INTERNET already revoked — checkSelfPermission(INTERNET)
semantics UNPROVEN, emulator/phone measurement; optional.
Split: T20a model/NetworkInvitePolicy.kt (pure logic+intent, tests) / T20b UI+switch
removal+manifest comment (SetupActivity, layout, strings, DownloadPolicy, manifest).
TDD-red-first: NetworkInvitePolicyTest matrix (Ready+never->show; shown same id->no; new
id->show; Failed/Cancelled->no; cold READY->once; both buttons set shown_for); modelSetId
deterministic; Robolectric intent action/data + click starts + after recreate card gone;
inverted regression: setup_network_switch view gone, no-net -> "netz".
Risk: keep existing metered/WLAN gating in DownloadPolicy (coder greps to confirm).

## T21 POST_NOTIFICATIONS — KEEP, measure first (T21a), decide (T21b)
Field evidence F28c (DictationPermissions.kt:26-30, VoiceKeyboardService.kt:1369-1372,
export #2 "api 37, notif=no, recording dead after 1.0 s") contradicts Android docs (FGS
mic must not die for missing notification). Only measurement decides.
(a) API33+ without permission: no notification-shade entry; running-service tile in QQS
remains; green mic indicator is system-level and independent. (b) losses: download progress
in shade (also visible in Setup), recording notice (indicator+task manager remain).
T21a measure-only: emu API35+36 RELEASE build; `pm revoke ... POST_NOTIFICATIONS`; bridge
ensurePermissions via designated debug flag; 3x60 s hold, cold+warm; collect dumpsys
activity services (FGS alive?), logcat fgs/stopForeground/ForegroundServiceDidNotStart,
had_output, fgs_stopped_by; control run with permission granted.
T21b (only if all green): drop POST_NOTIFICATIONS from DictationPermissions.requiredForApi
(dictation condition only; ask optionally in Setup; manifest KEEPS permission for download
progress). Inverted F28c tests with measurement reference. If abort reproduces: keep all,
document cause.

## Sequence & conflicts
T19 -> (T20a || T21a measure) -> T20b -> T21b(if green). T19/T20b/T21b touch
SetupActivity.kt: SEQUENTIAL, claim_files. Gradle mutex rule 5. Emulator duty before any
Go (user mandate). Version bump 46/0.1.45 after all green + review P0/P1=0.
