# D12 – Long-press menu for alternate characters

## 0. Decision and read seams

**Decision:** Long-press on a character key opens a small selection menu;
slide the finger to an alternate, release there → insert exactly that character.
Short tapping remains unchanged. No automatic uppercase preview on every
DOWN: D11's omission of the short-tap popup remains; the now explicitly desired
**long-press menu** is a different interaction.

Only this new document; no code/asset intervention, build, emulator/adb, or commit.
Read: `keyboard/KeySurface.kt`, `KeyboardTheme.kt`, `KeyboardView.kt`,
`layouts/LayoutAsset.kt`, `LayoutParser.kt`, current root structure, DE assets and
the alternates check in `LayoutCompletenessTest.kt:265ff`.
The prefixes `keyboard/` and `layouts/` live under `app/src/main/java/de/voicekeyboard/`.

- `KeySurface.decide` remains the **only** seam for base key surfaces and their
  priority: micRecording > CAPS_FILL > pressed > normal. Do not duplicate.
- The theme remains the **only** color/measure source; popup measures will be
  additional named design values there, no new colors and no second palette.
- `LayoutKey.alternates` is already `List<String>`, the parser reads the list;
  the existing tests require at most **8** entries. No nested JSON and no new
  interpretation language in the asset.
- The new pure logic seam `LongPressMenu` is an **implementation proposal**,
  not an Android handler that exists today. It provides state/geometry/selection;
  Android events/timers remain an adapter task of the existing KeyboardView.

## 1. Popup shape with measures

Reference measure: density 420 = **2.625 px/dp**, reference **1080×2400 px**.
Normal case **one row**, a maximum of **8 alternates**, the base character stays on
its real key and is **not an additional ninth menu cell**.

| Measure/Token | Binding value | Reference pixels |
|---|---|---|
| Cell width/height | **48×48 dp**, not the narrow base key width | **126×126 px** |
| Inner padding of the menu | **4 dp** on all sides | 10/11 px, rounding remainders distributed |
| Menu size | `48×n + 8 dp` wide, **56 dp** high | n=1: **147×147 px**; n=3: **399×147**; n=7: **903×147**; n=8: **1029×147** |
| Distance to the base key | **8 dp** | **21 px** |
| Distance to the keyboard clip edge | **8 dp** | **21 px** left/right/top/bottom |
| Outer radius | **4 dp**, only the menu's outer edge | about **11 px** |
| Frame / shadow / pointer tip | **none / 0 dp / none** | No new speech-bubble path |
| Character size | **24 sp**, normal typeface, no forced uppercase | 63 px at fontScale=1 |
| Menu surface | **palette.keyPressed** opaque | Light **#FFA3ABB0**, dark **#FF676F74** from the existing composition |
| Unselected character | **palette.textPrimary** | Light **#FF37474F**, dark **#CCFFFFFF** |
| Selected cell / character | **palette.accent / palette.micRecordingGlyph** | Light **#FF4DB6AC / #FF37474F**; dark **#FF80CBC4 / #FF263238** |

No individual tile shadows, no new brightness/accent color. The name
`micRecordingGlyph` is just the existing counter-color token and triggers no mic
semantics in the menu. The cell highlight stays clipped inside the menu's outer
edge. The normal pressed origin key continues to be determined exclusively via
KeySurface; a selected menu alternate is not a CAPS_FILL/recording state. The
additional menu drawing alone does not trigger a requestLayout; a re-measure with
an identical layout/rects does not count as a size change or abort.

### Position, first row and status protection

A character layer is **drawn last within the existing KeyboardView**, within its
current clip; no dialog/PopupWindow, no new FrameLayout measure child, no
WRAP_CONTENT overlay, no new root height and no editor/window switch.
The popup state does not grow into the S1/T20 height contract.

Default: menu bottom edge = base key top edge minus 21 px; horizontally centered
over the base key center, then shifted within `[21, W−21]`. For n=8 at W=1080,
only 9 px of distribution leeway remain: 1029 px menu + 42 px outer distance =
1071 px. **48-dp cells cannot be a very narrow menu with eight alternates at the
same time.**

