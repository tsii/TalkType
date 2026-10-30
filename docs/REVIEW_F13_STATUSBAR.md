# Review F13a — Statusleisten-Enteisung (StatusBarText, Watchdog, R-2/R-2b)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `ime/StatusBarText.kt` (neu), `ime/StatusBarTextTest.kt` (neu), `ime/VoiceKeyboardService.kt` (geändert), `res/values/strings.xml` (angehängt). Kein Build/Emulator.

---

## Befunde

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| **P2** | `VoiceKeyboardService.kt:250, 553` + `StatusBarText.kt:56-59` (`extra != null && !extraTransient → sticky`) | **Überbreites „extra⇒sticky" friert Nicht-Fehler ein.** Die reine Regel macht **jedes** `extra`, das nicht der Starttext ist, zu einem sticky-Fehler. Zwei Aufrufer übergeben aber Hinweise/Bestätigungen: Z.250 `emoji_blocked_recording` und Z.553 `"Diagnose kopiert — Teilen-Dialog geöffnet"` — beides keine Fehler, bleiben aber jetzt als R-2-Sticky stehen. B1-Kern (Starttext) ist gefixt, aber die Regel verfehlt „Hint/Bestätigung vs. Fehler". | Ein eigenes Transient-/Hint-Signal statt generischem `extra`→sticky: nur Fehler-Keys (`dictation_error_*`, `setup_error_*`) als sticky führen; `emoji_blocked`/`Diagnose kopiert` über den transienten Kanal (oder `extraIsSticky=false`). |
| **P2** | `VoiceKeyboardService.kt:396, 838` | **`SystemClock.uptimeMillis()` statt `elapsedRealtime()`.** `uptimeMillis` **pausiert im Deep-Sleep** — der 15-s-Watchdog (`START_DEADLINE_MS`) und der Klärheitsvergleich `lastErrorSeenAtMs >= transientSetAtMs` wären über einen Screen-Off/Bildschirm-Aus dehnen. Beim Diktat praktisch selten, aber die „Frist"-Semantik (15 s) ist dann nicht hart. `currentTimeMillis` wird nirgends verwendet (gut, kein Wall-Clock-Sprung). | `SystemClock.elapsedRealtime()` für Deadline und beide Zeitstempel (monoton + schlafübergreifend). |
| **P2** | `StatusBarTextTest.kt` (7 Tests) vs. `VoiceKeyboardService.kt`-Verdrahtung | **Tests beweisen die reine Funktion, nicht die Verdrahtung.** B1 war ein Ver-drahtungsfehler (alt: `if (extra != null) stickyError = extra` + Starttext als `extra`). Die 7 Tests prüfen nur `StatusBarText.select`; ein Rückschritt in der **Service**-Verdrahtung (Starttext wieder als `extra` statt als `transientStatus`, Z.395) würde **keinen** Test rot machen. Konkret: Test „extra ist nur sticky wenn es kein transienter Text ist" finge `select(extra=start, transientText=start)→extraIsSticky=true`, aber nicht `updateStatus(start)` im Service. | Ein schmaler Verdrahtungstest (oder ein Assert, dass `updateStatus` den Starttext nie als `extra` erhält): z. B. „onMicDown setzt transientStatus, ruft updateStatus() ohne extra". |

---

## Antworten auf die sechs Fragen

1. **Starttext sticky/später verdeckend?** **Nein.** Der Starttext wird seit F13a ausschließlich über `transientStatus = getString(R.string.dictation_starting)` (Z.395) gesetzt und `updateStatus()` **ohne** `extra` aufgerufen (Z.397). `StatusBarText.select` behandelt `transientText` nie als sticky (`extraIsSticky=false`), und `stickyError = extra` (Z.877) läuft nur, wenn die reine Funktion `extraIsSticky` meldet. B1 ist damit zu; einzig die o. g. Nebenwirkung (Hinweise/Bestätigungen werden ungewollt sticky) bleibt.

