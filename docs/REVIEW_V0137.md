# Review v0.1.37 vc38 — T7 Menü-Clamp + M-Regel
Reviewer: DeepSeek V4 Pro 0813 (sa-90301160), 2026-11-02 23:5x. Volltext sinngemäß persistiert.

## Verdikt: **OK — Push JA** (P0/P1 = 0, P2×3 report-only)

## Kernprüfpunkte (BESTANDEN)
- `menuLeftClamped` delegiert auf `menuLeftPx` (Mathematik identisch, Clamp bei [pad, width−pad−menuW]);
  Guard `menuW > width` ⇒ Menü wird nie abgeschnitten, nur unterdrückt (Bestandsverhalten F24e5).
- KeyboardView beide Fundstellen (Popup :539-542, Arming :915-923) nutzen dieselbe Quelle;
  Popup-Breite ≤ Arming-Breite ⇒ keine toten Zonen; T5-Bandzone unberührt.
- M-Regel im Skript: R2_MAX_CELLS=10, Überlauf-Assert + vor==nach-CharSet-Check (:258-283) echt;
  Assets nachgemessen: da/fi r2 10 Zellen à 0,75, … im dot-Menü (hyphen-Menü [–,—] bleibt ≤8);
  el-gr 10 Zellen, – im hyphen-/… im dot-Menü, ‰ Direkttaste; T4-Hygiene überall ≤8 (Maximum 7).
- Clamp-Test: echte Utils (menuWidthPx/menuLeftClamped); Raster-Unit äquivalent KeyboardModel
  (KEY_GAP_DP=0, Padding 0 ⇒ unit=viewWidth/sum). T6-Gegenprobe deckt … ” ª º ‰ ± vollständig ab.
- **Nutzer-Fund behoben (JA):** vc37-Überlauf = el-gr 12 / da/fi 11 Direktzellen in r2 (Planbreite
  0,75 verletzt, Zellen liefen über Enter/Fuß) ⇒ M-Regel kappt auf 10 à 0,75; Popup-Kanten-Clamp
  gegen Menü-Überlagerung (Beispiel hyphen: ohne Clamp left≈−94 ⇒ jetzt 0). Hypothese „8er-Menü"
  widerlegt (Maximum 7).

## P2 (report-only)
1. Auftragstext-Rechenfehler 8er-Menü: korrekt 8·126+9·11=1107 px (nicht 1085) — nur Doku.
2. MAX_CELLS=8 bei 1080 px/2,625 physikalisch unerreichbar (8er=1107>1080 ⇒ Guard unterdrückt;
   effektiv max 7 = 970 px); KDoc „höchstens acht" weicht ab. Latent, kein Asset hat ≥8.
3. Test bildet Raster-Unit inline nach (äquivalent, aber Drift-Risiko bei KEY_GAP_DP/Padding≠0);
   gleiche Familie wie REVIEW_V0136 P2#1.

## Auflage offen
„12 Assets byte-identisch" ohne git nicht unabhängig verprobt (als behauptet übernommen).
