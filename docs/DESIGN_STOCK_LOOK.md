# D9 – AOSP stock look for Voice Keyboard

## 0. Decision, reference, and limits

**Goal:** flat AOSP LatinIME look in light/dark, not Gboard/Pixel Material You pills.
All current characters and actions remain reachable and visible; status/diagnostics
remains, 🐞/info remains **in the strip above** the character keys. No not-yet-named
"later exception" is pre-empted. No stock cleanup by removing umlauts, cursor keys,
mic, emoji, or a whole row.

Read: current KeyboardView, KeyboardModel, KeyboardHeight, ImeRootView,
EmojiPanelView, service debug button, themes.xml, and the structure of all 15
layout assets. `keyboard/KeyboardTheme.kt` and `res/values/colors.xml` do **not**
exist in the read state. The actual layouts live under
`app/src/main/assets/layouts/`, not `keyboard/assets/`. Only sources were read and
this document created; no build, no adb, no change to the parallel T19.

### Actual AOSP reference

The following original sources were checked by reading under `android16-release`:
base URL **https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/android16-release/java/res/**
- `values/colors.xml:43–78`: LXX_Light/LXX_Dark colors, including press overlays.
- `values/themes-lxx-light.xml:45–55`, `themes-lxx-dark.xml:45–55`: assignment of
  key/function backgrounds and text colors.
- `drawable/btn_keyboard_key_lxx_light.xml:34–43`, corresponding dark file: normal
  keys with solid colors, press state as a color state; function keys corresponding
  selectors.
- `xml/rows_qwerty.xml:27–50`: 10 % character grid, shift 15 %, delete on the right;
  `values/themes-lxx.xml:31–41`: normal typeface and size-dependent typography.
- `values/config-common.xml:100–101`: lateral keyboard padding 0; the LXX popup
  style names horizontalGap 0, but is **no evidence for all main key gaps**.

**Important:** Android 16 alone does not define a universal fixed dp key height for
all screens/OEMs; LatinIME also works with resource variants and size ratios.
Below are **original colors and grid principles**, supplemented by explicitly our
own, binding dp design values for our renderer. This is not an invented
pixel-perfect screenshot replica of an AOSP device not opened here.

## 1. Concrete geometry and typography

All dp numbers are layout/display measures; text is implemented as sp with the same
nominal value at fontScale=1, instead of scaling exclusively with density as before.

| Component | Normal offer / target value | Limited offer |
|---|---|---|
| Top strip with status, existing mic/info actions | **48 dp**, always visible; info touch target 48×48 dp | Do not squeeze to 24 dp as long as interactive 48-dp actions remain there |
| Four key rows | **55 dp per logical row**, including the footer; 220 dp together | Derive all four evenly from the actually remaining budget |
| PT base/shift: five rows | **44 dp per row**, 220 dp together; all accent keys remain | Preserve all five proportionally, no cut-off or dropped fifth row |
| Lateral outer distance / distance top-bottom in the key block | **0 dp / 0 dp** | No additional hidden minimum heights |
| Physical model gaps horizontal / vertical | **0 dp / 0 dp** in the stock style | Unambiguous adjacent logical cells, no overlapping virtual targets |
| Normal key surface | Same color as the keyboard background, no visible individual cards | Press surface within its own cell, no painting over neighbors |
| Radius | **0 dp** normal/pressed character keys; **18 dp** maximum for the deliberately own mic recording indicator | Mic indicator at most `min(36 dp, row height−4 dp)` diameter |
| Letters / digits | **24 sp / 22 sp** | Lower to 18 sp in tight character columns, do not cut or hide characters |
| Function words ?123/ABC / space / status | **14 sp / 12 sp / 12 sp** | Condense long space words to ␣ only per the existing SPACE_LABEL decision; no "space" hardcode |
| Normal icons | Backspace, enter, shift, mic: **24×24 dp**, monochrome; stroke width **2 dp** | Centered within each measured cell, no emoji font bounding box as the position basis |
| Prominent footer mic | Icon **26 dp**; accent/recording indicator, do not enlarge by removing the enter space | Limit the size to the real cell; no new 96-dp width claim in this stock assignment |
| Elevation / shadow | **0 dp / none**, no text shadow either | No artificial relief edges or system-dependent button shadows |
| Dividers | None between letter keys; **1 dp** below the diagnostics strip | The only fixed line, consistent in the emoji view too |

This makes the vertical target sum **48 + 220 = 268 dp without navigation**;
with nominally 8 dp navigation **276 dp**. That is a total root sum, not the height
that KeyboardView may additionally claim on top of the strip. 55 dp is the logical
key height without the previous 6-dp gap, not a 55+6-dp row.

### Width ratios and actual layouts

- EN reference: row 1 ten cells at **10 %**; row 2 nine at 10 % with **5 %** margin
  left/right; last character row shift **15 %**, seven letters at 10 %, delete
  **15 %**.
- This indentation applies only with a matching existing structure: second pure
  character row shorter than the first → centered in the same base grid; do not
  delete any text key.
- DE eleven characters → **9.09 %** instead of 10 %; FR/IT/CZ twelve → **8.33 %** in
  pure twelve-key rows. Generally share `key.width / sum(row.width)`; existing
  special weights and case positions remain. A larger row is fitted completely,
  not trimmed.
- Current footer at weight sum 13: ?123/ABC and enter each **11.54 %**,
  space **38.46 %**, 🌐/😊/‹/›/mic each **7.69 %**; none of that disappears for a
  supposedly "cleaner" stock look. Rendering without visible boxes makes them
  appear calmer.
- Strict **48×48 dp for every key** remains impossible with these character sets at
  smartphone width. D9 does **not** claim this earlier hard requirement as
  fulfilled: for example, nine or eleven narrow letter cells are not 48-dp squares.
  The action strip/info keeps its 48-dp target; the text keys follow the stock
  grid. If the earlier square lower bound remains binding unchanged, this is an
  open product conflict, not solvable by a theme.

### Colors – ARGB, light/dark

Normal text/function surfaces are flat, not tiled white/light gray against each
other. Press colors with alpha are **overlays over the previously filled keyboard
surface**, not a sole transparent window color; no target app content shows
through.

| Token/State | Light | Dark | Origin/use |
|---|---|---|---|
| Keyboard/status background | **#FFECEFF1** | **#FF263238** | AOSP key_background_lxx_*; fill root and canvas opaquely |
| Normal key fill | **#FFECEFF1** | **#FF263238** | Identical to the background; no card look |
| Pressed, normal/functional target | **#2637474F** overlay | **#19FFFFFF** overlay | AOSP key_background_pressed_lxx_* |
| Normal letter/digit text | **#FF37474F** | **#CCFFFFFF** | AOSP key_text_color_lxx_* |
| Function glyphs/text, not active | **#CC37474F** | **#CCFFFFFF** | AOSP key_functional_text_color_* |
| Accent/indicator surface | **#FF4DB6AC** | **#FF80CBC4** | AOSP highlight_color_lxx_* |
| Toggled: shift/caps glyph | **#FF00796B** | **#FF80CBC4** | Our own darker light variant for legibility; no green whole key |
| Toggled: fill | Like normal; press overlay only while pressed | Like normal; press overlay only while pressed | Caps additionally with a 2-dp marker, not a mere color change |
| Recording mic: surface / glyph | **#FF4DB6AC / #FF263238** | **#FF80CBC4 / #FF263238** | Our own recording semantics with the AOSP accent, no evidenced AOSP ASR state |
| Secondary status/space text | **#B337474F** | **#B3FFFFFF** | AOSP secondary text/space colors |
| Disabled functional target | **#6137474F** | **#61FFFFFF** | Our own disabled gradation, not for error message text |
| Divider | **#2637474F** | **#26FFFFFF** | Our own subtle 1-dp separator |
| Error state | **#FFB3261E** | **#FFFFB4AB** | Our own clearly recognizable error color, not the AOSP LXX source value |

Pressing does not change the text color; toggled stays visible until the real state
change, not until the next frame. Color states: normal → pressed → normal and
normal → toggled; pressed+toggled keeps the active glyph over the press overlay.
The system light/dark decision governs the palette; no theme guessed from the
target app and no cloud/dynamic color dependency. Dark must also apply to
root/emoji/native buttons, not only to the letters.

Monochrome icons replace only the **display** of the existing actions: ⌫, ⏎,
shift arrow with caps marker, and microphone as consistent silhouettes/stroke
paths; globe/emoji function also monochrome, not as colorful font emoji. 🐞/info
may keep its recognizable diagnostics meaning, a 22-dp icon in a 48-dp cell.
No action is renamed or removed. Native button elevation/tints are explicitly
bound to the palette, not taken over from the always-light activity theme.

Space shows the localized asset word subtly centered at 12 sp; it suggests
neither speech recognition nor autocorrection. No new language label in place of
the existing space label. With the same layer/width, the labeling stays the same.

## 2. Delta against the read actual state

Effort = rough **changed/newly written lines**, not measured working time;
values overlap, so do not add them blindly. Line anchors apply before parallel
changes.

| Current → target | Location / file:line to change | Effort |
|---|---|---|
| keyHeight 52 dp + rowGap 6 dp → actual budget/all rows; nominally 55 dp for four, 44 dp for five rows, gap 0 | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:42–44,108–120,135–163`; `keyboard/KeyboardModel.kt:32–67` | approx. 35–65 |
| keyGap 4 dp, padding 0 → gap 0, optional stock indentation only for a short character row | `keyboard/KeyboardView.kt:44`; `keyboard/KeyboardModel.kt:14–16,37–65` | approx. 15–30 |
| Radius 6 dp, drawn individual tiles → flat normal surface/radius 0 | `keyboard/KeyboardView.kt:45,195–205` | approx. 10–20 |
| DKGRAY / text 20×density → light/dark tokens, 24/22/14/12 sp per key type | `keyboard/KeyboardView.kt:65–70,205–210` | approx. 25–45 |
| Press/toggle together #FF4CAF50, functional #FFE0E0E0, normal #FFF5F5F5 → separate source palettes and press/toggle states | `keyboard/KeyboardView.kt:197–205`; **newly proposed** `keyboard/KeyboardTheme.kt` | approx. 70–110 incl. tokens |
| ⌫/⏎/⇧/🎤 partly text/emoji font → uniform 24-dp glyphs; keep semantic labels | `keyboard/KeyboardView.kt:214–234` plus character paths in the same renderer | approx. 40–70 |
| SPACE already follows SpaceLabelRule → preserve, style secondary/12 sp, no German override | `keyboard/KeyboardView.kt:222–227`; labels in `app/src/main/assets/layouts/*.json` | approx. 5–12, **0 asset lines** |
| Root without a uniform palette; StatusText 13 sp/28 dp, actual strip through debug target 48 dp → explicitly 48 dp, 12 sp, opaque background/1-dp line | `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt:48–69,105–120` | approx. 20–40 |
| Debug button is appended in the service at 48×48 dp on the right → leave it there, theme/state instead of the standard button relief | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:350–368` | approx. 10–20 |
| T19 determines the grid height and hides last rows → fit the whole existing panel into the budget, no key loss | `keyboard/KeyboardHeight.kt:15–29,47–75`; `keyboard/KeyboardView.kt:157,182–190,422` | approx. 35–65, **coordinate with T19 first** |
| Emoji #FFFAFAFA, line #FFC0C0C0, text DKGRAY → same palette/separator/navigation | `keyboard/EmojiPanelView.kt:48–60,145–180`; native footer `ime/ImeRootView.kt:87–99` | approx. 15–30 |
| 15 raw layouts with 4 layers, PT base/shift 5 rows → data unchanged, only evaluate display/geometry | `app/src/main/assets/layouts/`: all 15 files; `keyboard/KeyboardModel.kt:41–65` | **0 assets / 0 JSON lines** |
| `colors.xml`/`KeyboardTheme.kt` missing; activity theme always Material.Light → renderer palette itself by night mode; no necessary setup theme rework | `app/src/main/res/values/themes.xml:3`; new theme file instead of "changing" a non-existent colors.xml | approx. 50–80 in the new token contract, see above |

The prefix `keyboard/...` in this table = `app/src/main/java/de/voicekeyboard/keyboard/...`.
No invented existing theme field/API names are presupposed.

## 3. Status row, info, and fluctuating height offers

**Decision: the top strip stays 48 dp visible always.** Not "only when needed",
not suddenly GONE on the second opening. Text in it at most two 14-dp lines at
12 sp, vertically centered; status/diagnostics on the left, existing controls
including 🐞/info on the right. Errors replace the status, remain per R-2, and are
not reset to "ready" by a redraw/theme switch.

A 24-dp text band would be visually calm, but a 48-dp info button arranged in it
would pull the real strip to 48 dp anyway or cover neighboring rows. That is why
the actual total height is computed, not the 28-dp TextView confused with the
parent height. No invisible overlapping touch rectangles over Q.

### A single height budget – integration contract for T19

Density 420 dpi corresponds to **2.625 px/dp**. N = the navigation inset actually
belonging to the root, not always 8 dp; 8 dp is only the nominal worked example.
Total root target = 268 dp + N. Root offered height H; usable key block =
`min(H, 268 dp + N) − N − 48 dp`. All existing R rows share this remainder.

| Root offer | Example N | Effective root / key block | Four rows / PT five rows |
|---|---|---|---|
| **829 px = 315.81 dp** | 8 dp = 21 px | Cap 276 dp = 724.5 px, integer at most about 725 px; keys 220 dp | **55 dp / 44 dp** |
| **650 px = 247.62 dp** | 8 dp = 21 px | No enforcing of 276 dp; keys = (650−21−126)/2.625 = **191.62 dp** | **47.90 dp / 38.32 dp**, all rows remain |
| **650 px**, if navigation lies outside the offer | N is **not deducted again** here | keys = (650−126)/2.625 = **199.62 dp** | **49.90 dp / 39.92 dp** |

**ASSUMPTION:** The handed-over requested heights are a root/window offer. If they
actually denote the child MeasureSpec size of KeyboardView already reduced by
status/insets, this table does **not apply directly**: the child already receives
its budget and may not deduct anything again. T19 must log the level, spec mode,
and current insets in the geometry proof; root and KeyboardView may not both
deduct status and navigation "to be safe".

48 dp corresponds to 126 px; **276 dp corresponds to 724.5 px, not 727 px**.
Rounding remainders are distributed once over the rows; the last lower edge is
exactly the content end. EXACTLY/AT_MOST are respected; no minimum row floor
above the offer. Drawing, VK-HIT, accessibility, and real visible cells use the
same rects. Merely reporting a smaller view while keeping the old 52-dp rects is
not a fix.

**The current T19 contract is not yet D9-conformant:** KeyboardHeight documents the
complete removal of a non-fitting row; `visibleKeys()`/`keyAtVisible()` implement
that. "Completely gone instead of half gone" likewise violates **all keys remain
preserved** — precisely space/mic/enter. The fitting requirement now reads: **fit
all rows into the current space anew, never remove an existing action row.** The
T19 coder must make the change; this document assignment does not edit his file.

For even smaller offers/large fonts: do not invent a successful fit. Missing
legibility/operability is to be reported as such a boundary case; neither cut
glyphs nor secretly introduce unknown new paging modes. The 650/829-px cases
above are concrete mandatory cases, not a general proof for all display
configurations.

## 4. At most four micro assignments, test-first

Shared budget: **at most two APK builds in total**, no builds in this design
assignment. First integrate all related changes/testable contracts, then build 1
and device acceptance; build 2 only for a bundled correction. No additional
cosmetic third build. T19 owns the height files until handoff. New file/test
names below are proposals, not already-existing APIs.

| Order | At most four files | Test first, then change |
|---|---|---|
| **S1 – conclude the T19 height contract** | `keyboard/KeyboardHeight.kt`; existing `app/src/test/java/de/voicekeyboard/keyboard/KeyboardHeightTest.kt`; `keyboard/KeyboardModel.kt`; existing `app/src/test/java/de/voicekeyboard/keyboard/KeyboardModelTest.kt` | Parameterized 650/829 px, 4/5 rows, status only once, navigation only once: no target dropped, no lower edge exceeds its budget; the wrong old row drop must go red. Generate complete rects from the budget. |
| **S2 – measurement integration and second opening** | `ime/ImeRootView.kt`; `keyboard/KeyboardView.kt`; **new** `app/src/androidTest/java/de/voicekeyboard/keyboard/StockKeyboardFitTest.kt` | Open twice/field switch/emoji return, DE and PT: all previous IDs fully drawn, hittable, and visible in the parent; 48-dp strip remains, no cumulative deduction. The indentation may not shift/lose any ID. |
| **S3 – palette, typography, and glyphs** | **new** `keyboard/KeyboardTheme.kt`; `keyboard/KeyboardView.kt`; `keyboard/EmojiPanelView.kt`; **new** `app/src/test/java/de/voicekeyboard/keyboard/KeyboardThemeTest.kt` | Light/dark token matrix and press≠toggle first; then integrate AOSP colors, flat surfaces, the 24/22/14/12-sp hierarchy, and monochrome paths. Do not declare images passed by mere token tests. |
| **S4 – strip and complete visibility acceptance** | `ime/ImeRootView.kt`; `ime/VoiceKeyboardService.kt`; `app/src/main/res/values/strings.xml`; **new** `app/src/androidTest/java/de/voicekeyboard/keyboard/StockStatusBarTest.kt` | Status/error persistent, info 48×48 dp above the keys, no theme switch reset, native mic/back buttons without foreign relief; before/after palette the same action count. Then screenshots light/dark, normal/pressed/caps, second opening, and emoji from exactly the same APK. |

`keyboard/` and `ime/` are each to be read under `app/src/main/java/de/voicekeyboard/`.
**None of the four assignments changes a layout asset: target 0 of 15.** No UI
repair via arbitrary JSON deletions, backspace moves, or new language variants in
the same build. The two existing JVM test paths were checked against the project by
reading.

## 5. Conflicts and binding recommendations

| Conflict | Recommendation |
|---|---|
| AOSP minimalism vs. all current actions | Preservation wins: cursor, emoji, globe, ?123/ABC, space, backspace, enter, and mic remain; no unpublished "exception" budget. Stock colors/hierarchy instead of deletion. |
| Lower-right mic wish vs. current enter bottom right | The read raw assets have mic **before** enter, not as the very last key. D9 is a look/fit assignment and does not authorize this additional position rework; both stay at their actual location for now. "Mic far right" needs a separate, unambiguous reflow contract while preserving all actions. |
| Two mic presentations, top and in the footer | No silent removal of a current key; theme the same recording states, add no third mic. A later explicitly named exception may simplify this double path. |
| 🐞/info above the keys vs. the earlier D-LAYOUT recommendation next to Q | **The newer user decision wins:** top strip; no debug target in the letter row. |
| Permanent diagnostics vs. stock without a diagnostics tool | Permanent 48-dp strip in the same background, small typography, 1-dp divider; do not disguise it as a suggestion/autocorrect strip. |
| Emoji V2 vs. stock emoji layer | Stack replacement mode and native "back to keyboard" return path remain; align colors/icons, no return to the overlay. No height fit by dropping the back button. |
| 15 languages/umlauts vs. rigid 10 % EN grid | Local letters and mandatory characters remain their own keys; respect the actual weights/row count, indent only matching short rows. PT stays five-row. |
| 48-dp squares vs. dense smartphone panel | Do not claim as fulfilled. D9 prioritizes the explicitly demanded complete stock grid inventory; before a binding square promise, the old geometric conflict remains separately open. |
| T19 "row dropped" vs. all keys preserved | Row drop is **not an accepted fix**; correct the T19 hand-off before the stock release after S1/S2. |
| Stock pixel perfection vs. special error/recording states | Our own clearly marked state colors/markers are deliberate extensions; keep original source colors and own tokens separate. |

## Conclusion

**APPROVED:** AOSP LatinIME LXX color/grid direction, concrete light/dark tokens,
flat look, constant diagnostics/info strip, and 0 asset changes as the
implementation target.
**NOT APPROVED:** a build that at 650 px removes the bottom row completely instead
of half, lets diagnostics disappear, or deletes a current key for stock aesthetics.
**OPEN:** T19 integration with the correct root/child budget level and subsequent
light/dark/second-opening screenshots on the device; real 48-dp squares are not
proven.
**VERIFICATION:** Only a reading check of local/original AOSP sources and this new
document; no builds, no adb, no parallel source/asset files changed.

## D11 — Pressed key feedback (decided 2026-10-30)

This addendum replaces the weak press opacity and the pure caps glyph feedback
from §1; otherwise palette, geometry, and interaction logic remain unchanged.
The handed-over emulator finding (27/255 light, 21/255 dark) carries the
decision; no new screenshots were measured and no Kotlin files changed here.

| Point | Binding decision | Rationale |
|---|---|---|
| **1. Pressed surface** | **Yes, clearly visible:** light alpha **0x66** (40 %), overlay **#6637474F** over **#FFECEFF1**; dark alpha **0x4D** (about 30 %), overlay **#4DFFFFFF** over **#FF263238**. Continue SrcOver composition to an opaque surface; existing text colors remain. | The same existing RGB colors, just higher opacity: this improves the recognizable press reaction without leaving the flat stock look through frames, shadows, rounding, or new color families. |
| **2. Letter popup** | **Deliberately no popup** in this change scope, and no forced uppercase preview either. | The stronger key press state delivers the immediate feedback; a popup of the topmost row could cover the diagnostics/info strip and would require additional positioning/clipping logic whose safety is not evidenced with our fluctuating heights. |
| **3. Caps state** | **The current pure glyph coloring is not enough:** with **capsLock** actually latched, the shift key surface is permanently filled with the existing **accent**: light **#FF4DB6AC**, dark **#FF80CBC4**; plus the already existing contrasting **micRecordingGlyph** color, light **#FF37474F**, dark **#FF263238**. The **⇪** caps marker remains. | A filled existing rectangular surface with a contrasting glyph clearly distinguishes caps from the brief shift state; existing accent/counter colors simultaneously prevent accent-on-accent, without a new arrow path, custom shape, or additional colors. |

The press alphas are a **deliberate project-specific reinforcement** of the
previously evidenced AOSP LXX values 0x26/0x19, not a claim that AOSP Android 16
uses these new values exactly. The Gboard comparison is a perceptibility
reference, not a switch of our visual reference product.

The caps fill is derived exclusively from the real caps state, not from "the last
input was uppercase": shift+q→A alone proves no latched capsLock. Temporary shift
stays accented as before; symbolsShift is not a caps state. Pressed+caps keeps
this accent fill with the counter color, without an additional overlay
application; after release it remains with caps, after deactivation it
disappears. No change to double tap, input text, touch areas, or the mic
recording priority.

**Acceptance for GLM:** compare light/dark normal/pressed and caps on/off from the
same APK: a clearly visible solid press reaction without frame/shadow, no popup
over the strip, the caps fill remains after UP and disappears after switching off.
No entered content in diagnostics/log texts. Keep the implementation small: two
overlay alphas and state-dependent reuse of existing accent/counter colors; no
new theme/window/layout mechanism. Device evidence is still outstanding.
