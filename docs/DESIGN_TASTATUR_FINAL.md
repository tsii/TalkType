# Keyboard – binding final construction plan

## 0. Scope

This plan sets target values; where older geometry drafts contradict it, it
prevails. All 15 current languages, existing characters/alternates, and four
layers remain usable. The already approved removal of ‹/› remains; enter and
backspace are moved, not deleted. Status and 🐞 remain above the keyboard; no
debug target next to Q, no popup height growth. Only this document is created;
no code, build, git, or new device evidence.

## 1. Row grid and centering

Reference: **W=1080 px**, density **2.625 px/dp**; use the actually usable view
width. Rows r0–r2 have **12u**, the footer **13u**; units per row are recomputed
from W. Top: **1u=90 px=34.286 dp**; footer: **1u=83.077 px=31.648 dp**. Do not
sum per-key rounding: edges from cumulative units, last edge exactly W. Margins
are undrawn, **non-hittable** areas; no nearest-key hit in them.

| Row | Binding assignment in u | Pixels at W=1080 |
|---|---|---|
| r0 standard | ten characters **0…10**, 1u each; ⌫ **10…12**, 2u | character field **0…900**, ⌫ **900…1080** |
| r1 standard | margin **0…0.5**; nine characters **0.5…9.5**, 1u each; @ **9.5…10.5**; margin **10.5…12** | margin 45; characters **45…855**; @ **855…945**; margin 135 px |
| r2 standard | ⇧ **0…1.5**; seven characters **1.5…8.5**, 1u each; ⏎ **8.5…10.5**, 2u; margin **10.5…12** | ⇧ **0…135**; characters **135…765**; ⏎ **765…945**; margin 135 px |
| r3 footer | ?123 **1.5**, language **1**, emoji **1**, space **6.5**, period **1**, mic **2** | complete **13u**, no additional side margins |

**Confirmed:** 1.5+7+2+1.5=12; letter center r2=(1.5+8.5)/2=**5u=450 px**.
That places r2 exactly under the center of Q…P **0…10u**, not under the whole
view (540 px). The previous pattern ⇧1 + seven characters + ⏎2 + margin 2 is
left-shifted and is replaced. r1's nine characters are likewise centered around
**5u**; @ does not belong to the centering block.

Footer edges: **0 / 124.615 / 207.692 / 290.769 / 830.769 / 913.846 / 1080 px**.
Footer shares: **11.538 / 7.692 / 7.692 / 50 / 7.692 / 15.385 %**. Mic is at the
far right, period immediately before it; enter appears exclusively in r2.
Period/language/emoji are each 31.648 dp wide; a general 48-dp promise is not
claimed. The space center lies at **560.769 px**, so not left of the view
center. The complaint "too far left" is fixed with the correct r2 anchor and our
own 13u footer measure; space is **not** shrunk against the specified 6.5u width.
No blanket shift to the right.

### Special cases – same anchoring, complete inventory

N denotes text keys without @/margins/function keys; no reordering of their
character sequence. r0: the character field stays **10u**, with N=11 every
character key **10/11u**; ⌫ stays 2u. r1: character field width B=min(N,9)u,
start **(10−B)/2**, every character key B/Nu; afterwards @1u and the remainder
right up to 12u. Thus ten/eleven characters also fit without negative margins.
r2: B=min(N,7)u, start **(10−B)/2**, ⇧ occupies the start area, characters each
B/Nu; afterwards ⏎2u and margin up to 12u. All centers remain **5u**, even with a
denser alphabet. With six characters: ⇧2u, characters **2…8**, ⏎2u, margin 2u;
do not start left at 1u. The densification is visibly honest: it guarantees **no
48-dp width** for every character key.

