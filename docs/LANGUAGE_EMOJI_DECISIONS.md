# Decisions E9: language switching & emoji (v1)

**Status:** 2026-10-28 · **Role:** decisions/rules (no code/Gradle/adb work)
**Basis:** `docs/KONZEPT.md` (15 languages, offline, no autocorrect/learning
dictionaries), `docs/DESIGN.md` §7.1 (keys, "a keyboard layout switch does not
automatically change the ASR", emoji "committed as complete sequences"),
`docs/LAYOUT_REQUIREMENTS.md` rule 4a, `docs/SETUP_UI_DECISIONS.md` E3 ("the
keyboard never blocks"), `keyboard/KeyboardView.kt` (`setLayout`,
`activeLayerName`), `ime/VoiceKeyboardService.kt` (`onCreateInputView` loads
`de-de` fixed; `onAction` does not know `language`/`emoji`),
`layouts/LayoutRepository.kt` (`availableIds()` returns only `de-de`/`en-us`
hard-coded).

**Current state (confirmed):** `🌐` and `😊` exist in all 15 assets but fall
into `onAction -> else -> Unit`. 14 of 15 languages are thus unreachable.
Additional finding: `LayoutRepository.availableIds()` is hard-coded — the
marketing claim "15 languages" is not true at the repository level either.

---

## E9.1 — Operating path for language selection: **(a) short tap = next active
language, long-press = in-IME panel**

**Decision:** a short tap on `🌐` cycles to the next **active** language; a
long-press (≥ 400 ms) opens the language panel **inside the IME window**
(E9.3).

**Against (b) "setup only":** the user would have to leave the app for every
language switch — the keyboard closes, the input field loses focus, the
switch costs 3–5 s and an activity change. With mixed-language dictation (THE
normal case) that is unusable. **Against (c) "swipe":** swiping on the space
key is already cursor movement (DESIGN §7.1); a second swipe handler on `🌐`
collides with short-/long-press semantics and is not discoverable without
visual feedback. **(b)+(a) combined** is the chosen route: **selection**
(which languages are active) happens on the setup page, **switching** in
everyday use via short tap.

**Rules (binding):**

1. **"Active"** = multiple selection on the setup page (checkbox list of all
   15 assets), persisted as an **ordered** list. At least **1** language is
   mandatory (deselecting the last one is refused, the checkbox stays set).
2. **Default active:** `de-de`, `en-us` (matches today's feature set; no
   behavior change for existing users).
3. **Order when cycling** = **canonical order of the §2.2 table** (`de-de,
   en-us, fr-fr, es-es, it-it, pt-pt, nl-nl, pl-pl, tr-tr, ru-ru, cs-cz,
   sv-se, da-dk, fi-fi, el-gr`), filtered to the active ones. **Not**
   alphabetical and **not** most-recently-used (no learning order — KONZEPT:
   no learning behavior). Wraps at the end.
4. **Only one active language:** a short tap changes **nothing** about the
   layout and shows the status row
   `Nur eine Sprache aktiv – in Einrichtung ändern` for 2 s. No silent no-op.
5. **The layout always matches the language:** the switch loads the target
   language's asset (`repository.load(id)`, fallback `de-de`) and sets
   `activeLayerName = "base"`. There is **no** state "ru layout with de
   labels" — the language *is* the layout (no separate layout switch in v1).
6. **Never block the IME input (E3):** the switch is a pure `setLayout(...)`
   call in the IME window; no activity, no dialog window, no `finishInput`.
   Typing is possible before, during, and after the switch.
7. **Persistence (exact names, so coder and test mean the same thing):**
   SharedPreferences file **`vk_layout`** (MODE_PRIVATE), keys:
   - `active_languages` — `String`, comma-separated, **in canonical order**,
     e.g. `"de-de,en-us,ru-ru"` (deliberately no `StringSet`: that is
     unordered).
   - `current_language` — `String`, last active ID (default `de-de`).
   On start: `current_language`; if not contained in `active_languages` →
   first active language.
8. **Repository:** `availableIds()` must **scan** the assets (15 IDs), not
   hard-code them; the setup list and the cycle use the same source.

---

## E9.2 — Language ↔ editor: **global, not per field**

**Rule:** the language is a **global setting** (`current_language`) that
applies to all editors. It is **not** remembered per `EditorInfo`/package.

Rationale: (i) there is no autocorrection and no dictionaries (DESIGN §7.1),
so no language-dependent text state that would need protection per field; (ii)
the ASR language is **auto** and is **not** changed by the layout switch
(DESIGN §3: "a keyboard layout switch does not automatically change the
ASR") — a per-field layout language would suggest a coupling that does not
exist; (iii) per-field memory is observable behavior with error potential
(wrong layout after an app switch) that we cannot test.

**During active recording (state ≠ IDLE) the language switch is locked:**
`onAction("language")` is ignored, status row `Sprachwechsel während Diktat
nicht möglich`. Reason: the committed text is in the old language's
orthography, the running hypothesis is decoded in the old language; a switch
mid-run would produce mixed-script revisions and rollbacks across language
boundaries and violate the commit invariants (E8). After release (IDLE) the
switch is immediately allowed again.

---

## E9.3 — Selection list: **in-IME overlay, no dialog, no activity switch**

| Aspect | Decision |
|---|---|
| UI | overlay `FrameLayout` **in** the IME window above the keyboard (own child view), **no** `Dialog`/`PopupWindow` with its own window token, **no** activity |
| Focus | stays intact because no new window is created: `currentInputConnection` remains valid, `onStartInputView` is not triggered again; the panel only consumes touches within its area |
| Entries | **15** (all assets), including inactive ones: one row each `endonym` + ISO tag, e.g. `Deutsch · de-DE`, `Русский · ru-RU` — the endonym is mandatory (the user looks for their language in their script) |
| Language of the labels | endonyms are **not** translated; the panel heading comes from Android resources (device language) |
| Arrangement | grid **3 columns × 5 rows** in canonical order; touch target ≥ **48 dp × 48 dp** (accessibility), `contentDescription` = endonym + tag |
| Selection | tap = set `current_language`, `setLayout(...)`, close the panel, `sendAccessibilityEvent(TYPE_WINDOW_CONTENT_CHANGED)`; **no** `commitText`, no text loss |
| Abort | tap outside, back key (`onKeyDown(KEYCODE_BACK)`), `onFinishInputView`, editor switch, or language switch via short tap → the panel closes without changes |
| Active language | marked in the grid (checkmark + `selected` state), inactive ones grayed out with the hint "activate in setup" |

---

## E9.4 — Emoji scope v1: **384 characters, 8 categories, one JSON asset**

**Decision:** **8 categories × 48 = 384 emoji**, offline, without search,
without skin tones, without history/favorites.

| # | Category (`id`) | Count |
|---|---|---|
| 1 | `smileys` (faces/emotions) | 48 |
| 2 | `people` (people/gestures, no skin-tone variants) | 48 |
| 3 | `animals` (animals/nature) | 48 |
| 4 | `food` (food/drink) | 48 |
| 5 | `travel` (travel/places) | 48 |
| 6 | `activities` (sports/leisure) | 48 |
| 7 | `objects` (objects/tools) | 48 |
| 8 | `symbols` (symbols/arrows/hearts; **without** flags) | 48 |

**Storage:** `app/src/main/assets/emoji/emoji_v1.json`

```json
{"schemaVersion":1,
 "categories":[
   {"id":"smileys","labelKey":"emoji_cat_smileys","emoji":["😀","😃","…"]},
   …]}
```

- `labelKey` refers to `strings.xml` (translatable); `emoji` is a flat list
  of **exactly one grapheme cluster** per entry.
- Validation in the test: exactly 8 categories, 48 entries each, no
  duplicates, NFC, **no** ZWJ sequences, **no** skin-tone modifiers
  (U+1F3FB–U+1F3FF), no regional indicator pairs (flags) — with that every
  character is a codepoint pair or single codepoint and the insertion length
  is deterministic.

**Display:** panel with 8 category tabs (endonym-free, icon/label from
resources), grid **8 columns × 6 rows = 48 per page** (one page per
category), paging via the category tabs; no swiping needed (v1). Above the
keyboard, same overlay technique as E9.3 (focus stays).

**Insertion:** via the **existing** path `listener.onTextInput(emoji)` →
`VoiceKeyboardService.onTextInput` → `ic.commitText(text, 1)`. No new editor
write path, no use of `setText`/`setComposingText`; the emoji lands at the
cursor position and the cursor stands behind it afterwards. During an active
recording (state ≠ IDLE) the emoji panel is **locked** (as in E9.2), because
a manual foreign edit ends the dictation's ownership (DESIGN §6) — status row
`Emoji während Diktat nicht möglich`.

**Explicitly NOT in v1:** skin-tone/gender variants, search, "recently
used"/frequency history (KONZEPT: no learning dictionaries, no learning
behavior), stickers, GIFs, clipboard insertion.

---

## E9.5 — Six verifiable statements (test matrix)

| # | Test | Statement |
|---|---|---|
| T1 | JVM | the `active_languages` default is `"de-de,en-us"`; after changes in the setup list the **canonical** order is preserved; deselecting the last language is refused |
| T2 | JVM | `nextLanguage(current, active)` with `["de-de","en-us","ru-ru"]` yields the sequence `en-us → ru-ru → de-de` (wrap); with only one active language it returns the same ID plus a hint flag |
| T3 | instrumented | after switching to `ru-ru`: `view.layout.id == "ru-ru"`, `activeLayerName == "base"`, a base key carries a Cyrillic label (e.g. `ф`), and `currentInputConnection` is still not `null` (focus intact) |
| T4 | instrumented | `onAction("language")` **during** RECORDING changes neither the layout nor `current_language` and sets the status row `Sprachwechsel während Diktat nicht möglich` |
| T5 | instrumented | an emoji tap inserts **exactly** the expected character at the cursor position (`textBeforeCursor` grows by 2 UTF-16 units for surrogate pairs) and does **not** alter the existing old text (prefix byte-identical) |
| T6 | JVM | `emoji_v1.json` validates: 8 categories × 48, no duplicates, NFC, no ZWJ/skin-tone/flag sequences, exactly 1 grapheme cluster per entry |

**Additional acceptance (manual, emulator):** language switch with the
keyboard open in an `EditText` — the cursor stays in the field, no character
is inserted, and the next typed key writes in the target language.

---

## ASSUMPTIONS (documented because the assignment left them open)

- `ASSUMPTION: Flags are not included in v1 — why: regional indicator pairs are`
  `not single codepoints and would complicate the deterministic insertion length`
  `(T5) and the asset validation (T6); 8 categories without flags fulfill`
  `"full-fledged keyboard" for dictation corrections.`
- `ASSUMPTION: The language panel lists all 15 languages (including inactive) —`
  `why: otherwise the user would have to leave the app to activate one; inactive`
  `entries are visibly marked and point to the setup.`

---

# E10 — emoji panel: back route, categories, gesture bar (addendum
2026-10-28)