If it does not fit completely into the keyboard clip above, it is placed
**below** the base key with the same distance. In particular, the first row must
not cover the diagnostics/info strip or paint outside the IME into the target
app's window. **Deliberate exception to "always above":** the topmost row opens
below if necessary; a guaranteed-above menu and a guaranteed-free strip are not
geometrically possible there at the same time. No hidden covering.

Example from the handed-over approximate screen coordinates: r0 center 1884 px,
assumed row height 106 px → base key about 1831…1937. A 147-px menu above it
would lie at 1663…1810 and cover the info strip around y≈1765. Hence below:
**1958…2105 px**. For the footer, center about 2225 and, by way of example,
128 px height: base key 2161…2289, menu above **1993…2140 px**.
These are derived examples, not a new measurement; real rects/clip bounds decide.

For narrower windows: a maximum of four columns and two rows is permissible as a
space fallback (n=8: **200×104 dp = 525×273 px**), still 48-dp cells. Only choose
it if the complete geometry fits **above or below**, without overlap with the base
key, inside the keyboard clip. Never escape over the diagnostics strip. If
neither variant fits: do not open the menu, the base key remains operable, report
the fit conflict for this configuration; do not hide alternates or shrink targets.
The 1080-px reference uses the one-row variant; narrow windows are to be accepted
separately.

## 2. Behavior, timings, and collision rules

| Event | Decision / numbers |
|---|---|
| DOWN on a text key with non-empty alternates | KeySurface press feedback immediately, remember origin key/layout generation/pointer; start a **450-ms** timer, do not insert a character yet. |
| UP before 450 ms within the origin key | Insert the normal base character exactly once via the existing text path; delete the timer. |
| Movement before the menu opens | Distance from DOWN greater than **8 dp / 21 px** ends the long-press timer for this gesture; a later return does not restart it. Normal base character only on a subsequently valid UP on the origin key. No slide typing into neighboring keys. |
| 450 ms reached, finger still validly held | Open the menu, do not automatically select an alternate yet; the base character remains marked as the starting point. No premature base commit that would have to be deleted later. |
| After opening, UP on the actual base key | Base character exactly once, no additional alternate; close the menu. |
| After opening, finger in a menu cell | Mark that cell; **4-dp** switching hysteresis at inner cell boundaries so the edge does not flicker. At UP, the actual cell counts without a stale hysteresis selection. |
| UP within an alternate | Commit exactly that visible value once; no base character before/after; close the menu. |
| UP outside the base key and menu cells | **Abort, 0 characters**; also in the 8-dp gap, no "nevertheless take the last hit". |
| ACTION_CANCEL, pointer loss, hide, editor/layout/size change | Discard the timer and menu immediately, **0 characters**, no late timer commit. The existing ownership rules apply for capture/editor switches. |
| Swipe up/down after opening | Serves only for spatial selection: up in the normal menu, down in the flip fallback; no new action/page, no scrolling. Back to the base key → base character; release outside → abort. |
| Mic DOWN/UP/CANCEL | **Classify the mic first, never a long-press timer.** Use the existing `onMicHoldStart` / `onMicHoldEnd(cancelled)` unchanged; 450 ms on the mic opens nothing and must neither delay nor stop PTT. |
| Hold backspace | No alternates menu; the existing/separately planned repeat behavior and its rate remain unchanged. This assignment introduces no new backspace timer. |
| Shift/Caps | Actions are not popup-capable; **DOUBLE_TAP_MS=350** unchanged. Text long-press uses 450 ms, does not count as a shift tap, and does not change caps. |
| Second pointer with the menu open | No second menu/character and no PTT start from an accidental stroke across; only the origin pointer decides. An already active mic hold gesture is never displaced by the popup. |
| Text key without alternates / space / globe / info / enter / dead key | Previous path; do not invent a generic "hold long = menu" rule for action/dead keys. Info/globe and PTT semantics remain separate. |

