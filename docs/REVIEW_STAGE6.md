# Review Stufe 6 — Produktions-Mikro-Fix (InputConnectionSink, E5/E6) + E8-Entscheidungsdokument

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Gegenstand A:** `InputConnectionSink` (Reentranz + `EDIT_TIMEOUT_MS`), `SetupActivity` (R-3), `VoiceKeyboardService` (E5-Mapping), `strings.xml` (E6). **B:** Entwurf `docs/COMMIT_RULE_DECISION_2.md` (E8). **Gradle-Lock → kein Build** (reine Lektüre; 197 Testläufe wurden vom Orchestrator verifiziert).

---

## Befundtabelle

| ID | Sev | Ort | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| A1 | **P1** | `InputConnectionSink.kt:74-89` (`onEditExecutor`) | **Geisterschreiben nach Timeout.** Bei `latch.await(2s)`-Überschreitung wird geworfen, aber der bereits gepostete Block (`editExecutor.execute { … block() … }`, via `Handler(main).post`) ist **nicht stornierbar** und läuft **später** auf dem Main-Thread. `block()` = `replaceBeforeCursorSync` ruft dann `connectionProvider()` (→ `currentInputConnection` **zum Ausführungszeitpunkt**, ggf. ein anderer Editor) und schreibt `deleteSurroundingText`/`commitText` — **nachdem der Aufrufer aufgegeben und abgebrochen/neue Session gestartet hat**. Folge: veralteter Text in den falschen Editor, Doppel-/Geisterschreiben. Das ist exakt das P1, nach dem der Auftrag fragt. | Z.74-80 `editExecutor.execute { try { result = block() } … }`; Z.82-86 `if (!latch.await(…)) throw IllegalStateException("Editor-Sync Timeout…")` — kein Cancel, kein Flag. | Pro Aufruf ein `AtomicBoolean abandoned`; bei Timeout `abandoned=true`; der Block prüft `if (abandoned) return` **vor** `replaceBeforeCursorSync`. (Alternativ: Write-Pfad nicht post-and-wait, sondern Meldung mit Completion-Callback.) |
| A2 | P2 | `VoiceKeyboardService.kt:296-340` + `strings.xml` | **E5-Mapping unvollständig.** Fehlende Klassen fallen alle auf `dictation_error_audio_start`: (a) „kein Mikrofon" (`dictation_error_no_mic` fehlt; kein `AudioManager.getDevices`-Check), (g) „Speicher knapp" (`dictation_error_out_of_memory` fehlt; `OutOfMemoryError` wird **nicht** gefangen — `catch (e: Exception)`/`startErrorText(e: Exception)` sehen ein `Error` nicht), Fallback `dictation_error_unknown` (dingt auf `audio_start` statt eigenem Text). E5 verlangt 7 + Fallback. | `mapVisibleError` only `permission`/`model:`/`btLost` → else `audio_start`; `startErrorText` only `SecurityException`/`BROKEN` → else `audio_start`; strings.xml hat 5 von 8 `dictation_error_*` (kein `_no_mic`, `_out_of_memory`, `_unknown`). | `OutOfMemoryError` (Throwable) zusätzlich fangen; `AudioManager.getDevices(GET_DEVICES_INPUTS)`-leerer-Katalog → `_no_mic`; eigener `_unknown`-Fallback; Muster `alloc`/`bad_alloc` → `_out_of_memory`. |
| A3 | Note | `VoiceKeyboardService.kt:319-339` (`updateStatus(extra)`) | **`extra`/`stickyError` ist weiterhin die Rohstring-Leitung.** `updateStatus(extra)` setzt `stickyError = extra` und rendert es ungeprüft; jeder Aufrufer, der einen Rohcode/literal übergibt (z. B. ein künftiger `updateStatus("… ${e.javaClass.simpleName}")`), landet direkt im UI. Aktuell mapped der Hauptpfad (`mapVisibleError`), aber der `extra`-Kanal ist nicht typsicher abgesichert. | Z.322 `if (extra != null) stickyError = extra`; Z.327 `extra != null -> append(extra)`. | `updateStatus` nur noch mit Enum/String-Resource-ID füttern (kein freier String); `extra` durch ein `sealed`-Statusobjekt ersetzen oder auf die `dictation_error_*`-Resourcen verpflichten. |
| A4 | ✓ | `SetupActivity.kt:112` + `strings.xml:29` | R-3 erledigt: `NetworkDisabled` → `setup_network_disabled` („Netzwerkzugriff ist deaktiviert — Schalter aktivieren"); echter Netzfehler (Download-Failed-Pfad, `:232`) bleibt bei `setup_error_network`. | — | — |
| A5 | ✓ | `strings.xml:31` | E6-Variante-A **wörtlich** umgesetzt („…deaktiviert die App ihren Download-Schalter automatisch; es findet dann kein weiterer Netzwerkzugriff statt. … Netzwerkberechtigung ganz entziehen …"). Deckt die Technik (App-Flag, kein Permission-Entzug) korrekt ab. | — | — |
| A6 | ✓ | `InputConnectionSink.kt:48-58` | Reentranz-Guard korrekt: `isOnMainLooper()` (`Looper.myLooper() === Looper.getMainLooper()`) → inline; deckt auch „bereits im geposteten Edit-Block" ab (der läuft auf Main). `MAIN_EXECUTOR` lazy mit JVM-Fallback (kein Looper in Unit-Tests → inline). `latch`/`result`/`error` sind lokale Variablen je Aufruf → kein Zustandsleck zwischen Aufrufen. | — | — |

---

## Teil B — E8-Entscheidungsdokument (COMMIT_RULE_DECISION_2.md)

| ID | Sev | Ort | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| B1 | P2 | E8 §4.1/§4.3 („≤ 60 **Codepoints**") | **Einheitenmehrdeutigkeit.** Das Dokument schreibt „60 Codepoints", die bestehende Implementierung (`DeltaTyper.maxRevisionChars`, `commonPrefixLength`, `droplast`/`substring`) zählt **UTF-16-Codeeinheiten**. 60 Emoji (je 2 UTF-16-Einheiten) = 60 Codepoints, aber 120 Einheiten → W3c vs. W3d entscheidet sich je nach Zählung anders. Im Dokument ist nirgends festgelegt, welche Einheit gilt. | E8 §4.1/4.3 „≤ 60 Codepoints"; `DeltaTyper.kt` `MAX_REVISION_CHARS=60` + `commonPrefixLength` (UTF-16/`char`-indiziert). | Explizit festlegen: „60 **UTF-16-Codeeinheiten** (wie `DeltaTyper.maxRevisionChars`)", oder beide auf Grapheme (`Graphemes.count`) umstellen — aber **eine** eindeutige Einheit nennen. |
| B2 | P2 | E8 §5 T1 („Vikti ×2 … Rollback genau einmal") | **T1 widerspricht C1 (N=3).** Mit `commit_agreements=3` committen 2 identische „Vikti"-Hypothesen **nicht** (nur 2 Übereinstimmungen) — es gibt daher nichts zurückzunehmen; „Vikti"→„Dikti" ist eine **normale Tail-Revision (W2)**, kein Rollback (W3). T1 behauptet aber „Rollback genau einmal". Der gemessene Gerätefall (2× falsch ⇒ commit) ist bei N=3 durch C1 allein verhindert; W3 ist dann nur noch Belt-and-Suspenders für „3× falsch". | E8 §1 (N=2 führte zum Commit) vs. §3 C1 (N=3) vs. §5 T1 („Rollback genau einmal"). | T1 präzisieren: entweder (i) T1 = „2× Vikti committet **nicht**, 3. Hypothese korrigiert ohne Rollback" (C1-Beweis), und ein **eigener** T-Test „3× falsch ⇒ commit ⇒ W3-Rollback" für den Rollback-Pfad — zwei getrennte Fälle statt einem vermischten. |
| B3 | Note | E8 §3 C1/C2/C3 + §6 | **Kurze Sitzung (1–2 Wörter) committet per Design nie** (C3 ≥3 Wörter), committedLength bleibt 0; das Dokument sagt nur indirekt (Finaldecode `committed_len=0`) was am Ende passiert, und stellt nicht explizit klar, dass der kurze Text **trotzdem** als Partial erscheint und final voll geschrieben wird (D3-Fix in §2). Für den Implementierer offen: „kein Commit" ≠ „kein Text". | §3 Konsequenz + §2 Zusatz-Invariante („Tail immer schreiben") — aber kein Satz zum Short-Session-Final. | Explizit ergänzen: „Sitzung < 3 Wörter: alles Partial, nichts committed; Finaldecode (committed=0) schreibt/korrigiert die ganze eigene Spanne." |
| B4 | Note | E8 §2 W4 + Begriffe | **Alignment von `target` (Hypothese) und `own` (currentText) bei vorbestehendem Fremdtext nicht definiert.** `common = commonPrefixLen(own, target)`, aber `target` ist der volle Transkripttext, `own` nur die Session-Spanne (ab `ownStart`). Weicht `target` an einer Detok-/Führungs-Kante vom Inhalt von `own` ab (`own` ist Suffix des Editors), wird `common=0` ⇒ fälschlich W3 ⇒ Rollback **der ganzen eigenen Spanne**. W4 behauptet „vor ownStart kann nicht auftreten", setzt aber genau diese Präfix-Ausrichtung voraus. | §2 „Divergenz = commonPrefixLen(own, target) < committedLength"; §2 W4. | Festlegen: `common` ist **innerhalb** von `own` zu berechnen (relative Ausrichtung, das `own`-Präfix wird gegen das `own`-Ende von `target`/Kathet abgeglichen), und ein Fehl-Alignment ⇒ W1 (nicht W3). |

---

## Punktgenaue Antworten (Auftrag)

- **(a) Reentranz-Erkennung:** korrekt — `myLooper === mainLooper` deckt „auf Main" und damit auch „bereits im geposteten Block" ab (der Block läuft auf Main) → inline (A6). ✓
- **(b) Nach Timeout:** **P1** — der gepostete Block ist nicht stornierbar und schreibt später (A1).
- **(c) Fehler an der Aufrufstelle:** der Timeout-`IllegalStateException` wird im Partial-/Final-Pfad von `DictationController.feedAudio`/`stop()` (`catch (e: Exception) → fail`) gefangen → kein IME-Absturz, aber er kollabiert zum Sammelcode `asr_partial_error` (Timeout nicht unterscheidbar). Kein C-1-Muster (kein Crash), aber unscharfe Diagnose.
- **(c) latch/Ergebnis-Reset:** sauber — lokale Variablen je Aufruf, kein Leck (A6). ✓
- **E5-Vollständigkeit:** drei Klassen fehlen (a/g/Fallback), OOM nicht fangbar (A2); `extra`-Kanal unsicher (A3).
- **E6:** Variante A wörtlich umgesetzt, technisch korrekt (A5).
- **W1/W2/W3-Lücken:** B4 (Alignment/ownStart) ist die einzige „beide/keine"-Lücke; W3d-Grenze (60 Codepoints) mehrdeutig (B1); T1 inkonsistent (B2).

---

## Verdikt

**Teil A: Merge mit Auflagen** — 1 P1 (A1 Geisterschreiben) muss vor Release gefixt werden; R-3/E6/A6 sauber, E5 teilweise (P2).
**Teil B (E8-Dokument): sperren bis präzisiert** — als Implementierungsgrundlage ungeeignet, solange B1 (Einheit) und B2 (T1/C1-Widerspruch) offen sind; die Regelhoheit muss sie klären, bevor der Coder E8 umsetzt.

**Gesamt: Merge mit Auflagen.**

## Restrisiken

1. A1: selten (nur bei >2 s blockiertem Main-Thread), aber dann Text in falschen Editor.
2. B2: ein Implementierer, der E8 wörtlich liest, schreibt einen T1-Test, der gegen C1 läuft und den Rollback-Pfad nie trägt.
3. B4: bei vorbestehendem Editor-Text kann ein Alignment-Bug zum Voll-Rollback der eigenen Spanne führen.

## Top-3-Aktionen

1. **A1 (P1)** — `abandoned`-Flag: bei Timeout setzen, der gepostete Block prüft es vor dem Editor-Write (kein Geisterschreiben).
2. **A2 (P2)** — `OutOfMemoryError`/`AudioManager.getDevices`/`_unknown` nachliefern, damit E5 (a)/(g)/Fallback unterscheidbar sind und kein `Error` ungefangen crasht.
3. **B1 + B2 (P2)** — E8 präzisieren: Einheit („60 UTF-16-Codeeinheiten") festlegen und T1/T-Rollback in zwei getrennte, zu C1 widerspruchsfreie Testfälle aufteilen.