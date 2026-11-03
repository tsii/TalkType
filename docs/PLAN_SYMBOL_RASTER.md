# Plan v0.1.36 — Symbol-Raster mit stabilen ⌫/⏎ (Buchstaben-Slots)
Quelle: Research sa-076eb (2026-11-02 22:3x), Volltext im Transkript; Orchestrator übernahm 1:1.
**Nutzer-Auftrag:** ⌫/⏎ auf Zahlen-/Symbolebene an derselben Stelle wie im Buchstabenraster;
Rest sinnvoll verteilen (Overlay erlaubt); **kein Zeichen rechts der Spacebar** (Symbol-Ebene).
AOSP hält ⌫ am Reihenende auch symboletig; ⏎ zieht AOSP/Gboard in den Footer —
**Nutzer-Geometrie schlägt Vorbild** (besprochen, abgenickt).

## Abnahmen (Orchestrator-Entscheid, dokumentiert)
- „Zeichen rechts neben Spacebar" = gerendertes ⏎ der Symbolebene (wandert an r2-Ende);
  das „." im BUCHSTABEN-Footer bleibt (AuftragKontext = Zahlentastatur).
- da-dk/fi-fi Überzählige symbolsShift: ‰-Direkttaste fällt zugunsten Einheitsraster —
  Erreichbarkeit über T4-Menü percent→[‰] (Doppelpfad). ¿/¡ bleiben Direkttasten.

## Ziel-Raster (sprachneutral; Σ je Reihe; 1u=108px) — symbols
| Reihe | Belegung | Σ |
|---|---|---|
| r0 | 1..0 je 1.0 (10 Ziffern; hyphen wandert raus) | 10 |
| r1 | - / : ; ( ) & @ = # je 0.9 · **⌫ 1.0 am Ende** | 10 |
| r2 | ⇧1.5 · . , ? ! ' " % + _ * je 0.75 · **⏎ 1.0 am Ende** | 10 |
| r3 | ABC1.5 · 🌐1 · 😊1 · space 7.5 · 🎤2 (**kein ⏎, kein Textzeichen neben Space**; mic zuletzt) | 13 |

**symbolsShift spiegelt die Struktur 10/10/10/13** (kein Springen beim ⇧-Toggle):
r0 10×1.0 (¹ ² ³ ¼ ½ ¬ | ¢ – ° wo vorhanden); r1 10×0.9er + ⌫1.0; r2 ⇧1.5 + 10×0.75 + ⏎1.0;
r3 wie symbols. Reihen mit <10 Tasten: Breite = 7.5/n bzw. 9.0/n, Σ bleibt; nie Zeichen erfinden.
⌫/⏎-Exactness: Vorblock 9.0/10 = 90-%-Slot wie base r1/r2; KEY_GAP_DP=0 ⇒ pixelgleich
(Fugen-Rechnung Δ≤0,4px < Touch-Slop, dokumentiert).

## Migration (ist→soll; kein Zeichen geht verloren)
- de-de symbols: hyphen r0→r1; ' " r1→r2; ⌫ r2→r1-Ende (1.5→1.0); ⏎ Footer→r2-Ende;
  space 7→7.5, 🎤1→2. 30 Direkttasten bleiben 30.
- de-de symbolsShift: € r0→r1; „ “ r1→r2; ⌫/⏎/Footer analog. 30 bleiben 30.
- da-dk/fi-fi/el-gr: € (symbols r0 Platz 11) → symbolsShift-Direkttaste; symbols r0 wird
  reine 10er-Ziffernreihe. da/fi symbolsShift 31→30 (‰→Menü, s. Abnahme).
- übrige 11 Assets: reine Umplatzierung; symbols wird in allen 15 inhaltlich identisch;
  symbolsShift behält asset-eigene Pflichtzeichen (¿ ¡ º ª № « » · …) als Direkttasten.
- Digit-IDs asset-lokal (nicht anfassen); T4-Alternates wandern mit ihren ids.
- Longpress-Geometrie: hyphen r0→Mittelreihe ⇒ Mittelreihen-Regel greift (Code vorhanden).