**Decision basis:** E9.4 of this document (384 glyphs, 8 × 48, grid, insertion
via `onTextInput → commitText(text,1)`), `docs/SETUP_UI_DECISIONS.md` E3 ("the
keyboard never blocks the input"), `docs/KONZEPT.md` (offline, no learning
functions).

**Measured current state (<device>, 1080×2400, IME from y≈1512):**
(1) the panel covers the **entire** IME area including the 😊 key → no way
back; only the **system button "hide keyboard"** helps, and that closes the
*whole* keyboard (E3 violation). (2) `labelKey` is loaded but **shown nowhere**;
the display only says "1.1/2". (3) the paging row lies below the **gesture
pill**. (4) the real page size is **4 × 8 = 32** (not 48) — the display is
consistent with that.

**Additional finding (asset/code check):** `emoji_v1.json` is correct (8
categories × 48, `labelKey` = `emoji_cat_smileys` … `emoji_cat_symbols`), but
**none of these eight string keys exists in `res/values/strings.xml`**. That is
why the label is visible nowhere — not only the display logic is missing, but
the source.

## E10.1 — Back route: **(c) the bottommost keyboard row stays visible**

**Decision:** the emoji panel draws **only above the key-row region** and
leaves the **bottommost keyboard row** (ABC/`?123` switcher, `🌐`, `😊`,
`Leer`, `⏎`, mic) unchanged and visible. The **😊 key is a toggle**: when
open → one tap closes the panel and returns to the previous layer (letters or
symbols, exactly the layer the panel was opened from).

