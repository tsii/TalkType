# Diagnose-Dossier + adaptiertes Emu-Programm (Planer sa-9b1d671e, 2026-11-05 21:5x UTC)

## 1. DIAGNOSE — Produktions-Bug P0: Wortlisten werden auf dem Geraet NIE geladen (doppel-praefixierter Asset-Pfad)

- `VoiceKeyboardService.kt:396-398` — Service-Lambda erwartet **lang**:
  `WordListRegistry { lang -> runCatching { assets.open("wordlists/$lang.bin")... }.getOrNull() }`
- `VoiceKeyboardService.kt:2769-2772` — Registry liefert **fertig praefixierten Pfad**: `readAsset("wordlists/$lang.bin")`
- ⇒ Lambda erhaelt `"wordlists/de-de.bin"` und oeffnet `"wordlists/wordlists/de-de.bin.bin"` → FileNotFound → `getOrNull()` = null STILL → Cache leer → `listsFor(...)` = leere Map.
- `WordCorrector.kt:85-88` — CURRENT: `lists["de-de"] ?: return null`; ALL_ACTIVE: `ifEmpty { return null }`
  ⇒ **correct() liefert auf dem Geraet fuer JEDES Wort null.** Assets selbst vorhanden (APK-Check: de-de.bin 1082777 B, en-us.bin drin).

Beweislage konsistent: Band zeigt unterstrichenes 'Morgem' = rules-only (Kapitalisierung `TypingRules.kt:50-59`),
kein `compute_abort` (Compute laeuft clean durch), Stab farbig. Keine Ambigitaets-Bremse: 'morgem'→'morgen'
waere eindeutiger Depth-1-Treffer (Ende-Insertionen per `innerEdit` 1 until length ausgeschlossen,
`WordCorrector.kt:181-183`).

**Warum JVM gruen (Testblindstelle, dreifach):** (1) `CorrectorGoldenTest` laedt Listen per File-Walk-up selbst
(umgeht Registry); (2) `CorrectionWiringTest` nutzt Fake-Compute (`region=="morgem " -> "morgen "`);
(3) der einzige End-to-End-Test assertet nur `corrected != null && != "morgem Welt "` — erfuellt schon durch
Grossschreibung; `walkUpLoads == 0` ist bei leerer Map vakuum-erfuellt (null-Rueckkehr VOR NeighbourModel.load).

## 2. ENTSCHEIDUNG: FAHRPLANWIDRIG — P0, Bau-Halt Punkte 1–3 (4–7 unaffected)

**Coder-Auftrag (Fix VK-8 P0):** `VoiceKeyboardService.kt:396-398` Lambda auf Pfad-Kontrakt umstellen
(`WordListRegistry { path -> runCatching { assets.open(path)... }.getOrNull() }`) ODER Praefix nur an EINER Stelle.
Regression: (a) Robolectric `corrComputeRegion("Heute morgem ") == "Heute morgen "` EXAKT (failt ohne geladene Liste);
(b) Registry-Test ueber Service-Lambda: `listsFor(listOf("de-de","en-us")).size == 2`;
(c) bestehende VK-1-Assertion schaerfen (rules-only-blind `!= null` ersetzen).

## 3. Neues Testwort (unambig, doppelbuchstaben-frei): `kalr ` → Band `Kalt `

de.tsv region-Goldenassertion existente; Einzigkeit: kalt (Sub r→t 0.7) vs. klar (Transposition 0.8);
Depth-2 laeuft nicht (bestDepth1 0.7 ≤ 0.8). Tippbar k,a,l,r = vier verschiedene Tasten.

## 4. Doppel-Tap-Workaround

App-seitig KEINE Same-Key-Drossel (`KeyboardView.kt:1159ff`; Double-Tap nur Shift L.952) → adb-Injektion dedupelt.
Fallback: `input -d 0 motionevent DOWN x y ; sleep 0.2 ; input -d 0 motionevent UP x y` (200 ms < 450 ms Schwelle).
`keyevent` vermeiden (umgeht Korrektur-Feeds). Mit `kalr ` nicht noetig.

