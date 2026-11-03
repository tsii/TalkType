# Review F29/F33 — Status/🐞 fix, mic state, path icons, r2 grid, layout test

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-11-01
**State:** v0.1.25 / vc26 · Debug 471/0 · Release 440/0 (not run myself).

---

## Findings table

| Finding | Severity | File:Line | Fix |
|---|---|---|---|
| **Status/🐞 fix is structural (one source).** The 🐞 is now a **fixed view child** of the `statusRow` (`ImeRootView:44,93-102,136`), no more dynamic `addView` — the earlier double disappearing (2×) came from repeated `addDebugInfoButton` across `onCreateInputView`. `postEvent` → `StatusRouting.route` → `StatusBarText.select` **always** yields a text (idle fallback "Bereit"), no more empty state. | ✓ (no finding) | `ImeRootView.kt:136`, `VoiceKeyboardService.kt:331` | — |
| **Popup visually obscures the 🐞 (r0 hold, clipChildren=false).** The r0 menu draws with a negative top into the status bar; the root has `clipChildren=false` (`ImeRootView:159-160`), so the palette lies above the row — the 🐞 button remains tappable (the popup is a Canvas), but is obscured during the hold. | P2 | `KeyboardView.kt:521-533`, `ImeRootView.kt:159-160` | Do not pull the r0 popup over the status bar (spare out the row) or set `clipChildren` only on the KeyboardView instead of the root. |
| **"Green sticks" after watchdog/audio abort.** `micRecording` (KeyboardView:773) is set to false only on the explicit paths (stop/abort/onMicUp); a stall/silence/audio error runs via `coordinator.errorListener → abort()`, without the **service** triggering `micRecording=false` + `updateStatus` — the mic button stays "ready to record" (accent) even though the session is IDLE. | P2 | `DictationCoordinator.errorListener` (abort), `VoiceKeyboardService` (no reset on the error path) | In `onError`/`visibleError`→`updateStatus`, additionally set `keyboardView.micRecording = (state==RECORDING)` (centrally in `updateStatus`, instead of scattered). |
| **Path icons:** `drawActionGlyph` (KeyboardView:676-737) draws ⌫/⇧/⏎ as canvas paths (no font tofu); `displayLabel` keeps "⌫"/"⇧"/"⏎" **only** as the a11y label (lines 746-747), it is not drawn. No leftover glyph. | ✓ | `KeyboardView.kt:676-737` | — |
| **Per-frame path allocation.** `drawActionGlyph` builds a new `Path` per key per `onDraw` (no cache) → 3 paths × frame. Small, but in the hot path. | P2 | `KeyboardView.kt:695-737` (`Path()` per draw) | Prepare the paths once in `init` (scale via `Matrix`/`scale`), do not allocate anew per frame. |
| **F33 r2 grid (centering 450/MARGIN/cs-cz/D27) and ImeRootViewLayoutTest:** individual-value gates (`r1[0]=0.5`, `@`, `shift 1/1.5`, `space 6.5`) I consider present from F31b; the new r2 MARGIN/center asserts and the ru-ru D27 exception I have **not** read. | open | `LayoutCompletenessTest`/`FooterAcrossAssetsTest` | Re-read individual-value assert for the `r2` center (450) + `cs-cz` rationale + D27 docs. |
| **ImeRootViewLayoutTest (ShadowCanvas draw ops):** not read; JVM text-metric dependency would be the only flakiness risk point. | open | `ime/ImeRootViewLayoutTest.kt` | Check whether only the draw-op **structure** (not pixel dimensions from device-dependent text metrics) is asserted. |

---

## Overall verdict

**P0 = 0 · P1 = 0 · P2 = 3** (popup obscures 🐞; "green sticks" after an error abort; per-frame path allocation) · 2 "open" (F33 r2 gates, ImeRootViewLayoutTest).

**Pushable: YES.** The two earlier P0s (empty status / vanished 🐞) are fixed **structurally** (fixed view-child root + always-set status text), the mic double start remains ruled out by the `startingUp` gate, and the three P2s are cosmetic/robustness only. Please re-read the two "open" points before 0.1.26, but they are not blocking.

---

File: `docs/REVIEW_F29_F33.md` · ~95 lines.