- Bottom panel edge = **top edge of the bottommost keyboard row** (no
  covering).
- **One** tap suffices; the keyboard stays visible; `currentInputConnection`
  stays unchanged (no `finishInput`, no activity/dialog switch).
- The return layer is remembered on opening (`savedLayer`) and restored on
  closing — not hard-coded to `base`.

**Rejected:**
- **(a) ABC key on the left in the paging row:** wrong label as soon as the
  panel was opened from the **symbol layer** ("ABC" promises letters but
  delivers `symbols`); moreover a **second** back route next to the already
  visible keyboard row (two routes = two error sources).
- **(b) × top right:** costs a grid slot, lies in the corner least reliable
  for hits (edge gestures), and is a **third** close route besides 😊 and
  system back.
- **(d) another tap on the system IME bar:** not our UI, device-dependent,
  and per the measurement it closes the **entire** keyboard — precisely the
  E3 violation.

## E10.2 — Categories visible: **name in the paging row, source
`strings.xml`**

**Decision:** the paging row shows in the middle **`<category name> <k>/8 ·
<s>/<S>`** (e.g. `Tiere 3/8 · 1/2`), `‹` on the left, `›` on the right. No
tab strip, no new fixed row (space: the 32 glyphs per page stay untouched),
no overlay while paging.