## Tests
1. **FooterAcrossAssetsTest**: `symbolEbenenBehaltenAltFooterMitEnter` umschreiben auf
   Σ13 = ABC1.5/🌐1/😊1/space7.5/🎤2, mic zuletzt, KEIN ⏎ (begründete Migration).
2. **NEU SymbolGridAcrossAssetsTest**: 15×2: Σ=[10,10,10,13]±0.001; ⌫ genau 1× r1-Ende 1.0;
   ⏎ genau 1× r2-Ende 1.0; ⇧ r2[0] 1.5; r0 10 Ziffern à 1.0; je Asset Zeichenmenge
   (Direkttasten+Menüs) vor==nach (verankerte Migrationsliste).
3. Grün bleibend: LayoutArtifactTest, LayoutCompletenessTest (⇧1.5 + 1×⌫/⏎ erfüllt),
   PunctuationCoverageTest (da/fi-‰ über Menü), SymbolLongpressAcrossAssetsTest (id-basiert),
   KeyboardModelTest (Footer ohne ⏎ abgedeckt).

## Umsetzung: EIN Coder-Auftrag mit Python-Asset-Skript (Σ-Drift-Schutz)
Dateien: 15× layouts/*.json + FooterAcrossAssetsTest + SymbolGridAcrossAssetsTest +
1 Satz LAYOUT_SCHEMA_REF.md („Symbolebenen-Raster 10/10/10/13; ⌫ r1-Ende, ⏎ r2-Ende wie
Buchstaben; kein Textzeichen neben der Spacebar"). Skript im Report ausweisen (tools/ ok).

## M-Regel (v0.1.37, T7) — r2-Überfüllung: 10 Direktzellen à 0,75 + Menü-Weitergabe
Erstlauf-Befund: da-dk/fi-fi symbolsShift r2 hatte 11, el-gr 12 Direktzeichen — das
Skript verteilte das r2-Budget 7,5 auf 11/12 Zellen (0,6818181818 bzw. 0,625), unter
der 0,75-Zellbreite aller übrigen Assets.

**Planabweichung (dokumentiert):** der T7-Auftrag „auf 9 à 0,75 reduzieren" ist
arithmetisch unmöglich — ⇧1,5 + 9·0,75 + ⏎1,0 = **Σ 9,25 ≠ 10,000** (der
Σ-Check SymbolGridAcrossAssetsTest bliebe rot, widerspricht dem Auftrag „JEDE
r2-Zeile Σ genau 10.000"). Ein „10er-r2" à 0,75 ist das MAXIMUM des Budgets
(10·0,75 = 7,5 exakt). ⇒ **Korrektur: 10 Direktzellen à 0,75; die Überlaufzeichen
fallen und bleiben über Bestands-Menüs erreichbar (Menü-Weitergabe)**:
- da-dk/fi-fi: „…" fällt (11. Zelle) → erreichbar über T4-dot-Menü [. → …];
- el-gr: „–" und „…" fallen → hyphen-Menü [–, —] bzw. dot-Menü […].
Keine neuen Menüs, kein Schema-Feld; das vor==nach-Zeichenmengen-Assert des Skripts
bleibt scharf (Skript prüft zusätzlich: JEDES gefallene Zeichen muss in Alternates
vorkommen). Skript ist ab T7 idempotent (Zweitlauf auf migrierten Assets: €-Wanderung
und ‰-Fall erkannt). Verankert: SymbolGridAcrossAssetsTest (c2) — r2 ≤ 10 Direktzellen
à 0,75; SymbolMenuClampAcrossAssetsTest — Menü-Clamp-Invariante (value+alternates ≤ 8,
linkeste Zellkante ≥ 0, rechte Kante ≤ View-Breite bei Zentrierung+Klemmung über der
Grundtaste; Rot-Beleg: Menü-Menge 10 = 10·126 + 11·11 = 1381 px > 1080 px View —
KEINE Position hält alle Zellen) + T6-Zeichen-Gegenprobe (… ” ª º ‰ ± je Asset).