The origin pointer remains in the keyboard touch path until UP/CANCEL; selection
gestures are not interpreted as a new DOWN on keys underneath and are not passed
to info, mic, or enter when closing. No new text long-press begins while the mic
is active; dictation/ownership is not undermined by this extra gesture.

Commit exclusively via the **listener.onTextInput → existing InputConnection
commitText(text,1)** chain, not via a popup-specific editor write path.
The currently displayed layer determines the alternates list; do not replace
shift/caps with a blanket uppercase() (TR, DE, EL). A temporary shift is consumed
once after a successful base/alternate commit, not on the timer or an aborted
menu. No normalization/correction of existing user text, no VAD/ASR intervention.

## §2b Upper bound of the view (row 1 of the keyboard)

**D12-K, decided:** For the topmost row, this addendum takes precedence over the
flip fallback from §1 and the corresponding previous test in §6. "Row 1" here
means the first visible key row, i.e. model index **0**, not model index 1.
Handed-over device finding: view **1080×576 px**, origin key top=0;
selection works in the next row, in the topmost row previously no
cell. That is not a new measurement of this document assignment.

### §2b.1 Selection must be direction-independent

**Decision:** The selection gate is not `keyTop`, but the **entire real base key
rectangle extended outward with an 8-dp jitter zone**. Within this protection
zone, a popup cell **never** wins; UP there yields the base character exactly
once. Only outside can the menu cell actually under the finger win — to the left,
right, top, **or** bottom. Outside without a menu cell, it remains an abort
without a character; a stale selection must not trail behind.

The zone is extended **outward** on each side, not inward; its edge still belongs
to the protection area. Not a shrunken inner zone as a supposed jitter tolerance.
The read F22-M approach with a subtracted slop would therefore not be this
decision: it would let alternates win already inside the original key.
The arming logic applicable before opening is separate from this.

**Rationale:** With top=0, the previous upward gate demands a negative view
coordinate. The six visible cells are thus never released for selection despite
movement. The rectangle distance, by contrast, enables the real lateral
selection and preserves "hold, without sliding away → base character".

### §2b.2 Placement now (a), window solution (b) only conditionally later

**Now binding (a):** If the usual position above the base key does not fit,
clamp the menu to the **view top edge 0**, draw within the same view, and
temporarily cover the topmost row. No additional height requirement, no new
window layer, no covering of the info/diagnostics strip lying outside. The base
key geometry underneath remains unchanged; there is no UP forwarding.

**Honest consequence:** The cell directly above the base key can be blocked by
the base key plus jitter zone; selection happens via the cell areas lying
**laterally outside**. This release is direction-independent, but not a promise
that every visible cell is reachable in every overlap. If a cell is fully
overlaid by the protection area, it is **not operable**; the reachability test
must report that openly. Do not shrink the protection zone to make the test
apparently green. In particular, also check single-alternate menus in the
topmost row: without a laterally reachable area, the feature is not yet accepted
for that key.

**Later explicitly permissible exception (b):** A real more-keys popup window
above the keyboard can solve this overlap limit; it is not fundamentally
forbidden. But it is implemented **only after the digit hints L1 are complete**
and a separate focus/window/height acceptance step: same target editor, no
restart of input, complete selection/abort gestures, no unintentional PTT and no
lost UP. AOSP's separate more-keys window justifies this exception, not an
unchecked second window management in the current correction step.

### §2b.3 Number of alternates and horizontal view limits

**The data cap remains eight per key, not eight per row divided by eleven.**
The menu uses the full view width, not just the origin key of about 1080/11≈98 px.
The correct outer target measure without inner cell gaps is 48·n+8 dp: for n=8
**392 dp = 1029 px**, i.e. smaller than 1080 px; with twice an 8-dp side margin
a total of **1071 px**, also fitting.

**Deviation in the read F22 state:** The width function additionally counts a
4-dp padding between the cells. Eight cells thus need **420 dp** instead of
392 dp; with the currently rounded 10-px padding that is **1098 px**, i.e. too
wide. That must not be released as "eight fit after all". With this existing
inner-gap calculation, at most **seven visible cells** fit on the reference;
without inner gaps per the original outer measure, at most eight.

