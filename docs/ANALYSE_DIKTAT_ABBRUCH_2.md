# Analysis of dictation abort #2 (0.1.17/18, api 37) + layout correction F27b

**Method:** read only; every finding with file:line. Anything not proven is marked as a
**hypothesis**. RULE 9: only numbers, keys, codes, paths.

## 0. Arithmetically forced by the export

`first_hyp_ms` is set in the **first flush()** — regardless of whether a hypothesis
results (`DictationCoordinator.kt:296-297`). flush() only runs on (a) tick overflow
(`consume()`, `DictationCoordinator.kt:263-269`; tick = `chunkMs` 2000 → 32,000 samples,
`DictationCoordinator.kt:47`) or (b) **closing the channel** (final flush
`DictationCoordinator.kt:276`). The tick flush needs 2.0 s of audio — impossible with
`audio_s 1.0`. So: first flush = final flush ⇒ **the channel was closed at t ≈ 1.201 s
⇒ `stop()` ran at t ≈ 1.2 s** (`ch.close()`, `DictationCoordinator.kt:375`).
What happened then: `stop()` → `controller.stop()` → final over 1.0 s (121 ms) →
`hyp_count 1`, `abort_reason "final"` (`DictationCoordinator.kt:388-393`); all partials
blocked by the 2.0-s guard (`DictationController.kt:257,470-471`). `hyp_gap_ms 2692` ⇒
export ≈ 3.9 s after start — **the whole episode is ~4 s, not 20 s**.
Additionally: `abort(reason)` **overwrites** the reason (`DictationCoordinator.kt:477`) —
if a further release event (abort `michold_cancel`,
`VoiceKeyboardService.kt:581/583`) or a second stop had occurred after the final, it
would not read `final`. **No mic event reached the app after the 1.2-s stop.**

## 1) Paths that can end the recording after ~1 s

- **No 1-s timer exists in the code.** maxDuration = 300 s (`AudioRecordSource.kt:38,
  108-110`); start watchdog = 15 s (`VoiceKeyboardService.kt:455-464`); FGS has no
  time limit in the project code.
- Only path to `abort_reason "final"` = **`stopDictationIfRunning()` →
  `coordinator.stop()`** (`VoiceKeyboardService.kt:566-585` → `DictationCoordinator.kt:
  366-393`). Callers:
  1. **Mic-UP**: `KeyboardView.kt:820` `onMicHoldEnd(cancelled=false)` →
     `VoiceKeyboardService.kt:708-718` (STOP) → `stopDictationIfRunning()`; alternatively
     tap-UP: `VoiceKeyboardService.kt:504-512` `onMicUp()`.
  2. **Belated release** during start: `VoiceKeyboardService.kt:506-509` →
     450-469 (`wantedStop` → `stopDictationIfRunning()`).
  3. **IME disappears**: `onFinishInputView`/`onWindowHidden`
     (`VoiceKeyboardService.kt:327-338`) → `stopDictationIfRunning()`.
- **Ruled out by the export:** `ACTION_CANCEL` (`KeyboardView.kt:820` →
  `MicToggle.kt:47-50` → ABORT) would write `michold_cancel`
  (`VoiceKeyboardService.kt:720-725`); audio system stop/InvalidOp
  (`AudioRecordSource.kt:129-132`) → `audio_error:…` (`DictationCoordinator.kt:355`);
  editor change → `editor_change` (`DictationCoordinator.kt:516`); OOM/consumer death →
  `no_final:…` or `lastError` (`DictationCoordinator.kt:311-341`).