| Layout | Target N(r0/r1/r2), without @ | Binding special case |
|---|---|---|
| de-de | 10/9/7 | QWERTZ; digit hints q…p; umlauts/ß remain reachable via existing alternates |
| en-us | 10/9/7 | QWERTY, standard grid |
| fr-fr | 10/9/7 | AZERTY: a,z,e,r,t,y,u,i,o,p on top; r1 q,s,d,f,g,h,j,k,l; r2 w,x,c,v,b,n,m |
| es-es | 10/9/7 | QWERTY; preserve the existing ñ/accent offering |
| it-it | 10/9/7 | QWERTY; preserve the accent offering |
| pt-pt | 10/9/7 | Current inventory four-row; do not reinvent an old fifth accent row, preserve alternates |
| nl-nl | 10/9/7 | QWERTY |
| pl-pl | 10/9/7 | QWERTY; preserve Polish alternates/shift forms |
| tr-tr | 10/9/7 | Turkish i/ı-/İ/I mapping from the language data contract, no default uppercase |
| ru-ru | **11/11/10** | r0 each 10/11u; r1 each 9/11u; r2 each 7/10u; add/move ⌫ to the top, @ after the r1 character field |
| cs-cz | **10/10/6** | r1 ten characters of 0.9u each; r2 six characters of 1u each starting at 2u |
| sv-se | 10/9/7 | QWERTY; preserve the å/ä/ö offering |
| da-dk | 10/9/7 | QWERTY; preserve the æ/ø/å offering |
| fi-fi | 10/9/7 | QWERTY; preserve the ä/ö/å offering |
| el-gr | 10/9/7 | First row contains nine Greek letters plus the existing punctuation; preserve case/tonos rules |

The numbers describe the **current** base inventory checked by reading, not
historic M7 assets. base/shift have identical edges and IDs; a layer switch moves
no key. All symbol layers get the same footer, ⌫ at the top and ⏎ right in r2;
avoid duplicate periods. Their existing text quantities remain per row: r0 in
10u, r1 centered in 10u, r2 between ⇧1.5u and ⏎2u in the 7u field; remaining
margins each yield 12u. RU digit row: symbols shows 1…0 including the previous
additional character in the 10u field, ⌫2u on the right; no new permanent digit
row above the Cyrillic base. SymbolsShift remains a real shift toggle; ABC in the
footer leads back, no dead end.

### Height and actual touch areas

Status **48 dp**; target in total **268 dp without**, nominally **276 dp with
8-dp navigation**. Account for the root/child budget and actual navigation
exactly once each; all rows scale. Footer preferably 56 dp, at least 48 dp; at
W=1080 the mic is **166.154 px=63.297 dp** wide. With the D10 root examples:
650 px → footer 128 px=48.762 dp; 829 px offer → root 725 px, footer 147 px=56
dp. Distribute the remaining height completely over the upper rows, cut nothing
off. These example values do not apply with a deviating child/inset allocation;
derive current sizes anew.

## 2. Vector icons – no tofu glyphs

⌫, ⇧, and ⏎ are drawn **as paths/vectors**, never via font characters or emoji.
Common character box **24×24 dp = 63×63 px**; optical height **20 dp=52.5 px**.
All lines **2 dp=5.25 px**, round line ends/joins; no shadows or outer frames.

| Icon | Normative contour within the character box |
|---|---|
| Backspace | Left arrowhead, closed rectangular body with X; optical width 24 dp/height 20 dp, ratio 6:5; outline, X also 2 dp |
| Shift | Classic upward arrow with a broad head and recognizable shaft/base, optically 20×20 dp; outline instead of a mere thin Unicode arrow |
| Enter | Right-angled bending return arrow, arrowhead at the bottom left; optically 22×20 dp, lines also 2 dp |

Optically center all three on the real cell center, not on a text baseline.
Normal: `palette.textFunctional` light **#CC37474F**, dark **#CCFFFFFF**.
Temporary shift: `keyToggledGlyph` light **#FF00796B**, dark **#FF80CBC4**.
Caps: existing accent surface, filled shift arrow plus a small caps marker in
the counter color; light **#FF37474F**, dark **#FF263238**. KeySurface keeps its
state priority. Press remains the D11 token, no new color: light overlay 0x66,
dark 0x4D, no double composition.

## 3. Status row – always visible and actually filled

**48 dp =126 px high**, left→right: **8 dp margin · statusText · mic 48 dp ·
4 dp distance · 🐞48 dp · 8 dp margin**. statusText flexible, minimum width
**96 dp**; at 1080 px about **295.43 dp** remain for text. Mic/debug each at
least **48×48 dp**, debug at least **8 dp** from the view edge on the right.
Parents and children exist, are VISIBLE, measured >0, and lie completely inside
the visible clip. The empty TextView is not a base state: **"Ready"** is actually
set, not merely reserved. Text **12 sp**, up to two lines, existing
secondary/error palette; no hidden diagnostics access. Target texts: ready
**"Ready"**; starting **"Microphone starting …"**; dictation **"Dictating …"**;
finalizing **"Processing …"**; error an understandable cause, e.g. **"Microphone
release missing"**. An error remains visible for at least 5 s after UP without a
new action; no silent return to ready. `status_shown` delivers
ready/dictation/finalizing/error matching the visible UI; the start is
recognizable. 🐞 remains operable even in the error case; export only metadata,
no user content/paths/URLs.

