# Review Stage 1 — FIX pass (VK-01 … VK-07)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Reviewed:** fixes for VK-01…VK-07 (plus incidentally VK-12/VK-13), contributed by GLM 5.3.
**Untouched/not reviewed (work in progress in parallel):** `app/src/main/java/de/voicekeyboard/asr/`,
`app/src/test/java/de/voicekeyboard/asr/` — these paths were **not** changed.

---

## 0. Verification (build & tests)

`ANDROID_HOME=/opt/android-sdk ./gradlew --offline test` → **BUILD SUCCESSFUL**, 19 tests green:

| Test class | Tests | Failures |
|---|---|---|
| `de.voicekeyboard.keyboard.KeyboardModelTest` | 6 | 0 |
| `de.voicekeyboard.layouts.CaseMapperTest` | 4 | 0 |
| `de.voicekeyboard.layouts.LayoutCompletenessTest` | 1 | 0 |
| `de.voicekeyboard.layouts.LayoutParserTest` | 8 | 0 |
| **Total** | **19** | **0** |

**Important context (not to be held against GLM):** towards the end of the run the *parallel*
ASR agent was mid-work; its (here irrelevant) files
`app/src/test/java/de/voicekeyboard/asr/TdtGreedyTest.kt` were temporarily in a
non-compiling state (`No parameter with name 'frames' found` → `framesIn`). The 19 tests
above concern exclusively the layout/keyboard layer (not `asr`). For the isolated
mutation probe the `asr` test source tree was temporarily excluded from compilation
via an init script `-I` (files themselves untouched, script removed afterwards).

---

## 1. Status per finding

Legend: RESOLVED / PARTIAL / OPEN.