2. **Watchdog abgebrochen/Zombie.** `startDeadlineJob` wird gekündigt: im `finally` des Async-Starts (Z.482), vor Wieder-Start (Z.403) und implizit durch `serviceScope.cancel()` in `onDestroy`. **Kein Zombie:** läuft die Frist ab, setzt der Watchdog `startAbandoned=true`; der nachfolgend fertige Start prüft `abandoned` → `coordinator?.abort(); micRecording=false; stopFgs()` (Z.443-454) — ein verspäteter Erfolg wird abgebaut, nicht stumm weitergeführt. `abortDictation()`/`stopDictationIfRunning()` brauchen kein eigenes `cancel()`, weil sie nur bei bereits `startingUp=false` laufen (der `finally` hat den Watchdog dann schon gekündigt).

3. **Stale-Werte für den nächsten Start?** Nein: `transientStatus/transientSetAtMs/lastErrorSeenAtMs/startAbandoned/successJustFinished` werden in `onMicDown` (Z.371-375, 399) bzw. `finally` (Z.485) zurückgesetzt; `successJustFinished` ist Einmal-Flag (Z.875) und wird direkt nach `select` genullt. Uhr = `SystemClock.uptimeMillis` (P2 s. o.), **nicht** `currentTimeMillis` — kein Wall-Clock-Sprung. `lastErrorSeenAtMs` ist „Zeit des ersten Fehlers"; für den `>=`-Vergleich zum transienten Starttext reicht das (erster Fehler ist stets ≥ Starttext).

4. **Reihenfolgen `startingUp=false` vs. laufender Coordinator.** Beide (Watchdog-Körper und Async-Start-`finally`) hüpfen per `withContext(Main)` und sind auf Main **atomar** (keine Suspension dazwischen) → kein Partial-Interleaving: (a) Watchdog zuerst ⇒ `startingUp=false, abandoned=true`, der spätere Start wird im `abandoned`-Zweig abgebrochen; (b) Start zuerst ⇒ `startingUp=false, started=true, micRecording=true, updateStatus()`, der `finally` kündigt den Watchdog, bevor er feuert. **Kein „Mikro ohne Rückmeldung"-Zustand gefunden.** (Nicht als Concurrency-Test fixiert — offen, s. unten.)

5. **Hätten die 7 Tests B1 gefangen?** **Teilweise — nur die reine Funktion, nicht die Verdrahtung.** B1 war ein Verdrahtungsfehler im Service; der neue Test „extra ist nur sticky wenn es kein transienter Text ist" finge eine Regression **innerhalb** von `StatusBarText.select`, aber keinen Rückschritt der Service-Wiring (Starttext wieder als `extra` in `updateStatus`). Siehe P2 oben.

6. **Regel 9/10.** Alle neuen/geänderten Status-Strings (`dictation_starting`, `dictation_start_too_long`, `dictation_no_text`) enthalten keinerlei Audio-/Textinhalt, keinen Pfad, keine URL, keine Autokorrektur; `strings.xml` ist nur angehängt (`dictation_start_too_long` neu, Menge 12→13). Die Diagnose-Kurzform in `updateStatus` bleibt `FLAG_DEBUGGABLE`-geschützt und zeigt nur Zahlen (Z.856-868). Kein Verstoß.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 3** (Hinweise/Bestätigungen werden ungewollt sticky; `uptimeMillis` statt `elapsedRealtime`; reine-Funktion-Tests ohne Verdrahtungsabdeckung).

**Nicht belegbar (offen):**
- Watchdog/`finally`-Wettlauf ist nur per Codeprüfung belegt, **nicht** als Concurrency-Test fixiert.
- Die 7 `StatusBarTextTest`s habe ich gelesen, nicht ausgeführt (kein Build).
- Verhalten bei echtem Deep-Sleep (Screen-Off) der `uptimeMillis`-Deadline ist eine Plausibilitätsaussage, kein Gerätebeweis.
- Der Gerätetest der ursprünglichen B1-Ursache (Streaming-Freeze) wurde hier nicht reproduziert (nur Analyse).