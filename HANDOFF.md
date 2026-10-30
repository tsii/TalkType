# HANDOFF — Voice-Keyboard (Projekt-Root, aktiviert Kompaktierungs-Checkpoint)

**Stand:** 2026-10-30 18:0x UTC · Details: `state/progress.md`, `docs/PROJECT_STATE.md`
**Protokoll:** `docs/CONTINGENCY_PLAN.md` · Regeln: `reminders/ALWAYS.md`

## Rollen (fix)
Design = GPT 6.1 Sol · Analysen/Plan/Doku = Kimi K3 · Code = GLM 5.3 · **Review jeder GLM-Arbeit = DeepSeek v4 Pro 0813**.
Orchestrator: beauftragen + mechanisch verifizieren; keine eigene Facharbeit, keine eigenen Reviews.

## Aktueller Stand (belegt)
- **Version: v0.1.13 / versionCode 14** (`app/build.gradle.kts:14-15`); nächster Build **15 / 0.1.14**.
- **Unit Debug 373 / 0 Fehler** (49 Ergebnis-Dateien, `app/build/test-results/testDebugUnitTest`),
  **Release 353 / 0** (47 Ergebnis-Dateien, `app/build/test-results/testReleaseUnitTest`) —
  gesamt **726 / 0** (neu: `app/src/test/.../keyboard/KeySurfaceTest.kt` zählt in beiden Varianten).
- **dist/** (nur noch zwei Dateien; ältere APKs entfernt):
  - `voice-keyboard-0.1.13.apk` — 40 327 468 B —
    SHA-256 `20d36569f87f8d38f3c43abf9abd55737fcca0c439a5c2783f136327cc6db344`
  - `voice-keyboard-0.1.13-diag-debug.apk` — 53 123 218 B —
    SHA-256 `89c91aa658ef58de79ecc74717595380eca5e9055bd6970d4b134bdbbd5b76df`
  - Download: `http://<download-server>/api/file?path=Voice-Keyboard/dist/<name>&download=1`
    (`curl -I` → 200).
- **GitHub:** Repo `tsii/Voice-Keyboard`, `main` = `7f917c8` (219 Dateien); Signier-Passwort
  geschwärzt; `keystore/`, `dist/`, `state/`, `testdata/`, `.models-fp*` ignoriert;
  11,8 GiB `.git`-Müll entfernt. **Weitere Pushes nur nach Nutzer-Freigabe (Regel 10).**

## Seit dem letzten Doku-Stand fertig (Auszug)
Stock-Optik · Info-Leiste · 48-dp-Zeile · Dunkelmodus · Fußzeile · **Haltegeste**
(Push-to-Talk mit Sichtzuständen, Leer-Hinweis D10) · kein Erfolgshäkchen ohne Finale ·
**Statuszeile friert nicht mehr ein** (`ime/StatusBarText.kt`, `ime/StatusRouting.kt`,
Startfrist 15 s) · Diktat-Ausgang als `StopOutcome` (`failedThisSession`/`droppedBlocks`) ·
fangsicherer Consumer (`ime/DictationCoordinator.kt:311`) · **8 neue Diagnosefelder**
(`ctl_state`, `ctl_failed`, `dropped`, `hyp_count`, `hyp_gap_ms`, `audio_s`, `start_ms`,
`starting`; `DebugInfoFormatter KEYS = 24`, Drift-Check aktiv) · 14 `dictation_`-Strings
(neu: `dictation_error_editor_busy`).

## D11/F15 — Tastendruck-Optik (umgesetzt und gerätebelegt)
Neue reine Naht `keyboard/KeySurface.kt` (keine Android-Abhängigkeit). Am Emulator gemessen
(**Orchestrator-Messung, Screenshot-Messung außerhalb des Repos** — die Bilder sind bewusst nicht
im Repository): gedrückte Taste Max-Kanal-Delta **73/255 (hell)** bzw. **65/255 (dunkel)** statt
vorher 27/21; CapsLock-Fläche erreicht den Akzent `#4DB6AC` (hell) / `#80CBC4` (dunkel) mit
Abweichung **0**. Sollwerte aus D11 stehen in `docs/DESIGN_STOCK_LOOK.md`, die Messwerte hier.

## Geklärt (nicht mehr untersuchen)
- **Mikro-Pegel am Telefon in Ordnung:** `peak_dbfs −22,0`, `peak_pct 7`, `route intern`,
  `src VOICE_RECOGNITION` → AudioSource-Experiment hinfällig (`docs/DICTATION_RELIABILITY_PLAN.md`).
- **Kein Sample-Verlust** bei gesundem Consumer (`Channel.UNLIMITED`).
- **Kein `maxDuration`-Abschaltverdacht** (300 s ≠ beobachtete 10–15 s).

## Offen — größter verbleibender Verdacht
**C2 Engine-Rückstau** (`rtf 0,44`, erste Hypothese nach 5 339 ms): erklärt fehlenden Text
nach 10–15 s Dauerdiktat. **Jetzt messbar, noch nicht behoben.**
Entscheidung fällt der nächste 🐞-Export vom Telefon:
`hyp_gap_ms > 4000` bei `ctl_state RECORDING` → Engine-/Chunk-Strategie;
`ctl_state IDLE` + `dropped > 0` → Kontrollpfad.

## Geräte-Behinderung (gilt für alle künftigen Läufe)
**`<device>` (x86_64 + ARM-Bridge) killt den IME-Prozess beim Laden von
`libonnxruntime.so` nativ per SIGSEGV** (dlopen/call_constructors, `am_anr 0`, nur
Worker-Thread) → Diktat-/Engine-Lauf auf DIESEM Emulator **nicht validierbar**;
Tasten-/Gestenzustellung schon (`q` → `qq`). Audio-Brücke ist wieder **AUS**
(`tools/emulator_audio_down.sh`), Peak = 0.

## Nächste konkrete Schritte
1. 🐞-Export vom Telefon mit **langem** Diktat (10–15 s) einholen → C2 entscheiden.
2. Danach: Engine-/Chunk-Strategie **oder** Kontrollpfad-Fix (je nach Messung).
3. Rest-Screenshots (Caps, gedrückte Taste, Emoji-Panel, zweite Öffnung, hell + dunkel).
4. fp16-Variante **pausiert** (`.models-fp16` 1,2 GB); Englisch/MULTILANG liegt auf
   Nutzerwunsch (`docs/MULTILANG_PLAN.md`, Phase-1-Beschlüsse dort).

## Fallstricke
- **Kein `sleep` im Turn** — `schedule_task` + Statuszeile; max. 2× `wait_all(60)`.
- Gradle = globales Mutex. Write-only nur mit **terminierter** Verifikation.
- Emulator-Lauf nie im Coder-Auftrag; Instrumented deinstalliert die App (`install -r` +
  `ime enable` + `ime set` danach), `clearPackageData=false`.

## Historie (überholt, nur zur Einordnung)
- v0.1.3 galt bis 2026-10-28 als aktuell (40 242 852 B, versionCode 4) — **ersetzt**.
- Testbestände 157 / 204 / 213 / 217 / 279 aus früheren Stufen — **überholt** (heute 368 Debug).
- „M6 Release lief noch nie" — **falsch seit** v0.1.0; heute v0.1.13.
