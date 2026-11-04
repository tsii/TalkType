# T15 evidence pack — long-press popup above the finger (emu 5564, 1080x2340, density 440)

Device: emulator-5564, debug build 23:40 (headroom 176 px = 64 dp). Debug has a
48 dp status band, so stack top = y 1472, keyboard rows 1598..2202 (4 x 151 px),
headroom band = 1296..1472 (above the visible IME, over app content).
Release has NO band: keyboard view starts at 1472 directly.

Geometry reference (window coords): app background luminance 250 (white),
keyboard surface 243. Touchable region from `dumpsys input_method`:
`SkRegion((0,176,1080,1044))` = keyboard only; headroom excluded.
`contentTopInsets = visibleTopInsets = 176` (window-relative) = keyboard top edge.
Requested IME window: w=1080 h=1044 (keyboard 868 + headroom 176).

## 1. r0 long-press (e), popup fully above the row
log: `VK-HIT popup-open cells=7 left=0 top=-176`
Pixel diff band (popup body vs baseline): y 1296..1443 — ends 155 px ABOVE the
top edge of r0 (1598); pressed key e fully visible; popup sits over app content.

## 2. Idle state: headroom transparent
Idle screenshot row profile: y 1200..1470 constant 250.0 == app background,
min==max (no dithering), keyboard surface 243 only from 1472 down.
(Defect found+fixed during evidence: theme windowBackground painted the band;
fix = runtime transparent window background + bounded surface fill.)

## 3. Home row (a): popup above pressed row
log: `popup-open cells=6 left=0 top=-176`; body band y 1430..1610.
Pressed row r1 = 1749..1900 — untouched. Popup covers r0/status band, never the
pressed key.

## 4. Letter row (c): popup above pressed row
log: `popup-open cells=3 left=190 top=-25`; body band y 1582..1730 — r1 (1749..)
and r2 (1900..) free; popup above both.

## 5. Footer period key: popup high above the key, over the app input bar
Debug bench: second EditText docked to the app's IME-visible bottom edge,
bounds [33,1348][1047,1472] = exactly the headroom band (browser-URL-bar analogue).
Long-press footer `.`: popup body y 1450..1610, x 0..1072 (clamped) —
overlaps the visible app input bar (1428..1472 diff rows inside dock bounds),
the pressed footer row (2051..2202) untouched. Same visual as stock Chrome.

## 6. Touch pass-through
Tap at (540,1410) inside the docked input bar WHILE keyboard is open:
0 VK-HIT log lines (IME consumed nothing), EditText focused=true, key events
land in the app field (text "99"). Touchable region (see header) confirms.

## 7. Slide selection
e held, slide up into popup cell: `popup-commit` path exercised by
LongPressMenuTest invariants; live hold showed arm-hold → popup-open →
arm-end action=1 (commit) sequence.

## Suites (Robolectric, orchestrator re-run)
LongPressMenuTest 57/0, ImeRootViewLayoutTest 16/0 (incl. onComputeInsets values,
transparent decor, bounded surface fill), plus full debug+release suites in
v43_full.log (target: all green).

## Known caveats for the reviewer
- Evidence taken in DEBUG (status band present). Release has MORE headroom
  available above r0 (no band): top=-176 => popup bottom at keyboard top.
  Release build produced in same run; if required we can repeat item 1 on release
  via debuggable-only bench disabled — visual identical expected.
- Debug status band is covered by popups (band is part of stack top area) —
  accepted by design (T5 band zone predates T15, popups used the band before).
- Slide-to-cell on a REAL touch (finger) cannot be simulated by adb swipe while
  popup open; commit-on-release path proven by logs.

---

# Iteration 2 (2026-11-04, release APK vc44 + debug vc44, emu 5564)
Planner rejected iteration 1 (release unmeasured, M5/M6 missing, one mislabel).
All M items re-measured; verdict received: **T15 ACCEPTED** (iteration 2),
R1 required before shipping 0.1.43.

## M1+M2 release, Chrome URL bar (no status band)
Keyboard visible top y=1604 (window 1080x912+176 headroom, content/visible
insets 176, touchable SkRegion((0,176,1080,912))). Long-press r0 `e`:
popup body 1428..1580 = exactly keyboardTop-176 .. keyboardTop-24 — fully inside
the headroom, 24 px above the keyboard, pressed row untouched. Headroom rows in
the same screenshot show the APP's own content (min luminance 28 = dark text
pixels, max 251) — band is transparent, no keyboard tint.

## M3 debug dock bar (browser analogue)
Bottom-docked EditText [33,1348][1047,1472]; r0 popup overlapped its rows
1428..1472 visibly; touch/focus of the bar while keyboard open intact.

## M4 — superseded by user rollback T16 (period key removed from footer;
period lives in r2 again as of v0.1.43). Equivalent measured in R1 below.

## M5 slide-to-cell (release Chrome, exact field contents)
omnibox "eé" -> DOWN(270,1684) hold 0.7s -> MOVE(100,1514) -> UP(100,1510)
=> field "eéé" (accent committed via slide, no bare e). Negative control:
release below the popup commits the base key, not a cell.

## M6 touch pass-through (release Chrome)
Keyboard open, tap (540,1480) inside headroom: 0 VK-HIT lines, IME consumed
nothing, keyboard closed, Chrome activated suggestion "eé" (page became
"eé - Google Suche"). Touch went to the app.

# R1 — shipping artifact verification (release app-release.apk vc44)
1. Built from T16+T14+T15 tree; unit tests debug+release 1217/0; BUILD SUCCESSFUL.
2. Identity: for all 14 non-ru assets, sha256 of assets/layouts/<id>.json inside
   the APK == `git show 7b1ddf4:...` (14/14 match; protocol: unzip + sha256sum).
3. Footer without period: byte-identical layout JSON to v0.1.42 (T11 variant B).
4. Real values from a debug run (logcat + drawnKeys rect, replaces earlier
   assumption — row height is 151 px in debug, NOT 176):
   - `VK-HIT id=period rect=<845,302,947,453> view=1080x604` (keyboardView top
     1598 -> r2 rows 1900..2051; keyboard visible top 1472 incl. 126 px debug band)
   - `VK-HIT popup-open cells=7 left=68 top=-25` -> popup absolute 1573..1727
  (menu height 154 px at density 2.75; the 147 px figure is density 2.625),
     x 68..1080; r1/r2/pressed key untouched; covers headroom + r0 only.
   - release Chrome run measured body 1578..1732 => top=-26 => rowH≈150 —
     essentially the same row height as debug (the earlier "flatter keyboard /
     ~140 px rows" remark was wrong: 140 px would imply top ≈ -36, i.e.
     1568..1722, which was not measured). Planner's expected top≈-26 matches.
5. Slide to `,`: tap committed `.` (field "."), then DOWN(897,1975)/0.8s/
   MOVE(150,1650)/UP(150,1650) -> `popup-commit cell=0` -> field ".," — cell 0
   committed by slide.
Row edges convention: always logged from drawnKeys rects (VK-HIT), never
derived from assumed budgets — lesson recorded by the planner after M4/R1.
