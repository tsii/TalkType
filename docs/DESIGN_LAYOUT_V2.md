# D-LAYOUT-V2 – smaller keyboard, action positions, and a safe height chain

**Status: partial release of the design; no unrestricted geometry sign-off.**
The vertical target geometry and the four desired action positions are decided.
The additional hard requirement **every touch area at least 48 × 48 dp** is not
reconcilable with all existing characters at the same time on a smartphone
portrait side. This is calculated below, not papered over with overlapping hit
areas or invented device widths. No coder should deliver such a contradiction as
"48 dp satisfied".

Only this document is being created. No build, no adb, no source/asset change.
Read: PLAN_AUDIO_UND_LAYOUT §2, LAYOUT_REQUIREMENTS in full, DESIGN §7,
KeyboardView, ImeRootView, KeyboardModel, plus the structure of all 15 JSON
assets. Line numbers refer to the read state, not to later parallel changes.

## 1. Cut-off on the second opening: finding instead of invented certainty

### Verifiable height chain

| Point | Read finding | Consequence |
|---|---|---|
| `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:135–147` | `heightMeasureSpec` is not evaluated; height always `max(240dp, model.heightPx)`. | The view can report more height than the current IME window offers; content lying below is then cuttable. **That is the concrete contract-violating spot.** |
| `KeyboardView.kt:141,146` | The rebuild guard compares `lastMeasuredHeight` with the previous own `measuredHeight`, not with the newly offered space. | A changed parent budget on reopening does not reliably trigger a rebuild through it. |
| `KeyboardView.kt:108–120,156–160` | The model receives constant 52-dp keys and a 6-dp row gap; `onSizeChanged(w,h)` rebuilds only with w. | Even a completed callback does not adapt the geometry to a smaller height offer; "rebuilding more often" alone is not enough. |
| `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt:53,65–76,105–112` | Status content 28 dp; vertical stack with a WRAP_CONTENT keyboard and an alternative emoji block; root is a **FrameLayout**, not itself a LinearLayout. | The parent budget must account for status and currently visible content together; Kimi describes the stack here, not exactly the root type. |
| `ImeRootView.kt:116–120` | Navigation insets are set as absolute bottom root padding. | The space requirement exists additionally; the read assignment does **not** repeatedly add to paddingBottom. Double accounting in the parent window remains a hypothesis to be checked. |
| `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:240–243` | `onStartInputView` only calls `updateStatus()`. | No explicit new visibility/space reconciliation is triggered here; normal Android measurements can still take place. |

**Why only the second time? ASSUMPTION:** The first visible window offers
sufficient space; on reopening, the available budget or the inset accounting is
different. The view ignores this difference and stays content-sized. That
explains the pattern, but without measuring both constructions it proves neither
the triggering system state nor "exactly half a row". An unchanged parent budget
would not suddenly become smaller through the guard alone; this restriction is
important.

Not found: a `setKeyboardView` size summation, cumulative padding, or a
StatusRow VISIBLE/GONE switch in the read path. These causes are therefore
**not** claimed as proven. Kimi §2.1 is a plausible working diagnosis, not an
already-delivered second-opening measurement test.

### Binding requirement

**"The height is redetermined from the current state every time it becomes
visible."** This includes the current available width/height, actual insets,
status visibility, active layer/row count, and keyboard/emoji mode. No value
from the first opening is the solely valid source; no old rect set may survive
after a changed offer.

- The root decides **once** which space remains for the visible content child
  after status and correctly accounted navigation; no double inset deduction.
- The keyboard measurement respects EXACTLY/AT_MOST; model height, view height,
  and visible clip bounds become consistent. Just `resolveSize` without a
  matching model construction would be wrong.
- Rebuild keys are the actual width, content budget, layer/rows, and density;
  no self-comparison with a previous own measurement.
- Insets, mode switches, field switches, and becoming visible again trigger a
  new layout reconciliation; no LayoutParams margin arithmetic in `onLayout`.