- **Conclusion:** the stop came through a **genuine UP path** (1./2./3.). **Hypothesis H-A
  (leading):** the finger was actually released at 1.2 s (the user's perception "held
  the whole time" is unreliable — the numbers permit nothing else).
  **Hypothesis H-B:** the F27 pointer rework/view event delivered a **traceless
  ACTION_UP** at 1.2 s (pointer capture loss); the later genuine release then no longer
  arrived as a gesture event. **Hypothesis H-C:** brief IME switch (system) at 1.2 s.
  All three are compatible with the export; only H-A additionally explains the
  status reset sequence (see 4).

## 2) Why 0 level / −56.2 dBFS

- **Where measured:** per raw capture block, **before queue and before any processing**
  (no resampling — recording runs directly at 16 kHz): `AudioRecordSource.kt:121`
  (`levelListener?.onLevel(rms(copy), block.seq)`); `LevelPeak.note(rms)` = max over
  block RMS (`debug/LevelPeak.kt:20-24`), `percent()` `LevelPeak.kt:26`, `dbfs()`
  `LevelPeak.kt:33-48`. ⇒ **no measurement error in the pipeline**: −56.2 dBFS is the
  digitizer noise floor — the speech signals never arrived in the recording.
- **Candidates (hypotheses, device-related):**
  h1) **System-side microphone revocation/throttling after ~1 s** (Android 13+/api 37,
  FGS-microphone permission without `POST_NOTIFICATIONS` — the project's assumption that
  it "also runs without it" is **unverified** for api 37: `DictationPermissions.kt:16-27`,
  `capture/CaptureForegroundService.kt:24`). Fits both the 1-s end and the silence level.
  h2) Hardware/routing state of the device in the session (mic mute, different microphone,
  do-not-disturb) — route internal / src VOICE_RECOGNITION are **identical** to the good
  F13 measurement (−22.0 dBFS / 7%), so configuration is not the difference.
- **Code gap (proven):** if `AudioRecord.read()` persistently returns 0/no blocks,
  the loop turns endlessly **without an error, without a timeout, without a status change**
  (`AudioRecordSource.kt:126-128` "no data — loop"; `DictationCoordinator.kt:241-251`
  `n==0` → implicitly continue). Silence/mic death is thus **indistinguishable** from
  "not currently using the mic" → see 4 and 6.

## 3) rtf 0.00 with decode_ms_max 121

**Plausible, not a counter error, but a measurement blind spot.** `rtf = processingMs/audioMs`
(`DictationCoordinator.kt:104`, guard `audioMs > 0` otherwise "-"); `processingMs` is
**only accumulated in flush()** (`DictationCoordinator.kt:294`) — i.e. only for
coordinator `feedAudio` calls. The 1.2-s flush ran **without decode** (2.0-s guard) ⇒
`processingMs ≈ 0`, `audioMs = 1000` ⇒ 0.00x → `%.2f` → `0.00`. The 121 ms final decode
runs in `controller.stop()` **outside** of flush() and therefore does **not** count in rtf
(`DictationCoordinator.kt:388`; decode measurement `DictationController.kt:183-191`).
Together with 0: `first_hyp_ms` measures **first flush, not first hypothesis**
(`DictationCoordinator.kt:296-297`) — F28 should cleanly separate both semantics.

## 4) Why the status "dictation" remains (despite audio_s 1.0)

Display: `updateStatus()` (`VoiceKeyboardService.kt:885-915`) →
`StatusBarText.select(recording = state==RECORDING, finalizing = …, recordingText
"Dictating… (…)")`. As long as the controller is `RECORDING`, it reads "dictation" —
**correct**, as long as a session is running. The **missing transition** is a
**stall-detection event**: no code path turns "no audio blocks / permanent silence"
into an error or stop (proof in 2). Two cases: (a) stop ran at 1.2 s (proof in 0) →
`stopDictationIfRunning` sets `micRecording=false` + `updateStatus()` synchronously
(`VoiceKeyboardService.kt:584-585`) and `onStopped` calls `updateStatus` again
(`DictationCoordinator.kt:406-411`) → "dictation" should only be visible **briefly**
(final 121 ms); the user-experienced "stays until release" is then **hypothesis H-B/D**
(main-thread update delayed/stuck — F9-P0 class,
`VoiceKeyboardService.kt:887-890` posts to main) or perception. (b) H-B: session
formally continued without data → "dictation" **should** be displayed — exactly the
observed state. → F28 field `status_shown` (see 5) correlates display with controller
state.

## 5) Minimal instrumentation F28 (4 new 🐞 fields, numbers/codes only)

| Field (name) | Type | Where measured | Formatter position |
|---|---|---|---|
| `cap_stall_ms` | Long | `DictationCoordinator.captureLoop` (lines 238-259): timestamp at every `n>0`; getter `now − lastBlockAtMs` | after `decode_skips` (30th→31st key) |
| `cap_blocks` | Int | same place: counter per `n>0` block (expected ≈ 100 for 1.0 s @ 10-ms blocks; frozen counter = capture end) | 32nd |
| `mic_rel` | String (encoded) | `VoiceKeyboardService.kt`: `onMicUp` (504) = `up:<ms>`, `onMicHoldEnd` (708) = `holdup/holdcancel:<ms>`, `onFinishInputView` (327) = `hidden:<ms>`, `onStartInput` (307) = `editor:<ms>` — timestamp relative to session start; passed through via the coordinator (`DebugInfoCollector.kt` next to line 94) | 33rd |
| `status_shown` | String (encoded: `ready/dictat/finalizing/error`) | `VoiceKeyboardService.updateStatus` (885): last state given to `statusText` as enum code | 34th |

