# Messwerte-Vergleich — Änderung → Gewinn (nur belegte Zahlen)

**Zweck:** am Ende vergleichbar machen, welche Änderung welchen Gewinn gebracht hat.
**Disziplin:** ausschließlich die gelieferten, belegten Messwerte; nichts erraten, nichts
schätzen — wo keine Messung existiert, steht **nicht gemessen** / **nicht erhoben**.
REGEL 9: nur Zahlen, Tasten, Geometrie, Pfade — keine Inhalte.

**Versionen/Kontext:** 0.1.14 = Bau 15, 0.1.15 = Bau 16, 0.1.16 = Bau 17,
0.1.17 = Bau 18 (`app/build.gradle.kts:14-15`). Emulator = <device> (API 36-AVD),
Telefon = Nutzer-Gerät api 37.

---

## A) C2-Diktat (Nutzer-Telefon, 🐞-Exporte)

| Änderung | vorher (0.1.14-Ära) | nachher (0.1.15, mit F21) | Gewinn | Belegquelle |
|---|---|---|---|---|
| F21 cost-aware Decode-Takt | rtf **0,44**; erste Hypothese nach **5 339 ms** | rtf **0,15**; decode_ms_max **583**; decode_ms_p95 **583**; buffer_s = audio_s = **11,9** | rtf ≈ **−66 %** (0,44 → 0,15) | 🐞-Export 0.1.14-Ära (HANDOFF „C2") / 🐞-Export #1 0.1.30 (docs/C2_EXPORT_AUSWERTUNG.md §0) |
| F21-Milderung, Auswirkung im Export #1 | — | hyp_count **6**; hyp_gap_ms **7 323**; ctl_failed **0**; dropped **0**; Zustand **IDLE** | **kein messbarer C2-Gewinn im Abbruchzelfall**: Bruch bei 11,9 s **ohne Finale und ohne Fehlerflagge** (Abort-Pfad, nicht C2) | 🐞-Export #1; Auswertung docs/C2_EXPORT_AUSWERTUNG.md §1/§2 |
| F21-Taktwerte `decode_pace_ms` / `decode_skips` | — | Felder im Export #1 **vorhanden**, Werte **nicht einzeln ausgewertet** | nicht einzeln ausgewertet | 🐞-Export #1 (Feldliste docs/DEBUG_INFO_FIELDS.md) |
| Quadratische Ursache `window()` (DictationController.kt:416) | sichtbar als 5 339 ms Erst-Hypothese | 583 ms bei 11,9 s Puffer (≈ 49 ms pro Audio-Second) — Wachstum noch **unter Schwelle** | **Gewinn nicht gemessen** (Abbruch lag davor); Fix-Kandidat unverändert offen | Auswertung §1 |

## B) Optik gedrückte Taste (D11/F15, Emulator, Kanal-Delta von 255)

| Änderung | vorher | nachher | Gewinn | Belegquelle |
|---|---|---|---|---|
| Tastendruck-Fläche (gedrückte Taste) | hell **27** / dunkel **21** | hell **73** / dunkel **65** | Delta ≈ **×2,7** (hell 27→73), ≈ **×3,1** (dunkel 21→65) | Orchestrator-Emulator-Messung (docs/DESIGN_STOCK_LOOK.md, Log in HANDOFF „D11/F15") |
| CapsLock-Akzent-Fläche | — | `#4DB6AC` (hell) / `#80CBC4` (dunkel), Abweichung **0** | Sollwert exakt erreicht | dito |

## C) Mikrofon (Nutzer-Telefon)

| Änderung | Wert | Interpretation | Belegquelle |
|---|---|---|---|
| Pegel/Routing (keine Änderung seit F13) | peak_dbfs **−22,0**; peak_pct **7**; route **intern**; src **VOICE_RECOGNITION** | **in Ordnung** — Mikro-Pegel ist kein Verdacht mehr (AudioSource-Experiment hinfällig) | 🐞-Export (Nutzer-Telefon); „Geklärt" in HANDOFF |

## D) Fußzeile F24e (Emulator, de-de, 0.1.16)

| Änderung | vorher | nachher | Gewinn | Belegquelle |
|---|---|---|---|---|
| Vereinheitlichte Fußzeile + Punkt-Menü | 14 Layouts mit `‹ ›`-Cursortasten, de-de ohne (Übergangs-Inkonsistenz, Review-Note) | View 1080×576; Tastatur-Oberkante y=1698; Fußzeile y=2202; **period-Rect <706,432,789,576>** (Mitte 747, Breite 83 px); popup-open **cells=5** left=384 top=264; Zellmitten 468/594/720/846/972; popup-zone left=384 w=696 | 15/15 Layouts einheitlich (Σ 13), Cursor-Aktionen als Menüzellen 4/5 statt Direkttasten | Orchestrator-Emulator-Messung 0.1.16; Zielbild docs/LAYOUT_REQUIREMENTS.md §2.3 |

## E) F27 Stock-Geometrie (Emulator, de-de, 0.1.17-Debug, 16:44–16:45)

| Änderung | vorher (0.1.16) | nachher (0.1.17) | Gewinn | Belegquelle |
|---|---|---|---|---|
| Zeilenraster Buchstabenreihen | r0 **93,9 px** (1080/11,5); r1/r2 **120 px** (1080/9) — **uneinheitlich** | alle drei Reihen **exakt 90 px** (VK-HIT: z <450,0,540,144>, a <135,144,225,288> mit 1,5u Rand links, b <585,288,675,432>) | einheitliches Stock-90-px-Raster (Vorher/Nachher-Geometrie-Abgleich) | Orchestrator-Emulator-Messung 16:44–16:45 |
| Leertaste | **498 px** (6u) | **582 px** (7u): Rect <290,432,872,576> | **+84 px / +16,9 %** Fläche | dito |
| Punkt + Mikrofon | Punkt **166 px**, Mikrofon **166 px** (D10-Umbau) | Punkt **83 px** (1u: 872–955), Mikrofon **125 px** (1,5u: 955–1080) direkt daneben | Punkt schmal (1u), Mikrofon direkt rechts — Stock-Zuschnitt | dito |
| Popup-Flip | (vorherige Popups-Position nicht separat vermessen) | r1-Taste (a): popup-open **cells=6** left=0 **top=0** (Klemmung an Oberkante), zone left=0 w=833; Row-0-Tasten öffnen **unterhalb** (Flip) | Flip verhalten gemessen; quantitative Zeilensparnis **nicht gemessen** | dito |

## F) Testbestand (Unit)

| Änderung | vorher | nachher | Gewinn | Belegquelle |
|---|---|---|---|---|
| F24e–F27-Auftragskette (Debug-Suite) | **419/0** (0.1.14) → 425/0 → 427/0 → 429/0 → 430/0 → 431/0 → 432/0 (F26a+b) → 437/0 (F27a1/a2/b) → **440/0** (F27d, 16:34) | **440/0** (Bau 18, LAUF 1, 2026-10-31 16:48) | **+21 Tests** gegenüber 0.1.14, durchweg 0 Fehler | /tmp/vk_tests.txt; app/build/test-results/testDebugUnitTest |
| Release-Suite | 399/0 (0.1.15) | **420/0** (50 Dateien), 2026-10-31 16:48 | +21 gegenüber 0.1.15 | /tmp/vk_tests.txt (LAUF 1) |
| Latenz-/Qualitäts-Gewinne der Engine | — | — | **nicht gemessen** (s. H) | — |

## G) APK-Größen (Byte, Release / diag-debug)

| Build | Release | diag-debug | Zuwachs je Shape | Belegquelle |
|---|---|---|---|---|
| 0.1.15 | 40 343 852 | 102 386 730 | — | dist-Liste (HANDOFF/PROJECT_STATE) |
| 0.1.16 | 40 345 748 | 102 388 918 | Release **+1 896**; Diag **+2 188** | dist-Messung (Orchestrator) |
| 0.1.17 | 40 346 420 | 102 390 034 | Release **+672** (vs. 0.1.16); Diag **+1 116** (vs. 0.1.16) | dist-Messung (Orchestrator) |

## H) Nicht messbar (dokumentiert, nicht beschönigt)

| Thema | Status | Grund |
|---|---|---|
| Engine-/Diktatlauf auf Emulator-5560 | **nicht validierbar** | SIGSEGV in `libonnxruntime.so` beim Laden (dlopen/call_constructors) → Latenz- und Qualitäts-Gewinne der Engine **nur über Telefon-Exporte** (A) belegbar; alle Emulator-Zahlen hier (B/D/E) sind Tastatur-/Geometrie- und Optik-Ebene |
| C2-Gewinn auf längeren (> 20 s) Sessions | **nicht gemessen** | Export #1 brach bei 11,9 s ab (Abort-Pfad, docs/C2_EXPORT_AUSWERTUNG.md); Folgemessung mit `abort_reason`/`hyp_last_buffer_s` ausstehend |
| Diktat-Abbruchgrund | **offen (3 Kandidaten)** | Mic-CANCEL / Editor-Wechsel / Audio-Systemstopp — Abgrenzung erst über `last_error` + neue Felder (docs/C2_EXPORT_AUSWERTUNG.md §2/§5) |

---
**Belegquellen gesamt:** 🐞-Exporte (0.1.14-Ära, Export #1 vom 30.10.), Orchestrator-
Emulator-Messungen (D11/F15, F24e 0.1.16, F27 0.1.17 16:44–16:45), `/tmp/vk_tests.txt`
(LAUF 1, 31.10. 16:48), `app/build/test-results/`, `app/build.gradle.kts:14-15`,
`docs/C2_EXPORT_AUSWERTUNG.md`, `docs/LAYOUT_REQUIREMENTS.md` §2.3, dist-Liste.

## I) Diktat-Härtung (F28–F31b, 0.1.18 → 0.1.24)

| Änderung | Wirkung (nur belegte Werte) | Beleg |
|---|---|---|
| F28b1 **Stall-Watchdog**: keine Capture-Blöcke > **3000 ms** → Fehler `stall` (statt Endlos-„Diktat") | Schwellwert 3000 ms; Trigger verifiziert in Testfall b (Stille ab Start) und c (Aussetzer 3,5 s → genau ein Fehler) | `ime/DictationCoordinator.kt:73` (`STALL_LIMIT_MS`), `app/src/testDebug/…/ime/FakeAudioScenarioTest.kt` (Fälle b/c) |
| F31 **Stille-Erkennung**: Blöcke laufen, aber > 3000 ms kein lauter Block → Fehler `silence` + neue Meldung + 🐞-Feld **`loud_s`** | Laut-Schwelle **`SILENCE_RMS = 0,01` (≈ −40 dBFS)**; Trennung „keine Blöcke → `stall`“ vs. „Blöcke ohne Signal → `silence`“ verifiziert (Fälle e/g) | `ime/DictationCoordinator.kt:100/337-338`, `debug/DebugInfoFormatter.kt:134/194`, `res/values/strings.xml:57` (`dictation_error_no_signal`) |
| F31b **`everLoud`-Latch**: `silence` feuert nur, solange noch nie ein lauter Block kam — **keine Fehlabbrüche bei Sprechpausen** (Review-P1) | Latch-Setz-/Gate verifiziert in Testfall f (still→laut, kein Fehler); P1 dadurch geschlossen | `ime/DictationCoordinator.kt:218/300/330-332`; `docs/REVIEW_F28_F31.md` (P1 → geschlossen) |
| F31b **`FGS_READY_TIMEOUT_MS` 2 s → 4 s** (Kaltstart-Frist für FGS-Handshake) | Konstante **4000 ms** | `ime/VoiceKeyboardService.kt:149` |
| F31b **Layout-Gates auf Einzelwerte** (space 6,5 / mic 2 / period 1, genau ein „@“ je Ebene, ru-ru ohne @-Taste, r2[0] shift = 1) | **3 neue Tests** (im 464/0-Lauf) | `FooterAcrossAssetsTest` / `LayoutCompletenessTest` (Orchestrator-Angabe) |
| Testbestand über die Härtung (0.1.15 → 0.1.24) | Debug **419 → 464**, Release **399 → 433**, durchweg **0 Fehler** (gesamt 818 → 897) | `app/build/test-results/` (Summe der Ergebnis-XMLs, Stand 0.1.24); F27/Vorlauf s. Abschnitt F |
| Review **F28–F31** | ursprünglich **P0=0/P1=1/P2=3** → nach F31b **P0=0 · P1=0 · P2=1** (offen: Popup-Clip/`isFooterRow`-Randfall); Urteil **push-fähig: JA** | `docs/REVIEW_F28_F31.md` (inkl. „Nachtrag F31b") |
| Emulator-Engine (Rahmenbedingung für alle Diktat-Tests auf 5560) | **nicht gemessen/behebbar**: SIGSEGV in `libonnxruntime.so` blockiert echte Decode-Läufe; Fake-Engine-Tests laufen davon unabhängig | `docs/PROJECT_STATE.md` „Umgebung“, `docs/EMULATOR_DIKTAT_HARNESS.md` §1 |