Clamp left/right to the available view area; if the menu width still does not
fit, reduce the visible cell capacity from the **actual** cell/padding measures
— do not cut cells short, squeeze them together, or paint outside. The fitting
does not, however, remove the overlap with the base key protection area from
§2b.1. More alternates than capacity are deliberately placed on the second level
per §3; **no silent truncation of the list**. Until the redistribution, the
affected key does not count as fully operably accepted. Menu width and picker
must use the same actual paddings/intervals.

### §2b.4 Existing tokens and targeted acceptance

Evidence: **KeyboardTheme, lines 84–96**, checked by reading:
- `POPUP_CELL_DP = 48f`, `POPUP_HEIGHT_DP = 56f`, `POPUP_PAD_DP = 4f`.
- `POPUP_GAP_DP = 8f`, `POPUP_RADIUS_DP = 4f`, `LONG_PRESS_MS = 450L`.
- `POPUP_MOVE_CANCEL_DP = 8f` is also there as an existing movement measure;
  do not introduce a new divergent number for the jitter zone.

No new measures, colors, contents, or export data. Test: UP on the base key and
the jitter zone each yield exactly one base character; finger beyond any of the
four sides plus a real cell → exactly one selection; edge of the zone → no cell
selection; CANCEL → no input. With top=0, prove at least the lateral selection
and explicitly count every fully blocked cell. The earlier test
`topRowFlipsBelow` is to be replaced for this addendum by **topRowClampsToZero**.
The 576-px view height and the L1 hints must not grow or disappear because of it.

## 3. Alternates budget and DE content

**At most eight alternates, no silent capping.** That corresponds to the
existing test contract and, on the reference width, to eight real 48-dp cells.
The base key occupies no menu cell; the direct starting point remains its real
area.

For DE, the first ten keys of the QWERTZ row get these additional characters:
**q→1, w→2, e→3, r→4, t→5, z→6, u→7, i→8, o→9, p→0**.
Do not accidentally assign to y instead of z. These digits apply equally in
base/shift. New convenience aliases **a→ä, o→ö, u→ü, s→ß** and their
linguistically correct shift forms are added. **R8 decision (2026-10-30):** the
sentence "the existing direct ä/ö/ü/ß keys remain" is **repealed** — de-de gets
**no** own keys for ä/ö/ü/ß; the umlaut/ß is the **first alternate** of
a/o/u/s (target design with key list: `docs/DESIGN_DEDE_ZIELBILD.md` §1;
requirements exception: `docs/LAYOUT_REQUIREMENTS.md` §2.2 de-de note). Not a
convenience double path, but the evidenced per-language exception (AOSP
`rowkeys_qwertz1.xml`/`rowkeys_qwertz3.xml` likewise carry no umlaut/ß direct
keys). The principle "own key plus alternate" remains untouched for the other 14
languages.

Order: the digit if present, then the desired umlaut/ß convenience alias, then
previous alternates in their previous order, without duplicate entries.
Example e: **[3, é, è, ê, ë, ę, €]**, i.e. **7**, not 9 alternates.
The handed-over list has six entries; the additional character "3" is **one**
entry, not three. An asset that is really nine entries long remains its own
stress case.

If there really are nine or more: first refer rare characters already directly
reachable on symbols/symbolsShift to there from the convenience menu (e.g. €),
**not** delete them from the layout. Otherwise the author must place the
additional rare alternate as a unique key on the second symbol level and check
the mandatory-character/mirroring rules. No "show only the first 8", no hidden
second long-press menu, no automatic paging while the finger is held. If the
redistribution cannot be evidenced rule-conformly, the affected asset is not
released instead of silently cutting data.

Parser and assets remain the data format: missing/invalid lists must not block
the normal keyboard. The runtime should handle the conflict unambiguously;
tests/import validation flag `>8`; they must not merely hide the error in the
renderer. D12 does not change these files; the later asset assignment adds
DE digits/aliases and both levels in a targeted way — do not invent global text
intervention/language rules for all 15.

