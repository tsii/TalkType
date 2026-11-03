# Review F23 — Hint field/-drawing, de-de target layout, hint tappability

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `LayoutAsset`/`LayoutParser` (hint), `KeySurface`/`KeyboardView` (drawKeyHint), `LongPressMenu.withHint`, `de-de.json`, `LayoutCompletenessTest`. No build/emulator (orchestrator device findings adopted).

---

## Findings table

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| **P2** | `KeyboardView.kt:392` (`drawKeyHint`) | **"only base/shift" is documented (§7.4), but not checked in code.** `if (src.kind != TEXT \|\| src.hint == null) return` does not check `activeLayerName ∈ {base, shift}` either — a future `hint` on a `symbols`/`symbolsShift` text key would be drawn (violates §7.4 "draw/clear per layer"). Harmless today, because only `de-de`-`base` (digits above q–p) carries hints. | Add `&& (activeLayerName == "base" \|\| activeLayerName == "shift")` to the guard clause (asset safety instead of code tolerance). |
| Note | `LayoutParser.kt:124-127` (hint tolerance) | **Tolerance correct, but a 2-UTF16 astral hint is accepted.** `optString` never throws (number→"5" accepted, object/array→toString→`length>2`→null, >2 characters→null) — no crash, no layout loss. A surrogate pair (e.g. "😀" = 2 units) passes the `length>2` gate (2 ≤ 2) and would be drawn as a legend. Harmless, but not grapheme-checked. | optional: `h.codePointCount(0,h.length)==1` instead of `h.length>2`. |
| Note | `LongPressMenuTest`/`LayoutCompletenessTest` (matrix) | **Matrix is manual maintenance — "silent gutting remains possible".** `MatrixRow("de-de", emptyList(), …)` + separate test `de-de - umlauts and eszett are the first alternate` (line 95) catches (a) "de-de loses umlaut alternate" red; but (b) gutting `requiredOwnBaseKeys` of a **different** language (e.g. `pl-pl`→`emptyList()`) does **not** turn red — the matrix is the only source, there is no cross-check against `LAYOUT_REQUIREMENTS §2.2`. | A (property) test that reconciles the matrix A-/B-sets against the §2.2 table from `LAYOUT_REQUIREMENTS` (otherwise §2.2 drift stays invisible). |

---

## Answers A–H

- **A) Tolerance — robust, no crash/no keyboard failure.** `LayoutParser.kt:124-127`: `optString` coerces (number→"5", object/array→toString), `h.isEmpty() || h.length > 2 → null`. No LayoutException path via hint; `loadOrFallback` is never triggered by hint. Only remainder = astral hint (note above).
- **B) Drawing — hint never transformed, collision→off; layer gate missing.** `drawKeyHint` (KeyboardView:390-410): `kind==TEXT && hint!=null`, `hintPaint` uses the raw `src.hint` (no shift/caps), and `if (baseline+descent > key.bottom) return` (collision→off). **But** no `base/shift` gate (P2 above) — §7.4 is only KDoc, not a code check.
- **C) `withHint` — digit at the end, no duplication, hint drops first.** `LongPressMenu.withHint` (lines 31-35): `hint.trim()`; empty/in `alternates`→unchanged; `alternates.size >= maxCells(8)`→hint **omitted**. 8 language alternates + hint ⇒ 8 alternates (hint dropped) — exactly the spec.
- **D) de-de geometry:** base `[11.5, 9, 9, 13]` **==** shift `[11.5, 9, 9, 13]` ✓; foot row Σ 13 ✓. "Exactly one period per layer" and "`. , ? !` on symbols exactly 1× each" I did **not** recount per character (open — covered by `PunctuationCoverageTest`/artifact test). `ẞ` in shift-`s`/`ß` = carried over from the F13 state (unchanged, covered via `shiftPairs = ß→ẞ`). `git diff --stat` = **only** `de-de.json` (74+/134−) ⇒ 14 layouts unchanged in content.
- **E) Test quality:** (a) de-de loses umlaut alternate → separate test (line 95) **red**; (b) another language silently emptied → **not red** (matrix without §2.2 cross-reference, note above).
- **F) Cursor refs not dead:** only `de-de` had `‹ ›` removed; the **other 14 layouts** still carry them. Refs remain live: `LayoutAsset.kt:60-61` (`KeyActions.CURSOR_LEFT/RIGHT`), `VoiceKeyboardService.onAction` (DPAD handler), `LayoutArtifactTest:37` (allowlist), `MicAndGlobeStateTest:44-45` (fixture). No orphan path.
- **G) Rule 9:** VK-HIT remains a static key label + geometry, `FLAG_DEBUGGABLE`-only — no user/editor/audio content. Defensible.
- **H) Rule 2 (ASR/dictation unchanged):** hint/drawing/`withHint` touch only `layouts`/`keyboard`; `dictation`/`asr` (finale, pace, commit) are untouched — the layout change is purely typographic/tappable.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 1** (missing `base/shift` gate in `drawKeyHint`) · 2 notes (astral hint tolerance; matrix without §2.2 cross-check).

**Open (to provide evidence I would need):** a `./gradlew test` run (419/0), the per-character check "exactly one `.` per layer / `. , ? !` exactly 1× each on symbols", and the three device findings (digit grid, backspace top right, hold cell vs. no movement) as my own reproduction.
