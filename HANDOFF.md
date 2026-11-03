# HANDOFF — Voice-Keyboard (Projekt-Root, Checkpoint für die nächste Schicht)

**Stand:** 2026-11-03 ~07:50 UTC · **Version ausgeliefert: v0.1.38 / versionCode 39** (T8 Y-Reihen-Zentrierung — @-Taste raus (→ Langdruck m, de neben µ), ⇧ 96,5 px, ⏎ 150,5 px, Y exakt auf A/S-Fuge, M exakt auf K/L-Fuge (13/9, 145/18; px-exakt, Planer sipgate-ultra freigegeben, Review unabhängig nachgerechnet); cs/ru dokumentierte Ausnahmen; checkGeometry neu gehärtet; T9 Statuszeile+🐞 NUR in Debug (Release GONE, FLAG_DEBUGGABLE-Naht). Tests 604/0+569/0=1173/0; Review docs/REVIEW_V0138.md P0/P1=0 Push JA; dist 0.1.38 `2a379f2f…`/diag `fca9592a…`; **auf dem Nutzer-Telefon per adb -r installiert (signaturgleich, Modell blieb)**; Y-Beweisbild w_dbg3.png). Planner-Bestätigung Nutzer 2026-11-03: sipgate-coding-ultra.
Davor v0.1.37 / versionCode 38 (T7 Nutzer-Fund „überlagernde Menüs“ geheilt: Rand-Clamp menuLeftClamped (Popup+Arming) + M-Regel r2 max 10 à 0,75 — Überläufe el-gr 12/da-fi 11 Zellen gekappt, …→dot-Menü, –→hyphen-Menü; Invarianten-Test: kein Menü läuft je aus dem View (Rot-Beleg 1381>1080 px) + Zeichenschutz … ” ª º ‰ ±; Clamp-Beweisbild u_clamp.png; Review docs/REVIEW_V0137.md P0/P1=0 Push JA; Tests 597/0+562/0=1159/0; dist 0.1.37 `0421519d…`/diag `418bc77a…`).
Davor v0.1.36 / versionCode 37 (T5 Langdruck-Menü in der STATUSZEILE — Band-Zone top=−statusBandPx + Elevation 2f, finaler Emulator-Pixelbeweis k3_hold: 6 Kacheln y1700–1838, keine Tastenreihe verdeckt; T6 Symbol-Einheitsraster Σ[10,10,10,13] in allen 15 Assets — ⌫/⏎ auf Buchstaben-Slots, Fuß ohne ⏎ und ohne Zeichen neben der Leertaste, €↔Shift-Ebene da/fi/el, ‰→percent-Menü; Review docs/REVIEW_V0136.md P0/P1=0 Push JA (P2×2 report-only); Tests 593/0+558/0=1151/0; dist 0.1.36 `26220180…`/diag `650162bd…`; T6-Großteil per Skript tools/rebuild_symbol_layers.py, vor/after-Gegencheck echt).
Davor v0.1.35 / versionCode 36 (T1 Overlay-Zweizonen — Q-Menü über der Tastatur, Emulator-Pixelbeweis; T2 ⇧-Pfeilfuß 0,18·s abgesetzt + Buchstaben 26/Ziffern 24 sp; T3 Tipp-/Halte-Rennen geheilt — Tap≤300 ms startet jetzt unabhängig vom Startzeitpunkt, Beweislog /tmp/vk_phone_e2.log; T4 Langdruck-Menüs Symbolebenen in allen 15 Assets: ¿ ¡ [{< }]‚ „“”… ‰ ± €£¥; Review docs/REVIEW_V0135.md P0/P1=0 Push JA; Tests 589/0+554/0=1143/0; dist 0.1.35 `438ec03d…`/diag `83c58dc0…`).
Davor v0.1.34 / versionCode 35 (A5 ownSpan-Leichen-Fix = Toggle-Wurzel vom Gerät; W-Paket: Preload bei erstem Tastatur-Show + Engine resident + 5 Export-Felder; Review P0→Fix→P0=0 Push JA, `docs/REVIEW_A5_W.md`; Tests 580/0+544/0=1124/0; Release-Echtheits-Schaden entdeckt&behoben: Test lag in falschem Sourceset — Release-Tests tagelang Up-to-date-Trugbild, echt jetzt 544). Davor v0.1.33/vc34 (A1–A4+B1/B2, C-emu E1–E5; `docs/PLAN_V0133.md`, `REVIEW_A2_A4.md`, `REVIEW_A1_A3_B.md`) und v0.1.32/vc33 (E1c/E1d, 1028/0).
**Protokoll:** `docs/CONTINGENCY_PLAN.md` · Regeln: `reminders/ALWAYS.md` · Details: `state/progress.md`, `docs/PROJECT_STATE.md`

