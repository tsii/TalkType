# E10.1-NEW – Emoji panel V2: mode switch instead of overlay

**Status:** binding new design decision, not yet implemented or accepted on the
device.
**Scope:** replaces the emoji surface/close decision from E9.4/E10.1; the
historic document `docs/LANGUAGE_EMOJI_DECISIONS.md` remains untouched.
**Work limit:** only this document is created. No code/XML/asset change, no
Gradle, no adb, no new device check.

## 1. Decision basis and responsibility

Fully read: `docs/LANGUAGE_EMOJI_DECISIONS.md` including E9.4 and E10,
`docs/SETUP_UI_DECISIONS.md` including E3, `docs/DESIGN.md`,
`ime/ImeRootView.kt` and `keyboard/EmojiPanelView.kt` under the existing Java
source path. Additionally read-only: relevant service wiring, KeyboardView
measurement, and string resources. Existing screenshots `/tmp/m8f_panel.png`,
`/tmp/m8f_closed.png`, and `/tmp/m8g_after_emoji_key.png` viewed; they are
evidence of the previous state, **not** acceptance proof for V2.

The three overlay attempts failed. I withdraw the promise that a visibly freed
bottom row automatically delivers a reliable return path. The reported touch
findings – visible 😊 at (356,2272) writes a space, 🌐 at (230,2272) actually
switches the language, two other taps insert nothing – are taken over as
handed-over device observations, not re-measured here. The cause
drawing/hitbox/touch interception remains explicitly open.

### Decision protocol over my previous statements

| Previous sentence/mechanism | Holds or falls? | Binding re-evaluation with evidence |
|---|---|---|
| "Overlay only over upper rows; bottom row stays our return path." | **FALLS** | Three device refutations: covered row, inflated root, most recently optically correct without working close. No further margin/gravity repair. |
| "A visible 😊 is a reliable one-tap toggle." | **FALLS** | A tap on the visible position can trigger a space instead of close; a visible label proves no hit area. V2 has no 😊 close path. |
| "The bottom row must remain visible when the emoji panel is open." | **FALLS** | That presupposes exactly the questionable KeyboardView geometry. V2 deliberately replaces the entire key surface; no partially visible, supposedly operable row remains. |
| "ABC is the right alternative?" – previously rejected | **rejection HOLDS** | ABC promises letters, although a return to symbols/symbolsShift must also be possible. The new key is called **"Back to keyboard"**, not ABC. |
| "An own back key would be redundant to the visible 😊 key." | **FALLS** | 😊 is not visible and not involved in emoji mode. An own key is now the only regular UI return path, not a redundant second toggle surface. |
| "× at the top right costs grid space and sits awkwardly at the corner." | **rejection HOLDS; blanket rejection of an own close key FALLS** | No × and no corner: an own full-width native button outside the emoji canvas. It occupies an explicit footer, not an emoji cell. Fewer emoji per page is deliberately accepted. |
| "Same IME window instead of dialog/activity gets the input context." | **HOLDS** | V2 stays in the same view tree. No new window, no restartInput/finishInput; focus must still be evidenced by real continued commits. |
| "Return exactly to the previous layer." | **HOLDS** | Existing KeyboardView instance only hidden/shown; no setLayout/resetShiftState calls when opening/closing emoji. |
| "Closing via system IME hide suffices." | **rejection HOLDS** | That closes the whole IME and violates the intended immediate return path. It is not a V2 success criterion. |
| "237 green unit tests prove operability." | **FALLS as release reason** | The screenshot/touch evidence contradicts the UI promise. JVM logic remains useful but does not replace device acceptance. |

## 2. Exactly one mechanism: mutually exclusive stack children

**Decision:** the existing vertical `stack` LinearLayout arranges all content
surfaces below each other. Emoji is **no longer an overlaying FrameLayout child**,
but an alternative content block in the stack. There are exactly two modes:
keyboard or emoji.

The outer ImeRootView may stay the existing FrameLayout type, but has only the
stack as its content child. No emoji character/touch surface lies above it as a
second FrameLayout child. The current root `onLayout` positioning and
`activeRowCount` are removed, not replaced by another coordinate computation.

### Tree and visibilities