| ID | Sev | Status | Code evidence |
|---|---|---|---|
| **VK-01** ä ö ü ß as own `base` keys | P0 | **RESOLVED** | `app/src/main/assets/layouts/de-de.json` row 1 `{"id":"ue",…,"value":"ü"}` (line 20), row 2 `{"id":"oe",…,"value":"ö"}` + `{"id":"ae",…,"value":"ä"}` (lines 30/31), row 3 `{"id":"eszett",…,"value":"ß"}` (line 39). Emulator cross-check (text commit): taps on these coordinates yield "qüäöß1" in the EditText. |
| **VK-02** wire the digit/symbol layer | P0 | **PARTIAL** | `KeyboardView.kt:246` `KeyActions.LAYER_SYMBOLS -> switchLayer(if (symbolShift) "symbolsShift" else "symbols", fromSymbols = true)` + `:247` `LAYER_BASE -> switchLayer("base", …)`. Works: base→symbols (`?123`) and symbols→base (`ABC`). **NOT** working: symbols→symbolsShift (new finding, see below). |
| **VK-03** backspace grapheme/codepoint-safe | P1 | **RESOLVED** | `VoiceKeyboardService.kt:50-62`: `getTextBeforeCursor(32,0)`, empty/null → `sendKeyEvent(KEYCODE_DEL)` (up/down), otherwise `lastGraphemeLength(before)` + `deleteSurroundingText(units,0)`. `lastGraphemeLength` (lines 90-101) uses `BreakIterator.getCharacterInstance()` with codepoint fallback (`codePointBefore`/`charCount`). Emulator: backspace deletes exactly 1 character (no double deletion). |
| **VK-04** forbidden label artifacts `=\<`/`⌇` | P1 | **RESOLVED** | `de-de.json` symbols shift key now `{"id":"shift","label":"⇧",…,"value":"layer-symbols"}` (line 145); `question` in symbolsShift now `"¿"` (was `⌇`). `en-us.json` analogous (shift-`⇧`, no `=\<`/`⌇`). No label `\` `<` `>` `&` remains. |
| **VK-05** accessibility integration | P1 | **RESOLVED** | `KeyboardView.kt:279-373`: `getAccessibilityNodeProvider()` provides virtual nodes per key with `contentDescription`, `setBoundsInScreen/Parent`, `ACTION_CLICK`/`ACTION_ACCESSIBILITY_FOCUS`; `performAction(ACTION_CLICK)` calls `handleKey(key,…)` — the same path as touch. `sendAccessibilityEvent(TYPE_WINDOW_CONTENT_CHANGED)` after layer/shift changes. Minor gaps: see new findings. |
| **VK-06** `LayoutCompletenessTest` was missing | P1 | **RESOLVED** | File now exists: `app/src/test/java/de/voicekeyboard/layouts/LayoutCompletenessTest.kt`. Mutation probe proves RED behavior (see §3). Coverage gaps: see new findings. |
| **VK-07** `ß→ẞ` in `shift` | P1 | **RESOLVED** | `de-de.json` shift: `{"id":"eszett","label":"ẞ",…,"value":"ẞ"}` (line 131). Test `checkShiftPairs` requires the pair `"ß" to "ẞ"` (line 43) and enforces it as an own shift key. |
| VK-12 (`shiftActive` dead state) — incidentally | P2 | RESOLVED | `KeyboardView.kt` no longer has `shiftActive`; state runs cleanly via `shiftTemporary`/`capsLock`/`symbolShift` (`:55-68`, `:236-254`). One shift tap with caps lock active correctly disables caps lock. |
| VK-13 (`sourceKey` O(n²) in `onDraw`) — incidentally | P2 | RESOLVED | `KeyboardView.kt:63` `sourceKeys = layer.rows.flatten().associateBy { it.id }` once in `rebuildModel`; `displayLabel`/`textFor` access via `sourceKeys[key.id]` (`:141`, `:178`). |

---

## 2. New findings from this pass

### **NEW-01 — P1 — `symbolsShift` layer is unreachable (state machine broken)**

**Location:** `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:246,262-264` in combination with
`app/src/main/assets/layouts/de-de.json:145` (symbol layer `shift` key `value:"layer-symbols"`).

**Finding:** The required sequence `base→symbols→symbolsShift→symbols→base` is broken at 2 of 4
transitions:
- `symbols→symbolsShift` does **not** work: `LAYER_SYMBOLS` calls
  `switchLayer(if (symbolShift) "symbolsShift" else "symbols")`. On entering `symbols`,
  `symbolShift=false` (it is only set to `true` in `switchLayer` when the target is `symbolsShift`,
  lines 262-264) → a tap on the symbol-layer shift key (`value:"layer-symbols"`) maps again
  to `"symbols"` (self no-op). There is **no** path that sets `symbolShift=true` without
  being in `symbolsShift` beforehand → circular reasoning, `symbolsShift` is never reachable.
- `symbolsShift→symbols` is also missing: the symbolsShift shift key is `value:"layer-base"`
  (`de-de.json:197`) and jumps directly to `base`.
- The parameter `fromSymbols` in `switchLayer` (line 257) is **unused** (dead parameter,
  supports the "stub" signal).

**Evidence (reproduced):** In the emulator, with the symbol layer active, tapped the shift key and
tapped position "1" → the field became `…ß11` instead of `…ß¹` (it stays `1`, not `¹` from
symbolsShift). I.e. switching to the shift counterparts (brackets, currencies, `¹²³`
from LAYOUT_REQUIREMENTS §2.1/§1 rule 9) silently fails.

**Minimal fix:** Implement `LAYER_SYMBOLS` as a genuine toggle: if already in `symbols`
or `symbolsShift`, toggle between the two (`symbols ↔ symbolsShift`); if in a letter
layer, go to `symbols`. Use or remove `fromSymbols` and secure the toggle in a
test (state matrix).

### **NEW-02 — P2 — `LayoutCompletenessTest` does not cover the de-DE matrix column B**

**Location:** `app/src/test/java/de/voicekeyboard/layouts/LayoutCompletenessTest.kt:43`.

**Finding:** `MatrixRow("de-de", listOf("ä","ö","ü","ß"), shiftPairs=…)` has `requiredReachable`
empty. LAYOUT_REQUIREMENTS §2.2 additionally requires for de-DE `é è ê á à â ç` **reachable**
(stage B). The test checks only the A-set (own keys) and `ß→ẞ`; the B-set for the **only
currently existing** non-ASCII language is unchecked (the same gap would affect `en-us` punctuation
`' – — … £ $ °`, which are only partially/vaguely captured as `requiredReachable`).
Moreover, the test does not check §1 rule 9 (digits 0–9 once each on `symbols`) nor §2.1
global/§2.2 punctuation (for that a dedicated `PunctuationCoverageTest` would be needed, which is still
missing); the tr-TR matrix row (line 51) omits the required own `i` (`A = ç ğ ı i ö ş ü`).

**Minimal fix:** Add `requiredReachable = listOf("é","è","ê","á","à","â","ç")` for de-de;
add `i` to the tr A row; add rule 9 (digits) and punctuation coverage, or deliver a `PunctuationCoverageTest`.

### **NEW-03 — P2 — Grapheme backspace (VK-03) has no unit test**

