# Review Stage 1 — Voice-Keyboard (Android IME)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Reviewed code state:** Stage 1 (M0/M1 scope: skeleton + operable keyboard, no dictation).
**Not reviewed (being worked on in parallel by another agent):** `app/src/main/java/de/voicekeyboard/asr/`,
`app/src/test/java/de/voicekeyboard/asr/`.

**Verification:** `ANDROID_HOME=/opt/android-sdk ./gradlew --offline assembleDebug test` → **BUILD SUCCESSFUL**
(68 tasks, 6 executed). `adb -s <device>` shows the IME active (`de.voicekeyboard/.ime.VoiceKeyboardService`, `mInputShown=true`),
host editor contains `qwe` (3 keys via `InputConnection` — M0 criterion met).

**Not verifiable / limitation:** The screenshot `/tmp/vk-stage1.png` was taken but could **not be visually evaluated** by the review model (no image support). The layout findings are instead evidenced **directly from `de-de.json`** (stronger evidence than a screenshot). `uiautomator dump` shows: the IME keys do **not** appear in the accessibility tree (only host-app views, cf. finding VK-05).

---

## Mandatory questions (short answers)

1. **ä ö ü ß on `base`?** **NO.** All four only as `alternates` (`ä`/`ü` on `u`, `ö` on `o`, `ß` on `s`),
   no dedicated key. Violates LAYOUT_REQUIREMENTS ("own keys in `base`: ä ö ü ß") and DESIGN §7.3.
   `=\<` (symbol layer, shift key) and `⌇` (symbolsShift) are exactly the forbidden artifacts from
   LAYOUT_REQUIREMENTS basic rule 5. Mic is **duplicated** (status-row button + `mic` key per layer).
2. **Backspace not codepoint/grapheme-safe.** `getTextBeforeCursor(2,0)` + `substring(len-1)` splits
   surrogate pairs (deletes only the low surrogate). `performEditorAction` uses `IME_MASK_ACTION` but does **not** check
   `IME_FLAG_NO_ENTER_ACTION`/multiline. `commitText(text, 1)` (`newCursorPosition=1`) is correct.
3. **Shift/CapsLock:** double-tap caps and single shift work; however `shiftActive` is a **dead
   state** (never becomes `true`). Critical: **`layer-symbols`/`layer-base` are not wired at all** — the digit/symbol layer is unreachable, "caps affects letters, not digits" cannot even occur.
4. **KeyboardView:** `onMeasure`/`onSizeChanged` rebuild the model (rotation ok). `onDraw` allocates one list per key
   (`rows.flatten()` in `sourceKey`, O(n²)); no Paint allocation per frame. `ACTION_CANCEL` handled
   (no wrong key). **Accessibility missing entirely** (Canvas only, no ContentDescription/NodeInfo).
5. **LayoutParser** validates allowlist, unique IDs, row sums, `width>0`, `schemaVersion==1`. Gaps:
   `requireString` checks only existence, not type; root `additionalProperties` not checked; `alternates` limit (max 8)
   and "characters only" not checked; `deadKeys` not enforced as `required`. Error isolation per asset and
   fallback DE→EN are present.
6. **CaseMapper** tr `i→İ`/`ı→I` and `I→ı`/`İ→i` correct; non-tr via `uppercase(locale)` (multi-character capable,
   `ß→SS`). The `ß→ẞ` requirement is **not** met by the layout (shift layer contains no `ß`/`ẞ`).
7. **Manifest** minimal and correct (`exported=true` + `BIND_INPUT_METHOD` on the service is mandatory).
   `RECORD_AUDIO`/`FOREGROUND_SERVICE*`/`INTERNET`/`foregroundServiceType` missing — **note for later stages** (FGS).
8. **Tests do not cover the risks.** In particular the mandatorily required
   `LayoutCompletenessTest` (LAYOUT_REQUIREMENTS acceptance criterion 1) is missing, whose absence let exactly the ä/ö/ü/ß error through. Details in VK-09/VK-10.

---

## Findings

Legend: P0 = merge blocked · P1 = fix before release · P2 = report.