| Part | Keyboard mode | Emoji mode |
|---|---|---|
| `statusRow` in the stack | VISIBLE, normal status and mic | VISIBLE at the same topmost position; error text is preserved |
| existing `keyboardView` | VISIBLE, unchanged own measurement/touch logic | **fully GONE**, including the bottom row; no invisible keyboard hit areas |
| new emoji content block in the stack | GONE | VISIBLE; its own vertical LinearLayout |
| existing `emojiPanel` in the new block | Not visible due to parent visibility | Weighted remaining area for emoji and the existing page display |
| new native button "Back to keyboard" in the same block | Not visible due to parent visibility | VISIBLE, full width, at least 48 dp tall, below the emoji panel and outside its canvas/hit test |

"Emoji content block" is a **grouping to be newly created**, not a claimed
existing field. No new source file is required for it. The back button is part
of this block and is measured and hit by Android as a real button view; it is
not a key drawn in the canvas and not a KeyboardModel entry.

### Size contract without overlay arithmetic

- The stack stays content-high, not MATCH_PARENT beyond the offered screen
  height.
- The new emoji content block gets a **design measure of 240 dp total height**,
  including the back button. That is its own size contract, not a position
  computed from `kv.bottom`, row count, keyboard height, or screen coordinates.
  The existing KeyboardView already reports a 240 dp minimum height in
  `onMeasure`; but V2 does not depend on all future layouts having exactly that
  height.
- In the emoji content block, the LinearLayout first reserves the normal footer
  button; the emoji panel gets `height=0` with weight 1. That way **the layout
  manager** distributes the remainder. A WRAP_CONTENT panel must not claim the
  entire offered IME height.
- No upper/lower panel margin, no gravity anchor computation, no stored keyboard
  pixel height, no `translationY`, no derivation of the last key row, no
  LayoutParams rewriting during `onLayout`.
- The root accounts for system navigation insets as a normal bottom padding once
  for the whole stack. This is not a panel positioning hack; footer and keyboard
  lie above this safe area in their respective modes.
- The emoji panel must respect the height limit actually handed over by the
  LinearLayout. Only the **internal** grid/page size is computed from its own
  area. This computation is legitimate: it places no overlay over keyboard keys.
- The current `coerceAtLeast(cellMinPx)` approach must not produce an
  artificially larger grid area when its own area is too small. No drawing/touch
  zone outside its own view; no grid hit in the footer. Empty data/too little
  grid space shows no selectable entry; the native back button remains operable.
- No scroll container around the back button: it cannot be scrolled out of the
  picture. If there is not even room for the fully visible footer in the actual
  window, emoji must not stay active: back to keyboard mode, an explanatory
  status instead of a stranded partial view. No shrinking of keyboard keys as a
  substitute solution.

**Deliberate price:** the letter panel is not simultaneously visible in emoji
mode. E3 here means: no modal dead end, emoji input is possible, and a safe
one-tap return path restores normal input. It does **not** mean that two complete
input surfaces must lie on the same area at the same time. Whoever wants letters
in emoji mode taps "Back to keyboard" once, then continues normally. No claim
that normal letter keys remain tappable while GONE.

### Why the focus stays

Opening/closing changes only child visibilities in the already active IME
window. No activity, no dialog/popup window, no new editor instance, no
`restartInput`, no `finishInput`, no IME hide/show, no own focus call on the back
button. Native buttons may offer touch/accessibility operation but must not
request new text input focus. A relayout adjustment of the target app excerpt is
not an editor change. That the same editor continues to be written to is an
acceptance gate, not a success message derived solely from a non-null
InputConnection.

## 3. Closing, inserting, and dictation lock

### Binding close path

**Own native button "Back to keyboard" in the panel footer. The 😊 close path is
dropped.** The label is layer-neutral and the same UI action for all 15 keyboard
languages, not an additional layout key. German resource base; no newly required
15 translation files and no layout asset change. The footer lies above the system
navigation area and not in its corner.

One tap goes through the already existing `EmojiPanelView.Listener.onDismissRequested()`
to the existing root `hideEmojiPanel()` operation. The root can use the existing
`emojiPanel.listener` for that; no invented second editor channel is needed.
`hideEmojiPanel()` is idempotent and coherently restores: emoji block GONE,
keyboard VISIBLE, `closeAndResetPage()` once; no text commit through closing. A
MotionEvent is not passed on to the keyboard returning beneath after the
visibility switch. The footer button fully processes its own gesture.

The previous invisible "tap on the center of the page number closes" is dropped.
The center remains exclusively category/page display. This leaves exactly one
visible app close button. System back/lifecycle cleanup are not required
alternatives for the usage flow; the footer must work even without them.

