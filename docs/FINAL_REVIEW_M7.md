# M7 — Final design/device acceptance

## Test frame and limits

- Date: 28.10.2026, test actions from 01:33 UTC; report written afterwards within the 20-minute budget.
- Actually tested: **<device>**, API **36**, model identifier **sdk_gphone64_x86_64**, 1080 × 2400 px, 420 dpi. This is **not a physical GrapheneOS device**.
- Per the handover, the emulator runs with `-no-audio`, i.e. without a usable microphone. Real recording, recognition quality, live commit, ARM64 performance and Bluetooth routing are therefore **not provable on this device**.
- Installed: `de.voicekeyboard`, version **0.1.0**, targetSdk 36, x86_64, DEBUGGABLE, last update **01:32:52**. Package evidence: `/tmp/m7_package.txt`.
- The release signature and model hash check were handed over by the orchestrator, not independently cryptographically re-verified. Setup shows the active model visibly.
- Active IME confirmed via adb: `de.voicekeyboard/.ime.VoiceKeyboardService`.
- Input target: the Settings search field (`com.google.android.settings.intelligence:id/open_search_view_edit_text`). All documented characters were entered by taps on the tested IME, **not** with `adb input text`.
- No builds, no Gradle, no git, no code/resource/asset changes. A few existing source files were read solely to classify unsuccessful UI actions. The only new project file is this report; evidence resides under `/tmp/m7_*`.
- `am start -S` was used only for Setup/Settings; afterwards the IME was set again. An initial setup dump during the splash screen failed; a successful dump and screenshot were repeated after the full start.

**Rating:** P0 = acute integrity/crash hazard; P1 = blocking product function; P2 = UX/diagnostic deficiency. Missing hardware evidence is not a passed test and is reported separately as a release gate.

## Acceptance table