- If the area does not suffice for the minimum targets, that is an explicit fit
  conflict; do not secretly scale below 48 dp and do not clip at the bottom. No
  artificial "success" layout as long as the last key is not completely inside
  the visible area.

## 2. Size decision: current values and vertical target

The read actual value is **52 dp**, not 62 dp: `KeyboardView.kt:42–44`.
Four rows yield 4×52 + 3×6 = 226 dp model content, but **240 dp view height**
due to the minimum; plus nominally 28 dp status = **268 dp** without system
navigation. PT base/shift has five rows: 5×52 + 4×6 = 284 dp + 28 = **312 dp**.

**New target:** normal key height **48 dp**, footer with mic **52 dp**,
row gaps **2 dp**, non-interactive status row nominally **24 dp**.
Touch area and drawing keep the same geometry; no 44-dp rows with merely claimed
48-dp targets. 48 dp is the required lower bound and the minimum for thumb
operation; the frequently held mic key is deliberately somewhat taller/wider.

| Case | Key content | With 24-dp status, without system navigation | Change |
|---|---|---|---|
| Four rows | 3×48 + 52 + 3×2 = **202 dp** | **226 dp** | **268 → 226 dp**, 42 dp or about 16 % less |
| Five rows, PT base/shift | 4×48 + 52 + 4×2 = **252 dp** | **276 dp** | **312 → 276 dp**, 36 dp or about 12 % less |

Navigation is considered separately on both sides with the same method; a
dropped inset does not count as a keyboard reduction. Large system fonts can
make the status row need more height; then report the actual height, do not cut
text. The target values are design measures, not yet a pixel device measurement.
Concept fidelity: PTT hold/release, idempotency, and decoupled recording remain
unchanged; the smaller display changes no ASR/commit rule.

## 3. Rows and widths – hard feasibility limit

W denotes the **actually usable width in dp**, after side insets. Fixed targets
at least 48 dp wide; remaining character keys distributed evenly, but likewise
never below 48 dp. Percentages are therefore derived from W, not asserted as a
guessed "width 1.5" without a relation to the display width.

| Row | Sequence / content | Width specification in % of W | Height |
|---|---|---|---|
| Topmost character row | Share, protection gap, previous first character row complete, backspace | Share and backspace each `100×48/W %`; protection gap `100×8/W %`; characters divide the remainder after the other 2-dp gaps | 48 dp |
| Second character row | Previous characters complete, no new backspace in the middle of the block | Equal shares of the available row remainder, each at least `100×48/W %` | 48 dp |
| Last character row before the footer | Shift, previous characters, **enter at the previous backspace position** | Shift/enter each at least `100×48/W %`; characters divide the remainder | 48 dp |
| Additional PT accent row | Preserved, including its letters; shift/enter on the last character row | Same minimum rule, remove no accents | 48 dp |
| Footer | ?123/ABC, 🌐, 😊, space, ‹, ›, **mic at the far right** | The five small actions each `100×48/W %`; mic `100×96/W %`; space `100×(W−348)/W %`; six gaps of 2 dp each | 52 dp |

Footer: 5×48 + 96 + 6×2 = 348 dp without space; space gets the remainder and
needs at least 48 dp. **Minimum footer width 396 dp.** Worked example W=411 dp:
small actions each 11.68 %, mic 23.36 %, space 15.33 %, gaps together 2.92 %.
That keeps the space key reachable but deliberately narrower than before; for
long words the existing SPACE label compaction rule applies instead of cut-off
labeling.

### The width conflict that cannot be drawn away

For DE, the first row contains eleven letters. With share and backspace, that is
13 targets: **13×48 + 8 + 11×2 = 654 dp minimum width** including the
protection gap. CZ has **13 letters** in the last character row plus shift and
enter: **15×48 + 14×2 = 748 dp**. That real asset is the hardest row considered
here. Even without the new actions, eleven adjacent 48-dp targets on a roughly
411-dp-wide smartphone panel are impossible. Overlapping virtual targets do not
solve that: they cannot simultaneously lie unambiguously within their own
48-dp widths.