No audio/text content (rule 9). With this, the **next** export shows: stop trigger
(`mic_rel`), capture stall (`cap_stall_ms`/`cap_blocks`), display truth
(`status_shown`) and the H-A/H-B distinction in one run.
Side fix (free, semantics): `first_hyp_ms` stays, but documentation corrigendum in
`docs/DEBUG_INFO_FIELDS.md` ("= first feedAudio flush, not first hypothesis") or
F28 field `first_real_hyp_ms` (in `noteHypothesis`, `DictationController.kt:412-414`).

## 6) Cause ranking + smallest effective fix

| # | Cause (probability) | Rationale | Smallest fix |
|---|---|---|---|
| 1 | **Microphone was muted/stopped system-side after ~1 s** (api 37 FGS-mic/`POST_NOTIFICATIONS`, h1) | explains **both** anomalies (1-s end + silence) with one mechanism; project assumption for it unverified (`DictationPermissions.kt:16-27`) | first measure with **F28**; then check FGS start order/type (`VoiceKeyboardService.kt:401,793-800`, `CaptureForegroundService.kt:67`) — if h1 confirmed: guarantee FGS + notification **before** `AudioRecord` start and run `notif=yes` as an A/B comparison |
| 2 | **No stall/silence watchdog** (code gap, certainly present) | proof in 2: `read()=0` eternal loop without exit (`AudioRecordSource.kt:126-128`) | 10-line fix: in `DictationCoordinator.captureLoop` (238-259) `if (now − lastBlockAtMs > 3000)` → `errorListener.onError("stall", …)` → existing abort/status path (visible error instead of "dictation eternity") |
| 3 | **Traceless ACTION_UP after the F27 pointer rework** (H-B) | only if H-A is ruled out (F28 `mic_rel`) | measurement before fix: debug pointer log on the mic key (VK-HIT style, `KeyboardView.kt:820/837`); fix only after proof |
| 4 | Main-thread update delay (H-B/D) | F9-P0 class (`VoiceKeyboardService.kt:887-890`) | only if `status_shown` ≠ controller state shows: onStopped update with timeout resync — otherwise nothing |
| — | windowed streaming (`KONZEPT` §5.1) | **remains blocked** — this run yields no C2 share (decode 121 ms, no buffer growth) | — |

---

## 7) Layout correction F27b — popup top per row (specification, no implementation)

Base (density 2.625): status bar = **48 dp = 126 px**, at the top in the root
(`ImeRootView.kt:98-99`); KeyboardView below (root-y 126..702, 576 px); gap
`POPUP_GAP_DP` 8 dp = **21 px** (`KeyboardTheme.kt:90`, `KeyboardView.kt:195`); menu
height `POPUP_HEIGHT_DP` 56 dp = **147 px** (measured 146, `KeyboardTheme.kt:87`); popup
today drawn only in the KeyboardView canvas (`KeyboardView.kt:423`) → **clips when
extending upward out of the KeyboardView**.

| Key/row | Current (F27b, `LongPressMenu.kt:79-83`) | Desired | New top formula | Numeric example |
|---|---|---|---|---|
| r3 (foot, keyTop 432, height 144) | `keyTop − rowHeight − gap − menuH` = **132** (2 rows up) | **1 row above** (0.1.16 position) | **`keyTop − gap − menuH`** (no rowHeight deduction) | 432 − 21 − 147 = **264 px** (0.1.16: 276 with old gap 10 px; difference 12 px = new 8-dp gap) |
| r1 / r2 | `keyTop − rowHeight − gap − menuH` (1 row higher) | unchanged (user confirmed "one row higher") | **stays** | — |
| r0 (digits, keyTop 0) | Flip: `keyTop + rowHeight + gap` = **154** (BELOW) | **ABOVE**, visible above the finger | **Root overlay:** `top_root = max(0, statusLine(126) + keyTop_view − gap − menuH)` | 126 + 0 − 21 − 147 = **−42 → clamped to 0**; menu occupies root **0..147** → covers 21 px of the r0 row (126..147), but lies **above the finger** (key center ≈ root 198); **no window clip** (top 0). In KeyboardView coordinates top would be −126 → impossible in the canvas |