## 4. Footer BEFORE/AFTER — period replaces both cursor arrows

The removal of **both** cursor actions is explicitly authorized here by "‹ ›
gone, period instead"; otherwise the key inventory remains. D10's separate
decision **mic all the way right** remains valid. Enter therefore stands before
mic, even if the handed-over actual row still shows mic before enter.

**BEFORE, sum 13:**
`[?123 11.54%][🌐 7.69%][😊 7.69%][space 38.46%][‹ 7.69%][› 7.69%][mic 7.69%][⏎ 11.54%]`

**AFTER, sum 13:**
`[?123 11.54%][🌐 7.69%][😊 7.69%][space 30.77%][. 15.38%][⏎ 11.54%][mic 15.38%]`

Weights afterwards: **1.5 / 1 / 1 / 4 / 2 / 1.5 / 2**.
The previous two arrow slots finance the **2.0-wide period**; the space
reduction 5→4 already decided in D10 finances mic 1→2. No additional row.
At 1080 px: period and mic each about **166 px / 63.30 dp** wide; period about
x=623…789 px, enter 789…914, mic 914…1080. Distribute edge pixels jointly.

Period is **kind text, value/label "."**, short = period, long = **[, ! ?]**;
no new action command "dot-popup". Three alternates yield **399×147 px**.
Period/mic share D10's footer height: 650-px root example **128 px / 48.76 dp**,
829-px offer with root cap **147 px / 56 dp**; target ≥48 dp is satisfied here.
If the current integrated footer in PT still scales down to 44 dp, this target
is **not yet met**: integrate the D10 height contract first, not just enlarge
the icon.

On symbols a period key already exists: **move that same ID/character function
into the footer**, no second period in the same layer. Add it in a targeted way
to layers without a period; the pairs base/shift and symbols/symbolsShift must
keep their row sums. The later asset/composition assignment checks all 15×4
layers; no ghost key at the old period position and no lost mandatory symbols.
The popup aliases , ! ? are intended convenience double paths to the direct
symbol keys.

**Splitting key / decision #8:** 🐞/info stays at the top in its **48×48-dp**
area, no replacement in the footer and no shrinking to icon size. A popup may
neither cover the strip while drawing nor trigger its events at UP. This
separation is more important than the impossible promise "first row always up".

## 5. Measurement plan — exclusively for the orchestrator

No emulator operation in this document assignment. Reference data r0≈1884,
r1≈1990, shift row≈2100, footer≈2225, and info≈1765 are anchors, **not**
universal tap coordinates; carry the real key rects/root position and the
650/829-px budget along with each image.

### Screenshot sequence from the same APK

1. Light/dark normal q and held q at **100 ms**: only the press surface, no menu.
2. The same states at **500 ms**: menu with "1", base character not yet in the
   field.
3. e menu after the asset addition: seven entries, **903×147 px**, fully inside
   the clip; at least **21 px** distance near the left edge, no covering of the
   info strip.
4. a/umlaut and period menus, finger on an alternate: exactly one accent cell;
   for period **399×147 px**, image before UP and afterwards closed with one
   test character.
5. MENU→UP base key, UP outside, and ACTION_CANCEL: exactly the base character
   or the unchanged empty test field; never a double input or a menu trailing
   behind.
6. Topmost/right/bottommost origin key, both heights, DE/PT, second opening,
   caps: popup clipped/folded as described, all normal keys preserved after
   closing.
7. Hold mic for **900 ms**: no menu, recording state unchanged; shift double
   tap within **350 ms** remains caps, no alternate handler and no second
   session.

Use only controlled test fields; do not collect/export user content. For hold
images, the orchestrator may use its existing touch/instrumentation fixture,
capture `adb exec-out screencap -p` during the hold, and map base key rects with
`adb logcat -d -s VK-HIT`. Popup rect/index may be logged as additional debug
metadata, **not the entered content**. A mere image taken after UP does not
prove the briefly opened menu.

