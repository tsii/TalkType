# Review v0.1.34 — A5 (Sitzungs-Reset) + W-Paket (Warming)

Reviewer: DeepSeek V4 Pro 0813 (`cortecs/deepseek-v4-pro-0813`), 2026-11-02 21:10.
Basis: vc35-Arbeitsstand (Debug 579/0), Root-Cause-Bericht Debugger (ownSpan-Leiche,
Toggle-Beweislogs `/tmp/phone_toggle_{sess,asr}.log`).

## Verdikt (Review-Lauf 1)

**P0 = 1, P1 = 0, P2 = 2.** Push: NEIN bis P0-Fix.

### P0 — A5 greift in Produktion nicht: `CountingSink` delegiert `resetSession()` nicht
- `VoiceKeyboardService.kt:1008` baut `CountingSink(InputConnectionSink(...))`;
  `DictationController.start():373` ruft `sink.resetSession()` → Wrapper;
  `CountingSink` (`:1081–1096`) überschrieb `resetSession` nicht → Interface-Default
  (`EditorSink.kt:61`) = No-op → ownSpan-Leiche in Produktion weiter aktiv.
- `InputConnectionSinkSessionTest` injizierte den inneren Sink direkt → falsches Grün.
- **FIX (Orchestrator, 21:11):** `CountingSink.override fun resetSession() = inner.resetSession()`
  + Gegenprobe `produktions-wrap … delegiert Sitzungsreset` (neuer Test, Wrapper-Pfad).

## P2 (nicht-blockierend)
1. A5(b) verlässt sich auf beiläufigen `Graphemes.isBoundary`-`require` statt explizitem
   `deleteLength ≤ ownSpan`-Guard. Empfehlung für spätere Runde.
2. `coordinator`-Feld (`VoiceKeyboardService.kt:175`) jetzt Zwei-Schreiber (Main über
   Preload `:555`, Worker `:754`), nicht `@Volatile`; Zeitenfolge + Single-Flight
   mitigieren, Muster bestand bereits. Optional `@Volatile`.

## Korrekt verifiziert
- A5-Ort/Reset (`start():373` direkt nach `tracker.reset`), `InputConnectionSink.kt:126–128`
  synchron @Volatile, `EditorSink.kt:61` Default-No-op (Fakes unverändert).
- Race: `start()` nur aus IDLE; Sink-Writes synchron via `onEditExecutor`;
  A1-Timeout setzt `abandoned` vor Wurf → kein apply-in-flight-Fenster.
- W1: `loadEngineOnce` synchronized + double-check (`DictationCoordinator.kt:417–431`);
  Fehlerpfad `engineLoaded=false`, Zähler/Trigger nur bei Erfolg; `preload()` schluckt;
  `start():309` `loadEngineOnce("mic")` (Lazy-Fallback echt).
- Service: `preloadDue` rein (`:1973`), `preloadTried` 1×/Lebenszeit, Preload in
  `serviceScope.launch` (nie Main).
- W3: 5 KEYS hinten (`:288–292`), 68+Kopf=69 (`check :365`), 0/1-Kodierung, Sanitizer,
  Collector null-sicher (`DebugInfoCollector.kt:177–181`). Regel 9: `load_trigger`
  closed {mic, ime_show, "-"}, keine Pfade.
- W2: `shutdown()` einziger `close()`-Pfad (`:1091–1099`, CAS), kein Idle-Unload.
- `EnginePreloadTest` (3 Tests) echt: `CountingEngine` wirft bei Zweitload; warm(<500)/
  kalt(≥500) echte Messung; Preload-Fehlschlag ⇒ lazy 2. Load (`loadCalls=2`).

## Status nach Fix (Orchestrator-Verifikation, 21:14)
- **P0-Fix eingebaut:** `CountingSink.resetSession()` delegiert an `inner`
  (`VoiceKeyboardService.kt` :1094ff, KDoc mit Review-Verweis) + **Gegenprobe**
  `produktions-wrap … delegiert Sitzungsreset` in `InputConnectionSinkSessionTest`
  (Wrapper-Pfad wie Produktion: Reset-Überleben, DELTA in Sitzung 2, Zähler ok).
- Release-Echtheit wiederhergestellt: Compilation-Schaden (F56-Stub in `src/debug`,
  Test in `src/test`) durch Test-Verschiebung nach `testDebug` behoben —
  **Release 544/0 frisch** (war tagelang alt-482 wegen Up-to-date-Trugbild; XML-mtime
  jetzt 21:13, 67 Dateien). Release-APK 21:13 BAUT MIT P0-FIX.
- Debug-Runde 2 (Volltest inkl. Wrapper-Test + assembleDebug mit Fix) läuft
  (`/tmp/b35_dbg2.log`, Ende-Marker D2_DONE; erwartet 580/0).
- Review-Lauf 2 (P0-Nachbewertung + P2-Entscheidungen): steer auf sa-b7b53, Ergebnis
  unten anzuhängen.

## Review-Lauf 2 (Folgebewertung, 21:17 — DeepSeek, sa-b7b53)
**P0 behoben, korrekt und vollständig.** Delegationskette `Controller.start():373 →
CountingSink.resetSession() → InputConnectionSink.resetSession()` geschlossen;
Gegenprobe `CountingWrapper` + Wrapper-Zwei-SitzungsTest bestätigt ownLength 5→0,
writeReason=delta, commits=2. Hinweis des Reviewers (akzeptiert): Test repliziert die
Wrapper-Struktur (private inner class), Produktions-Code direkt gelesen und verifiziert.

### Verdikt final: **P0 = 0, P1 = 0, P2 = 2 (Follow-ups 0.1.35) — Push: JA.**
- P2-1: expliziter Längen-Guard in `replaceBeforeCursorSync` (statt beiläufigem
  Graphemes-`require`) — Kandidat 0.1.35.
- P2-2: `coordinator`-Feld `@Volatile` / Build in `serviceScope` — Follow-up.

## Auslieferung 0.1.34 (21:20)
Debug **580/0** + Release **544/0 frisch** = **1124/0**. dist: voice-keyboard-0.1.34.apk
`92ca385f…`, -diag-debug.apk `d948dbc2…`; 0.1.33 rotiert. Telefon-Beweise stehen aus
(Halten→Toggle-Szene, Warmstart; Kriterien beim Debugger angefragt).

## Geräte-Bewertungskriterien 0.1.34 (Debugger conv-6cf9903c, 21:2x — Beweiskette Telefon)
- **BESTAND Besitz:** H→T im selben Feld; ab `audio_s=2` `write=delta/same`, kein `w1`
  ohne nachgewiesenen Fremdgriff; `current_len/output_chars` wachsen; Toggle endet normal.
- **BESTAND Warm:** Tastatur vorher sichtbar ⇒ `warm_hit=1`, `load_trigger=ime_show`,
  `engine_loads=1` über beide Sessions, `start_ms<250`, PID unverändert; Modell-PSS resident.
- **Fallback gültig (kein Bug):** neue PID ⇒ Prozessverlust; `warm_hit=0`, `load_trigger=mic`,
  erneuter Load = korrekter Lazy-Fallback.
- **Echter Fehler:** gleiche PID + nachweislich vorheriges IME-Show, aber `warm_hit=0`
  (Preload-Ordering/`preloadTried`/finished prüfen); jedes unerklärte W1-Corpse-Muster.
- **Wackelkandidat, kein Fail:** W3d mit DELTA-Recovery, Pacing-Skips, leichte
  RSS-Schwankungen, `start_ms≥250` ohne vorheriges Keyboard-Show.