**ASSUMPTION:** The Pixel portrait lies below this 748 dp usable width; its
current dp measures were not measured here. The earlier emulator values around
411 dp are not being reported as a new pixel measurement. If the width is smaller
than the row minimum, the above single-side arrangement is **not fully
released**.

**Decision:** I do not sign off a seemingly finished pixel design with
undercut 48×48 targets. The vertical correction can be implemented
independently; before the complete reflow, a product decision on **more
character sides/a different row distribution** or an explicitly relaxed minimum
width is needed. Since the minimum size is hard here, it is not relaxed on my
own authority. Likewise, a multi-page letter keyboard is not introduced
unasked as a supposed small layout correction. No follow-up question in this
assignment; the conflict is a documented geometry blocker, not a missing tool
release.

## 4. Four new action positions and interaction rules

- **Backspace:** outermost right key of the **first** character row, not "row 2"
  from Kimi's proposal if that means the second character row; the status does
  not count as a key row. Hold/repeat and grapheme-safe deletion are preserved.
- **Enter:** outermost right key of the last character row before space; in
  DE/EN the previous third, in PT base/shift the previous fourth character row.
  The editor action/line break remains semantically unchanged. No additional
  enter at the bottom.
- **Mic:** far right in the footer, **96×52 dp target size**, filled accent
  surface, large microphone pictogram; IDLE clearly distinguished, STARTING
  visible waiting, RECORDING filled plus an unmistakable "recording" indicator,
  FINALIZING as processing/locked restart. Color alone is not a state proof.
- **Share:** left before the first character, in DE/EN directly left of Q, in
  FR left of A, RU left of й — no artificial Q key for other alphabets. Icon
  idea: a neutral share symbol, German meaning **"share debug info"**; compact
  visible legend **"Info"**, full accessibility name.

### Exactly one PTT area

The previous small mic button in the status row is dropped; the old footer mic
is moved to the new far-right position and not duplicated. The status remains as
non-interactive state text and does not disappear unpredictably when idle.

**Important real finding:** The status mic has a DOWN/UP touch listener today
(`VoiceKeyboardService.kt:208ff`); the keyboard mic, by contrast, goes through a
normal key release (`KeyboardView.kt:225ff`) to the mic action branch
(`VoiceKeyboardService.kt:575ff`). Merely moving the icon would therefore not
reliably move PTT. The new single area must deliver DOWN→start and
UP/CANCEL→orderly stop/abort; never start only on release or wait for a second
toggle. New gesture/state contracts are interfaces to be implemented, not
claimed existing methods. The main thread stays free, no waiting for inference.

Emoji-V2 remains a complete alternative content mode with a native back button.
When emoji is open, there is no hidden mic hit area of the GONE keyboard; for
recording, first "back to keyboard". No second status mic as a second exit.

### Not accidentally triggering share

48×48-dp target, **8 dp non-interactive protection gap** to the first letter;
the existing nearest-key logic must not fill this gap as a share hit. A short
tap only shows the hint "Hold Info to share debug info"; a long press from
**600 ms** opens the deliberate choice **"Copy" / "Share"**. The subsequent UP
gesture must not generate a further click/character entry. During
recording/finalization no external share action; normal typing remains
possible.

Only the limited technical debug snapshot from PLAN §3; no field text, audio,
logcat, stack trace, model path, or account/device identifiers such as a serial
number. Only the chosen action copies or opens the Android share chooser; no
automatic clipboard. An explicit external share can change the editor focus —
unlike emoji, no unchanged InputConnection is guaranteed across the activity
switch for it. The app itself sends nothing to the network; Signal or similar
is the user's decision.

## 5. Shared definition instead of 15 repetitive asset reworks

**Recommendation: a shared action-frame composition; 0 changes to the 15
language JSONs.** The 15 files were read; base/shift have the same structure, PT
base/shift have five instead of four rows, CZ/PL/RU clearly more characters in
individual rows. All four layers contain their previous action frame; this data
remains untouched for the existing parser and the language/mandatory-character
checks.