| ID | Sev | File:Line | Finding | Evidence (code quote / command) | Concrete fix |
|---|---|---|---|---|---|
| VK-01 | **P0** | `app/src/main/assets/layouts/de-de.json:21,23,27,28` | Mandatory characters ä ö ü ß missing as own `base` keys; only `alternates`. Violates DESIGN §7.3 and LAYOUT_REQUIREMENTS (table: "own keys in `base`: ä ö ü ß", basic rule 1). | `{"id":"u",…,"alternates":["ü",…]}`, `{"id":"o",…,"alternates":["ö",…]}`, `{"id":"a",…,"alternates":["ä",…]}`, `{"id":"s",…,"alternates":["ß",…]}` | Rebuild DE-QWERTZ: ä ö ü ß as own (dedicated) keys, e.g. `p ü` column and ß next to `0`; alternates only as an addition. |
| VK-02 | **P0** | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:44-71` + `keyboard/KeyboardView.kt:205-223` | Digit/symbol layer unreachable: `layer-symbols`/`layer-base` are handled neither in `KeyboardView.handleAction` nor in `VoiceKeyboardService.onAction` → `?123`/`ABC` have no effect. | `handleAction`: `when(action){SHIFT->… else->listener?.onAction(action)}`; `onAction` knows only `BACKSPACE/SPACE/ENTER/CURSOR_L/R`. | Implement the layer switch in `KeyboardView` (analogous to shift) and/or extend `onAction` with `LAYER_SYMBOLS`/`LAYER_BASE` and set `activeLayerName` + `rebuildModel`. |
| VK-03 | P1 | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:47-54` | Backspace splits surrogate pairs/emoji: `getTextBeforeCursor(2,0)` + `substring(before.length-1)` yields only the low surrogate → `deleteSurroundingText(1,0)` leaves corrupt text (high surrogate). Violates DESIGN §6. | `val last = before.substring(before.length-1); val utf16 = last.length; ic.deleteSurroundingText(utf16,0)` (with `…😀` only `\uDE00` is deleted). | Determine the full last codepoint/grapheme (detect high surrogate → 2 units; if needed `Character.charCount`/grapheme boundary) and only then `deleteSurroundingText(n,0)`. |
| VK-04 | P1 | `app/src/main/assets/layouts/de-de.json:134,185` (and `en-us.json:134`) | Forbidden label artifacts `=\<` (symbol-layer shift key) and `⌇` (symbolsShift `question`). Violates LAYOUT_REQUIREMENTS basic rule 5 ("in particular … `=\<` and `⌇` are forbidden"). | `{"id":"shift","label":"=\\<","value":"layer-symbols"}`; `{"id":"question","label":"⌇",…}` | Replace the labels with real characters (shift key e.g. `ABC`/`⇧`, `question` e.g. `?`); ban `\`/`<`/`>`/`&` from labels. |
| VK-05 | P1 | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt` (entire view) | No accessibility integration: keys only via `Canvas`; no `contentDescription`, no `onPopulateAccessibilityEvent`, no description. `performClick()` without `setOnClickListener`. TalkBack cannot announce individual keys. Violates DESIGN §8 (accessibility). | `uiautomator dump` shows only host-app nodes, no IME keys; no `contentDescription`/`AccessibilityNodeProvider` in the code. | Implement `onInitializeAccessibilityNodeInfo` per key (label + action), `contentDescription` on keys, `setOnClickListener` for `performClick`. |
| VK-06 | P1 | `app/src/test/java/de/voicekeyboard/layouts/` (file missing) | The mandatorily required `LayoutCompletenessTest` (LAYOUT_REQUIREMENTS "acceptance criteria 1+2") does **not** exist; hence the ä/ö/ü/ß error (VK-01) and the artifact (VK-04) slipped through unnoticed. | `find app/src/test -name '*Completeness*'` → empty; `LayoutParserTest` checks only schema structure, not character coverage. | Add `LayoutCompletenessTest`: per asset mandatory characters from the LAYOUT_REQUIREMENTS matrix on `base` **as own keys**, ß→ẞ in `shift`, artifact ban (`\ < > &`, `=\<`, `⌇`), alternates ≤8 & characters only. |
| VK-07 | P1 | `app/src/main/assets/layouts/de-de.json:shift layer (id "s", line ~78)` | `ß → ẞ` (uppercase form) is entirely missing in `shift`. LAYOUT_REQUIREMENTS German special case: "ß → ẞ must be present in shift, even though the Unicode default would be SS". shift-`s` has only `["Ś","Š"]`. | shift: `{"id":"s",…,"value":"S","alternates":["Ś","Š"]}` (no `ẞ`). | Add ẞ in the shift layer (own key or `alternates` in uppercase form) and cover it in the completeness test. |
| VK-08 | P2 | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:62-70` | `performEditorAction` selects the action without `IME_FLAG_NO_ENTER_ACTION` and multiline checks; a multiline field with a set `ImeAction` would trigger `performEditorAction(GO/…)` instead of a line break. | `val action = info?.imeOptions?.and(IME_MASK_ACTION)` → if `!= NONE` always `performEditorAction(action)`. | Before `performEditorAction` check: `(inputType & IME_FLAG_NO_ENTER_ACTION)!=0` or `TYPE_TEXT_FLAG_MULTI_LINE` → then `commitText("\n",1)`. |
| VK-09 | P2 | `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt:36` vs. `de-de.json:55` | Mic key duplicated: disabled `micButton` in the status row **and** `mic` action key in every layout layer. Both are ineffective in stage 1, but redundant/confusing. | `micButton = Button(context).apply { text="🎤"; isEnabled=false }` + every layer `{"id":"mic",…,"value":"mic"}`. | Define one location (DESIGN §8: "separate large touch area"); remove the status-row placeholder or make it a pure status display without a button. |
| VK-10 | P2 | `app/src/main/java/de/voicekeyboard/layouts/LayoutParser.kt:169-172` (`requireString`) + `parse` | "Strict" validation is only partially strict: `requireString` checks only `has(field)`, not the type — `getString` silently converts integers/booleans to strings (violates schema `type:"string"`). Root `additionalProperties:false` is not checked. | `private fun requireString(obj,f,path){ if(!obj.has(f)) throw … }` → `{"id":5}` is accepted as `"5"`. | Check the type (`opt(field) is String`) and the root fields against an allowlist (`schemaVersion,id,language,layers,deadKeys`) via `rejectUnknown`. |
| VK-11 | P2 | `app/src/main/java/de/voicekeyboard/layouts/LayoutParser.kt` (`parseKey`, `parseDeadKeys`) | `alternates` is limited neither to max 8 nor to "characters only, no action names"; `deadKeys` is `required` in the schema but accepted as optional. | `val alternates = if(obj.has("alternates"))…map{getString(it)}` (unchecked). | Add a limit of 8 and a character check; treat `deadKeys` as a mandatory field. |
| VK-12 | P2 | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:42,96,208-210` | `shiftActive` is a dead state (initialized `false`, only ever set to `false`, never `true`); the state logic relies exclusively on `shiftTemporary`/`capsLock`. Complicates caps-lock interaction (one shift tap does **not** turn caps lock off). | `var shiftActive=false`; in `handleAction` only `shiftActive=false`. | Clean up the state machine: either use `shiftActive` for the display or remove it; define + test the behavior "one shift with caps lock active disables caps lock". |
| VK-13 | P2 | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:120,215` | `sourceKey()` calls `rows.flatten().firstOrNull` per key in `displayLabel`/`textFor` (per `onDraw` frame, O(n²) + list allocation). | `private fun sourceKey(id)=layout?.layers?.get(…)?.rows?.flatten()?.firstOrNull{it.id==id}` | Hold an ID→key/index map once at `rebuildModel` and reuse it in `onDraw`/hit detection. |
| VK-14 | P2 | `app/src/main/res/xml/method.xml:4-8` | Only the `de_DE` subtype is declared; the existing `en-us` layout has no IME subtype (system integration/language metadata). | Only `<subtype … imeSubtypeLocale="de_DE" …/>`. | Add the `en_US` subtype (DESIGN §7.3 activated layouts via system subtypes). |
| VK-15 | P2 | `app/src/main/AndroidManifest.xml` (entire manifest) | For the final product the permissions `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `INTERNET` and `android:foregroundServiceType="microphone"` on the later FGS are missing. **Note** (DESIGN §2.2) — correctly omitted for stage 1, since neither an FGS nor a mic path exists yet. | Manifest contains only `<application>` + activity + IME service. | Add them in the FGS stage (M2/M6); declare `foregroundServiceType` only with the mic service. |