| Checkpoint | Expectation | Actual (with evidence file) | Finding (P0/P1/P2/ok) |
|---|---|---|---|
| 1 – DE visible and tappable | DE QWERTZ visible; tapping q/w/e yields `qwe` | `/tmp/m7_1_de.png` shows DE with `q w e r t z u i o p ü`; `/tmp/m7_1_de.xml` contains exactly **`qwe`** in the focused EditText. | **ok** |
| 2 – Language switch EN/RU | Switch the language via the intended UI, appropriate labels, tap three characters each | A tap on the globe at about (165,2295), a repeated tap and a 900-ms long-press open no menu and do not change the DE layout. Before `/tmp/m7_2_language_menu.png`, after repetition `/tmp/m7_2_language_nochange.png` + `.xml`; the field stays `qwe`. Setup likewise offers no language selection. EN and RU labeling/character input are therefore **not provable via the shipped operating path**, not passed. | **P1-01** – language switch without effect |
| 3a – simple shift | One capital letter, then lowercase again | Shift (65,2140), q, w produce **`qweQw`**. `/tmp/m7_3_shift_on.png` shows the activated uppercase letters; `/tmp/m7_3_shift.xml` + `.png` prove the result and the return to lowercase. | **ok** |
| 3b – shift double-tap/Caps | Two immediately consecutive shift taps activate permanent uppercase | Two shift taps in the same adb shell invocation, then q/w: result **`qweQwqw`**, not `qweQwQW`. `/tmp/m7_3_caps_on.png` and `/tmp/m7_3_caps.png` show lowercase letters, `/tmp/m7_3_caps.xml` the field text. No alternatively visible caps-lock switch. | **P1-02** – caps double-tap does not work |
| 4a – ?123 and return | ?123 opens symbols; a repeated tap on the same physical key, now ABC, returns | `/tmp/m7_4_symbols.png` shows digits/symbols and ABC. `/tmp/m7_4_abc.png` shows DE letters and ?123 again after ABC. | **ok** |
| 4b – Symbols ↔ SymbolsShift | Shift on the symbol board visibly switches to the second symbol board and back | Shift (65,2140) remains without visible change: still `1 2 3 …`, no `¹ ² ³ …`. `/tmp/m7_4_symbols_shift.png` shows unchanged symbols. After entering the first symbol, shift, another symbol, another shift and another symbol, the field text ends on **`111`** instead of an alternating sequence; `/tmp/m7_4_toggle_failed.xml` + `.png`: **`qweQwqw111`**. The ABC return works separately. | **P1-03** – supposedly fixed symbol-shift bug still reproducible |
| 5 – Emoji | The emoji key opens the panel; the selected emoji appears in the field | A tap on 😊 (254,2295) opens **no panel**. `/tmp/m7_5_emoji.png` shows the unchanged letter board; `/tmp/m7_5_emoji.xml` still **`qwe`**, no emoji. Insertion therefore impossible, not passed. | **P1-04** – emoji action without effect |
| 6 – Setup | Title, active status row, privacy text and the download switch switched off | `/tmp/m7_6_setup.png` + `.xml`: **"Voice Keyboard – Einrichtung"**, **"Modell aktiv – Sprachfunktion freigeschaltet."**. Switch **"Netzwerk für Downloads erlauben"**, `checked="false"`. The privacy text contains exactly **"Nach dem Download deaktiviert die App ihren Download-Schalter automatisch; es findet dann kein weiterer Netzwerkzugriff statt."** | **ok** for the visible setup state; the automatic switch-off/network traffic not separately proven |
| 7a – PTT without a microphone | Hold 3 s: visible audio error, no crash | A stationary 3000-ms swipe at (1006,1742) produces **"Audio-Start fehlgeschlagen – erneut versuchen"**. `/tmp/m7_7_after_release.png`. App PID identical before/after the test **11053**. `/tmp/m7_7_logcat_review.txt`: no `FATAL` in the test window. | **ok** for the expected negative path |
| 7b – R-2 / keep typing | The error remains visible after release; the keyboard stays usable | The error visible after UP at 01:37:11 and still at 01:37:26: `/tmp/m7_7_after_release.png`, `/tmp/m7_7_persistent.png` (**about 16 s apart**). No jumping back to "Bereit". A subsequent e-tap: `/tmp/m7_7_typing.xml` contains **`qweQwqw111e`**, additionally `/tmp/m7_7_typing.png`. | **ok** – R-2 passed in the tested scenario |
| 7c – error differentiation | A missing recording path is differentiated as understandably as possible | The message is readable and action-oriented, but **generic audio start**, not specifically "no microphone". The actual distinction between a missing device, an audio conflict and an initialization error is therefore not proven. `/tmp/m7_7_after_release.png`. | **P2-01** – diagnosis too unspecific, no crash finding |
| 7d – real dictation/headsets | Audio → ASR → safe live/final output, internal and external microphones | Because of `-no-audio` **not provable on this device**. This test only evidences the error entry and the subsequent operability; no successful model inference, no audio/BT release. | **P1 gate**, no additionally counted implementation defect |
| 8 – layout quality | Consistent rows, well-reachable special keys, clear status presentation | Rows close horizontally consistently, key surfaces are clearly separated, ABC/?123 sits stably; wide space bar, Enter and Backspace visually recognizable. At the same time three concrete quality deficiencies, listed below. Evidence `/tmp/m7_2_language_menu.png`, `/tmp/m7_1_de.png`, `/tmp/m7_7_after_release.png`. | **P2-02 through P2-04** |

## At most three visual things that look cheap/unfinished

1. **P2-02 – status row without a clean background of its own:** In the screenshot `/tmp/m7_2_language_menu.png`, target-app content ("Search Support for …") visibly runs across the area of "Bereit". This looks like an overlaid test surface rather than a clearly delimited IME. The statement concerns the visible combination of this target app with the IME, not categorically all apps.
2. **P2-03 – two differently designed microphones:** A very flat mic key at the top and additionally a large emoji microphone at the bottom. The primary PTT button looks like a small standard button; the second suggests equivalent operation. The status row is visually only about 28 dp tall, not a generous hold target. `/tmp/m7_1_de.png`, `/tmp/m7_7_after_release.png`.
3. **P2-04 – very narrow targets and inconsistent icon language:** Eleven keys per letter row on about 411 dp display width yields only roughly 37 dp column width; the cursor/emoji special keys are likewise narrow. Colorful emoji icons next to thin shift/enter glyphs and the text key "Leer" together look improvised. Taps via adb worked; real finger hit accuracy is expressly not proven. `/tmp/m7_1_de.png`.