A **to-be-newly-implemented**, pure shared composition takes over the parsed
layer: language characters including IDs, order, alternates, case metadata, and
localized space label are preserved; old action positions are dissolved and the
shared action arrangement described above is added exactly once. The renderer
and geometry tests use exclusively this result, not an old action frame that is
still in effect at the same time. `sourceKeys`, touch dispatch, and accessibility
nodes must also use the same composed source: a merely drawn share/mic key
without a matching action record is an error. No second drawn footer/mic layer
and no transparent overlay touches.

| Contract | Preservation / necessary adjustment |
|---|---|
| 15 assets × 4 layers | All **60 effective layers** must have new positions; the raw JSONs remain byte-identical. Tests explicitly distinguish raw data completeness and composed display. |
| VK-HIT | Continue real local rects and touch coordinates of the same model as onDraw; additionally log parent/visible bounds and a build number for the height proof, no field contents. Old screenshots/rects do not count as a V2 proof. |
| Shift/Caps | Same state logic, same key/ID; no reset on relayout; second tap remains caps, symbols↔symbolsShift remains a toggle. |
| Rule 4a / symbols | Raw asset check remains; the effective shift key continues to sit left in the last character row, **no** new wrong layer-base action. ABC stays left in the footer. Supplement documentarily fixed row indices with a semantic position. |
| Character/case checks | Unchanged: delete no letters, digits, or mandatory symbols as a space gain or move them into unreachable alternates. |
| New action | `share-debug` is **new**; to be added in `layouts/LayoutAsset.kt`/KeyActions whitelist and the parser contract, not to be presented as an already existing API. |
| Emoji | The V2 composition remains without overlay; return fully restores the currently measured keyboard, including the PT five-row case. |

A demand "the position must be changed literally in all 15 raw JSONs" would be
incompatible with the shared composition and unnecessary. What is professionally
relevant is all 15 **rendered** layouts. If the shared composition is waived,
15 assets/60 layers would instead have to be changed; do not mix both
strategies.

## 6. Rejected

- 44-dp rows from PLAN §2.2: fall below the hard touch lower bound here.
- `min(content, space)` without new rects: only papers over the measurement,
  keeps cutting off keys.
- "Repairing" the second construction with ever larger fixed heights or negative
  insets: hides the measurement contract error and can inflate other
  windows/emoji again.
- Keeping mic bottom right and enter next to it at the old footer location:
  contradicts the desired enter move and needlessly takes space from the
  prominent recording area.
- Share-debug without a protection gap/with a short tap directly opening
  externally: too error-prone next to Q.
- Blanket four-row assumption: PT and dense CZ/PL/RU data refute it.
- 15 copied action frame changes: avoidable drift; shared composition preferred.
- Unannounced paging or overlapping 48-dp hitboxes: no honest fulfillment of
  the simultaneously demanded compact single-side keyboard.

## 7. TDD assignments – order, at most four files each

All new file names below are **proposals**, not claimed existing files.
Source paths with short prefixes: `main` = `app/src/main/java/de/voicekeyboard/`,
`test` = `app/src/test/java/de/voicekeyboard/`, `androidTest` analogous.
Tests first; successful JVM tests do not replace a pixel/second-opening
acceptance.

