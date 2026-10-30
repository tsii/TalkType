# Review F14 — Diktat-Kette (StopOutcome, Watchdog, StatusRouting, Diagnose-Export)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `dictation/DictationController`, `ime/DictationCoordinator`, `ime/VoiceKeyboardService`, `ime/StatusRouting`, `debug/DebugInfoFormatter`/Collector. Kein Build/Emulator.

---

## Befunde

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| **P2** | `VoiceKeyboardService.kt:652-655` (`onDictationStopped`) | **`cleanSuccess` liest einen Live-Zustand, kein Snapshot.** `lastStopOutcome` wird im Worker **atomar** vor `onStopped` gesetzt (`DictationCoordinator.kt:335`), aber `onDictationStopped` liest zusätzlich `c?.failedThisSession` (Z.653) und `c?.visibleError` (Z.658) — **mutable Felder, die der nächste `start()` zurücksetzt** (`failedThisSession=false` in `DictationController.kt:123`). Reihenfolge „schnelles Loslassen → sofortiger Neustart": zwischen A-`controller.stop()` (IDLE, Worker) und A-`onStopped` (Main) läuft `start()` B und nullt `failedThisSession` → A-`cleanSuccess` wird mit B-Zustand beurteilt (falsches Häkchen bzw. unterdrückter Fehler). | `failedThisSession` **und** `visibleError`/`finalError` zusammen mit `lastStopOutcome` im Worker **direkt nach `controller.stop()`** zu einem Snapshot bündeln und diesen an `onStopped` übergeben; `onDictationStopped` darf den Controller nicht mehr neu lesen. |
| **P2** | `DictationController.kt:100-101` (`currentHypGapMs`) | **`hyp_gap_ms` kann mit injizierter Uhr negativ/absurd werden.** `(clock() − lastHypAt)/1e6` mit `clock=System.nanoTime` ist im Betrieb monoton (ok), aber ein Test-/Injektions-`clock`, der rückwärts läuft, liefert negative Werte in den 🐞-Export (Regel: Diagnose soll robust sein). `nanoTime` ist außerdem relativ, nicht sitzungsgeeignet. | Wert clampen (`max(0, …)`) oder auf `SystemClock.elapsedRealtime` im Produktions-Default umstellen; Negativwerte im Formatter als `0`/`-` ausweisen. |
| **offen** | `DictationController.kt:157-160, 232-235` (`isEditorSyncTimeout` → `abort(); throw e`) | **Editor-Sync-Timeoute rethrowt `IllegalStateException`** nach oben — ich konnte **nicht belegen**, dass `DictationCoordinator.consume/flush` diesen Fall fängt (`flush` fängt sichtbar nur `OutOfMemoryError`). Falls ungefangen ⇒ Coroutine-/App-Crash statt einer R-2-sticky-Meldung (das wäre dann P1). | Im Coordinator einen expliziten `catch (e: IllegalStateException)` um `controller.feedAudio/stop` ergänzen und `lastError`/`visibleError` setzen (oder den Timeout als StopOutcome statt Exception führen). |

---

## Antworten

1. **Folgenloser echter Fehler?** FeedAudio-`fail()` → `failedThisSession=true` → `stop()` liefert `NoFinal(lastErrorCode)` → `onDictationStopped` zeigt `mapVisibleError(code)` sticky (Z.655-658). Finale-`fail()` → `FinalRan(finalError)` → ebenso. Stop-auf-IDLE mit `failedThisSession` → `NoFinal`. Watchdog → `Error(…too_long)` (Z.414). Abort (CANCEL/Editorwechsel) ist **kein** Fehler (kein sticky nötig). Einzig nicht belegter Pfad: der Editor-Timeoute-`throw e` (s. „offen").

2. **`lastStopOutcome`-Rennen:** Innerhalb **einer** Sitzung kein Rennen (`lastStopOutcome` wird im selben Coroutine-Happen-before vor `onStopped` gesetzt). Das Rennen liegt **quer**: `onDictationStopped` liest `failedThisSession`/`visibleError` **live** und nicht als Teil des Outcomes → schnelles Loslassen+Neustart verfälscht die Bewertung der Vor-Sitzung (Befund P2).

3. **`failedThisSession` erst im nächsten `start()`:** Der *neue* Erfolg wird dadurch **nicht** verdeckt (start() nullt es, Z.123) — das Problem ist die **umgekehrte** Richtung (siehe 2): der *alte* `onStopped` liest den bereits genullten Wert. Kern ist dieselbe unsynchronisierte Live-Lesung.

4. **`droppedBlocks`/`hyp_count`:** `dropped` zählt jeden `feedAudio` mit `state != RECORDING` (Z.144) — also auch nach `stop()`/`abort()` gedraines Reste; das ist **korrekt** (es sind verworfene Blöcke). `hyp_count` zählt via `noteHypothesis()` auch das **Finale** (Z.284/236) und nur bei echter `transcribe`-Rückkehr — korrekt. `hyp_gap_ms` s. Befund.

5. **Diagnose-Export:** Der Collector liest die 8 Passthroughs (`ctlState/ctlFailed/dropped/hypCount/hypGapMs/audioSeconds/lastStartMs/starting`, `DictationCoordinator.kt:364-380`); `DebugInfoFormatter` emittiert alle als Zahlen/`0`/`1`/Kurzcode (kein Inhalt/Pfad, Regel 9 ✓). Drift-Check `check(lines.size == KEYS.size + 1)` (Z.137, KEYS=24 ⇒ 25 Zeilen) macht ein vergessenes/eines zusätzliches Feld **rot**.

6. **Regeltreue:** `StatusEvent.Error/Starting/Hint` → nur gemappte Ressourcen-Strings; `strings.xml` unverändert (13 `dictation_*`); keine Autokorrektur/Netzwerk/Inhalte in Nutzertexten. Kein Verstoß.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 2** (Live-`failedThisSession`-Lesung in `onDictationStopped`; `hyp_gap_ms` ohne Clamp bei injizierter Uhr) · **offen = 1** (Fang-Garantie des Editor-Timeoute-`throw e` im Coordinator).

**Nicht belegbar:** der `IllegalStateException`-Fang im Coordinator (kein vollständiges Lesen der `consume/flush`-Catch-Kette), echtes Renn-Verhalten ohne Gerät (nur Codeprüfung), `hyp_gap_ms` unter injizierter Rückwärts-Uhr (kein Testlauf).