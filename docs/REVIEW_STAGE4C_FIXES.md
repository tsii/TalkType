# Review Stufe 4c — FIX-DURCHLAUF (C-1…C-5)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Gegenstand:** Fixes zu `docs/REVIEW_STAGE4C.md` in `DictationCoordinator.kt`, `VoiceKeyboardService.kt`,
`DictationCoordinatorTest.kt`. **Gradle-Lock belegt → kein Build** (auch kein gefilterter Lauf). Reine Lektüre.

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| N-1 | **P1** | `DictationCoordinator.kt:235-244` (+ `:205-214` Partials) | **`InputConnection` wird vom Background-Thread benutzt — nicht thread-sicher.** `stop()` verlagert das Finale in `scope.launch` (Scope=`Dispatchers.Default`); dort läuft `controller.stop()` → `writeHypothesis` → `DeltaTyper.revise` → `InputConnectionSink.replaceBeforeCursor` → `ic.deleteSurroundingText`/`ic.commitText`. Diese Editor-Zugriffe laufen damit auf einem Nicht-UI-Thread (InputConnection ist an den IME-Main-Thread gebunden). Derselbe Pfad galt schon für Partials (`consume`-Coroutine). DESIGN §2.3-1: „alle InputConnection-Operationen" auf Main/UI. | `DictationCoordinator.kt:238-243` `scope.launch { consumerJob?.join(); controller.stop(); … }`; `controller.stop()` ruft `writeHypothesis()` → `typer.revise()` → `sink.replaceBeforeCursor()`. | Decode (Produktion der `Hypothesis`) auf dem Worker belassen, aber **Commit/Delta** (InputConnection) über `withContext(Dispatchers.Main)` oder Main-Handler anwenden — Worker liefert nur das Resultat, der Editor-Write passiert auf dem UI-Thread. |
| N-2 | **P1** | `DictationCoordinator.kt:243` → `VoiceKeyboardService.kt:210` | **`onStopped`-Callback läuft auf `Dispatchers.Default` und mutiert Views → `CalledFromWrongThreadException`.** `onStopped = { updateStatus() }` wird im Worker-Coroutine-Ende aufgerufen; `updateStatus()` setzt `view.statusText.text`/`micButton.isEnabled/isActivated`. View-Mutation vom Nicht-UI-Thread ⇒ Crash (IME). | `DictationCoordinator.kt:243` `onStopped?.invoke()` (im `scope.launch`-Kontext); `VoiceKeyboardService.kt:210` `onStopped = { updateStatus() }`; `updateStatus` (Z.280 ff.) schreibt direkt in Views. | `onStopped` über einen Main-Dispatcher aufrufen (Coordinator bekommt ein Main-Dispatch-Handle, oder der Callback postet in `runOnUiThread`/`handler.post`). |
| C-3 | **P1** | `SetupActivity.kt:10-31` (Platzhalter) + `VoiceKeyboardService.kt:197-208` | **C-3 ist nicht geschlossen — `SetupActivity` ist noch ein Stub.** Der IME-Seiten-Reader (`getSharedPreferences("vk_permissions")` → `denied_forever_*`) ist korrekt, aber **niemand schreibt den Marker**: `SetupActivity` behandelt weder den `REQUEST_PERMISSION`-Extra noch ruft es `requestPermissions` auf (kein `onRequestPermissionsResult`), noch setzt es `denied_forever_*`. Folge: `getBoolean(..., false)` bleibt immer `false` ⇒ `permanentlyDenied` leer ⇒ der `Ask`-Pfad öffnet weiterhin einen Platzhalter („Einrichtung folgt"), der System-Dialog erscheint **nie**, der Settings-Pfad ist unerreichbar. Die Coder-Note („SetupActivity muss den Marker setzen") bestätigt den fehlenden Schreibpfad explizit. | `SetupActivity.kt` (1025 B, nur zwei TextViews, kein `requestPermissions`/`onRequestPermissionsResult`/`putBoolean`); `VoiceKeyboardService.kt:201-208` liest `prefs.getBoolean(PERMISSION_DENIED_FOREVER_PREFIX + it, false)`. | `SetupActivity` (bzw. die dialogtragende Activity) implementieren: `REQUEST_PERMISSION`-Extra lesen → `requestPermissions`; in `onRequestPermissionsResult` bei zweiter Ablehnung `denied_forever_<perm>=true` schreiben. Erst dann ist C-3 substantiv erledigt. |
| N-3 | P2 | `VoiceKeyboardService.kt:106-114` (`onDestroy`) | **Use-after-close-Race im `onDestroy`.** `stopDictationIfRunning()` ruft `c.stop()` (launcht den Final-Worker, kehrt sofort zurück); direkt danach `coordinator?.shutdown()` → `abort()` + `engine.close()` + `serviceScope.cancel()`. `engine.close()` läuft damit **unsynchronisiert** zu einem evtl. bereits gestarteten `controller.stop()`→`engine.transcribe`. Meist rettet `controller.abort()` (state→IDLE) den Worker, aber die Reihenfolge ist nicht garantiert; zudem wird der soeben gestartete Final-Worker durch `serviceScope.cancel()` sofort gekillt — finales Decode sinnlos gestartet. | `VoiceKeyboardService.kt:106` `stopDictationIfRunning()`, `:110` `coordinator?.shutdown()`, `:113` `serviceScope.cancel()`; `DictationCoordinator.shutdown()` → `abort()` + `engine.close()`. | In `onDestroy` **nicht** erst `stop()` (Final-Worker) und dann `shutdown()`: entweder `abort()`/`shutdown()` direkt (kein Final bei Service-Ende) oder `shutdown()` nach `join` des Workers (Engine erst nach beendetem Decode schließen). |
| N-4 | P2 | `VoiceKeyboardService.kt:278-289` (`updateStatus`) | **C-2-Sichtbarkeit nur halb gefixt.** Der neue `visibleError`-Getter (`lastError ?: controller.lastErrorCode`, `DictationCoordinator.kt:90-92`) wird **nicht** verwendet — `updateStatus` liest weiterhin `c?.lastError` (nur Audio-/Start-Fehler). Ein Mid-Run-ASR-Fehler (`controller.lastErrorCode = asr_partial_error`/`asr_final_error`) bleibt unsichtbar → Status zeigt „Bereit". Die C-2b-Hälfte (Capture stoppen bei `state==IDLE` via `flush`) ist korrekt gefixt. | `DictationCoordinator.kt:90-92` `val visibleError get() = lastError ?: controller.lastErrorCode`; `VoiceKeyboardService.kt:281` `c?.lastError != null` (statt `c?.visibleError`). | `updateStatus` auf `c?.visibleError` umstellen; Test „Engine wirft ⇒ Status/error sichtbar". |
| — | ✓ | `DictationCoordinator.kt:130-152` (`start`) | C-5 behoben: `engine.load`/`audioSourceFactory` in try/catch, Wurf → `lastError` + `active=false` + `return false` (kein FGS-Leak, kein TouchListener-Crash). `@Volatile engineLoaded` + `active.compareAndSet` verhindern Doppel-Load (Doppeltipp läuft ins `active`-Gate). C-1 (`runBlocking`) vollständig entfernt. | Z.132-145 try/catch; Z.66 `@Volatile engineLoaded`; Z.93 `active.compareAndSet(false,true)`. | — |

