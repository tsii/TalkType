# Review F15 — KeySurface (D11: pressed-key feedback, reinforced press, caps accent)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `keyboard/KeySurface.kt` (new), `KeyboardView.onDraw` (D11 seam), `KeyboardTheme.kt:88/103` (press alphas), `KeySurfaceTest`/`KeyboardThemeTest`. No build/emulator.

---

## Findings table (notes only — no P0/P1)

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| Note | `KeySurface.kt:38-45` (`surface` order) | **No defect — GLM's "deviation" is exactly D11.** `micRecording > CAPS_FILL > pressed > normal` matches DESIGN_STOCK_LOOK.md verbatim "Gedrückt+Caps behält diese Akzentfüllung … **ohne zusätzlichen Overlayauftrag**". The "lost" case (caps-active Shift shows no press darkening while pressed) is **intended by the decision**; the key shows accent + `⇪`, and deactivation (second tap) changes the surface only on release. No user problem. | None. |
| Note | `KeySurface.kt:42-44` (`capsFill` gate) | **Caps fill correctly only from genuine `capsLock`.** `istShiftKey && capsLock && !symbolShift && !activeLayerIsSymbols` — never `shiftTemporary` (line 529 "glyph accent only"), never `symbolsShift`, never symbols layer. Derived **live** per `onDraw` (no cache) → cannot stick after a layer/editor switch (`resetShiftState` lines 142/156 additionally nulls `capsLock`). | None. |
| Note | `KeyboardTheme.kt:88,103` + contrast calculation | **Contrast ≥ 3:1 met.** Light `micRecordingGlyph #37474F` on `accent #4DB6AC` ≈ **4.2:1**; dark `#263238` on `#80CBC4` ≈ **7.0:1** (relative luminance, hand-calculated). No P1. | None. |
| Note | `KeyboardView.kt:288-315` (`when` chain) | No hidden state: the checkmark (`micCheckShowing`) is a **glyph** (`keyToggledGlyph`, line 311) and is drawn on every surface; `micRecording` is the highest priority (D10 unchanged); the press overlay does not change text color; dark-mode readability remains (text on a minimally lightened surface). | None. |
| Note | `KeySurfaceTest`/`KeyboardThemeTest` (14 tests) | **Behavior + constants tested.** `KeySurfaceTest` checks the priority chain + "shiftTemporary does not fill" + "symbolsShift no caps fill" (would turn red on regression); `KeyboardThemeTest` asserts the **exact** `keyPressed` values `0xFFA3ABB0`/`0xFF676F74` (= `compose` from 0x66/0x4D, recalculated) — red on regression to 0x26/0x19. | None. |
| Note | (all files) | **Rule-compliant:** only existing tokens, no popup/frame/shadow, no geometry/touch-area/layout change (KeySurface is a pure decision, `onDraw` changes only `keyPaint.color` + glyph), no content in text/logs (only Int enums). | None. |

---

## Answers A–F

- **A:** GLM's priority is **not** a deviation — it is the literal D11 spec ("ohne zusätzlichen Overlayauftrag"). The only "loss" (caps-active Shift without press darkening) is intended per the specification; the `⇪` marker + the accent surface remain the feedback. No user problem.
- **B:** correct — `capsFill` only from `capsLock && !symbolShift && !activeLayerIsSymbols`; derived live, no sticking; `resetShiftState` (lines 142/156) + double tap (lines 481-487) null the genuine caps state.
- **C:** contrast light ≈ 4.2:1, dark ≈ 7.0:1 → **≥ 3:1**, no P1.
- **D:** no hidden branch; checkmark/toggle/mic accent/dark remain readable (checkmark = glyph, mic recording = highest surface priority).
- **E:** tests are meaningful (behavior in `KeySurfaceTest`, exact target values in `KeyboardThemeTest`); regression to 0x26/0x19 and caps fill from `shiftTemporary` would turn **red**.
- **F:** rule-compliant — only tokens, no surface/geometry/layout change, no rule-9 violation.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 0** (notes only). F15 implements D11 faithfully.

**Open (for re-measurement — I can provide the evidence myself):**
- Contrast values are **hand-calculated**, not device-measured; for a hard acceptance please confirm the relative luminance on a device/device dump (light ≈ 4.2:1).
- I read the 14 tests, did not run them (no build).
- Visible device evidence (light/dark "normal/pressed" + "caps on/off") is still pending per the D11 acceptance.
