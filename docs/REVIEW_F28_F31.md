# Review F28/F31 — Watchdogs, FGS-Handshake, Popup-Geometrie, F29-Layout-Gates, F30-FakeWav

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Stand:** v0.1.23 / vc24 · Debug 461/0 · Release 433/0 (nicht selbst gelaufen).

---

## Befundtabelle

| Befund | Schwere | Datei:Zeile | Fix |
|---|---|---|---|
| **SILENT-Watchdog bricht eine laufende, gute Session bei natürlicher Sprechpause (> 3 s) ab.** `SILENCE_RMS = 0,01` (−40 dBFS) + `STALL_LIMIT_MS = 3000`; der Silent-Zweig feuert, sobald `capBlocks > 0 && (now − lastLoudAtMs) > 3000` — also nach 3 s ohne einen Block ≥ −40 dBFS. Natürliche Pausen (Atem/Raumrauschen typ. −50…−60 dBFS) haben **keinen** solchen Block und beenden das Diktat via `errorListener.onError(ERR_SILENT)` → `abort()`. | **P1** | `DictationCoordinator.kt:87-90, 337-338` (`SILENCE_RMS`, `STALL_LIMIT_MS`, Silent-Zweig) | Silent-Schwelle auf 5000–8000 ms heben **oder** `SILENCE_RMS` auf ~−55 dBFS senken **oder** Silent nur am Ende (nach `stopAndDrain`), nicht mid-session prüfen. Minimalsicher: Silent als Warnung statt Abbruch. |
| **FGS-Handshake kalt-startet nahe der 2-s-Frist.** `awaitFgsReady(2_000)` wartet auf das `startForeground`-Signal des FGS; bei kaltem Prozess/erstem Start kann `onStartCommand`+`startForeground` > 2 s brauchen → „no audio start" für einen eigentlich gültigen Start. Kein Deadlock (Condition-`awaitNanos` gibt die Lock frei), aber die Frist ist knapp. | P2 | `VoiceKeyboardService.kt:148, 471, 903-918` | `FGS_READY_TIMEOUT_MS` auf 3–4 s erhöhen oder einen einmaligen Retry-Handshake zulassen. |
| **Stall- vs. Silent-Doppelfeuer ausgeschlossen, Race mit stop() bleibt harmlos.** Der Stall-Zweig feuert zuerst und `return`t (Kommentar „ERR_STALL hat Vorrang"), Silent wird nie im selben Lauf nachgezogen; ein verspäteter Watchdog gegen `stop()` liefert nur `NoFinal` (kein Datenverlust). | Note | `DictationCoordinator.kt:327-338` | — |
| **Reset-Pfade sauber.** `capBlocks/lastBlockAtMs/lastLoudAtMs/micRelValue/statusShownValue` werden in `start()` zurückgesetzt (Z.202-211) — kein Leck in den nächsten 🐞-Export. | Note | `DictationCoordinator.kt:202-211` | — |
| **Popup-Top negativ (r0) + `clipChildren=false`:** Überstand über die Statusleiste ist gewollt (Z.72-73, ImeRootView:159-160), aber derselbe `clipChildren=false` gilt für **alle** Root-Kinder (auch Emoji-Block) — ein künftiger Overdraw außerhalb der Bounds würde nun sichtbar. Bei 5-Zeilen-Layouts (pt-pt) ist `rowIndex==0` weiterhin die oberste Reihe, aber die Fußzeilen-Erkennung („Reihe enthält space") konnte ich **nicht** belegen (Symbol fehlt im grep) — `isFooterRow`-Quelle unklar. | P2/offen | `KeyboardView.kt:521-533`, `LongPressMenu.kt:90-94`, `ImeRootView.kt:159-160` | `isFooterRow`-Herleitung zeigen/testen (Layout ohne `space`/ru-ru-Ziffernreihe); `clipChildren=false` ggf. nur am KeyboardView statt an der Root. |
| **F29-Gates:** die Sollwerte (space 6.5, mic 2, @-Regel, m-left 0.5, shift 1) werden vom Gate nur **summenbasiert** abgedeckt; ein „@ als eigenständige Taste UND als alternate" (Doppelpfad) würde Σ nicht verletzen und bliebe u. U. unerkannt — Prüfung der Einzelwerte fehlt im Befundmaterial. | P2/offen | `FooterAcrossAssetsTest` / `LayoutCompletenessTest` | Einzelwert-Assert (space==6,5, mic==2, @ je Ebene genau 1× ohne alternate-Doppel) statt nur Σ. |
| **F30 FakeWavAudioSource (Debug-only):** Source-Set nicht gelesen; Zeitabhängigkeit (sleepbasiertes Vorspulen) ist ein Flakiness-Risiko, sofern sie über `System.sleep` statt injizierbare Uhr läuft. | offen | `debug/FakeWavAudioSource.kt` | Source-Set (`src/debug`) bestätigen + injizierbare Uhr statt `sleep`. |

---

## Gesamturteil

**P0 = 0 · P1 = 1 · P2 = 3** (Gesamt; die drei P2 sind FGS-Kaltstart-Frist, Popup-Clip/Footer-Erkennung ungeklärt, F29-Gates summenbasiert).

**Push-fähig?** **Nein — nur nach Fix des P1.** Der SILENT-Watchdog (3 s / −40 dBFS) beendet ein Diktat bei einer natürlichen Sprechpause > 3 s — das ist ein Fehlabbruch im Normalfall und darf nicht in v0.1.24. Mindestfix: Silent-Schwelle auf ≥ 5 s heben oder `SILENCE_RMS` auf ~−55 dBFS senken (oder Silent erst am Session-Ende prüfen). Die drei P2 sind nicht push-blockierend, gehören aber vor 0.1.25.

**Nicht selbst belegt (offen):** Testläufe (461/433), `isFooterRow`-Herleitung, `FakeWavAudioSource`-Source-Set/Uhr, F29-Einzelwert-Gates, Permission-Gate ab api 33 (nur Signatur gesehen).

---

## Nachtrag F31b (P1-Fix)

1. **P1 ist GESCHLOSSEN.** `everLoud` (DictationCoordinator:218/300/330-332) ist ein Einmal-Latch: Sobald der erste laute Block (`rms ≥ SILENCE_RMS`) kam, feuert SILENT **nie wieder** (`:361-362` `!everLoud`-Gate). Eine natürliche Sprechpause nach erstem Ton bricht nichts mehr ab. **Rest (Note, kein P1):** `everLoud` ist irreversibel — ein einzelner lauter Störknack am Start schaltet die „Mikrofon stumm"-Diagnose dauerhaft ab; der Fall bleibt am `Kein Text erkannt`-Toast bei stop() erkennbar (kein Datenverlust, nur schwächere Frühdiagnose).
2. **4-s-FGS-Frist ausreichend, nicht zu großzügig.** Im Normalfall signalisiert `markFgsReady` in <100 ms; die 4 s greifen nur im Fehler-/Kaltstart (sichtbare Obergrenze, keine Latenz im Normalfall; `VoiceKeyboardService:149,472`).
3. **Einzelwert-Gates decken die Sollwerte jetzt ab.** `r1[0]=0,5`, genau ein `@`, ru-ru ohne `@` + `period.alternates[0]="@"`, `r2[0] shift=1`, `space 6,5`/`mic 2`/`period 1` werden einzeln assertiert — der „summenbasiert zu weich"-P2 ist erledigt; auch der `@`-Doppelpfad (Taste + alternate) ist über „genau ein at" + `period.alternates[0]` abgedeckt.

**Aktualisiert: P0 = 0 · P1 = 0 · P2 = 1** (verbleibend: Popup-Clip/`isFooterRow` offen) · 1 Note (`everLoud`-Latch).

**Push-fähig: JA** — der einzige P1 ist durch das `!everLoud`-Gate sauber behoben; der Rest sind Nicht-Blocker.