---

## Punktgenaue Antworten

1. **Worker-Final + InputConnection:** **P1 (N-1)** — `controller.stop()` → Commit läuft auf `Dispatchers.Default`; InputConnection ist nicht thread-sicher und gehört auf den Main-Thread. Korrekt: Worker produziert die fertige `Hypothesis`, dann Commit/Delta per `withContext(Dispatchers.Main)`/Handler auf dem UI-Thread anwenden.
2. **`onStopped`:** läuft auf `Dispatchers.Default` → `updateStatus()` (View-Mutation) ⇒ `CalledFromWrongThreadException` (**N-2**, P1). Race gegen `onWindowHidden`/`onDestroy`: der Callback liest `rootView`/`coordinator`, die ggf. schon `null` sind — sekundär, der Crash dominiert.
3. **`shutdown()` in `onDestroy`:** ja, Race möglich — `engine.close()` vs. laufender `controller.stop()`→`transcribe` (**N-3**, P2).
4. **`engineLoaded`-Sichtbarkeit:** `@Volatile` + `active`-AtomicGate ⇒ kein Doppel-Load, Thread-sicher.
5. **try/catch in `onMicDown`:** meldet sichtbar (`updateStatus("Start fehlgeschlagen …")`), schluckt nicht still — C-5 korrekt; **kein** C-2-Muster-Rückfall. Aber N-4 bleibt (ASR-Mid-Run-Fehler weiterhin ohne `visibleError`-Anzeige).
6. **Tests:** 6 `DictationCoordinatorTest`-Tests, **0 neue** für die 5 Fixes. Bei Rückbau **nicht** rot würden: C-1 (Worker-Final), C-4 (`shutdown`/`engine.close`), C-2-Sichtbarkeit (`visibleError`), C-2b (IDLE-Stopp), C-5 (try/catch). **3 wichtigste fehlende Tests:** (a) `stop()` kehrt sofort zurück + `onStopped` feuert genau 1× nach IDLE (C-1); (b) `shutdown()` schließt die Engine und kein `transcribe` läuft danach (C-4/N-3); (c) Engine wirft ⇒ `visibleError` ≠ null (N-4/C-2).

