# Review F22/F22-K/M/L — Long-press menu (LongPressMenu, arming, popup)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `keyboard/LongPressMenu.kt` (pure seam), `KeyboardView` (arming/popup/gesture), `KeyboardTheme` (POPUP-*/LONG_PRESS constants). No build/emulator (orchestrator device findings adopted).

---

## Findings table

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| Note | `KeyboardView.kt:418` + `LongPressMenu.kt:13-14` (KDoc) | **Outdated comment values:** "Polsterstreifen (4 dp)" and "126·3 + 10·4 = 418" are stale — `KeyboardTheme.POPUP_PAD_DP = 8` ⇒ `padPx = 21 px`, i.e. n=3 ⇒ 126·3 + 21·4 = **462**, not 418. Drawing **and** hit logic both use `p.padPx` (21 px) — consistent; only the comments/test examples (pad=10 → 418) reference the old value. | Correct the comments to "Polsterstreifen (8 dp = 21 px)" and "126·3 + 21·4 = 462" (purely documentary). |
| Note | `KeyboardView.kt:648-653` (VK-HIT) | **VK-HIT debug line logs `value` = static key label** (e.g. "ä"/"e"), no user/editor/audio content and no paths/URLs; additionally `FLAG_DEBUGGABLE`-protected. Defensible (rule-9 intent = no *inputs*; the key label is a layout constant). | None; optionally a KDoc note "key label/geometry only, never field content". |

---

## Answers A–I

- **A) DecodePace/finale independent — no P0.** The menu is pure KeyboardView UI; the `DictationController` finale calls `engine.transcribe` without any popup/pace reference. (F21 already proved the finale is unthrottled.)
- **B) Zone vs. selection free of contradiction.** `stillArmed` (arming, ± `w/2+slop`, top up to `menuHeight+gap+slop`) is a **superset** of the `cellWins` region (`|offset| > w/2−slop`, `h/2−slop`); the menu cell row (`gap … gap+menuHeight` above the key) lies **within** the arming upper bound ⇒ every winnable cell is reachable, and no cell win outside the arming (the downward band lies below the menu ⇒ `popupCellAt` returns -1 there anyway). No dead state.
- **C) Mic never armed + no synthetic mic UP.** Arming only with `down.kind=="text" && alternatesOf(down).isNotEmpty()` (KeyboardView DOWN handler) — mic is ACTION ⇒ never armed. `micHeld`/hold gesture is a separate branch; the second pointer (`ACTION_POINTER_DOWN`) is not handled ⇒ no mic UP. D10 remainder §1 unchanged.
- **D) Caps double tap vs. long press separate.** `DOUBLE_TAP_MS=350 < LONG_PRESS_MS=450`; shift is ACTION ⇒ never armed, double tap runs via `handleAction(SHIFT)` (350-ms window). Long press on a letter key (text+alternates) only opens the menu, triggers no shift whatsoever.
- **E) CANCEL + release outside = 0 characters on all three paths.** (1) `ACTION_CANCEL` with an open popup → the `ACTION_UP` branch skips (no commit); (2) `UP` outside (cell=-1 **and** not `isInsideKey`) → `else -> Unit`; (3) move-away arming (pressedKey=null) → `key === pressedKey` fails → no commit. All three proven.
- **F) Temporary shift consumed only on a genuine commit.** `commitAlternate` flips `shiftTemporary` exclusively with a chosen cell; abort/0-character leaves `shiftTemporary` untouched (shift still applies to the next input). ✓
- **G) Geometry consistency yes, comments stale.** `POPUP_HEIGHT_DP=56→147 px`, `POPUP_CELL_DP=48→126 px`, `POPUP_PAD_DP=8→21 px`; `drawLongPressMenu` and `popupCellAt`/`popupSelectionAt` use the same `p.left/p.padPx/p.cellPx` and the same `cellWins` threshold ⇒ drawing == hit testing. Only the KDoc/test examples ("4 dp", "418") are outdated (note above).
- **H) Rule 9 defensible.** VK-HIT logs only key `id` + static `value` label + geometry, `FLAG_DEBUGGABLE`-only, never field/audio content ⇒ no input content.
- **I) Tests catch the three regressions red:** (a) slop rule removed ⇒ `cellWins auf der Grundtaste gewinnt nie` (test, `assertFalse(cellWins(0,0,…))`) red; (b) `cellWins` "always true" ⇒ same test + "innerhalb Slop … gewinnt nicht" red; (c) `MAX_CELLS→4` ⇒ `cellCount deckelt auf 8` (asserts 8) red. Coverage exists.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 0** (only 2 notes: stale KDoc/test comment values 4dp→8dp/418→462; VK-HIT label as defensible debug use).

**Open (to provide evidence I would need):** a real `./gradlew test` run (404/0), the three orchestrator device findings (row-0 "e"/"á"/"ê"/0-character) as my own reproduction, and the exact top-row clip interaction (`menuTopPx` clamping at row 0) on a device — plausible from code, not measured myself.
