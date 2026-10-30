# Nächste Aufgaben (Reihenfolge)

**Stand 2026-10-30 · v0.1.13 / versionCode 14 ausgeliefert**; nächster Build **15 / 0.1.14**.
Vorgänger-Listen (M4b/M5b/M6 „offen") sind erledigt — siehe `state/progress.md`, Historie am Ende.

1. **C2 entscheiden (höchste Priorität):** 🐞-Export vom Telefon mit **langem** Diktat
   (10–15 s) einholen. Auswertung:
   - `hyp_gap_ms > 4000` bei `ctl_state RECORDING` → **Engine-/Chunk-Strategie**
     (Hypothesen-Intervall adaptiv, Fenstergröße, ggf. RTF-Ziel ≤ 0,5).
   - `ctl_state IDLE` + `dropped > 0` → **Kontrollpfad** (stillen Fail sichtbar machen,
     Aufnahme beenden, Toast/Statuszeile).
   Grundlage: `docs/STREAMING_FREEZE_ANALYSE.md` (C1/C2, Instrumentierung §3).
2. **Danach Fix als Mikro-Auftrag** (≤ 4 Dateien, ≤ 2 Builds, test-first) und neue
   Version bauen; Unit-Bestand muss **≥ 373 Debug / ≥ 353 Release** bleiben.
3. **Rest-Screenshots** (kein Code): Caps-Zustand, gedrückte Taste, Emoji-Panel,
   zweite Öffnung, je hell + dunkel — für M7-Abnahme.
4. **Emulator-Blocker dokumentiert lassen:** `<device>` killt den IME-Prozess beim
   Laden von `libonnxruntime.so` (SIGSEGV) → Diktat dort nicht validierbar; Tasten/Gesten
   weiterhin prüfbar. Audio-Brücke bleibt **AUS** (`tools/emulator_audio_down.sh`).
5. **Zurückgestellt, nicht anfassen ohne Nutzerfreigabe:** fp16-Variante (`.models-fp16`,
   1,2 GB), MULTILANG-Phase 1 (`docs/MULTILANG_PLAN.md`), NPU/GPU (abgeschlossen: nicht
   erreichbar), Variante B (abgelehnt).

## Betrieb (verbindlich, `docs/CONTINGENCY_PLAN.md` §12/§13)
- ≤ 4 Dateien / ≤ 2 Builds / ≤ 10 min pro Coder-Auftrag; Instrumented-Test nie im Coder-Auftrag.
- Kein `sleep` im Turn — `schedule_task` (≥ 60 s) bzw. max. 2× `wait_all(60)`.
- Gradle = globales Mutex; Emulator exklusiv; `claim_files` vor Multi-Datei-Aufträgen.
- ≤ 6 lebende Subagenten; Slot-Recycling per `steer`; fertige Sessions dismissen.
- Liveness am Dateisystem messen (mtime/`git status`), nicht am Session-Output.
- Vor jeder Kompaktierung: `HANDOFF.md` + `state/progress.md` + `reports/NEXT.md` aktualisieren.
- **Push nur nach Nutzer-Freigabe (Regel 10)**; `main` steht auf `7f917c8`.

## Historie (erledigt, nicht wieder aufnehmen)
- „Build wieder vollständig grün (157 Tests)" → **726 / 0** (Debug **373** laut
  `app/build/test-results/testDebugUnitTest`, Release **353** laut `testReleaseUnitTest`).
- „DeepSeek-Review M4b + M5a" → abgeschlossen (`REVIEW_STAGE4B`/`4C`/`6`).
- „M4c IME-Verdrahtung", „M4d WAV-Test", „M5b Setup-UI", „M6 Release", „M7 Review" →
  **alle umgesetzt**; Release **v0.1.13** signiert (SHA-256 `20d36569…b344`), Download-Link `curl -I` = 200.
- **D11/F15 Tastendruck-Optik** umgesetzt und gerätebelegt (Naht `keyboard/KeySurface.kt`;
  Delta 73/255 hell / 65/255 dunkel, CapsLock-Akzent `#4DB6AC`/`#80CBC4`, Abweichung 0 —
  Orchestrator-Messung, Screenshot-Messung außerhalb des Repos).