### Pixel proof instead of a rough delta threshold

From **KeyboardTheme.compose with integer division** it follows exactly:
- Light normal RGB **(236,239,241)**, pressed **(163,171,176)** = #FFA3ABB0;
  channel changes **(73,68,65)**, maximum **73**.
- Dark normal **(38,50,56)**, pressed **(103,111,116)** = #FF676F74;
  channel changes **(65,61,60)**, maximum **65**.

These are **maximum RGB channel changes, not luma/brightness metrics**. In the
PNG, compare one **10×10-px inner patch** each without glyph/edge/antialiasing:
per channel target value ±**2**; not just "any delta >50". KeySurface determines
the caps/recording exceptions, which are therefore not compared against the
normal press value.

For the popup: outer rect from the geometry, an opaque menu surface in these
theme values, at least one readable alternate glyph image plus a 48-dp selection
cell in the held screenshot. Size/position tolerance **±1 px**; do not use the
radius edge as a color patch. After UP: the popup rect is a normal keyboard
again; diagnostics text/info rect unchanged. More changed pixels alone prove
**no** menu — glyphs, correct rect, and a selected cell are jointly required.

## 6. Pure JVM seam — test list with concrete results

`LongPressMenu`/geometry functions are pure functions to be newly proposed;
inputs are numbers, lists, IDs, time values, state values — **no Android
types**. For two rows, `pick` must consider x **and** y; offsetX alone does not
suffice. Cell intervals are closed left/top, open right/bottom. Outside → no
selection.

| Test name | Expectation |
|---|---|
| `doesNotOpenAt449ms` | Time 449 ms → menu closed, 0 commit events |
| `opensAt450ms` | 450 ms, valid text key/pointer → open, still 0 commits |
| `emptyAlternatesStayNormal` | Empty list → no menu, normal UP unchanged |
| `movementCancelsArming` | Distance >8 dp before the timer → timer definitively aborted |
| `sameGestureDoesNotRearm` | Move away/back and wait 900 ms → no late popup |
| `geometry3AtDensity420` | 3 alternates → **399×147 px**, cells **126×126 px** each |
| `geometry8Fits1080` | 8 alternates → **1029×147**, with a 21-px margin inside 1080 |
| `leftEdgeIsClamped` | Desired menu left <21 → left=21, not a negative position |
| `topRowFlipsBelow` | Example key top=1831, bottom=1937, safe top>1663 → below-top=1958 |
| `doesNotOverlapSourceOrToolbar` | No candidate fully fitting → fit=false, no clipped menu |
| `pickThirdCell` | Menu inner coordinate x=120 dp, y=24 dp at 48-dp cells → index **2** |
| `rightEdgeIsOutside` | 3 cells, inner x=144 dp → **no** selection, not index 2 |
| `twoRowPickUsesY` | 4 columns, second row, third cell → index **6** |
| `paddingIsNotACharacter` | UP in the 4-dp padding → no alternate, 0 commits |
| `upOnBaseAfterOpen` | UP in the separate origin key rect → exactly **1 base commit** |
| `upOnAlternateOnly` | UP on index 2 → exactly list entry 2, **no additional base commit** |
| `outsideAndCancelNeverCommit` | UP outside or CANCEL → closed, timer deleted, **0** commits |
| `lateTimerHasNoEffect` | Timer of an old layout/editor generation → closed, **0** commits |
| `micNeverArmsMenu` | Mic DOWN plus 900 ms → **1 HoldStart, 0 menu events**; UP exactly 1 HoldEnd |
| `shiftDoubleTapUntouched` | Shift taps within the existing 350-ms contract → caps as before, **0 menu events** |
| `oneShotShiftConsumedOnlyOnCommit` | Abort preserves shift; alternate commit consumes it once |
| `sevenIsNotNine` | [é,è,ê,ë,ę,€] plus 3 without duplicates → **7** entries |
| `nineAlternatesAreNotTruncated` | 9 entries → unambiguous validation/release error, not silently the first 8 |
| `footerReflowKeepsRequiredKeys` | Weight sum **13**, period/mic weight **2**, enter before mic, cursor-left/right deliberately removed; no other action lost |
| `surfacePriorityUnchanged` | Existing KeySurface regression cases: mic before caps before press before normal; press colors continue **73/65** channel maximum |

