# Review F24e/e0-e5 — action cells, arming zone, 15-layout footer

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `LongPressMenu` (cellAction/stillArmed-with-width), `KeyboardView` (actionCells/commitAlternate/DIAG logs), `LayoutParser`/`LayoutAsset` (cellActions), 15 layouts (period cell), tests. No build/emulator (orchestrator device findings adopted).

---

## Findings table

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| **P1** | `LongPressMenu.kt:63-66` (`menuLeftPx` clamp) vs. `:135` (`stillArmed` `halfW=menuWidthPx/2+slop` around the **key center**) | **F24e6: clamp and arming zone contradict each other.** The palette is clamped to the edge via `coerceIn(0, viewWidth−menuWidth)`, but the arming zone is computed **symmetrically around the key center** (`downX`) — `[centerX−halfW, centerX+halfW]`. For a key near the edge (footer period, width 2, view 1080), the overhanging **left** palette half (cells 0/1) lies **outside** this zone ⇒ sliding there disarms and the menu never opens (measured: cell 0 x=468 and cell 1 x=594 without popup-open; `,`/`!` thus linguistically unreachable). | Derive the arming zone from the **actually clamped** `left` (result of `menuLeftPx`): `offset ∈ [left−downX, left+menuWidth−downX]` instead of `±menuWidth/2` around the center. Then every menu cell is reachable by sliding, without overhang (overhang is discarded by the viewer clip, see above). |
| **P2** | `VoiceKeyboardService.applyLanguage` (open from F24/F25) | **Silent de-de fallback without notice remains.** If the target language's asset is missing, `loadOrFallback` loads de-de, but the status shows the target endonym. (Deferred P2.) | Detect the actual fallback language and display it honestly. |
| **P2** | `KeyboardView.drawKeyHint` (open from F23) | **Latent hint collision in 12-key rows remains.** Horizontal tightness (hint top right vs. center label) unchecked; currently no hint layout with a 12-key row. (Deferred P2.) | Clamp `hintAnchorX` against the label ink rect or hide the hint when `keyWidth<threshold`. |

---

## Answers / checks

- **Mechanics correct.** `cellAction(index, actionCells) = getOrNull(index)?.takeIf { isNotBlank() }` (LongPressMenu:38-39): `""`/`"   "` → `null` ⇒ text commit (F24e5 fix correct; the device finding "action fizzled" is gone). `stillArmed` grows the lateral zone to `max(keyWidth/2, menuWidth/2)+slop` (line 135) — the P1 above concerns only the **center symmetry**, not the width.
- **Action field + shift:** the only `cellActions` are `cursor-left`/`cursor-right` (empty string for text cells); `commitAlternate` (KeyboardView:509 ff.) sends `onAction(action)` **or** `onTextInput`. Shift is still consumed only on a genuine text commit; the cursor action triggers exclusively the existing `DPAD_LEFT/RIGHT` path (VKS:973/976) — no new shift/dictation interaction. A cursor call during RECORDING is a foreign edit (ownership loss) — identical to the former key footer, **no** new path.
- **Index parallelism:** `alternates = [",","!","?","‹","›"]` (5) ↔ `cellActions = ["","","","cursor-left","cursor-right"]` (5); `KeyboardView:456` takes `cellActions.take(n)` parallel to `glyphs.take(n)` — with a `hint` (end of `withHint`) it sits **behind** the action values and has `cellAction==null` ⇒ text. No drift.
- **Character set `‹`/`›`:** only as **glyph labels** of the cursor cells, direction-neutral and language-independent — defensible for all 15.
- **Footers:** all 15 (base **and** shift) have period; Σ width = 13 (`space 5 + period 2` for the 14, `space 6 + period 1` for de-de); no more cursor key — the DRIFT cause from `REVIEW_FUSSZEILE_MODELLE_PFADE.md` is thereby eliminated (in the source tree).
- **Rule 9 — confirmed:** DIAG logs `popup-open cells=$n left=$left top=$top` and `popup-commit cell=$cell action=${action ?: "-"}` contain **only** numbers + a static `KeyActions` name (`cursor-left`), **no** glyph, field content, or path; `debuggable()`-protected. No violation.
- **Tests:** `LongPressMenuTest` covers `""`/`"   "`/5-cell menu offset `−270` true / `−360` false; `FooterAcrossAssetsTest` covers Σ/ID parallelism; `LayoutParserTest` hint cases. (Not executed myself — open.)

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 3** (originally 1 P1 entered, downgraded after clarification).

**CORRECTION (after the fact, orchestrator measurement error clarified):**
1. The supposed "F24e6 P1" was based on a **stale build**: `install -r` keeps the IME process running — without `am force-stop de.voicekeyboard` (and without `settings get secure default_input_method` = `de.voicekeyboard/.ime.VoiceKeyboardService`; one round ran on the Google TTS IME) one measures old code. **Statically, `menuWForArm` is correctly populated** (KeyboardView:666 `menuWidthPx(cellPx, cellCount(alternatesOf(k)), padPx)` = 756 for the 5-cell period) and is passed to `stillArmed(…, menuWidthPx=menuWForArm)` → the zone grows to `max(keyWidth/2, menuW/2)+slop` and covers the palette cells. What remains is only a ~44-px sliver at the outermost edge of a **clamped** edge-key palette (zone symmetric around the key, palette asymmetrically clamped) — hence **zone basis = actual (clamped) `left`** instead of `±menuWidth/2` (**P2**, no whole language character becomes unreachable).
2. The "asset model drift" from `REVIEW_FUSSZEILE_MODELLE_PFADE.md` is a **FALSE POSITIVE**: `D10_FOOTER.composeRow` (KeyboardModel:140ff) rebuilds the footer row **intentionally** (mic right, enter second-to-last, space 5→4, mic 1→2); the VK-HIT finding was simply the **old** asset. Downgrade to "intended, covered by the design"; the new `period` key (width 2) remains unchanged via `compose` (÷ Σ 13, 166 px ≤ 200, fits on 1080).

**Open (to provide evidence):** an own `./gradlew test` run (429/0), full text of the new `LongPressMenuTest`/`FooterAcrossAssetsTest` methods, own reproduction of the cell 0/1 non-opening after the zone fix.