## 4. Microphone – one hold semantics, no green toggle

**A green/accented mic surface only with capture of this session actually
running**, not already on DOWN, start of loading, desired recording, or a past
success. Both existing mic entries share the same idempotent controller, never
two sessions.

| Event | State before | Action | State after | Visible feedback |
|---|---|---|---|---|
| DOWN, prerequisites met | IDLE | Request start exactly once asynchronously, remember pointer | STARTING | Press feedback ≤500 ms, start text; **not green** |
| Start finished, finger still down, capture active | STARTING | Confirm the current generation | RECORDING | Mic accent and "Dictating …" |
| Short tap: UP already during start | STARTING | Remember wantedStop; a late successful start receives exactly 1 stop, no double abort | FINALIZING→IDLE or ERROR | Never green late; result or a clear hint, no silent nothing |
| Keep holding | RECORDING | Continue audio/ASR, no repeated starts | RECORDING | Accent as long as capture is active, true level |
| UP | RECORDING | Stop capture immediately, finalize the remainder in an orderly way | FINALIZING | Green off, "Processing …", restart locked until completion |
| Successful finale | FINALIZING | Apply only current valid results | IDLE | "Ready"; check mark at most 600 ms, **no green recording surface** |
| Empty finale | FINALIZING | Explain the empty result, do not fake success | ERROR display | "No text recognized", not a mere ready |
| CANCEL/focus loss | STARTING/RECORDING/FINALIZING | Exactly 1 abort, invalidate the generation, release mic/FGS | IDLE or ERROR | Accent off; abort cause set, no ghost output |
| Capture/stall/silence error | active | End the session safely, store the error | ERROR display | Accent off immediately, cause remains visible |
| Permission/model missing | IDLE | Do not record; offer a reasoned setup path | ERROR/setup | No green state; after the grant a new deliberate DOWN is required |

A short tap is not a recording toggle; holding needs no second press and no
long-press timer. Start at most **15000 ms**, then a visible error; UP/CANCEL
must never wait behind inference. Late callbacks are discarded or free
resources; they start no new recording. The cold first attempt is explicitly
tested: no warm-up tap as an operating prerequisite.

## 5. Acceptance – target/actual instead of test count

1. All 15×4 layers: standard-conformant row sums, no lost text/action ID; margins
   produce **0 hits**.
2. DE/EN r2: first letter x=135 px, last right edge 765 px; center 450 px,
   deviation **≤1 px**.
3. CS: six letters 180…720 px; RU: complete 11/11/10 character fields without a
   covered key.
4. Footer ends exactly at 1080 px; period 830.769…913.846 and mic
   913.846…1080; no enter in the footer.
5. Icon screenshots light/dark: no boxes, all three with 2-dp strokes and 20 dp
   optical height, drawn box 63 px.
6. First/second/third opening at 650/829 px: all rows complete, same state →
   same rects **±1 px**.
7. Root test plus device image: statusText/mic/🐞 always visible;
   ready/dictating/processing/error actually readable.
8. Fresh install with/without grants: the first permitted hold attempt starts;
   no recording after UP during the start.
9. Per 20 start/UP/CANCEL sequences: at most 1 session/1 stop or 1 abort; mic
   accent off **≤500 ms** after the end.
10. No signal/no blocks >3000 ms: visible silence/stall error, end ≤3500 ms; no
    stuck dictation display.
11. 2-s and 30-s dictations plus soak per TESTPLAN_DIKTAT K01–K10; result or
    explained error, no silent loss.
12. Key press feedback <500 ms; caps/symbol toggle/popup/emoji return work from
    the same checked APK.

Log only codes, times, counters, and rects; no entered content, file paths, or
URLs in logs. This plan is a binding target, not evidence that the current build
already fulfills the criteria.