The later Android adapter must additionally instrument and prove timer abort,
real pointer ownership, the text path, and clipping. TalkBack long-press
selection is not to be reported as checked by JVM picks alone; accessibility
remains its own device item.

## §7 Hint labeling (keyHintLabel)

**D12-H — decision:** `LayoutKey.hint` is drawn as a small extra legend, not as
additional key text or its own hit area. Null/empty/blank means no drawing; the
existing tolerant reading and the text/popup commit path are not changed by this
assignment. No derivation from the first alternate: the source is exclusively the
explicit hint field of the currently visible key.

### §7.1 Font size and token

Existing main label token: **KeyboardTheme.SP_LETTER_SP = 24 sp**.
Desired **new, not yet existing** theme token: **SP_KEY_HINT_SP = 12 sp**,
derived as **0.5 × SP_LETTER_SP**, not as a scattered second font constant in
the renderer. The value matches the existing **SP_SECONDARY_SP = 12 sp**, but the
new name separates the hint role from status/space labeling.

At fontScale=1, that is nominally **12 dp or 31.5 px** at density 420. Text
stays sp, not fixed in device pixels. If the actual main label is shrunk by the
fit, the hint follows in the same ratio: **hint paint size = 0.5 × actual main
label paint size**; it does not become larger than the target value derived
from the theme. Do not shrink further to illegibility to hide a collision: if
space is scarce, the priority from §7.5 applies.

### §7.2 Position and AOSP evidence

**Top right, text centered on the top-right anchor**, not left-aligned and not
over the center of the whole key. The lateral inner distance is the existing
**POPUP_PAD_DP = 4 dp**; the right visible glyph edge stays accordingly inside.
For the 98-px key and 18 px measured hint ink width, the text center lies at
**98 − 10.5 − 9 = 78.5 px** relative to the left key edge.

Vertical specification: nominally **2 dp** distance between the key top edge and
the visible glyph top edge, derived from **POPUP_PAD_DP / 2**, no new distance
token. For the actual row height h and the existing target value from
**KeyboardTheme.rowHeightDp(rows)**, the distance is
**(POPUP_PAD_DP / 2) × min(1, h / rowHeightDp(rows))** in dp.
For a target height of 55 dp that is 2 dp; for 44 instead of 55 dp about
1.6 dp; a taller key does not enlarge the distance beyond 2 dp. Determine the
baseline only from the actual font/ink metrics, do not set "baseline = top edge
+ 2 dp". For a narrower key, anchor and measured ink width follow its real right
edge; no fixed screen x value, no change to the touch area or row height.

**Evidence checked by reading, AOSP android16-release:** `rowkeys_qwertz1.xml:24–71`
carries keyHintLabel 1…0 for q,w,e,r,t,z,u,i,o,p, separate from
additionalMoreKeys. The phone hint branch in AOSP's `KeyboardView.java:473–480`
places the hint legend top right and draws it with CENTER alignment, including
the measured digit/text width. That evidences the look, **not** our concrete
4-/2-dp values: those are the derivation from existing theme measures decided
here. AOSP has its own branches for other hint kinds; our short digit hint is
not a phone multi-letter label next to the main glyph.

### §7.3 Color and dark mode

**Existing field palette.textSecondary**, no invented keyText/keyLabel fields:
light **#B337474F**, dark **#B3FFFFFF**. The chosen palette handles the
light/dark difference; no special dark path and no new color token. The hint is
an extra legend, hence neither **palette.accent** nor
**palette.keyToggledGlyph**. Even in caps, the digit stays a digit.

