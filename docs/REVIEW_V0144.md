# REVIEW V0.1.44 (vc45) — reviewer: DeepSeek V4 Pro 0813 (cortecs), 2026-11-04
Scribed by the orchestrator (reviewer session was read-only; content verbatim below).
Post-review actions appended at the end.

## Gesamturteil: NO-GO (P1 offen) — see Actions: fixed same night

### Correct (verifiziert)
- **T15b**: `keyboardBottomInWindow() = top + stack.bottom` (`ImeRootView.kt:374`);
  `onComputeInsets` (`VoiceKeyboardService.kt:534-546`) ruft `super` ZUERST
  (Fallback bleibt Framework-Standard), guardt via `keyboardSurfaceValid`
  (`isLaidOut && height>0 && stack.bottom>stack.top`, `ImeRootView.kt:380-382`),
  ankert Region/Insets an stack.top/stack.bottom statt `root.bottom`.
- **onEvaluateFullscreenMode=false** (`VoiceKeyboardService.kt:512`): Produkt ohne
  Extract-UI — korrekt.
- **navBottomPx** (`ImeRootView.kt:85-99`): API-Guard `>=R && insets!=null`,
  API29-Fallback identisch, `?:0` null-sicher, 120dp-Cap erhalten.
  „exactly once"-Contract (`setPadding` in `ImeRootView.kt:340`) intakt;
  `requestApplyInsets` re-triggert nur denselben idempotenten Listener.
- **Logger**: `insetsEvidenceLine` (`ImeRootView.kt:54-71`) nur Zahlen + feste
  Feldlabels, kein Nutzerinhalt (Regel 9), niedrige Frequenz.
- **T17-Fälle korrekt** außer UNSPECIFIED (s.u.): null, `NONE`, multiline,
  `NO_ENTER_ACTION` → `commitText("\n",1)`; deckungsgleich mit AOSP `isNoAction`.

### Findings

| ID | Sev | Datei:Zeile | Befund |
|---|---|---|---|
| **VK-R1** | **P1** | `VoiceKeyboardService.kt:156` | Regression einzeilig: `IME_ACTION_UNSPECIFIED` ⇒ Newline |
| VK-R2 | P2 | `VoiceKeyboardService.kt:658` | `requestApplyInsets`-Seam + Logger ohne Test (PLAN kündete spy/counter-Test an) |
| VK-R3 | P2 | `onStartInputView` vs. `onWindowShown` | PLAN_T18 forderte requestApplyInsets in beiden; nur `onStartInputView` umgesetzt |

**VK-R1 (P1) — Detail.** `applyEnterKey` (`VoiceKeyboardService.kt:152-158`) setzt
`action == EditorInfo.IME_ACTION_UNSPECIFIED` auf Newline. Das widerspricht AOSP und
dem ausgelieferten v0.1.43:

- AOSP (`InputMethodService.sendDefaultEditorAction` und LatinIME
  `InputOptionsUtils.isNoAction`) behandelt **nur** `IME_ACTION_NONE` +
  `IME_FLAG_NO_ENTER_ACTION` als „no action" (NEWLINE). `IME_ACTION_UNSPECIFIED` (0)
  = „default action", wird an `performEditorAction(0)` geroutet — nie `commitText`.
- v0.1.43: `if (action != NONE) performEditorAction(action)` ⇒ für UNSPECIFIED(0)
  galt `0 != 1` ⇒ `performEditorAction(0)` (Default Done → Keyboard schließt).
  AOSP-konform.
- neu: UNSPECIFIED ⇒ `commitText("\n")` — im **einzeiligen** Feld ohne explizite
  `imeOptions` (einfaches `<EditText>`, z. B. Namenzfeld) wird der Newline
  verschluckt, `performEditorAction`/Dismiss unterbleibt ⇒ Enter bewirkt sichtbar
  nichts, Keyboard bleibt offen. Der Emu-Nachweis deckt nur GO/SEARCH + multiline
  ab, nicht diesen Fall.

`EditorActionEnterTest` (`single line + UNSPECIFIED - commitText newline`) zementiert
das abweichende Verhalten; die Begründung „not a concrete action" ist AOSP-widrig.
`PLAN_T17_ENTER.md:8` behauptet fälschlich „mirrors AOSP LatinIME".

**Minimal-Fix:** `VoiceKeyboardService.kt:156` (die `IME_ACTION_UNSPECIFIED`-Zeile)
entfernen ⇒ Newline nur für `null || NONE || multiline || NO_ENTER_ACTION`; Test
`single line + UNSPECIFIED → performEditorAction(0)` ergänzen. `IME_ACTION_NONE`
bleibt korrekt (AOSP `isNoAction`).

### Prüfpunkte (gefordert)
1. **Enter-Matrix**: vollständig/korrekt *außer* UNSPECIFIED (VK-R1) —
   IME_MASK_ACTION/multiline-Flag/NO_ENTER_ACTION/null korrekt;
   **Regression einzeilig = ja**.
2. **navBottomPx**: Guards ✓, Cap ✓ (`.roundToInt()` vs. altes `.toInt()` = nur
   1px-Artefakt an der Cap-Grenze, irrelevant), exactly-once ✓, null-sicher ✓.
3. **requestApplyInsets**: idempotent, kein Flicker-/Layout-Risiko; aber untestet
   (VK-R2).
4. **fullscreen=false/extract**: korrekt, Fallback via `super.onComputeInsets`
   abgesichert.
5. **Logger**: nur Zahlen, keine Inhalte, Frequenz ok.
6. **Testabdeckung 4 Stapel**: T15b=3, T17=7, T18-B=5, **T18-A=0** (Logger +
   requestApplyInsets unbelegt). Tests substanzhaltig (echte
   `WindowInsets.Builder`, `@Config("420dpi")` für ungecappten 132px,
   `dispatchApplyWindowInsets`-Pfad).

---

## Actions (Orchestrator, same night)

- **VK-R1**: fixed per Minimal-Fix (UNSPECIFIED-line removed from treatAsNewline;
  test `single line + UNSPECIFIED → performEditorAction` flipped to AOSP
  semantics). Coder session sa-a411442e; re-run EditorAction suite + assembleDebug.
- **VK-R2 / VK-R3**: P2, report-only. R3 rationale: `onWindowShown` does not exist
  as an override target in this service; `onStartInputView` fires on every
  show-with-focus, the emulator evidence (`ev=start` on reopen without IME switch)
  proves coverage of the practical case. R2 acknowledged; seam is 1 line,
  side-effect-free (idempotent listener re-trigger), emulator-proven.
- **Emulator duty program (user mandate) all GREEN** with the final APK:
  insets normal/reopen/rotation (`pad=132`; landscape `ign=0` held by
  `tappableElement` — live proof of the new max), Enter multiline (\n + open) and
  single-line URL (loads + closes), Chrome transition (no ghost window, footer
  live), smoke (keys/shift/symbols/mic), edge profile (footer ends exactly at
  2340−132, painting identical to stock Latin).
- **Release-suite isolation**: `DictationHoldGateTest` release-variant failure
  reproduces on CLEAN HEAD (stash-isolated run) ⇒ pre-existing/environmental
  (system load 14–37 from co-running projects), NOT introduced by 0.1.44.
  Debug variant 641/0 green, incl. all 4 hold-gate tests.
