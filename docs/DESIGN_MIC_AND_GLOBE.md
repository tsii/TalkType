# D10 – large mic key bottom right and an understandable globe

## 0. Decision and responsibility

D10 supplements D9; **the previously postponed mic position rework is now
explicitly released.** All current actions/characters are preserved; the
status/diagnostics row and 🐞 remain unchanged at the top. No ASR backend, VAD,
language model, or commit algorithm rework in this assignment.

Handed-over evidence: 306 unit tests green, at 650 px all 38 instead of 30 IDs
and scaling 0.873. This is acknowledged as progress of the parallel S1/S2 work,
not re-tested here. Read: D9, current KeyboardHeight/KeyboardModel, service
wiring, and KONZEPT's PTT/decoupling rules; no builds/adb/code changes.

"Yeah.", absent English output, and frequently ignored inputs are real user
reports, but **not ASR errors fixed by a better icon**. RTF 0.80,
first_hyp_ms 1116, and peak 2 % prove neither safe live output nor sufficient
microphone level nor a wrong language setting. Causes and quality limits remain
with the parallel analysis assignment; the layout language does not control the
ASR language.

## 1. Reflow: enter moves up, mic gets the right edge

**Binding footer:** `?123/ABC · 🌐 · 😊 · space · ‹ · › · enter · mic`.
Enter takes **the outermost right position** but remains the second-to-last key;
it is not moved up and displaces no letter key. Mic moves from before enter to
far right. Space gives up **one weight unit** so that mic grows from weight 1 to
**2**; space 5→**4**, total sum still **13**.

| Key | Weight | Share | At W=1080 px / 411.43 dp |
|---|---:|---:|---:|
| ?123 or ABC | 1.5 | 11.538 % | 124.6 px / 47.47 dp |
| 🌐 | 1 | 7.692 % | 83.1 px / 31.65 dp |
| 😊 | 1 | 7.692 % | 83.1 px / 31.65 dp |
| Space, localized label | 4 | 30.769 % | 332.3 px / 126.59 dp |
| ‹ | 1 | 7.692 % | 83.1 px / 31.65 dp |
| › | 1 | 7.692 % | 83.1 px / 31.65 dp |
| Enter | 1.5 | 11.538 % | 124.6 px / 47.47 dp |
| **Mic** | **2** | **15.385 %** | **166.2 px / 63.30 dp** |

Grid DE, four rows: `[?123 11.54%][🌐 7.69%][😊 7.69%][space 30.77%][‹ 7.69%][› 7.69%][⏎ 11.54%][mic 15.38%]`
Grid PT, five rows: `[?123 11.54%][🌐 7.69%][😊 7.69%][space 30.77%][‹ 7.69%][› 7.69%][⏎ 11.54%][mic 15.38%]`
In symbols/symbolsShift, ABC is first. The full PT accent row remains above.
Percentages are display roundings; computation uses weights, the last right
edge is exactly W. No additional footer gaps in the D9 stock look.

The table is not a new width measurement on the Pixel: **ASSUMPTION W=1080 px
usable** for the worked example. For a different W, the same shares apply; mic
reaches 48 dp width from W=312 dp. Other small actions do **not** reach 48 dp
width; this is reported openly as in D9 and not prettified with overlapping
targets.

### Heights and hit area – density 420 = 2.625 px/dp

The footer gets its own desired measure **56 dp = 147 px**, under pressure at
least **48 dp = 126 px**. The new mic area is thus not only visually larger but
really wider and tall enough within the checked budget. The upper rows share the
remainder; a uniform scaling factor across all rows is not sufficient for this.

The D9 root target 268 dp plus actual navigation remains. For the following
concrete numbers: status unchanged 48 dp = 126 px, navigation by way of example
8 dp = 21 px, root cap 276 dp rounded to **725 px**. K is exclusively the key
block. Nominally K=578 px; footer height = `147×K/578` rounded to whole pixels,
at least 126 px, at most 147 px. Upper rows share K−footer exactly with
distributed rounding remainders.