### State rules

1. Opening continues via the existing EMOJI action; it sets emoji mode and is no
   longer a visible close toggle. A late second open action in the already open
   mode is idempotent and writes nothing.
2. Independent of model/download state, emoji is usable offline. With unloaded/
   defective emoji data, the normal keyboard stays visible instead; no empty
   full-area replacement without a working exit.
3. On closing, keep the category, reset the page to 1. Use the existing methods
   `closeAndResetPage()` / `openWithFreshPage()`; no learning history.
4. The exact layer **base, shift, symbols, or symbolsShift** and the shift/caps
   state remain unchanged by preserving the KeyboardView instance. No new
   savedLayer system and no forced base. A deliberate language switch may, as
   before, set the new layout afterwards; emoji is closed first.
5. An emoji follows exclusively the existing listener path:
   `emojiPanel.listener.onEmojiSelected` → `VoiceKeyboardService.onTextInput` →
   `currentInputConnection.commitText(text, 1)` in its existing implementation.
   No direct commit from the root, no setText, no clipboard. The panel stays open
   after inserting.
6. State ≠ IDLE: opening **and** emoji insertion locked. The existing status
   message "Emoji not possible during dictation" remains valid. The back button
   is never disabled because of dictation. If the mic in the visible status row is
   pressed: first close emoji, then use the existing PTT start path; no active
   emoji grid over a recording. Discard late emoji touch events.
7. Editor switch/IME hide resets the mode to keyboard. No panel reappearance with
   old touch events in the next field. Existing audio errors must not be
   overwritten by "ready" on mere closing (R-2).

**Not solved, not concealed:** the reported drawing/hitbox deviation of the
bottom KeyboardView row is neither measured nor repaired by V2. It may continue
to affect **opening** via 😊 or other keys. V2 only removes the dependence of the
**return path** on it. An unreachable open action is its own failed acceptance
point, not a reason to save V2 with another overlay hack.

## 4. Hard device acceptance – one line per checkpoint

These lines are target specifications for the subsequent coder/device job, not
measurement results claimed here. Before/after screenshots belong to the **same**
APK. Screenshots prove the visible result; focus/touch success additionally needs
at least the described input sequence, not merely a drawn cursor image.

| Checkpoint | One-line acceptance specification |
|---|---|
| **Panel open** | After opening, a screenshot shows the status row at the top, below it exclusively the emoji grid with a readable page display, and below that the fully visible full-width button **"Back to keyboard"**; no letter key and no keyboard bottom row are visible, the system gesture pill covers no button text. |
| **Panel closed** | A tap on the center of **"Back to keyboard"** shows, in the next screenshot, the complete previous letter or symbol panel including the bottom row; emoji grid and back button are gone, the editor content is unchanged by the closing. |
| **Continued typing afterwards** | In the field `ab|cd` select an emoji, close with **"Back to keyboard"**, and tap `q` without tapping the field: the screenshot shows exactly `ab😀qcd` with the chosen 😀/q and the same open IME; with symbols mode open, correspondingly its visibly selected character instead of q. |
| **Nothing covers the bottom row** | After closing, a screenshot shows the complete bottom keyboard row from left to right with all labels and lower edges above the system navigation; no emoji/footer remnant lies above it – with the panel open this row is deliberately completely absent, it must not partially peek out beneath. |
| **Layer preserved** | Open from symbolsShift and close: before/after screenshots show the same second symbol panel, not base; closing inserts no space and no other character. |
| **No height explosion** | In the open screenshot, a visible editor remains above the IME; the emoji area contains its footer, does not displace nearly the whole editor, and has no empty area expanded up to the top screen edge. |
| **Recording lock** | During recording, an open attempt produces no emoji grid but the visible lock message; mic start from an open panel subsequently shows keyboard/recording state instead of still-operable emoji. |

Additionally before release: the first four checks on DE, EN, and RU as well as
opening/closing on all 15 layouts; letter and symbol mode; gesture and 3-button
navigation. Only screenshots **plus real typing** pass the acceptance. A unit
test status alone does not override any of these lines.

## 5. Coder file list – exactly four existing paths

Each path was checked here with `grep` for existing symbols. Line numbers refer
to the read state; after a rework they may shift. **New groupings/resources below
are work orders, not existing APIs.**