**Implementation hint:** the r0 overlay must be hosted by **`ImeRootView`** — the
status bar (48 dp, `ImeRootView.kt:63-115`) and the `KeyboardView` are children of a
stack container (`ImeRootView.kt:168`); provide a popup host there (its own child view
or its own draw pass in root coordinates, `left` unchanged via
`LongPressMenu.menuLeftPx`, `KeyboardView.kt:504`). r1/r2/r3 stay in the
KeyboardView canvas. `menuTopPx` (LongPressMenu) then gets only the r3 formula
without rowHeight; the r0 case moves out of the canvas.

---

## 8. Addendum F30/F31 (v0.1.23, versionCode 24, 01:38)

- **F30 — fake audio source (debug only):** `app/src/debug/java/de/voicekeyboard/audio/
  FakeWavAudioSource.kt` — time-accurate **10-ms blocks** from WAV, configurable
  scenarios (continuous / silence sections / dropout `inGap` / `stopAfterMs`,
  `FakeWavAudioSource.kt:29/40/144`). Plus `app/src/testDebug/java/de/voicekeyboard/ime/
  FakeAudioScenarioTest.kt` with 8 cases (a–h, VirtualClock-driven). **The user case is
  thereby deterministically reproducible:** case d "end after 1.2 s"
  (`stopAfterMs = 1_200` → `abort_reason final`, `audio_s ≈ 1.2`) reproduces the
  numbers from export #2 in the lab. **Not covered:** engine decode on the emulator —
  still blocked by the x86_64 `libonnxruntime` SIGSEGV (see §1/
  `docs/EMULATOR_DIKTAT_HARNESS.md` §1).
- **F31 — silence detection:** `lastLoudAtMs` (`ime/DictationCoordinator.kt:282/206/313`);
  new rule in the watchdog: **blocks are flowing** (`capBlocks > 0`) **but no loud signal
  for > 3000 ms** → `ERR_SILENT` `"silence"` with `no_signal` (`DictationCoordinator.kt:
  337-338`, new constant `ERR_SILENT` `:78`); user message
  `dictation_error_no_signal` (`res/values/strings.xml:57`); new 🐞 measurement field
  **`loud_s`** (seconds with a loud signal, `DictationCoordinator.kt:284-287`,
  `debug/DebugInfoFormatter.kt:134/194`). **3 F31 tests** (FakeAudioScenarioTest
  cases e/f/g): permanently silent → exactly one `ERR_SILENT`, session ends;
  silent→loud → **no** `ERR_SILENT`, `loud_s` grows; no blocks → still `ERR_STALL`
  (precedence, `DictationCoordinator.kt:327-329`) — the two cases cleanly separated.
  **Meaning for export #2:** the state "blocks are flowing, but only noise (−56.2 dBFS)"
  would today (instead of "dictation eternity") end in a visible `audio_error:silence`
  after 3 s.

## 9. Decision tree for export #3 (user's phone, 🐞 export with F26/F28/F31 fields)

Reading rule: the fields `cap_blocks`/`cap_stall_ms`/`loud_s`/`mic_rel`/`status_shown`/
`abort_reason`/`hyp_count` separate the layers (source → level → engine → commit → UI)
**bottom to top**. First non-matching row = the layer with the fault.