| Root offer | Root effective | K | Footer/mic height | DE: three upper rows | PT: four upper rows |
|---|---:|---:|---:|---:|---:|
| **650 px = 247.62 dp** | 650 px | 503 px | **128 px = 48.76 dp** | **125 px = 47.62 dp** each | 93/94 px, mean **35.71 dp** |
| **829 px = 315.81 dp** | 725 px | 578 px | **147 px = 56 dp** | 143/144 px, mean **54.73 dp** | 107/108 px, mean **41.05 dp** |

Mic at W=1080: x approximately **914…1080 px**, width 166 px.
At 650-px root: y **501…629 px**, hit area about **63.30×48.76 dp**.
At 829-px offer/725-px root: y **557…704 px**, about **63.30×56 dp**.
These y values are **root-local**; the screen position is root top edge plus y.
Navigation starts only at 629 or 704 px, not inside the mic target.
Since K and the footer depend on the budget and not on DE/PT, the footer row
remains anchored at the bottom for both layout types; more PT rows only shrink
the upper block.

**Important budget limit:** If the reported 650/829 px are already a child
offer after status/navigation, these shares must not be deducted again. Then K
is to be taken from the actual S2 allocation; the root example numbers are not to
be blindly built in as a child height. The reported factor 0.873 is a fit of
the state at that time, not a factor binding for every new footer; the example
yields K/578≈0.870.

**Honest price:** For PT/650 px, upper keys are only about 36 dp tall. All
remain fully visible, but a general 48-dp promise is **not** fulfilled. The mic
key fulfills it; the real operability of smaller upper rows is to be accepted
separately. If even 126 px footer plus a sensibly readable remainder do not fit,
report a fit conflict instead of dropping a row/drawing half keys. No unnoticed
new character side.

### Data and PTT

Reflow for all 15×4 layers as a shared **display composition**: keep ID,
action, label, and alternates; reorder/weight the footer. **0 JSON changes**
recommended. The same composed source provides drawing, hit test, and
accessibility; no merely painted large mic area with a still-small hit area.

The previous upper mic action is **not silently deleted** in this assignment:
the user's release names the position/size of the lower key, not explicitly the
removal of every previous action. The upper area remains unobtrusive and
unchanged; both entries share the same idempotent PTT controller, never two
recordings. No third mic is added. 🐞 and the diagnostics text remain untouched.

The lower key needs real DOWN→start, UP→stop, and CANCEL→safe-abort events;
today's normal action-key release path alone does not suffice for that.
Exclusively the mic pointer owns the hold gesture; a second pointer on 🌐 must
neither replace it nor cause a synthetic mic UP. On rotation or an
editor/IME switch, end the recording safely; do not leave a session attached to
the new editor.

## 2. Mic states without a diagnostics strip rework

The only own feedback area is **the mic cell**: 26-dp microphone, a small
level/border indicator, and a short-lived commit check mark. No new strip
above, no new status row logic/diagnostics format change. Existing error
messages are preserved. The icon size of 26 dp corresponds to about 68 px at
density 420; the state badge 10 dp about 26 px. All states use the same
unchanged cell rects; no layout animation.

