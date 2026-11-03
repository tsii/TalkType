# Review v0.1.38 vc39 — T8 Y-Reihen-Zentrierung + T9 Release-Statuszeile
Reviewer: DeepSeek V4 Pro 0813 (sa-7a11fe67), 2026-11-03 07:5x. Volltext im Transkript.

## Verdikt: **OK — Push JA** (P0=0, P1=0, P2×2 report-only)

## T8 (BESTANDEN,独立 nachgerechnet)
- de-de r2: Σ=10,0000 u; Y-Mitte 1,444445≈13/9 ✓; M-Mitte 8,055545≈145/18 ✓ (Δ≈5·10⁻⁶);
  px 96,5+7×119+150,5=1080 (KEY_GAP=0). tr-tr identische Positions-Invariante.
- cs: Σ 10,00002 (±0,001), m→["@"], kein @ in r2. ru D27 unangetastet (em-Kein-@-Erfindung ✓,
  @ nur Symbolebene + period).
- Skript: splice nur base/shift (assert Adjazenz), symbols bleiben; ru read-only; Idempotenz
  durch @-Entfernung/append-Guard + before==after-CharSet-Assert.
- checkGeometry: echte neue Invarianten (c2[0]≈13/9, c2[6]≈145/18 ±0,01), cs/ru-Äste echt,
  Σ-Guards bleiben — Härtung statt Entleerung.
- @-Erreichbarkeit 15/15 (de m→[µ,@], en m→[—,…,–,@] distinct, el μ→[@]).

## T9 (BESTANDEN)
- Naht FLAG_DEBUGGABLE aus applicationInfo einmal init (ImeRootView.kt:126-128), GONE :158;
  showEmojiPanel/hideEmojiPanel (:260-274) fassen statusRow NICHT an; Service setzt nirgends
  .visibility ⇒ Release-GONE überlebt Modi-Wechsel. Robolectric-Bit explizit gesetzt/ge löscht.
- T5-Fallback: bandPx=this.top=0 bei GONE ⇒ T1-Zweizonenregel (LongPressMenu.kt:157 Guard).

## P2 (report-only)
1. DESIGN_TASTATUR_V2 §10 Tabelle F36-Zeilen (109/112) veraltet (F40/T8 obsolet; Nachträge
   32-37/122-126 korrigieren) — vorbestehende Doc-Drift.
2. LetterRowAlignment cs-Kommentar nennt alte 1,88887/1,88875-Diskrepanz, Code/Asset 1,88889.

## Nutzer-Ziele-Nachweis
Y unter A/S ✓ · M unter K/L ✓ · @ 15/15 erreichbar ✓ · Release ohne Statuszeile ✓
Offen wie gehabt: 14-Assets-identisch/ru-ungeändert ohne git nicht unabhängig diffbar.
