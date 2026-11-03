# Static investigation — footer model paths (device contradiction)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Question:** Why does VK-HIT report `cursor-left/right` rects although the asset (working tree **and** pulled APK) no longer contains cursor keys?

---

## Answers (with lines)

**1) The model decides the footer row — no cursor keys are produced.**
`KeyboardModel.kt:69` builds every row via `D10_FOOTER.compose(layer.rows)`; `composeRow` (KeyboardModel ~127-159) does **nothing** other than: with `mic`+`enter` in the row → `space` by −1, `mic` to the end with width 2, returning everything else unchanged. **No key is added, hence also no `cursor-left/right`.** The mic key afterwards is a **model key** (not the status-bar `micButton`, which is its own `Button` in `ImeRootView.kt:75/112` and has nothing to do with the popup/footer-row mic). With the current `de-de.json` footer (row3 = `[symbols 1.5, language 1, emoji 1, space 6, period 1, mic 1, enter 1.5]`, Σ 13), `compose` necessarily yields `[symbols, language, emoji, space 5, period, enter 1.5, mic 2]` — **no** cursor pair.

**2) No second layout path with `‹ ›`.** `grep -rn "cursor-left" app/src/main/java` yields only: `LayoutAsset.kt:67-68`/`:72` (the `KeyActions.CURSOR_LEFT/RIGHT` **constants**), `VoiceKeyboardService.kt:973/976` (command branches in `onAction`), and `KeyboardView.kt:913-914` (a11y labels "Cursor links/rechts"). **No** embedded fallback layout, no `res/xml` footer, no in-code `LayoutAsset` that materializes cursor keys.

**3) VK-HIT is bound to the **current** model — and it is rebuilt on `setLayout`.** `KeyboardView.setLayout` (line 188) → `rebuildModel` (line 213) → `model = KeyboardModel(…)` (line 220) replaces the instance; `keyAtVisible` (lines 315-316) reads `model?.keyAt(x,y)`, and the VK-HIT line (line 724) logs exactly its `id`/`rect`. A "stale model" would only be explicable if `setLayout` did **not** run.

**4) / Conclusion — the device finding is incompatible with this code ⇒ build drift, not a code bug.** The measured rects (`cursor-left 623..706`, `cursor-right 706..789`, `enter 789..913` at unit 83.08 px) correspond **exactly** to the **old** `de-de` footer (`space 5` + `cursor-l/r` + `enter 1.5` + `mic 2`) after `compose`. But the pulled APK asset is the **new** footer (`space 6` + `period`). Since the code synthesizes no cursor keys (1+2) and `setLayout` replaces the model (3), only one explanation remains: **the installed v0.1.15 was built from a mixed state** (new asset committed, code/`KeyboardView`/model still compiled from the old state) — a **build/version drift** that is not reproducible in the source tree.

---

## Findings table

| Sev | Location | Finding | Fix |
|---|---|---|---|
| **P1** | installed APK v0.1.15 (not reproducible in the source tree) | **Asset↔model drift:** the APK contains the new `de-de` footer, but the running view builds the model from the old footer (cursor-l/r). Consistent in the working tree (asset==model). | Before any further footer rebuild: `assembleDebug` from a **clean** state + `adb install -r` + `force-stop` **and** check VK-HIT against the new rects; cause = mixed build state, not F23/F24d code. |
| Note | `LayoutAsset.kt:67-68` among others | `CURSOR_LEFT/RIGHT` constants/commands remain as dead-/other-language remainder (all 14 non-de-de continue to use them) — **no** bug, but the blur that made the drift noticeable. | After F24e (all 15 on the period key), remove the constants/`onAction` branches/a11y labels — then the only evidence that cursor keys ever existed will be gone. |

---

## TOTAL

**P0 = 0 · P1 = 1 · P2 = 0.** The "contradiction" is a **build/version drift** of the installed APK, not a defect in F23/F24d (source tree: asset and model are consistent, no second path, `setLayout` replaces the model).

**Mandatory to clarify before F24e (order):** (1) clean `assembleDebug` from the same commit as `de-de.json`; (2) match `adb shell dumpsys package … | grep versionName` against the build; (3) re-measure VK-HIT on the fresh APK and confirm `period`/`mic` rects (not `cursor`). Only then are the 13 further `‹ › → .` rebuilds (F24e) verifiably safe.

**Rule 9:** met — no user content, only key names/digits/geometry.