## Classification through a read-only inspection

The following source findings explain the device observations but do not replace them:

- `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`: `onCreateInputView()` loads `de-de` fixed; `onAction()` has no LANGUAGE/EMOJI handling and falls through to `else -> Unit` for these actions. There was no alternative language switch in the visible setup activity.
- `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt`: On the second shift tap, the branch for an already active temporary shift hits first and disables it. That matches the observed caps failure.
- `app/src/main/assets/layouts/de-de.json`: The ⇧ key in `symbols` and `symbolsShift` has `value="layer-symbols"`, while the special switching path in the view handles `KeyActions.SHIFT`. That matches the first symbol board remaining unchanged despite the tap.
- None of these files were changed; no repair attempt and no rebuild were performed.

## Logcat evidence and reach

Requested was `logcat -d`, filtered on `AndroidRuntime|FATAL|VK-`:

- Raw dump: `/tmp/m7_7_logcat_full.txt`.
- Full filter including older runs: `/tmp/m7_7_logcat_filtered.txt`.
- Filter limited to this acceptance from 01:33 UTC: `/tmp/m7_7_logcat_review.txt`.
- **0 FATAL hits** in the evaluated test window. `AndroidRuntime` contains normal starts/shutdowns of the adb/uiautomator commands; these are not app crashes.
- At the time of the error: `10-28 01:37:06.171 11053 11053 I VK-Capture: stop; durationMs=2`.
- `durationMs=2` does not contradict the 3-s hold: recording initialization fails immediately while the touch gesture continues to be held. No claim of a successful three-second recording.
- Older VK-ASR entries before this acceptance are **no** ASR success proof for M7.

## Verdict

**not ready for release**

**Count:** 0 observed P0, **4 confirmed P1 defects** (language switch, caps double-tap, symbol shift, emoji), 4 P2 findings (generic audio diagnosis plus three visual points). Additionally an open P1 release gate for a real audio/GrapheneOS/headset acceptance; this is not a claimed further implementation defect.

The visible basic functions DE, single shift, ?123/ABC, setup and the persistent audio error path work. That is however not enough for the fixed product with usable multilingualism, caps lock, a second symbol board and emoji. "Release with conditions" would treat essential missing operating functions as mere leftover polish and is therefore not appropriate.

### Mandatory before a renewed release

1. Re-check the four P1 defects on exactly the APK then installed/signed: EN/RU actually switching and typing, caps via double-tap, second symbol board visibly switching, emoji panel opening and an emoji evidenced in the field.
2. Check a physical GrapheneOS/ARM64 device with an internal microphone plus cable/USB and Bluetooth microphones: recording release, PTT-up, routing, live/final text, cursor ownership, offline restart. Here expressly **not provable on this device**.
3. Re-secure R-2 and keyboard operability after these changes; the setup download switch must not be switched on unintentionally with an active model.

### The three most important remaining points for v0.2

1. **Complete keyboard interaction:** Accept language selection across all 15 layouts, caps lock, symbol shift and emoji as a coherent, genuinely operable product scope; the current P1s must be closed even before any release of this build.
2. **Real offline dictation acceptance:** Evidence GrapheneOS/ARM64, internal/headset/BT routes, long pauses, cursor switches and warm/thermal latencies with real audio.
3. **Product polish:** A delimited status surface, one unambiguous large PTT key, consistent icons/touch surfaces and more precise audio errors instead of generic retry text.

## Evidence files and retention

All files named above reside in the current container under `/tmp/`; they are volatile and must be backed up externally for a permanent acceptance. No evidence files were copied into assets/source resources. The report explicitly names missing evidence and does not use the no-audio emulator as a substitute for a successful physical GrapheneOS acceptance.