---

## Antwort auf R-2 (Fehlermeldung verschwindet bei Loslassen)

**Severity: P2** (UX, kein Daten-/Privacy-Risiko). Der Coder zeigt „Modell nicht installiert — Setup öffnen" über `updateStatus(extra)` beim Mic-**Down**; beim Mic-**Up** läuft `stopDictationIfRunning()` → `updateStatus()` (ohne `extra`, `lastError==null`, state `IDLE`) → „Bereit", genau in dem Moment, in dem der Nutzer die Meldung lesen will.

**Entscheidungsvorlage (minimale Regel):**

> **Eine blockierende/„letzte" Meldung bleibt stehen** („Modell nicht installiert", „Berechtigung nötig", „Start fehlgeschlagen") **bis zur nächsten bewussten Aktion** — d. h. bis zum nächsten Mic-**Down** oder bis ein Erfolg sie überschreibt. Der Mic-**Up** (reines Loslassen) darf den Status **nicht** auf „Bereit" zurücksetzen, wenn gerade eine Fehlermeldung angezeigt wurde.

- **Sonderfall „Bereit nach erfolgreichem Final":** Bei Erfolg gilt das Gegenteil — „Verarbeite…" (FINALIZING) darf nach beendetem Final auf „Bereit" fallen. Unterscheidung: `lastError`/Fehlercode gesetzt ⇒ sticky; `lastError == null` ⇒ transient („Verarbeite…" → „Bereit").
- **Minimalimplementierung:** `updateStatus()` löscht einen gesetzten Fehlertext nur, wenn (a) ein neuer Mic-Down kommt (Fehler zurücksetzen) oder (b) ein Fehlercode aktiv ist und erst ein Erfolg ihn ersetzt — nie allein durch `onMicUp`. Persistenz über `coordinator.visibleError != null` statt eines `extra`-Nur-Flags.

---

## Verdikt

**Nur mit Fixes freigeben** — 3 P1 (N-1 InputConnection auf Background-Thread, N-2 `onStopped`→View-Crash, C-3 weiterhin offen, da `SetupActivity` den Schreibpfad noch nicht hat), 2 P2 (N-3 Use-after-close-Race, N-4 sichtbarer ASR-Fehler). C-1 (runBlocking weg), C-5 (FGS-Leak behoben) und der C-2b-Teil sind sauber gelöst.

## Restrisiken

1. Editor-Crash/-Korruption durch InputConnection-Nutzung abseits des Main-Threads (N-1/N-2) — tritt beim ersten erfolgreichen Diktat sofort auf.
2. C-3 bleibt funktional offen, solange `SetupActivity` weder Dialog noch `denied_forever`-Marker liefert — frische Installation kann `RECORD_AUDIO` über diesen Fluss nie erteilen.
3. `onDestroy`-Race (N-3) wird im Feld selten, aber als OrtException/abgebrochenes Finale sichtbar.

## Top-5-Aktionen

1. **N-1** — Commit/Delta (InputConnection) auf den Main-Thread verlagern; Worker liefert nur die `Hypothesis`.
2. **N-2** — `onStopped`/`updateStatus` über Main-Handler dispatchen (kein View-Zugriff vom Default-Dispatcher).
3. **C-3** — `SetupActivity` fertigstellen: `requestPermissions` + `onRequestPermissionsResult` + `denied_forever_*`-Marker schreiben.
4. **N-3/N-4** — `onDestroy`-Ordnung entzerren (kein `stop()` vor `shutdown()`); `updateStatus` auf `c.visibleError` umstellen.
5. Drei Tests ergänzen: `stop`-non-blocking+`onStopped`-1×, `shutdown`-ohne-Use-after-close, Engine-Fehler ⇒ `visibleError` sichtbar.