## Wer darf was (Rollen, fix)
- **Design/UI:** `my/work-sipgate-coding-ultra` (Provider My) — **seit 2026-11-01, ersetzt Sol** (`openai-codex/gpt-6.1-sol`, dauerhaft ausgefallen: Codex-Limit). Effort-Levels: low/high/max.
- **Plan:** `my/work-sipgate-coding-ultra` (Provider My, <llm-provider-host>) — **seit 2026-11-01, vorher Kimi**. Effort-Levels: low/high/max.
- **Analysen/Doku:** Kimi K3 (`moonshot/kimi-k3`) — verfasst zusätzlich Design/Grafik als Sol-Ersatz.
- **Code:** `my/work-zai-org-glm-5.3-verda` (Provider My) — **seit 2026-11-01, vorher cortecs/glm-5.3**. Effort: low/high/max.
- **Review jeder Code-Arbeit:** DeepSeek V4 Pro 0813 (`cortecs/deepseek-v4-pro-0813`).
- **Orchestrator:** beauftragt + verifiziert mechanisch; keine eigene Facharbeit, keine eigenen Reviews.

## Versions-Historie (ausgeliefert, alle in `dist/`, SSH-Link über `<download-server>/api/file?path=Voice-Keyboard/dist/<name>&download=1`)
| Version | Build | Anmerkung |
|---|---|---|
| 0.1.14 … 0.1.25 | — | historisch, aus `dist/` entfernt (je Bau rotiert) |
| **0.1.26** | 27 (07:43) | P0-Pack (Statuszeile+🐞, Mikro-Zustand, Pfad-Icons ⌫/⇧/⏎) + F33-r2-Raster (Mitte 450px) + F34-P2 (Mikro-Reset, 🐞-Z-Ordnung, Pfad-Cache). Tests Debug **473/0**, Release **442/0**. |
| **0.1.27** | 28 (16:33) | F35 „Weg D“ grün (KorpusFixture 30/60 s, Stub 150 ms, `KorpusTaktTest` 7 Assertions; Befund: Watchdog-Uhr-Race im 60-s-Fall gefixt — Stub-Latenz pumpt eigene Uhr) + **F36 V2-Tastatur** (1u = 108 px, r0–r2 Σ10, ⌫ zwischen M/⏎, Flächen/Fugen/Radius, ⌫-Glyph Links-Pfeil mit Sockel; fr = de-Muster, cs r1 = 0,5+10×0,85, ru r2 bleibt D27/Σ12). Tests Debug **475/0**. Review F35 (P0/P1=0, P2=2) + F36 (P0/P1=0, P2=1), je Push-Empfehlung JA. |
| **0.1.28** | 29 (interim, dist-rotiert) | **F38–F44:** Diktat-Auto-Stop-Diagnose-Paket (F42 = 6 neue 🐞-Felder `had_output`/`output_chars`/`first_loud_ms`/`first3s_loud_blocks`/`first3s_peak_dbfs`/`fgs_stopped_by`, Sanitizer `:`→`=`, Export Kopf+42=43 Zeilen; F43 = Auto-Stop nach ~2,5 s Sprechpause `TRAILING_SILENCE_MS=2500`, c-Gate nur streamende Stille; F44 = `fgs_stopped_by`-Codes). |
| **0.1.29** | 30 (rotiert) | **+F45** = FGS-Leak auf dem Auto-Stop-Pfad gefixt (`stopFgs("autostop")` im onStopped-Finale, `VoiceKeyboardService.kt:1356-1360`). Tests 483/0 + 452/0 = 935/0. |
| **0.1.30** | 31 (rotiert) | **F46 + F49–F52** (Plan `docs/PLAN_V0130.md` Schnitt A): Auto-Stop-Hold-Gate (`autoStopSuppressed`, Halten = kontinuierlich, Nutzervertrag), Start-Serialisierung + UP-Hinweis, K2-Strings, `status_res`, ⇧-Aufwärts-Pfeil (V9). Tests 496/0 + 465/0 = 961/0. |
| **0.1.31** | 32 (01:40) | **AKTUELL — Nutzer-Vertrag-Fortsetzung:** **F53-Zwischensegment-Finalisierung** (`segmentFinalizeMs = 35 000` scharf; α = 38,5 ms/s Kurve, Decode-Fenster ≤ 35 s ⇒ Hyp-Latenz ≤ ~1,4 s, kein RSS-/Zeit-Exponential; §5.1-konform: whole_buffer nur segmentiert; Kette golden: `SegmentGoldenTest` „alpha. bravo. charlie.“ exakt; (b)-Zwangsschnitt-Trade-off = genau ein Leerzeichen, KDoc-dokumentiert) + **Halte-Obergrenze 900 s** (Hold+Toggle, einmaliger Warn-Hinweis ab 780 s; `holdWarnDue`, `HoldWarnTest` 5/0) + **F50-Fix:** `ConsumerFaultTest`-Last-Flaky an der Wurzel (Poll auf Null-Sentinel war No-Op → Endzustands-Poll; 2× Vollbild grün) + **finalizeSegment-Fehlerpfad:** transient ⇒ Antrag verwerfen, Session lebt (nur OOM hart) — Review-P1 behoben + **B2b** `onAborted`-Resets. Tests **513/0 + 482/0 = 995/0**. Reviews A/B1/B2 **P0/P1 = 0**. |

