# Nächste Aufgaben (Reihenfolge)

**Stand 2026-11-03 · v0.1.38 / versionCode 39 ausgeliefert + auf Telefon installiert** (07:50;
T8 Y-Reihe zentriert (@→m-Menü, ⇧/⏎ umgebaut), T9 Statuszeile nur Debug; Tests
**604/0 + 569/0 = 1173/0**; Review `docs/REVIEW_V0138.md` P0/P1=0 Push JA; nächster Bau **40 / 0.1.39**.

1. **Telefon-Beweise 0.1.38 (höchste Priorität):** Halte-Lauf 60-90 s (offen — Feldtest
   endete mit michold_cancel; Tap-Kette 2× BESTANDEN 22:30 /tmp/vk_phone_e3.log), 🐞-Export
   (warm_hit/engine_load_ms); vc34-Kriterien bleiben (write=delta, kein w1-Corpse,
   start_ms<250). ADB-Port dreht — vom Nutzer erfragen (34147 zuletzt).
   Follow-ups: P2 Anker-Provenienz (git), SegmentFinalizeTest lastflaky, P2-1 Längen-Guard,
   P2-2 coordinator @Volatile (docs/REVIEW_A5_W.md, docs/REVIEW_V0136.md).
2. **P2-Berichtspunkte (optional):** 3 Stück in `docs/REVIEW_A1_A3_B.md` (stale Kommentar,
   Arming-Sonderfall, redundante Baseline-Setzung).
3. **PUSH ERFOLGT (Nutzer-Freigabe 2026-11-03):** main `7d50174` — 0.1.32–0.1.38
   inkl. Reviews/Doku/README-Bild hochgeladen; Regel 10 gilt wieder ab jetzt.
4. Zurückgestelltes unverändert (MULTILANG, NPU/GPU, Variante B, fp16); alte C2-Export-
   Entscheidungsbaum-Arbeit ist durch A4-Diagnose überholt.

---
# Archiv (Stand 0.1.31, erledigt)

1. **C2 = Diktat-Abbruch: OFFEN (höchste Priorität).** Längere Aufnahmen bleiben
   unvollständig (Ursache nicht lokal erklärt); die **Varianz ist jetzt messbar** via
   F42-Felder `had_output`/`output_chars` (+ `first_loud_ms`, `first3s_*`, `fgs_stopped_by`,
   jetzt `status_res`).
   Nächster Beweis: **🐞-Export #3 vom Telefon mit 0.1.30-diag** — lang halten, 🐞 drücken,
   einsenden; Auswertung nach dem Entscheidungsbaum `docs/ANALYSE_DIKTAT_ABBRUCH_2.md`
   §9 (Schichten: `cap_blocks`/`loud_s` → `hyp_count` → Commit → UI). Beweispunkte:
   `status_res`, **`fgs_stopped_by`** (bei Halten `up`), **Halte-Beweisführung**.
   Mit 0.1.31 ist der **Segmenter (F53)** drin: lange Aufnahmen dekodieren segmentiert
   (Fenster ≤ 35 s, Hyp-Latenz ≤ ~1,4 s) — der Export zeigt, ob die Schicht Engine oder
   Kontrollpfad war. F43-Auto-Stop (2,5 s, bei Halten via F49 unterdrückt), F45-FGS-Fix,
   F49–F53 sind IN; windowed-Streaming (`KONZEPT` §5.1) bleibt **gesperrt**
   bis Export #3 die Schicht zuordnet. Die 0.1.15-Auswerte-Regeln (F21-Milderung/Takt
   via `decode_skips`/`decode_pace_ms`, quadratisches Neudekodieren via
   `decode_ms_max`/`buffer_s`, `hyp_gap_ms`/`ctl_state`-Lesarten) gelten weiterhin als
   Hintergrund. Grundlage: `docs/STREAMING_FREEZE_ANALYSE.md`.
2. **0.1.32-TODOs (Bau 33):** **`hint:<res-name>`** (StatusEvent.Hint-Codefeld —
   P2-Follow-up aus Review A1–A3), Rest-P2-Follow-ups; **I5 Hold-600-s-Lauf MUSS als
   ERSTE Session nach Kaltstart laufen** (Emulator-Audio-GESETZ, s. Item 5).
3. **F37 = Clock-Injektion im Coordinator** (Follow-up aus F35): die Controller-Uhr ist
   **nicht injizierbar** → erst mit ihr läuft **Weg B** (Korpus + echte Engine,
   Emulator/Phone) deterministisch. Plan: `docs/KORPUS_DIKTAT_TESTPLAN.md` §5.