| State | Light: surface / glyph or marker | Dark: surface / glyph or marker | Meaning and subtle mechanism |
|---|---|---|---|
| Ready | **#FFECEFF1 / #CC37474F** | **#FF263238 / #CCFFFFFF** | Mic 26 dp, 1-dp accent outline; no success signal merely because of readiness |
| DOWN/STARTING | Normal surface, ring **#FF00796B** | Normal surface, ring **#FF80CBC4** | Immediate press feedback plus a small ring; depict a real recording as RECORDING only when it starts |
| Recording running | **#FF4DB6AC / #FF263238** | **#FF80CBC4 / #FF263238** | Filled mic indicator, an additional small recording dot and a subtle 2-dp level border; color not the sole distinguishing feature |
| "Pause" = held, currently quiet | **#FFDDE8E7 / #FF37474F** | **#FF344946 / #CCFFFFFF** | Outline remains as recording active; level drops, a small resting dot instead of a supposed pause button; the microphone keeps running |
| FINALIZING | Normal surface, ring **#FF00796B** | Normal surface, ring **#FF80CBC4** | Recording dot gone, a small processing ring; new start locked, no stealth toggle recording |
| Error / no text output after a completed run | **#FFFCE8E6 / #FFB3261E** | **#FF601410 / #FFFFB4AB** | A small ! or ?, the 26-dp mic remains recognizable; do not clear the state immediately after UP |
| Just successfully inserted | existing state plus check mark **#FF00796B** | existing state plus check mark **#FF80CBC4** | 10-dp check mark inside the cell for **600 ms**, no full-surface flash and no size change |

Priority error > processing > recording/quiet > ready; the commit check mark is
only additional feedback, not a new permanent recording state. It appears only
after an actually accepted edit in the valid editor, not on a mere hypothesis.
No check mark for a discarded revision, a late result, or a missing
InputConnection. A later error covers the check mark, not the other way around.

"Pause" is **not a new switchable PTT mode** and no ASR VAD barrier. A quiet
phase must not stop recording/buffer or discard speech. Peak 2 % alone
justifies no "silence" diagnosis; level rules need the signal reference checked
by the audio assignment. If a fresh level is missing, show an unknown/static
level, not an invented deflection. No permanently blinking CPU-expensive
animation.

If the result remains empty after finalization: additionally show the short
explanation **"No text recognized. Please try again."** once, e.g. as a toast,
without overwriting the diagnostics/error row. No "success" check mark for an
empty run. A running job is recognizable as processing; "empty output" and
"still working" are different states. This UI does not automatically correct
wrong recognized text.

## 3. Globe during dictation: recommendation (b)

Globe means **keyboard layout**, not forcing voice input. Do not justify the
previous lock reason with a supposedly ASR fixed to the old keyboard language:
recognition follows the model. An EN layout does not fix absent English
recognition; the demanded reliability improvement remains separately necessary.

| Option | Exact short message text, example EN | Assessment |
|---|---|---|
| **(a) Reject as before** | **"Layout change only after the dictation."** (35 characters) | Technically conservative, but repeats exactly the action the user experienced as ignored; the recognition automatism does not explain it. Not recommended. |
| **(b) Pre-select for the next recording** | **"Layout EN pre-selected; dictation language auto"** (40 characters) | **Recommended:** the deliberate tap is accepted, the running PTT/edit session remains unaffected; no false promise of an EN ASR setting. |
| **(c) Stop, switch, automatically continue** | **"Recording is being cut for the layout change."** (41 characters) | Rejected: fragments utterances, creates final/start races and a surprising new recording without a new DOWN; contradicts the simple hold/release semantics. |

The messages are not a new row in the diagnostics band: a one-time short toast,
plus a **persistent small `→EN` marker in the globe cell** for (b).
The marker changes no cell width and paints over no neighboring key;
accessibility describes "Next keyboard layout English, recognition automatic".
In tight space, a clear dot marker on the globe suffices; the one-time text
names the target. This is not an ASR language indicator and no proof of which
language was recognized.

### Pre-selection rules

- IDLE: globe switches immediately as before, without an additional recording
  start.
- STARTING/RECORDING/STOPPING/FINALIZING: only pre-select the target ID;
  layout/engine, audio buffer, commit state, and language automatism of the
  current session stay the same.
- Further taps cycle from the **pre-selected** instead of again from the current
  ID; the target remains limited to active languages. Returning to the current
  ID cancels the pre-selection: **"Pre-selection canceled. Recognition auto."**
  (38 characters).
- One active layout: no phantom pre-selection, hint **"Only one layout
  active."**.
- After finalization, no spontaneous re-layout under fingers currently typing.
  At the **next deliberate mic DOWN**, before the new capture begins, apply the
  target and remove the pre-selection. The footer mic stays in the same place
  thanks to a layout-independent budget/anchor; the editor and existing text
  remain unchanged.
