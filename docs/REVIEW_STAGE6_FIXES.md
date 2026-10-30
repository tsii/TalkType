# Review Stufe 6 — FIX-DURCHLAUF (A1-Geisterschreiben, A2-E5-Mapping)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Gegenstand:** Behebung A1/A2 aus `docs/REVIEW_STAGE6.md`. **Gradle-Lock → kein Build** (reine Lektüre).

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| F1 | ✓ | `InputConnectionSink.kt:91-116` | **A1 (Geisterschreiben) behoben.** Pro Aufruf `AtomicBoolean abandoned`; der gepostete Block prüft es **als Allererstes** (`if (abandoned.get()) return@execute`) und wird zum No-op; `abandoned.set(true)` steht **vor** dem Werfen. Invariante eingehalten: nach Timeout keine Editorberührung. Restfenster: theoretisch kann der Block zwischen `get()` (false) und `block()` das Timeout-Signal verpassen — praktisch unerreichbar, weil ein laufender Block sofort `countDown()` auslöst und damit `await` vor der 2-s-Grenze zurückkehrt; ein *wirklich* verspäteter Block sieht `abandoned==true`. | Z.93 `if (abandoned.get()) { …; return@execute }`; Z.112 `abandoned.set(true)` vor `throw`. | Keiner. |
| F2 | Note | `InputConnectionSink.kt` + `DeltaTyper` | **Timeout ⇒ kein Drift im Normalfall; Regel: Session abbrechen.** `apply()` setzt `currentText` erst nach erfolgreichem `sink.replaceBeforeCursor` → bei Timeout bleiben `ownSpan` (Sink), `currentText` (DeltaTyper) und der Editor atomar-konsistent; `commitUpTo` klemmt auf `currentText.length`. **Restvektor:** der `>60`-Zweig schreibt `committedLength = currentText.length` **vor** `apply()` (Befund S4-02) — ein Timeout dort hinterlässt ein vorab fortgeschriebenes `committedLength`. Da der Timeout einen blockierten Main-Thread signalisiert, sind Folge-Edits unzuverlässig. **Regel:** Editor-Sync-Timeout ⇒ laufende Diktat-Session `abort()` (nicht weiter-revidieren). | `InputConnectionSink.onEditExecutor` (throw); `DeltaTyper.apply` (erst Sink, dann `currentText`). | `>60`-Zweig prüfen/bereinigen (`committedLength` erst nach `apply`); Timeout im `DictationController` als Session-Fehler (abort) behandeln. |
| F3 | P2 | `DictationController.kt` `feedAudio`/`stop` (`catch (e: Exception)`) | **A2 (g) OOM nur am Start abgedeckt, nicht während `transcribe`.** Der neue `catch (e: OutOfMemoryError)` in `onMicDown` fängt OOM bei `coordinator.start()` (Engine-Load/Audio-Init). Aber der nativer ORT-Speicherfehler tritt typischerweise **während der Inferenz** auf — `DictationController.feedAudio`/`stop()` fangen nur `Exception`, ein `OutOfMemoryError` (Error) propagiert ⇒ Coroutine-/App-Absturz statt `dictation_error_out_of_memory`. `mapVisibleError`-Muster `alloc`/`bad_alloc`/`oom` erreicht diesen Pfad nie (der Fehler crasht vorher). | `DictationController`: `try { engine.transcribe(…); applyPartial(…) } catch (e: Exception) { fail }`; `VoiceKeyboardService.onMicDown` `catch (e: OutOfMemoryError)`. | `OutOfMemoryError` (und ggf. `Throwable`) zusätzlich in `feedAudio`/`stop` fangen und auf `fail("oom")` → `dictation_error_out_of_memory` mappen. |
| F4 | ✓ | `VoiceKeyboardService.kt:295-340` + `strings.xml:34-41` | **A2 (E5 a–h + Fallback) vollständig.** Alle acht Strings vorhanden (`_permission`, `_model_absent`, `_model_broken`, `_bt_lost`, `_no_mic`, `_out_of_memory`, `_unknown`, `_audio_start`); `mapVisibleError` mappt `noMic`/`audioInit:noInputDevice`, `alloc/bad_alloc/oom`, `unknown/internal`, `SecurityException`/`permission`, `model:` (BROKEN vor ABSENT), `btLost`/`route:bluetooth`; `startErrorText` ordnet `SecurityException`/`BROKEN`/Rest zu. Andere `Error` fliegen bewusst weiter (nur OOM gezielt gefangen — korrekt). | `strings.xml:34-41` (8 Keys); `mapVisibleError`/`startErrorText`. | Keiner. |
| F5 | ✓ | `strings.xml:31` | **E6-Variante-A wörtlich.** „…deaktiviert die App ihren Download-Schalter automatisch; es findet dann kein weiterer Netzwerkzugriff statt … Netzwerkberechtigung ganz entziehen …" — benennt korrekt das App-Flag, nicht einen Permission-Entzug. | `setup_privacy_text`. | Keiner. |
| F6 | Note | `VoiceKeyboardService.kt` `updateStatus(extra)` | **`extra`-Kanal verbleibt untypsiert.** Die Diktat-Pfade mappen jetzt über `mapVisibleError`/`startErrorText`; aber `updateStatus(extra: String)` (sticky) akzeptiert weiterhin beliebige Strings. Aktuell kein Rohcode mehr belegt (Bestandsprüfung: Aufrufer übergeben nur gemappte Ressourcen), aber es fehlt die typsichere Sperre gegen Re-Intrusion. | `updateStatus(extra)` setzt `stickyError = extra` ungeprüft. | Optional: `extra` durch `@StringRes`/Enum ersetzen (nicht blockerend). |