4. **Push erst nach Nutzer-Freigabe (Regel 10):** `main` = `dc3b4f0`; **v0.1.14 … v0.1.31
   sind nicht gepusht** (Reviews F35/F36/F38–F53: P0/P1 = 0, jeweils Push-Empfehlung JA).
5. **Emulator-Blocker dokumentiert lassen:** `<device>` killt den IME-Prozess beim
   Laden von `libonnxruntime.so` (SIGSEGV) → Diktat dort nicht validierbar (hängt auch
   an F32-B); Tasten/Gesten weiterhin prüfbar. Audio-Brücke bleibt **AUS**
   (`tools/emulator_audio_down.sh`). **Emulator-Audio-GESETZ (0.1.31, für ALLE Audio-/Halte-
   Läufe verbindlich):** **nur die ERSTE App-Session nach Kaltstart liefert Ton** (jede
   weitere: App-Stream −84 dBFS; tinycap/host OK; audioserver-Neustart/`emu mic`
   wirkungslos) → lange Halte-Beweise nur als **Erste Session** planbar (92-s-Beweis
   deshalb nicht erbracht; vc32-Erste-Session lief real: 93 Zeichen, peak −17 dBFS,
   `cap_blocks` 280). Google-Onboarding: `messaging`/`gms`/`calendar`/`quicksearchbox`
   jetzt `disabled-user`.
6. **Zurückgestellt, nicht anfassen ohne Nutzerfreigabe:** MULTILANG-Phase 1
   (`docs/MULTILANG_PLAN.md`), NPU/GPU (abgeschlossen: nicht erreichbar), Variante B
   (abgelehnt). fp16 **geprüft und verworfen** (`docs/FP16_ABWEGUNG.md`: identische
   Token-IDs, aber 1,90× Download / 1,30× Rechenzeit / 2,13× Startzeit / 4,2× Speicher).

## Betrieb (verbindlich, `docs/CONTINGENCY_PLAN.md` §12/§13)
- ≤ 4 Dateien / ≤ 2 Builds / ≤ 10 min pro Coder-Auftrag; Instrumented-Test nie im Coder-Auftrag.
- Kein `sleep` im Turn — `schedule_task` (≥ 60 s) bzw. max. 2× `wait_all(60)`.
- Gradle = globales Mutex; Emulator exklusiv; `claim_files` vor Multi-Datei-Aufträgen.
- ≤ 6 lebende Subagenten; Slot-Recycling per `steer`; fertige Sessions dismissen.
- Liveness am Dateisystem messen (mtime/`git status`), nicht am Session-Output.
- Vor jeder Kompaktierung: `HANDOFF.md` + `state/progress.md` + `reports/NEXT.md` aktualisieren.
- **Push nur nach Nutzer-Freigabe (Regel 10)**; `main` steht auf `dc3b4f0`;
  **v0.1.14 … v0.1.31 sind nicht gepusht.**

## Historie (erledigt, nicht wieder aufnehmen)
- „Build wieder vollständig grün (157 Tests)“ → 726/0 (0.1.13) → 734/0 (0.1.14, überholt)
  → 818/0 (0.1.15, überholt) → 819/0 (F24d, überholt) → 828/0 (F24e, überholt) → … →
  897/0 (0.1.24, überholt) → (0.1.27: 475 Debug, Release-Messung folgt, überholt) →
  935/0 (0.1.29, überholt) → 961/0 (0.1.30, überholt) → aktuell **995 / 0** (Debug **513**
  laut `app/build/test-results/testDebugUnitTest`, Release **482** laut `testReleaseUnitTest`).
- „DeepSeek-Review M4b + M5a" → abgeschlossen (`REVIEW_STAGE4B`/`4C`/`6`).
- „M4c IME-Verdrahtung“, „M4d WAV-Test“, „M5b Setup-UI“, „M6 Release“, „M7 Review“ →
  **alle umgesetzt**; aktuell signiert: Release **v0.1.31** (SHA-256 `367165ea…`,
  Diag `2ee37322…`); Historie u. a. v0.1.30 (`34edb6d7…`), v0.1.29 (`1cdb1801…`),
  v0.1.14 (`cdb1606c…f462`).
- **D11/F15 Tastendruck-Optik** umgesetzt und gerätebelegt (Naht `keyboard/KeySurface.kt`;
  Delta 73/255 hell / 65/255 dunkel, CapsLock-Akzent `#4DB6AC`/`#80CBC4`, Abweichung 0 —
  Orchestrator-Messung, Screenshot-Messung außerhalb des Repos).