- If a different layout is deliberately selected directly during IDLE, that
  choice replaces the pre-selection; no later surprising second switch.
- Pre-selection only in working memory, no stealth change of current_language
  before the actual application. After a process restart, no old invisible
  pending; after a target change in the active language settings, delete an
  invalid pending.

(b) most readily gives the user, among the three options, a visible, safe
reaction to his wish to switch. It explicitly does **not** fulfill the possible
expectation "the globe makes my running recognition English now". That
expectation would be dishonest with the existing model contract; it is explained
with "dictation language auto", not simulated through a hidden encoder restart.

## 4. Device acceptance "clean and smooth" – 12 mandatory points

Only a measurement prescription for the orchestrator; **no adb execution**
here. For logs: VK-HIT exists; additional monotonic timestamps DOWN/first
display, capture start/stop, and successful edit are **debug measurement points
to be created**, not claimed current log fields. Without them, the
corresponding latency point is not proven. Logs contain IDs/times/rects/states,
no transcripts or audio data.

| # | Measurement point / evidence | Target value |
|---|---|---|
| 1 | Root/fit log plus screenshots at 650/829 px for DE and PT; `adb logcat -d -s VK-HIT` for actual hits | All previous IDs of the same layer preserved; 0 half-drawn keys; last lower edge ≤ content end, mic on the outer right. The number 38 applies to the measured layout, not blanketly to every language. |
| 2 | Screenshot comparison first/second/third opening, same editor/same offer; `adb exec-out screencap -p > /tmp/d10_second.png` | No cut-off footer row; with the same budget, identical cell edges within **1 px**, no cumulative insets. |
| 3 | Mic center tap with the VK-HIT rect; width from `adb shell wm size`, density from `adb shell wm density` | Mic reachable from the visible keyboard with **1 hold gesture**; in the example about 166×128 or 166×147 px, each ≥48×48 dp. Hit name mic, not enter/space. |
| 4 | Monotonic DOWN→first press/starting frame measurement, 20 hold gestures under running ASR | **Every** visible reaction <500 ms; no waiting for encoder/finalization for press feedback. Not to be confused with first_hyp_ms. |
| 5 | Capture/controller timestamps for 20 DOWN/UP/CANCEL cycles, including UP during inference | Start request exactly once per DOWN, stop request immediately on UP; microphone release as a measurement target ≤500 ms after UP/CANCEL, otherwise a documented error, no silent continued running. |
| 6 | Light/dark screenshots ready/pressed/recording/quiet/finalizing/error; existing diagnostics visible in parallel | Correct table from §2, no RECORDING before the actual capture; error remains after UP for at least **5 s without a new action**, no false "ready" reset. Status/info strip neither removed nor redesigned. |
| 7 | Successful insertion in the test field plus edit timestamp and subsequent empty/rejected runs | Check mark only on an accepted edit, duration **600±100 ms**; 0 success check marks without an edit. An empty completed run receives an explanatory message; a processing run remains clearly distinguishable. |
| 8 | During a held mic, a long speech pause and then continued speech; level/state log | No automatic recording abort/rebuild through the "quiet" display; speech after the pause still captured. Level reacts only to real measurements; absent text is not reported as successful recognition. |
| 9 | Second pointer taps the globe during a held mic; before/after screenshots and a session/layout ID log | Exactly one session, no stop/restart, current layout ID unchanged; `→EN`/marker and the exact (b) message visible; 0 requested ASR language changes. |
| 10 | UP→final done→normal typing→new mic DOWN; test further taps up to the wrap | No re-layout during normal typing; target applied exactly once before the next capture; pending gone afterwards; same mic cell edge, wrap cancels pending. |
| 11 | Rect/screenshot comparison before/after normal typing, shift/caps, symbols↔symbolsShift, and emoji return | No key wanders without a deliberately chosen layout/layer/window change; shift single/double tap work; the emoji return path fully visible, no lost action. |
| 12 | Screenshots light/dark/pressed/caps/second opening/emoji plus `adb shell dumpsys package de.voicekeyboard` and a documented APK hash | Everything from **the same APK** on the reported API-37 phone; 15 layouts position-checked, DE/EN/RU/PT/CZ in depth. No emulator images as phone evidence. Recognition quality separately per the analysis gates, not replaced by a passed UI list. |

