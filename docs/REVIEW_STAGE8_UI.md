# Review Stage 8 — UI (Emoji V2, KeyboardView P0 rebuild, language/emoji wiring)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-28
**Subject:** `ImeRootView.kt`, `EmojiPanelView.kt`, `VoiceKeyboardService.kt` (emoji/language switch), `KeyboardView.kt` (P0 repair), `strings.xml`. **No build/emulator** (Gradle lock); orchestrator measurements used as geometric evidence.

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| UI-01 | **P2** | `KeyboardView.kt` `displayLabel` (`KeyActions.SPACE -> "Leer"`) | **Space label hard-coded German (L-01).** `displayLabel` overrides the layout label for action keys; `SPACE → "Leer"` thereby ignores the already localized `label` of the 15 layouts (`pt-pt` has "Espaço", en-us would expect "Space"). All languages show "Leer". **Not dist-blocking** (cosmetic, the key works), but a genuine i18n defect. | `displayLabel`: `when(src.value){ … KeyActions.SPACE -> "Leer" … else -> src.label }`. | Drop `SPACE` from the hard-code list and use `src.label` (the layouts already carry the label) — hard-code only genuine symbols (`⌫`, `⏎`, `⇧`/`⇪`). |
| UI-02 | Note | `ImeRootView.kt:96-135` + measured rects | **Geometry consistent — P0 repair confirmed.** Orchestrator: row centers 1712/1864/2016/2168 (spacing exactly 152 px = `keyHeightPx` 52dp=136.5 + `rowGapPx` 6dp=15.75); emoji key `rect=<214,456,291,593>` (height 137 px ≈ 136.5). Matches `keyHeightPx=52*density`, 4 rows. The single-angle basis holds: `onMeasure` rebuilds on width **and** height changes (`lastModelWidth != width || lastMeasuredHeight != measuredHeight`), `onDraw` and `keyAt` share the same `model`. No contradiction. | `KeyboardView.onMeasure/onSizeChanged`, `keyHeightPx=52f*density`, measurements. | — (no finding). |
| UI-03 | Note | `ImeRootView.kt` (`showEmojiPanel`/`hideEmojiPanel`) + `VoiceKeyboardService` `toggleEmojiPanel`/`onEmojiSelected` | **No focus/ownership break in the emoji path.** The panel is a stack child (KbdView GONE, emoji block VISIBLE), no own window → `currentInputConnection`/focus remain. Insertion exclusively via `onTextInput → ic.commitText(text,1)`; no `restartInput`, no `finishInput`, no `setComposingText`. Opening **and** inserting are blocked when `state != IDLE` (`toggleEmojiPanel` + `onEmojiSelected`). Mic down with an open panel closes it first (line `onMicDown` → `hideEmojiPanel`). `OwnershipTracker`/`InputConnectionSink.ownSpan` are not touched. | `onTextInput` = `ic.commitText`; `toggleEmojiPanel`/`onEmojiSelected` gate `c.state != IDLE`. | —. |
| UI-04 | Note | `VoiceKeyboardService.onDestroy` | **Double `abort()` (harmless).** `onDestroy` calls `coordinator?.abort()` and afterwards `coordinator?.shutdown()` (which internally executes `abort()` again). `abort()` is idempotent (`active.set(false)`, `channel?.close`, `controller.abort`) → no error, just redundant. No more final-worker race (N-3 correctly eliminated). | `onDestroy`: `abort()` + `shutdown()` (which contains `abort()`). | Optional: directly only `shutdown()` (contains `abort()`), drop the `abort()` call. |
| UI-05 | Note | `KeyboardView.onMeasure` (height tracking) | **`lastMeasuredHeight != measuredHeight` is nested/confusing.** The comparison runs against the *previous* `measuredHeight` instead of a clean "last height" value; effect: the rebuild trigger for height changes hangs on `onSizeChanged` (which fires anyway). Functionally covered by `onSizeChanged(w,h)`, but fragile to read. | `onMeasure` condition `lastModelWidth != width \|\| lastMeasuredHeight != measuredHeight`. | Compare `lastMeasuredHeight` against the `h` set in the last round (clearer), or leave the height rebuild solely to `onSizeChanged`. |

---

## Point-by-point answers (REVIEW QUESTIONS)

- **Focus/input chain:** no path breaks the editor focus — the panel is a pure stack child without its own window; `onStartInput`/`onFinishInputView` close the panel (`V2 §3.7`) **and** call `coordinator?.onEditorChanged()`/`stopDictationIfRunning()` — cursor ownership is thereby not violated but correctly relinquished. `resetShiftState` on a language switch (L-02) prevents a dead shift; no `restartInput`/`finishInput` in the language or emoji path. **ASSUMPTION:** the panel shares the focus correctly because `commitText` runs over the existing `currentInputConnection`.
- **Dictation interplay:** opening **and** inserting are blocked when ≠IDLE; a late touch event after `abort()` cannot reach a GONE panel (no hit surface). The emoji path uses **no** `onEditExecutor` (direct `commitText`, synchronous on the main thread) → the A1 `abandoned` flag is not affected here and there is no ghost-write danger.
- **Geometry:** one unambiguous coordinate basis (`model` for `onDraw` **and** `keyAt`); `rebuildModel(width ?: lastModelWidth)` is safe because `setLayout` first sets `lastModelWidth>0` (a negative `totalWidthPx` cannot occur); measurements match `keyHeightPx`/row pitch (UI-02).
- **Resources:** 8 category names exactly per E10.2 (faces, people, animals & nature, food & drink, travel & places, activity, objects, symbols) + `emoji_back_to_keyboard` "Zur Tastatur" — 8/8 correct. The only remaining silent-German hard-coding = `SPACE → "Leer"` (UI-01, L-01).
- **Regressions:** symbolShift ping-pong fix (`shift` in symbol layers → `symbols↔symbolsShift`), double-tap ordering (P1-02), backspace grapheme, push-to-talk via the row mic — all consistently preserved; no silent behavior change found.

---

## Verdict

**ready for release** (for the dist APK) — **0 P0, 0 P1, 1 P2** (UI-01 "Leer"). The P0 repair is confirmed by the orchestrator's measurements (spacings/key rects); the emoji path is focus- and ownership-safe; the only finding is the L-01 cosmetic issue.

**Not checked (no device/emulator):** real microphone + dictation over the emoji panel, actual emoji glyph rendering/width, TalkBack on the emoji grid/footer, behavior with a hardware keyboard/`TYPE_NULL`.

## Top-3

1. **UI-01 (P2)** — No longer hard-code `SPACE` to "Leer"; use `src.label` (the layout provides the localized label).
2. **UI-05 (Note)** — Simplify the `onMeasure` height tracking (compare against the last `h`) to make the rebuild triggers explicit.
3. **UI-04 (Note)** — Reduce `onDestroy` to a single `shutdown()` (contains `abort()`).