| Existing path | Concrete grep evidence | Narrowly limited coder assignment |
|---|---|---|
| `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt` | L. 51 `val statusRow = LinearLayout(context).apply`, L. 65 `val stack = LinearLayout(context).apply`, L. 97 `override fun onLayout`, L. 122/126 `fun showEmojiPanel()` / `fun hideEmojiPanel()` | Create emoji as a stack alternative block with its own native footer; remove overlay onLayout/activeRowCount; convert the existing show/hide/visible contracts to both modes; insets only as root padding. |
| `app/src/main/java/de/voicekeyboard/keyboard/EmojiPanelView.kt` | L. 36/37 `fun onEmojiSelected(emoji: String)` / `fun onDismissRequested()`, L. 40 `var listener`, L. 103 `override fun onMeasure`, L. 167 `override fun onTouchEvent`, L. 208/212 `closeAndResetPage()` / `openWithFreshPage()` | Neither draw nor hit a foreign footer surface; secure the limited own measurement/small grids; remove the hidden dismiss on the page center; display category and real page number within its own nav area. |
| `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt` | L. 171 `view.emojiPanel.listener = object ...`, L. 172/176 `onEmojiSelected` / `onDismissRequested`, L. 250 `private fun onMicDown()`, L. 495/497 `onTextInput` / `ic.commitText(text, 1)`, L. 605 `private fun toggleEmojiPanel()` | Preserve the existing listener/commit chain; wire idempotent opening, recording/event guards, and closing before mic/lifecycle/language switch; no new editor or persistence architecture. |
| `app/src/main/res/values/strings.xml` | L. 2 `<resources>`, L. 52 `<string name="emoji_blocked_recording">Emoji während Diktat nicht möglich</string>`, L. 55 `</resources>` | **To create:** one string for "Back to keyboard" plus the eight category keys already named in E10.2; no asset rework and no additional resource file. |

### KeyboardView.kt: no mandatory change

**V2 requires no change to `keyboard/KeyboardView.kt`.** No splitting of its rows,
no additional drawing/hit test offset, no shrinking or new layout asset
convention. On closing, the same instance merely becomes visible again. The four
files above are the complete implementation upper bound of this assignment.

If the separate touch measurement proves a defect in KeyboardView, that is **its
own repair assignment**, not silently a fifth file in V2. The smallest
professionally conceivable later repair would be a shared, identical coordinate
basis for drawing and hit test; without measurement, neither cause nor concrete
method is claimed here. That is no change necessary for the V2 mechanism.

## 6. Execution limits for the later coder

- At most **4 changed existing files**, exactly the list above; no new
  class/asset/test files and no layout change among the 15 language assets.
- At most **2 builds**: first build after a coherent four-file rework; then hard
  device checking. On failure exactly one consolidated correction round and a
  second build. Then stop and report evidence, no fourth overlay attempt.
- Existing tests may be run in addition, but neither their number nor their
  color counts as geometry/touch acceptance. Necessary new automated test files
  are to be commissioned separately, not covertly outside the file budget.
- Unchanged offline, 384 emoji/8 categories from the existing asset; fewer slots
  per page are allowed. Page count from the actual grid, not fixed "1/3".
  E10.2 category names remain; search/favorites are not added here.
- No fallback to activity, dialog, IME restart, system IME hide, or keyboard
  bottom-row coordinates as a required exit.

## CLOSING PROTOCOL

**DONE:** One mechanism decided: LinearLayout stack with mutual visibility of
the complete keyboard and the emoji block; own native footer "Back to keyboard".
E10.1 overlay and 😊 close are explicitly repealed.

**OPEN:** implementation and hard device acceptance; separate measurement of the
existing drawing/hitbox defect of the bottom keyboard row. The latter is not
reported as repaired here.

**ASSUMPTION:** the target IME window offers room for the 240-dp emoji content
block plus status/insets in normal portrait. For smaller offers, a fully
reachable back button takes priority; if the footer cannot be displayed, keyboard
mode stays active. Activation must never accept a clipped exit.

**BLOCKER:** no open design decision. For delivery, the missing device evidence
per section 4 blocks; the new mechanism is not yet implemented. A still
unreachable 😊 open action would be a separate operability defect, not a passed
test.

**VERIFICATION:** mandatory documents and both views read, existing screenshots
viewed, all four coder paths evidenced with grep and concrete symbols. No code/
Gradle/adb work performed; V2 device behavior not claimed.