The operation list is a necessary release basis, **not** an assurance "German
and English now recognize reliably". A new version only after this list **and**
the separate ASR/audio acceptance; a pretty mic area does not paper over ignored
sentences.

## 5. Classification in D9-S1 through S4 – no additional third build

No fifth feature wave: D10 supplements the not-yet-delivered S chain. The shared
APK budget remains **at most two builds**, not two more builds. If build 1 has
already been created, bundle everything D10 together with the Kimi findings and
use only the still-available correction build; if the budget is used up, stop
transparently/replan instead of starting a stealth third build.
New class/test names below are proposals; existing methods are not invented.

| Phase | Files, at most four per micro assignment | D10 addition / first red test |
|---|---|---|
| **S1 geometry contract** | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardHeight.kt`; `keyboard/KeyboardModel.kt`; existing `KeyboardHeightTest.kt` and `KeyboardModelTest.kt` under `app/src/test/java/de/voicekeyboard/keyboard/` | Different footer/upper row heights and footer order as a shared display rule; test 650/829 px, 4/5 rows, all IDs, mic minimum target, same footer lower edge. No global fits from an old session. |
| **S2 touch/measurement integration** | `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt`; `keyboard/KeyboardView.kt`; new/from D9 planned `app/src/androidTest/java/de/voicekeyboard/keyboard/StockKeyboardFitTest.kt` | Actually draw/hit the composed footer; prepare the DOWN/UP/CANCEL contract for the lower mic, independent mic pointer, no second action on release. Test second opening and the globe second pointer first. Do not redesign the strip/🐞. |
| **S3 stock palette/feedback** | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardTheme.kt` (new/planned); `keyboard/KeyboardView.kt`; `keyboard/EmojiPanelView.kt`; planned `KeyboardThemeTest.kt` under the JVM test path | §2 palette/markers/check mark, state priority, and constant rects; extend the existing D9 theme job, no additional isolated cosmetics build. |
| **S4 service/pending wiring** | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`; new `keyboard/PendingLayout.kt`; `app/src/main/res/values/strings.xml`; new `app/src/test/java/de/voicekeyboard/keyboard/MicAndGlobeStateTest.kt` | Bind existing controller events to the lower mic; accepted edits instead of hypotheses as feedback; pre-selection automatism, exact texts, 1 session despite globe, and the next start with the target. Replace the old rejection, no automatic recording continuation. |

Abbreviated `keyboard/...` production paths live under
`app/src/main/java/de/voicekeyboard/`; all raw layout assets remain unchanged.
If a necessary test/integration path exceeds the four-file limit, split the
assignment **without** an additional APK build; do not silently attach files or
device jobs. The later Kimi finding is integrated before the last shared build
or tracked as an explicit remaining release blocker, not pre-empted by this
design assignment.

## Conclusion

**DONE:** Mic far right/double width, enter before it, space 5→4; complete
footer row and all characters preserved; concrete 650/829-px geometry including
limits; mic states, globe (b), 12 acceptance points, and S chain allocation
decided.
**OPEN:** Implementation and phone acceptance; ASR/audio analysis of "Yeah.",
English, and ignored inputs; confirm the root/child budget allocation and the
real usable width.
**ASSUMPTION:** The worked examples use 1080 px usable width, the root offer,
and 21 px navigation; deviating real insets are accounted for exactly once.
**VERIFICATION:** Only sources/D9 read and measure/text lengths arithmetically
checked; no adb/build and no code/asset changes, the only new file is this
document.
