# Review v0.1.35 (vc36) — T1/T2/T3/T4
Reviewer: DeepSeek V4 Pro 0813 (sa-ba715e7e), 2026-11-02 22:2x. Volltext im Session-Transkript.

## Merge-Verdikt
| Schnitt | Befunde | Verdikt |
|---|---|---|
| T1 Overlay-Zweizonen | — | OK |
| T2 Glyphen/Schrift | P2×4 stale Kommentare | OK with notes |
| T3 Tipp/Halte | — | OK |
| T4 Menüs/Parität | — | OK |

**P0 = 0, P1 = 0, P2 = 4 (report-only). Push: JA.**

## Kernprüfpunkte (alle BESTANDEN)
- T1: Zweizonenregel `LongPressMenu.menuTopPx:100-110` (Zone A above / Zone B top=0, nie below);
  Arming+Position gemeinsamer Wechsel (`KeyboardView.kt:967-971 menuBelowKey=false`);
  Tests echt (reale Zahlen, assertNotEquals 0/232/171/-168, kein Tautologie-Muster).
- T2: SP 26/24 `KeyboardTheme.kt:73,76`; ShiftGlyph 0.30/0.18/0.12; ⌫-Korrespondenz gegen
  Code verifiziert (0.465−0.285=0.18); Raster-Fit nachgerechnet (68,25/63 px in 108er Zelle);
  Hierarchie-Test-Migration begründet; Hint-Deckel konsistent.
- T3: `micHoldReleaseDecision` vollständig (alle DictationStates, Grenze >300: 300=Tap/301=Hold);
  HOLD_STOP_PENDING→cancelStartRequested→micStartFinishedAction(STOP_AFTER_START)→stop
  (`:800-830/:1851-1855`); TOGGLE_ON setzt kein wantedStop (`:1210`); @Volatile beidseitig Main
  (Gürtel+Träger); Listener-Migration vollständig (einzige Override `:1191`); Befund-2a =
  saubere additive Migration. Beweis `/tmp/vk_phone_e2.log` Z.75-97.
- T4: Stichproben 5 Assets + Grep-Vollzugriff exakt Plan §2 (11 Mengen; dollar-Menü genau in
  den 7 $-haltigen Assets); Ziffern menülos; el-gr/semicolon-[·] dokumentierte Ausnahme OK;
  PunctuationCoverage-Mutations-Erweiterung begründet (Gegen-Tautologie);
  SymbolLongpressAcrossAssetsTest prüft gegen hart-kodierte Plan-Mengen + echte 15er-Parität.
- Regel 9/10: VK-HIT nur Zahlen/Ids, debuggable()-Gate; kein git, keine Gradle-Läufe im Review.

## P2 (report-only → Kommentare nachgezogen, siehe unten)
1. `KeyboardTheme.kt:29` KDoc 24/22 → 26/24
2. `KeyboardView.kt:113` Kommentar 24/22 → 26/24
3. `KeyboardView.kt:428` `// 22 sp` → `// 24 sp`
4. `KeyboardView.kt:429` `// 24 sp` → `// 26 sp`

## Erledigt nach Review
- P2×4 Kommentar-Sync eingebaut (Zeilen 2026-11-02 22:2x); Verifikation per testDebugUnitTest
  nach Bauserie (Kommentare =_compile-only_, Volllauf als Netz).
- Caveat des Reviewers (Testzahlen ohne eigenen Gradle-Lauf) vom Orchestrator XML-seitig
  verifiziert: Debug 589/0 frisch; Release-Zählung folgt nach V36_DONE.