## Belegter Gesamtstand
- **F24a–e, F25, F26, F27, F28a/c, F29, F30, F31/F31b, F33, F34, F35, F36, F38–F46, F49–F53:** grün, alle Geräte-/Grafikbelege vorhanden (F36: Abgleich 6/6 ✓, `docs/DESIGN_TASTATUR_V2.md` §10; F43-Konzept-Präzisierung in `docs/KONZEPT.md`; F49: `DictationHoldGateTest`; F53: `SegmentGoldenTest` golden; 900-s-Haltegrenze: `HoldWarnTest`).
- **Reviews:** `docs/REVIEW_F28_F31.md` (P0=0/P1=0/P2=1 → P1 gefixt), `docs/REVIEW_F29_F33.md` (P0=0/P1=0/P2=3 → alle 3 in 0.1.26 gefixt), F35 (P0/P1=0, P2=2) + F36 (P0/P1=0, P2=1) + A1–A3 (0.1.30: P0/P1=0, P2=2) + A/B1/B2 (0.1.31: P0/P1=0) mit je **Push-Empfehlung JA**. Push-fähig: **JA**.
- **GitHub:** `main` = `dc3b4f0`. **NICHTS gepusht** (0.1.14–0.1.31 liegen nur lokal). Push erst nach Nutzer-Freigabe (Regel 10).
- **M7-Screenshots** (hell/dunkel, 8 Stück) → beim Nutzer bestätigt.

## Offene Arbeit (in dieser Reihenfolge)
1. **F37 = Clock-Injektion im Coordinator** (Follow-up aus F35): die **Controller-Uhr ist im Coordinator
   NICHT injizierbar** — ohne sie läuft **Weg B** (Korpus + echte Engine, Emulator/Phone) nicht
   deterministisch. Beleg/Fund: F35-Testlauf (60-s-Fall). Plan: `docs/KORPUS_DIKTAT_TESTPLAN.md` §5.