---

## Verdict

**BLOCK for merge (do not release stage 1 without the two P0s).**

The M0 core (installable IME, 3 keys via `InputConnection`, build + JVM tests green) is met and solid.
Blocking are **VK-01** (mandatory DE characters ä ö ü ß missing as own keys, violates
LAYOUT_REQUIREMENTS) and **VK-02** (digit/symbol layer is entirely unreachable — operating scope DESIGN §7.1/§7.3).
Both are direct contract violations against the mandatory docs, not matters of taste. Additionally, the P1s
(VK-03 data integrity on backspace, VK-04 artifacts, VK-05 accessibility, VK-06 missing completeness test,
VK-07 ß/ẞ) must be fixed before release.

---

## The 5 most important measures (in order)

1. **VK-01** — Fix the DE layout: ä ö ü ß as own `base` keys, add `ß→ẞ` in `shift` (VK-07 included). Direct violation of mandatory characters.
2. **VK-02** — Wire the layer switch `layer-symbols`/`layer-base`; otherwise the digits and both symbol layers (DESIGN §7.1) are useless.
3. **VK-06** — Introduce `LayoutCompletenessTest` per LAYOUT_REQUIREMENTS (mandatory characters yes/no, artifact ban `=\<`/`⌇`, alternates≤8, ß→ẞ); this test would have caught VK-01/VK-04/VK-07 automatically.
4. **VK-03** — Make backspace codepoint/grapheme-safe (do not split surrogate pairs/emoji). Text integrity is a DESIGN §6 requirement.
5. **VK-04 + VK-05** — Remove artifact labels and add accessibility (ContentDescription/AccessibilityNodeInfo) to satisfy DESIGN §8.