- **Source of the names: `res/values/strings.xml`** via the `labelKey` stored
  in the asset (`emoji_v1.json → categories[].labelKey`). The display is thus
  **in the device language** (E9.3 rule) and the asset stays locale-neutral.
  The `labelKey` **must** exactly match the string name — the test checks this
  link (E10.5/T3) so that coder and test mean the same source.
- **Missing keys are an error**, not a silent fallback: if a string is
  missing, the row shows the category `id` in parentheses and the test fails.
- Text style: 12 sp, single line, `ellipsize=end`, `contentDescription` =
  "Kategorie <Name>, Seite <s> von <S>, Kategorie <k> von 8".

**The 8 display names (German, binding — exactly these strings):**

| `id` | `labelKey` | German |
|---|---|---|
| `smileys` | `emoji_cat_smileys` | `Gesichter` |
| `people` | `emoji_cat_people` | `Personen` |
| `animals` | `emoji_cat_animals` | `Tiere & Natur` |
| `food` | `emoji_cat_food` | `Essen & Trinken` |
| `travel` | `emoji_cat_travel` | `Reisen & Orte` |
| `activities` | `emoji_cat_activities` | `Aktivität` |
| `objects` | `emoji_cat_objects` | `Objekte` |
| `symbols` | `emoji_cat_symbols` | `Symbole` |

(The eight keys must be **created new** — they are missing from
`strings.xml` today.)

## E10.3 — Gesture bar distance and page size

