# Review v0.1.36 vc37 — T5 Statusband-Overlay + T6 Symbol-Raster
Reviewer: DeepSeek V4 Pro 0813 (sa-77441537), 2026-11-02 23:0x. Volltext sinngemäß persistiert.

## Verdikte
- **T5 Statusband-Overlay: OK** (keine P0/P1). Band-Zone-Invariante `LongPressMenu.kt:125`
  (`<=` spezifikationskonform: Unterkante berührt, überdeckt nicht); Guard-Fallback Riesenmenü
  → Zweizonenregel (`:122-128`, Test menuH=400→0); Default `statusBandPx=0` byte-identisch
  (Regressionsschutz `LongPressMenuTest.kt:529-534`); Elevation 2f nur bei top<0, Reset an
  BEIDEN Schließ-Stellen (`:709-712`, `:1033-1036`; `popup = null` nur dort — kein dritter
  Pfad; Panel-/Sprachwechsel über rebuildModel→discardLongPress); kein translationY;
  negative-y-Touch richtungsoffen (`cellWins :181`, Test `:510-522`).
- **T6 Symbol-Raster: OK mit Notizen** (keine P0/P1). Stichprobe 5 Assets Σ=[10,10,10,13],
  ⌫ r1-Ende 1.0, ⏎ r2-Ende 1.0, ⇧ r2[0] 1.5, r0 10 Ziffern, Footer ohne ⏎/kein Textzeichen;
  €-Wanderung da-dk/el-gr/de-de belegt; ‰ in allen 15 im percent-Menü (el-gr Direkttaste
  bleibt); Skript `rebuild_symbol_layers.py:181-216` schreibt NUR zwei Sektionen,
  json-validiert, zerstörungsfrei; vor/after-Gegencheck echt (`:224-231`).

## P2 (report-only)
1. Ankerlisten-Provenienz `SymbolGridAcrossAssetsTest.kt:49-75`: hartkodiert, ohne Git nicht
   unabhängig verifizierbar; echter Schutz lag im Skript; Wert = Zukunfts-Guard.
   → Bei Git-Historie: anchoredSets gegen vc36-Assets verproben.
2. T5-Grenzfall `top+menuH==rowBelowTop` ungetestet (Code korrekt, Doku-/Test-Lücke).

## Push-Verdikt: **JA** (P2 ohne Freigabe-Bedarf)