| # | Measurement situation in export #3 | Most likely cause | Responsible code location (file:line) | Next step / fix type |
|---|---|---|---|---|
| a | `cap_blocks = 0` **and** `cap_stall_ms` grows | **AudioRecord delivers nothing** (FGS mic/permission/routing) | capture loop `audio/AudioRecordSource.kt:113-141` (n==0 loop `:127`); watchdog `ime/DictationCoordinator.kt:327-329` (`ERR_STALL no_audio`); FGS start `ime/VoiceKeyboardService.kt:401/793` + F28c handshake `:183-185` | check FGS/permission path (api 37, `POST_NOTIFICATIONS`), if needed audio-source switch at the injection seam `VoiceKeyboardService.kt:651` |
| b | `cap_blocks > 0`, **`loud_s = 0`** | **Mic delivers only noise** → hardware mute, mic occupied by another app, or the **VOICE_RECOGNITION source** is dead | F31 check `ime/DictationCoordinator.kt:337-338`; level from the raw capture `audio/AudioRecordSource.kt:121` (`LevelPeak`) | **ask the user first** (other apps/mic mute — see §10); then audio-source experiment (SOURCE-mic vs. VOICE_RECOGNITION, param on `AudioRecordSource`/`VoiceKeyboardService.kt:651`) |
| c | `cap_blocks > 0`, `loud_s > 0`, **`hyp_count = 0`** | signal present, **engine/VAD delivers nothing** (decode error, model, 2.0-s guard + no final) | `dictation/DictationController.kt:250ff` (`feedAudio`), guard `:470-471`, `lastError` fields; watchdog errors in `ime/DictationCoordinator.kt:311-341` | decode-path debug: read `last_error` in the export; OOM/ISE logs; if needed a mini WAV from the `loud_s` time window as a pipeline test fixture (rule 9: only if the user consents) |
| d | **`hyp_count` grows**, but **text is missing** | **commit/editor path** (CommitPolicy refuses, ownership lost, editor InputConnection dead) | `commit/CommitPolicy.kt`/`LocalAgreement.kt`, `editor/InputConnectionSink.kt`/`DeltaTyper.kt`/`OwnershipTracker.kt`; sink wiring `ime/VoiceKeyboardService.kt:585ff` (buildCoordinator) | commit/editor path debug: hypothesis counter vs. sink operations (dictation output counter counts characters only, proven there); `dictation_error_editor_busy` path `VoiceKeyboardService.kt` |
| e | **`mic_rel = up:…` < 1.5 s** | **Genuine release path** (finger/sensor/hold gesture ends early) — **not a product fault** in dictation | `keyboard/KeyboardView.kt:820` (`onMicHoldEnd`), `ime/MicToggle.kt:47-50`, `ime/VoiceKeyboardService.kt:708-718/504-512` | no code fix; UX option: hold threshold/visual feedback (design decision), if needed H-B pointer log to delimit a traceless UP |
| f | **`mic_rel = hidden:…` / `editor:…`** | **IME/focus loss** (system hides the keyboard, editor switch) | `ime/VoiceKeyboardService.kt:327-338` (`onFinishInputView`/`onWindowHidden` → stop), `:307-318` (`onStartInput` → `onEditorChanged`/abort) | lifecycle fix: behavior on brief hide (grace period?) or make the abort visible; then check F28 `status_shown` coherence |
| g | **`status_shown` ≠ controller state** (export period) | **display fix** (F9 class: main-thread update delayed/stuck) | `ime/VoiceKeyboardService.kt:885-915` (`updateStatus`, main post `:887-890`), `ime/StatusBarText.kt`/`StatusRouting.kt` | display fix: resync timeout in `updateStatus`/`onStopped` (`DictationCoordinator.kt:406-411`); measurement stays (`status_shown` field) |

**Reading example (export-#2 assumption, retrofitted):** `abort_reason final` +
`mic_rel up:≈1200` + `cap_blocks ≈ 120` + `loud_s 0` ⇒ row e (genuine UP) **plus**
row b (noise mic) as a parallel finding — two independent stories in one export.

## 10. What to check on the phone (open user question)

Before/during the next dictation attempt with 0.1.23 (🐞 export #3):
1. **Mic in other apps:** start a short test-tone app (or voice note) — is the level
   normal there? → no: **device/mic mute/occupation** (row b, hardware/permission
   layer, no app fix).
2. **GrapheneOS permissions:** explicitly check that **`RECORD_AUDIO`** and
   **`POST_NOTIFICATIONS`** (notification) are enabled for the IME — GrapheneOS
   handles FGS-microphone permissions more strictly; `notif=-`/`notif=no` in the export
   is the indicator (`dictation/…`, field `permissions` in the 🐞 export).
3. **Repeat with 0.1.23:** F31 makes the noise state visible (`audio_error:
   silence` instead of endless dictation), F28/F30 fields (`cap_blocks`,
   `cap_stall_ms`, `loud_s`, `mic_rel`, `status_shown`) then separate the layers
   per §9 — **one export suffices for classification**, provided the user documents
   1./2.
4. **Do not do:** no real speech content in logs/exports (rule 9); export =
   numbers + codes + key names, nothing else.