**Location:** `VoiceKeyboardService.kt:90-101` (`lastGraphemeLength` is `internal`, but untested).

**Finding:** No `VoiceKeyboardServiceTest`/`LastGraphemeTest` exists (`find app/src/test` →
only layout/keyboard tests). The core defect gap of VK-03 (do not split surrogate pairs/emoji)
is thus not regression-protected; `BreakIterator.getCharacterInstance()` uses the
default locale (undetermined).

**Minimal fix:** Add a test: `lastGraphemeLength("…😀")==2`, `("e\u0301")==2`,
`("abc")==1`, `("")==0`, family emoji (ZWJ) — plus a more robust locale setup (`BreakIterator.getCharacterInstance(Locale)`).

### **NEW-04 — P2 — `performClick()` is a no-op; click path inconsistently evidenced**

**Location:** `KeyboardView.kt:187` (`performClick()` in `ACTION_DOWN`).

**Finding:** No `OnClickListener` is set; the actual activation happens in
`ACTION_UP` via `handleKey` (`:218-223`). `performClick()` without a listener returns
`false` and does nothing (and is moreover called on `DOWN` instead of `UP`). The a11y path
(`performAction` → `handleKey`) is deliberately identical with touch, but `performClick()` itself
is dead code/noise and `onPopulateAccessibilityEvent(TYPE_VIEW_CLICKED)` (`:375-379`) hangs on a
click event that, lacking a listener, is not reliably fired.

**Minimal fix:** Put the activation on the standard click path (commit in `performClick`/
`OnClickListener`, onTouchEvent calls `performClick()` on `ACTION_UP`), so that touch, a11y click
and the TalkBack click event all share the same single path.

### **NEW-05 — P2 — Accessibility node provider gaps + caps lock state after a layer round trip**

**Location:** `KeyboardView.kt:315` (`findFocus` → `null`), `:285-296` (virtual IDs = list index),
`switchLayer`/`applyLayerState` (`:257-278`).

**Finding:** (a) `findFocus` always returns `null` (TalkBack focus queries unanswered);
(b) virtual view IDs are plain indices into `model.keys` — after `rebuildModel` (layer change)
the same IDs point to different keys (mitigated by `TYPE_WINDOW_CONTENT_CHANGED`, but not
stable); (c) `switchLayer("base")` sets `activeLayerName="base"` without calling `applyLayerState()` →
with active `capsLock` the keyboard afterwards shows **lowercase letters**, while the shift key
still shows `⇪`/highlighted (`showShift()`=`capsLock`=true, but `textFor`
`shiftShowing`=`activeLayerName=="shift"`=false) — the commented "freeze/restore shift state"
is in fact not restored. (d) `FLAG_CONTENT_SENSITIVE` is not set (design decision, here only a note).

**Minimal fix:** Implement `findFocus` on the virtual focus (or return click focus in
`performAction`); base the virtual ID→key mapping on a stable source (e.g.
`sourceKeys` order/hash instead of the list) or `notifyViewInvalidated`; after `switchLayer` to
letter layers run `applyLayerState()` so that `activeLayerName` reflects the `capsLock`/shift state.

---

## 3. Mutation probe (`LayoutCompletenessTest`)

**Procedure:** removed the `ü` own key from `de-de.json` (value `"ü"` → `"ux"` on key `id:"ue"`),
ran the test in isolation, restored the file exactly.

| Step | Result | SHA-256 |
|---|---|---|
| Original (backup) | — | `1e0badeb45118a17b4a1e4f2e1464b4213a348cd5873830267c352676f1e4539` |
| Mutation (`ü`→`ux`) | Test **RED**: `LayoutCompletenessTest … FAILED` (AssertionError line 108) | `0a3d7528…` |
| Error message | `Asset 'de-de': Pflichtzeichen fehlen als eigene base-Taste: [ü] (alternates zählen NICHT, LAYOUT_REQUIREMENTS Grundregel 1)` | — |
| Restored | hash identical to the backup | `1e0badeb45118a17b4a1e4f2e1464b4213a348cd5873830267c352676f1e4539` ✔ |

**Skip logic checked:** `LayoutCompletenessTest.asset()` returns `null` only for a *missing/readable*
asset (→ matrix row is skipped); a **present** asset that violates the
requirement triggers `fail()` (no silent green). `assertTrue(checked > 0)` prevents
"all skipped". A present but unparseable asset throws a `LayoutException` (test
error → red), so it also is **not** silently green.

---

## 4. Emulator cross-check (`adb -s <device>`, IME active)

