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
