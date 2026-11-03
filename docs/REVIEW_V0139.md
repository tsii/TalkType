# Review vc39-Snack — KDoc-Realität + Produktions-Geometrie im Clamp-Test
Reviewer: DeepSeek V4 Pro 0813 (sa-bae9d8e4), 2026-11-03 01:0x. Volltext im Transkript.

## Verdikt: **OK — Push JA** (P0/P1 = 0; P2 a/b/c report-only)

| Auflage | Status |
|---|---|
| V0137 #1 (Rechenfehler 8er-Set: 1085→1107 px) | ✅ KDoc-Formel + Mini-Test `menuWidthPx(8)=1107` |
| V0137 #2 (MAX_CELLS=8 bei 1080 px unerreichbar) | ✅ KDoc: 1107>1080 ⇒ Guard unterdrückt, effektiv 7 (970 px) |
| V0137 #3 (Inline-Raster-Drift im Clamp-Test) | ✅ Produktions-`KeyboardModel` + Zellen-Paritäts-Anker (108 px; Rot-Beweis KEY_GAP_DP=1 ⇒ 105,6 FAILED) |
| V0136 #1 (Anker-Provenienz) | ✅ Provenienz-Satz (T6-Ist, echter Schutz = Skript-Assert vor==nach) |

## P2 (report-only)
a) Test nutzt `keyHeightPx = POPUP_HEIGHT_DP·density` statt `desiredRowHeightPx` (nur Vertikale, harmlos).
b) Pad-Doppeldeutung 10 (Formel-Default) vs 11 (Produktions-Rundung 10,5→11) in derselben Datei — kein Widerspruch.
c) `horizontalPaddingPx`-Drift nicht vom Anker gedeckt (im KDoc als Annahme deklariert).

## Entscheidung (Orchestrator)
Snack ist funktionsneutral (Produktion nur KDoc) → **kein eigener Bau**;vc38 bleibt dist-Stand,
der Snack läuft im nächsten Feature-Bau (versionCode 39 / 0.1.38) mit. Tests 598/0 Debug.