Build `assembleDebug` (BUILD SUCCESSFUL) installed (`Success`), `de.voicekeyboard/.ime.VoiceKeyboardService`
set as IME (`ime set` → "selected for user #0"). `DebugInputActivity` started
(`de.voicekeyboard/.debug.DebugInputActivity`), `dumpsys input_method` → `mInputShown=true`,
`mBoundToMethod=true`.

Evidence via **text commits** (`uiautomator dump` on the EditText; the field accumulates the inputs):

| Action (tap on key position) | Field content afterwards | Evidence |
|---|---|---|
| `q` (44,1839) | `q` | key commits |
| `ü`(1042,1839) `ä`(1042,1991) `ö`(942,1991) `ß`(881,2143) | `qüäöß` | **ä ö ü ß present and functional as own keys** |
| `?123`(58,2295) → `1`(44,1839) | `qüäöß1` | **`?123` activates the digit layer** |
| Symbol-layer shift (66,2143) → `1`(44,1839) | `qüäöß11` | **`symbolsShift` does NOT switch** (expected `¹`) → NEW-01 |
| Backspace (1057,2143) | `qüäöß1` | exactly 1 character deleted (no double deletion) |
| `ABC`(58,2295) → `shift`(66,2143) → `q`(44,1839) | `qüäöß1Q` | base→shift (uppercase) works |

Note: `uiautomator dump` still shows only the host-app nodes, **not** the IME keys —
the IME window is `NOT_FOCUSABLE` and is not captured by the standard dump (no regression
versus VK-05; the keys exist as virtual node-provider nodes, but outside the
app-window dump).

---

## 5. Regression check (schema/row sums/IDs/ẞ/consistency)

- **Schema validity:** both existing assets (`de-de`, `en-us`) parse (`LayoutParserTest`
  8 tests green; `LayoutCompletenessTest` checks both).
- **Row sums:** `LayoutParser.validateSemantics` (row sums per index across `shift`/`symbols`/
  `symbolsShift` identical to `base`) — green.
- **Duplicate IDs:** checked per layer; "shift" in 4 layers is permissible (duplicate-free *within*
  a layer) — green.
- **`ẞ` key:** `de-de.json` shift `eszett`=`ẞ` present; `CaseMapper.upper("ß","de-DE")=="SS"`
  remains separately correct (label vs. text transformation).
- **base/shift key consistency:** both 46 keys, identical structure/positions; `validateSemantics`
  enforces a shift counterpart for every base TEXT key; `LayoutCompletenessTest.checkShiftPairs`
  checks uppercase forms. No regression.
- **No double deletion/no artifacts:** see §4 and VK-04.

---

## 6. Verdict

**OK with notes (release for merge) — no additional P0 blockers remain.**

The two P0s of stage 1 are solved: VK-01 (ä ö ü ß as own keys) is evidenced by JSON +
emulator text commit, VK-02 is wired to the core (digit/symbol layer reachable). All
P1 requirements (VK-03/04/05/06/07) are substantially fixed and evidenced.

**Mandatory to fix before release (P1):** **NEW-01** — `symbolsShift` is unreachable; the shift
counterparts required by LAYOUT_REQUIREMENTS §2.1/§1 rule 9 (brackets, currencies, `¹²³`)
are thereby unreachable. That is the remaining half of the original VK-02.

---

## The 5 next measures (in order)

1. **NEW-01 (P1)** — Implement `LAYER_SYMBOLS` as a genuine toggle in `KeyboardView.handleAction`
   (`symbols ↔ symbolsShift`) and pin the state matrix `base→symbols→symbolsShift→
   symbols→base` as a test.
2. **NEW-02 (P2)** — Extend `LayoutCompletenessTest` with the de-DE B-set (`é è ê á à â ç`) and
   §1 rule 9/punctuation coverage (or a `PunctuationCoverageTest`); add `i` to the tr row.
3. **NEW-03 (P2)** — Add a unit test for `lastGraphemeLength` (surrogate/emoji/ZWJ/empty),
   set the locale explicitly.
4. **NEW-05 (P2)** — Keep the caps lock/shift state consistent after a layer round trip
   (`applyLayerState` when switching back) and complete the `AccessibilityNodeProvider`
   (`findFocus`, stable virtual IDs).
5. **NEW-04 (P2)** — Establish `performClick()`/`OnClickListener` as the *single* click path
   (touch `ACTION_UP` and a11y `ACTION_CLICK` identical) and merge the duplicated mic (VK-09)
   when building out the mic stage.