| Assignment | Files, at most 4 | First red test / check criterion |
|---|---|---|
| **G1 height contract, independent of the width reflow** | `main/ime/ImeRootView.kt`; `main/keyboard/KeyboardView.kt`; `main/keyboard/KeyboardModel.kt`; **new** `androidTest/keyboard/KeyboardFitTest.kt` | **secondShowKeepsFullFooter**: show→hide→show the same IME instance, vary the current offer/insets; the bottom rect's lower edge lies completely inside the view **and** the visible parent. Also check DE and PT, status/emoji switches; pixel screenshots of both constructions are mandatory. |
| **G2 shared composition and positions** | **new** `main/keyboard/LayoutChrome.kt`; **new** `test/keyboard/LayoutChromeTest.kt`; `main/layouts/LayoutAsset.kt`; `docs/LAYOUT_REQUIREMENTS.md` | **all15EffectiveLayoutsHaveNewAnchors** across all 60 layers: share as the first target, backspace top right, enter in the last character row right, mic in the footer right exactly once; same character sets/shift pairs as before. New share-debug action permissible. |
| **G3 size feasibility, before renderer release** | `main/keyboard/LayoutChrome.kt`; `main/keyboard/KeyboardModel.kt`; `test/keyboard/KeyboardModelTest.kt`; **new** `test/keyboard/KeyboardTargetSizeTest.kt` | **everyTargetIs48By48OrReportsUnfit**: check all 60 layers at a real W; targets ≥48×48 and non-overlapping, otherwise explicit unfit. The 411-dp/CZ case must currently yield **unfit**, not "green by calculating small". **Before a complete reflow, the width decision is mandatory.** |
| **G4 rendering, action protection, and states** | `main/keyboard/KeyboardView.kt`; `main/keyboard/LayoutChrome.kt`; `main/ime/ImeRootView.kt`; **new** `androidTest/keyboard/KeyboardInteractionV2Test.kt` | **drawnAndHitRectsAgree** including the share protection gap; **shiftAndSymbolRoundTripSurvivesRelayout**; **micShowsRecordingState**: mic rect 96×52, recording fill/status instead of only a generic press color. Still no parallel second PTT area. |
| **G5 the only real PTT entry** | `main/keyboard/KeyboardView.kt`; `main/ime/VoiceKeyboardService.kt`; `main/ime/ImeRootView.kt`; **new** `androidTest/keyboard/MicGestureV2Test.kt` | **holdStartsReleaseStopsOnce**: DOWN starts exactly once, UP/CANCEL stops exactly once; repeat/relayout/hide without a second session; recording/completion independent of inference load. |
| **G6 private debug snapshot** | **new** `main/debug/DebugInfo.kt`; **new** `test/debug/DebugInfoPrivacyTest.kt` | **snapshotContainsNoInput**: at most 25 lines, only permitted technical fields/codes, no dictated or typed content, no raw logs/paths. |
| **G7 deliberate copy/share** | `main/ime/VoiceKeyboardService.kt`; **new** `main/debug/DebugShare.kt`; `app/src/main/res/values/strings.xml`; **new** `androidTest/debug/DebugShareTest.kt` | **shortTapDoesNotExport** and **confirmedShareUsesTextPlain**: short tap no clipboard/chooser; long press plus a deliberate choice generates exactly one action; no export during dictation, no character in the field. |

After each integrated layout state: check second/third opening, field switch,
emoji return, DE/EN/RU/PT/CZ, both symbol layers, gesture/3-button navigation,
and large fonts. Visibility needs a screenshot plus a rect/clip proof;
uiautomator alone does not see the IME reliably. No device commands are executed
in this assignment.

## 8. Sign-off and open limit

**DONE:** Cause localized as a concrete measurement contract error; the
second-opening trigger named as a hypothesis instead of an invented measurement.
Target height and four positions decided, 0 raw asset changes recommended
through a shared action frame, TDD assignments limited.

**APPROVED:** Height chain correction and vertical target 268→226 dp / PT
312→276 dp; backspace top right, enter at the old backspace location, mic bottom
right, info left before Q or the local first letter. This partial release
permits no unchecked delivery.

**BLOCKER:** A smartphone single side with all characters unchanged and real
48×48-dp targets is impossible below the calculated minimum widths. No overall
release of the pixel geometry before an openly documented resolution of this
product conflict; the keyboard must not become artificially wider just for the
test.

**ASSUMPTION:** The current Pixel space budget/second insets are not measured.
New positions/minimum widths are design specifications, not already-tested
rects.
**VERIFICATION:** Only a reading check and this new document; no build/adb/commit.
