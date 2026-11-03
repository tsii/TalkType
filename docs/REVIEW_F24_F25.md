# Review F24/F25 — Hint additivity (9 layouts) + language-switch wiring

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `VoiceKeyboardService` (onAction/language switch/deferred application), `git diff` over 9 layout files, hint-freedom of special keys, hint in base+shift. No build/emulator. **F24c (tr-tr/pl-pl/ru-ru/el-gr) is currently running — not checked.**

---

## Findings table

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| **P2** | `VoiceKeyboardService.kt:applyLanguage` (loadOrFallback + `putString`) | **Missing asset ⇒ status lies.** `applyLanguage(id)` sets `putString(KEY_CURRENT_LANGUAGE, id)` and shows `language_switched_to(endonym(id))`, but `repository.loadOrFallback(id)` silently falls back to de-de when the file is missing — space bar/display show the x endonym while the layout is de-de. All 15 assets do exist, but the night path (corrupt/removed asset at runtime) is unmarked. | Detect the fallback (e.g. `loadOrFallback` returning the actual ID) and then show the real (fallback) name, not the requested one. |
| **P2** | `KeyboardView.kt:376-399` (`drawKeyHint`, anchor top right) | **Collision risk with narrow rows.** The digit hint sits top right; with 12-key rows (~63 px key) it can collide with the center label — the guard check `baseline+descent > key.bottom` only catches **vertical** overlap, not horizontal (no spacing to the label). Currently **no** hint layout carries a 12-key row (all r0 of the 9 = 10–11 keys), hence latent. | Account for horizontal tightness (e.g. clamp `hintAnchorX` against the label ink rect, or hide the hint when `keyWidth < threshold`). |
| Note | `VoiceKeyboardService.kt:again` | **No a11y announcement after a language switch.** Key labels/`contentDescription` are re-set via `setLayout`, and the status line shows the endonym — but there is no `announceForAccessibility`. Screen readers learn about the switch only by re-exploring. | optional `rootView?.announceForAccessibility(endonym)` after `applyLanguage`. |
| Note | (14 layouts vs. de-de) | **Footer inconsistency.** de-de lost `‹ ›` (cursor keys), the other 14 still carry them → the footer changes on language switch. Functionally non-critical (no language breaks), but as a transition defensible only until F24c/d. | Unify in F24c/d; until then document as a conscious transition. |

---

## Answers

1. **F25 language switch — wired, deferred application is honored.**
   a) `onAction` is **complete**: `BACKSPACE/SPACE/ENTER/CURSOR_LEFT/CURSOR_RIGHT/LANGUAGE/MIC/EMOJI` all have a branch (`VKS:970-998`; `EMOJI→toggleEmojiPanel`, `MIC→micToggleAction`, `LANGUAGE→switchToNextLanguage`). No `KeyActions.*` without a branch.
   b) `pendingLayoutId` is **applied on the next mic-down**: `applyPendingLayoutBeforeCapture()` is called in `onMicDown` (VKS:373) and applies `applyLanguage(target)` if the target is active and ≠ current. Nothing gets stuck.
   c) Missing asset → silent de-de fallback **with wrong display** (P2 above).
   d) Labels updated (space bar + status), but no a11y announcement (note above).

2. **F24a/b additivity — purely hint-additive, 0 deviations.** `--numstat`: `en-us 40/20 · nl-nl 32/12 · it-it 34/14 · es-es 32/12 · da-dk 34/14 · fi-fi 38/18 · sv-se 36/16 · fr-fr 28/8 · cs-cz 30/10`. Every `-` line is an old `"value": "x"` line, every non-hint `+` line does **not** exist (all `+` lines contain `"hint"`); the "removed" is only the line re-wrap (`"value":"q"` → `"value":"q",` + `"hint":"1"`). No `width`/`alternates`/key changed or removed.

3. **hint in base AND shift — proven.** In `en-us` the `-` lines cover both `q..p` (base) and `Q..P` (shift) → both layers carry the same hint. qwerty (en-us) and qwertz (cs-cz) likewise.

4. **Special keys hint-free — AOSP-conformant.** r0 end without hint: `da-dk/fi-fi/sv-se` = **1** (`å`), `it-it` = **2** (`è é`), `cs-cz` = **2** (`ě š`). The digit hints sit only on q…p, never on special keys. ✓

5. **Wide rows:** see P2 above (horizontal unchecked, latent, currently not hit).

6. **Footer:** see note above (transition, P2-like until F24c/d).

6b. **Rule 9:** new code/logs without user content; `docs/AOSP_ROWKEYS_BELEGE.md` quotes only file/key names (allowed). No violation.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 2** (fallback display lie; horizontal hint collision latent) · 2 notes.

**Diff lines per layout:** en-us 40/20 · nl-nl 32/12 · it-it 34/14 · es-es 32/12 · da-dk 34/14 · fi-fi 38/18 · sv-se 36/16 · fr-fr 28/8 · cs-cz 30/10.

**Mandatory before v0.1.16 (build 17):** nothing blocking — the two P2s are UX/night-path quality; **F24c (tr/pl/ru/el)** as the next reviewed block must go through the same additivity check. Open: test run not executed myself (O/S 419/0 adopted), `pendingLayoutId` persistence (RAM-only, lost on process restart — documented as in F10).