- **Lower bound:** the whole keyboard (and thus the bottommost row) gets
  **≥ 24 dp** of inner padding at the bottom to the system **gesture insets**
  (`WindowInsetsCompat.Type.systemGestures()` or `navigationBars()`),
  measured from the top edge of the gesture pill. The **paging row** lies
  with **≥ 8 dp** clearance above the top edge of the bottommost keyboard row.
  With that the pill is by construction **never** above the paging row
  (current-state defect 3 fixed). With 3-button navigation the inset is 0 —
  the rule stays valid (only the buffer drops away).
- **If a grid row disappears** (small displays, large font, display cutout):
  `pageSize = rows × 8` is computed **from the actually measured space**,
  `pages(cat) = ceil(48 / pageSize)`, and the display **always** shows the
  computed number. **Forbidden:** a hard-wired page count ("2") or a display
  that does not match the realized page size (current-state defect 4: 32
  instead of 48, the display matched only by chance). With 4×8 = 32 that is
  **2 pages per category**, the second page partially filled with **16**
  glyphs — that is correct and not a gap.

## E10.4 — State rules (closing/opening/language switch)

1. **Remembered on closing:** the **category** is preserved; the **page** is
   reset to **1**. Rationale: whoever reopens the panel is usually looking
   for a *different* character; a stuck half-filled page 2 (16 of 32 slots)
   is the worse starting position. No learning behavior (KONZEPT).
2. **Language switch with the panel open** (the code already calls
   `hideEmojiPanel()`): the panel closes, **the category stays**, page → 1,
   the new layout loads; the editor focus remains. No character is inserted.
3. **Tap on 🌐 with the panel open** (visible again thanks to E10.1):
   identical to (2) — close first, then switch the language; **never** leave
   the panel open across a language switch (grid geometry and counting
   depend on the layout).
4. **Dictation active (state ≠ IDLE):** the panel stays locked (E9.4),
   status row `Emoji während Diktat nicht möglich`; an opening attempt
   closes nothing.
5. **Insertion:** unchanged via `onTextInput → commitText(text,1)`; the panel
   stays **open** after insertion (several emoji in a row), the cursor stands
   behind it.

## E10.5 — Four verifiable JVM statements

| # | Test | Statement |
|---|---|---|
| T1 | `EmojiPanelCloseTest` | the close signal delivers **exactly one** action (`panelClosed`) and changes **neither** the editor text **nor** the keyboard layer except restoring `savedLayer`; `currentInputConnection` stays set (no `finishInput`) |
| T2 | `EmojiPagingTest` | for every category `pages = ceil(48 / pageSize)`, and **concatenating** all pages yields **exactly** the asset list (no losses, no duplicates, a partially filled last page allowed); the displayed number matches `pages` |
| T3 | `EmojiCategoryLabelTest` | every `labelKey` in the asset exists as a string resource, and the displayed text matches the resource value (DE fixture: the 8 names from E10.2); a missing key turns the test **red** |
| T4 | `EmojiPanelStateTest` | after `open → (category 3, page 2) → close → open` the category is 3, the page 1; after a language switch with the panel open, the panel is closed, the category preserved, and the layout = target language |

**On-device acceptance (manual):** open the panel → insert an emoji → tap
**😊** → the keyboard is immediately back in the previous layer, the inserted
character is in the field, the cursor blinks in the same field; the paging row
is fully visible (not covered by the gesture pill) and shows
`Gesichter 1/8 · 1/2`.

## ASSUMPTION lines

- `ASSUMPTION: The category is preserved on closing, the page is not — why:`
  `reentry usually searches for a different character; a half-filled page 2 as`
  `the start would be misleading.`
- `ASSUMPTION: No tab strip in the grid — why: it costs one glyph row (32 → 24`
  `per page, 3 pages per category) and thus exactly the space that E9.4 marked`
  `as scarce; the paging row carries name and progress without an extra row.`