2. **C2-/Diktat-Fix:** NICHT gelöst (längere Aufnahmen unvollständig; Varianz jetzt via
   `output_chars`/`had_output`/`status_res` messbar). Wartet auf **🐞-Export #3** vom Nutzer
   (mit **0.1.31-diag**; Beweispunkte: `status_res`, `fgs_stopped_by`, Halte-Beweisführung).
   Entscheidungsbaum: `docs/ANALYSE_DIKTAT_ABBRUCH_2.md` §„Entscheidungsbaum
   Export #3". Was belegt ist: Stop über echten UP-Pfad bei ~1,2 s, Pegel −56 dBFS (echt
   gemessen), kein 1-s-Timer, kein Decode-Stau. Weg B (Emulator+echte Engine) hängt an der
   ONNX-x86_64-Probe (F32-B, abgebrochen/offen) und an F37.
3. **0.1.32-TODOs:** `hint:<res-name>` (StatusEvent.Hint-Codefeld), Rest-P2-Follow-ups;
   **I5 Hold-600-s-Lauf MUSS als ERSTE Session nach Kaltstart laufen** (s. GESETZ unten).
4. **Push** nach Nutzer-Freigabe (0.1.14–0.1.31); **Abschlussdoku** (Kimi).

**Smoke-Transparenz 0.1.31 — Emulator-Audio-GESETZ (verpflichtend für alle Halte-Läufe):**
**Nur die ERSTE App-Session nach Kaltstart liefert Ton** (jede weitere Session: App-Stream
−84 dBFS; tinycap/host bleiben OK; audioserver-Neustart + `emu mic`-Befehle wirkungslos)
→ 92-s-Haltebeweis auf dem Emulator NICHT erbracht. Die vc32-Erste-Session transkribierte
**real** (93 Zeichen, peak −17 dBFS, `cap_blocks` 280). Google-Onboarding-Plage
bereinigt: `messaging`/`gms`/`calendar`/`quicksearchbox` jetzt `disabled-user`. Finale
Halte-Beweisführung via Nutzer-🐞-Export (`fgs_stopped_by`, `status_res`).

**Retest-Transparenz (F41, gültig für alle Pegel-Befunde):** Im F41-Repro lief `paplay`
auf den **FALSCHEN PulseAudio-Server** (leeres `XDG_RUNTIME_DIR`) → die App bekam nie
Sprache; **F41-Pegel-Deutungen ungültig**, die Kontrollpfad-Befunde (Auto-Stop-Fehlen,
`ERR_SILENT`-3-s-Fenster, FGS-Leak) gültig. Retest 0.1.28 mit korrekter Kette:
peak −17 dBFS, Transkription ≈ Golden, Auto-Stop 2,1–2,5 s ✓, FGS-Abort 13 ms ✓.

## Puls/Wecker
- Läuft `*/5 * * * *` und wird bei jedem Standwechsel neu geschrieben. **Nie ausschalten.** Wenn Projekt fertig → auf `*/30 * * * *` (Ruhezustand).
- Nur EINE eigene Uhr aktiv halten. `task-ibps9w`/`task-192pn2` gehören ANDEREN Projekten (nie anfassen). Emulator: 5558 = fremdes Projekt.

## Wichtige Pfade
- Layout-Assets: `app/src/main/assets/layouts/*.json` (15 Stück).
- Status-/Sprach-Endonyme: `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:45-61` (`LanguageCycle.ENDONYMS`).
- Popup-Geometrie: `keyboard/LongPressMenu.kt`, `keyboard/KeyboardView.kt`.
- Fake-Audio-Harness (Debug): `app/src/debug/java/de/voicekeyboard/audio/FakeWavAudioSource.kt`.
- Gemessene Kennzahlen: `docs/MESSWERTE_VERGLEICH.md`.
- V2-Keyboard: Spec `docs/DESIGN_TASTATUR_V2.md` (inkl. §10 Abgleich 6/6 ✓), Previews `docs/preview/keyboard_v2*.png/.gif`, Render-Scripts `tools/render_keyboard_v2*.py`.