---

## Punktgenaue Antworten

- **(a) IC-Zugriff nach Timeout:** Nein, praktisch ausgeschlossen — der Block prüft `abandoned` als erste Anweisung; der `catch`-Zweig verwirft den Fehler (`if (!abandoned.get()) error = t`), `finally` nur `countDown()`. Kein Pfad, auf dem der Block nach Aufgeben noch `connectionProvider()`/`deleteSurroundingText` ausführt (F1).
- **(b) Drift-Frage (1b):** **Kein echter Drift im Normalfall.** `ownSpan`, `currentText` und Editor bleiben konsistent, weil `apply()` nur nach erfolgreichem Sink-Schreiben aktualisiert und `commitUpTo` klemmt. **Regel:** Timeout = `abort()` der laufenden Session — ein blockierter Main-Thread macht Folge-Edits unzuverlässig, und der `>60`-Zweig (`committedLength` vor `apply`) ist der letzte Drift-Restvektor (F2).
- **A2-Vollständigkeit:** Mapping + Strings vollständig (8 Klassen); einzige Lücke = OOM während `transcribe` (F3).
- **E6:** technisch korrekt (App-Schalter, nicht Permission) — F5.

---

## Verdikt

**Merge freigeben.** A1 (das P1) ist korrekt geschlossen; A2 ist bis auf den P2-Rest (OOM während Inferenz) vollständig. Offen: **0 P0, 0 P1, 1 P2** (F3).

## Top-3

1. **F3 (P2)** — `OutOfMemoryError` auch in `DictationController.feedAudio`/`stop` fangen → `fail("oom")` → `dictation_error_out_of_memory` (sonst crasht nativer ORT-OOM während der Inferenz).
2. **F2 (Note)** — `>60`-Zweig in `DeltaTyper` sichten (`committedLength` erst nach `apply`), Timeout als Session-Abbruch verdrahten.
3. **F6 (Note)** — `updateStatus(extra)` typsicher machen (`@StringRes`/Enum), damit kein Rohcode-Reintritt möglich ist.