## 5. Stab-Formel @420dpi (d=2.625) — Plan-Korrektur

debug-Band rowHeightPx = 48dp ≈ 126 px; Wand letzte Child 48dp rechts ⇒ **Stab = (1080−63, K−63) = (1017, 1635)**.
K,R dump-frei per VK-HIT: space-Tap (521,2202) ⇒ `touch=<521,ty> view=1080xH` ⇒ K=2202−ty, R=H/4 (K=1698, R=144, B=2274).
Zeilen-y: r0=1770 r1=1914 r2=2058 r3=2202. X: a105 s207 d309 f411 g513 h615 j717 k819 l921 r378 e270 o918 m795 space521 globe154 backspace1026.
P7: Popup-Hoehe 56dp@2.625 = **147 px** (Soll T+147 ≤ 0).

## 6. Adaptiertes dump-freies Programm (IME-Fenster faellt aus uiautomator; Activity-Dump OK)

Readiness-Gate: Punkte 1–3 erst nach Fix+neuem APK; 4–7 laufen auf aktuellem APK (Teildeckung).

- **P1** (Feld leer, Deutsch): taps k(819,1914) a(105,1914) l(921,1914) r(378,1770) space(521,2202), je sleep 0.45.
  Soll `rd()`=='kalr '; Screenshot p1: Band `Kalt` unterstrichen + Stab farbig. Ast alter APK: Band `Kalr` ⇒ P0 dokumentiert, 2–3 aussetzen.
- **P2 APPLY:** `input tap 1017 1635; sleep 1` ⇒ `rd()`=='kalt ' (p2_applied.png). Ast: 1 Retry, dann 🐞-Export corr_applied lesen.
- **P3 UNDO** unmittelbar danach, KEIN Tasten-Tap dazwischen: erneut tap (1017,1635) ⇒ `rd()`=='kalr ' (p3_undo.png). Zweiter Tap = No-op.
- **P4 Setup-Schalter:** SetupActivity Dump OK ('Show suggestions while typing', pref corr_row_enabled). OFF: `kalr ` getippt ⇒ Screenshot ohne Wand/Unterstreichung, Feld unveraendert (p4_off_norow.png); ON zurueck ⇒ wieder Vorschlag (p4_on_row.png).
- **P5 Globus-Langdruck:** swipe-hold (154,2202) 1600ms ⇒ `VK-HIT popup-open cells=2` + Screenshot.
- **P6 13x coming soon + Toast:** Setup-Dump `text="[^"]*· coming soon"` count==13; Tap Francais-Zelle ⇒ exakter Toast-Knoten `text="coming soon"` (p6_langs_grey/p6_toast).
- **P7 Band-Regression:** swipe-hold (270,1770) ⇒ `popup-open cells=6 ... T<0 und T+147 ≤ 0` + Screenshot.

Belege: /tmp/v47_p1_row.png (P0-Beweis), p1c/p1d (Doppel-Tap), p2/p3 nach Fix, p4–p7 Teildeckung.

---
## Nachtrag §4-Aufloesung (Planer, 22:1x UTC)
- kafffe-Doppellog = **Artefakt** (VK-HIT zaehlt DOWN+UP-Phase, nicht zwei Feldzeichen); Counter-Test `grep -c id=f` bei Pflicht mitzuschneiden (2 Logs -> 1 Feld-f = normal).
- ABER Sweep fand echten latenten Mangel **VK-9 (P2, P1 bei Reproduktion)**: `rebuildModel` rotiert Modell-Instanz bei jedem onMeasure; UP-Gate `key === pressedKey` (Objekt-Identitaet) => IME-Relayout (z.B. Vorschlags-Publish alle 300ms) zwischen DOWN/UP verwirft Zeichen STILL. Fix (id+Rect-Kriterium oder Stamp-Fruehausstieg) + hit-drop-Log + Robolectric-Zwischenrelayout-Test — als VK-9 in 0.1.47 aufgenommen (Coder laeuft).