The base key surface continues to come exclusively from **KeySurface**; the hint
paint changes no surface/press state and does not apply the overlay again. On
the dark press background **#FF676F74**, the existing secondary text color
remains; **#FF80CBC4** is the selection/accent token, not the general hint
color. The popup itself draws its alternates as before; no second hint legend
there. Legibility on normal and pressed surfaces is to be checked separately on
the device, not merely asserted through identical RGB tokens or reported as
already-checked accessibility.

### §7.4 Layers

**base and shift show the same explicit digit hint**, including caps:
q/Q→1 … p/P→0; no uppercasing/transforming of hint and no switching of the digit
to the shift special character. Both layers get the matching hint data, no
render fallback from another layer's layout.

**symbols and symbolsShift draw no such hints in this F23 scope:** there the
digits are already directly visible character keys; an inherited 1…0 would be
redundant or wrong. The same applies to action keys, space, mic, and the popup
cells. The AOSP QWERTZ layout separates letter hints from actual characters; it
does not prove a universal prohibition of other symbol hints — this limitation
is our deliberately small implementation decision, not a claimed AOSP rule for
all layouts.

### §7.5 Collision and priority

**Main label including its own accents/umlaut dots > necessary dead-key marker
> hint.** An umlaut dot as part of the main glyph must never disappear for a
hint. An existing separate dead-key/mode marker likewise keeps precedence.

Check with the measured ink rectangles, not with string length or the 98 px cell
width alone. The hint must stay inside the key and must not cut the main label
or the marker, including a distance of **POPUP_PAD_DP / 2 = nominally 2 dp**.
For a smaller height, this distance follows the same relative derivation as
above. If that does not suffice: **hide only that key's hint**, do not
shift/shrink the main glyph, paint over no dot, and introduce no new row.
Alternates and long-press continue to work unchanged. In particular, for
strongly shrunken PT rows, this fakes no visibility.

### §7.6 Smallest test/drawing seam

No new stateful or color architecture. A small pure placement function in the
drawing adapter suffices: inputs are the key rect, the row target height, the
actual text size, and the ink/marker rectangles measured by the adapter; output
is the hint rect or "do not draw". It needs neither Android types nor a new
editor path.

| Minimal test | Expectation |
|---|---|
| `hintUsesHalfMainLabel` | 24 sp main label → **12 sp hint**; effective 20 sp → **10 sp**, no clinging to 12 |
| `hintTracksKeyGeometry` | 98 px width/18 px hint ink, density 420 → anchor **78.5 px**; 55-dp target height → top distance **2 dp**, at h=44 dp **1.6 dp** |
| `hintIsLayerExplicit` | Same set digit in base/shift/caps unchanged; null/blank and symbols/symbolsShift → **no hint drawing** |
| `mainAndDeadkeyWinCollision` | Hint cuts a protected ink/marker rect → **hint invisible**, main label/marker/alternates unchanged |
| `hintKeepsExistingPaletteAndSurface` | Light/dark textSecondary as in §7.3; KeySurface result identical before/after hint planning |

Real font metrics/rasterization remain device evidence: one normal/pressed
screenshot each in light/dark as well as base/shift/caps of the topmost DE row
from the same APK; 1…0 on the matching ten keys, no collision at the 650-/829-px
fit and second opening. Do not automatically invent another digit on the
eleventh key. No uiautomator substitute for glyph images, no user texts, and no
new export fields. This assignment only adds §7; no drawing, no model/parser
change, and no build.

## Conclusion

**DECIDED:** 450-ms menu, at most 8 alternates, 48-dp cells/56-dp height in the
reference case, reuse palette/KeySurface unchanged, no new window/height
mechanism. **DELIBERATE EXCEPTIONS:** the top row may open below; cursor arrows
are dropped by user order; umlaut/digit/punctuation aliases are documented
convenience double paths. **OPEN:** later implementation/asset addition, real
held screenshots, narrow windows, and TalkBack acceptance. Do not secretly
loosen the parser/test limit of >8.
**VERIFICATION:** Only sources read and this document written; no app/asset
changes, no build, no emulator/adb action, and no export of user content.
