# C2-Export-Auswertung — 🐞 aus 0.1.15 (debug), Nutzer-Telefon api 37, Diktat ~20 s, brach ab

**Methode:** nur lesen; jede Behauptung unten mit Datei:Zeile belegt (HEAD-Worktree,
2026-10-30/31). REGEL 9: nur Zahlen, Tasten, Codes, Pfade — keine Inhalte.

## 0. Ist-Zahlen (Export, 30 Zeilen = Kopf + 29 KEYS)

rtf 0.15 · first_hyp_ms 2320 · decode_ms_max/p95 **583** · buffer_s **11.9** = audio_s
**11.9** · ctl_state **IDLE** · ctl_failed **0** · dropped **0** · hyp_count **6** ·
hyp_gap_ms **7323** · decode_pace_ms 2000 · decode_skips 1 · start_ms 55 · starting 0 ·
peak_pct 13 · peak_dbfs −17.6 · permissions record=yes **notif=no**.

## 1) Quadratische C2-Ursache: in DIESEM Export **nicht belegt**

`window()` dekodiert pro Lauf den ganzen Puffer (`dictation/DictationController.kt:416`
`PcmWindow(pcm.copyOf(totalSamples), sampleRateHz)`). Aber die Zahlen widersprechen einem
Daueranstieg bis zum Abbruch:

- Letzter (11,9-s-)Decode dauerte **583 ms** ⇒ effektives Verarbeitungsverhältnis am Ende
  ≈ **49 ms pro Audio-Second** (0.15 kumuliert mit `processingMs/audioMs`,
  `debug/DebugInfoCollector.kt:61-64`).
- Linear fortgesetzt erreicht der Decode die 1-s-Marke erst bei ≈ **20 s Audio** —
  **nach** dem Abbruchzeitpunkt (11,9 s). Ein quadratisches Neudekodieren wäre hier also
  noch unter der sichtbaren Schwelle gewesen.
- Konsistenz: 6 Hypothesen bei 2-s-Takt (chunk 2000, pace 2000, min 2,0 s Partial-Guard
  `DictationController.kt:243ff`) = Hypothesen bei ≈ 2/4/6/8/10/12 s — exakt 6.
  **Das Finale fehlte** (sonst hyp_count 7).

**Fazit 1:** Die 583 ms belegen, dass die C2-Ursache (quadratische Fensterarbeit) auf
diesem Gerät langsam wächst, aber **nicht** die Abbruchursache dieses Laufs ist.
Entweder war der Abbruch zu früh (11,9 s) für die sichtbare Wurzel, oder der Abbruch
liegt woanders — die Exit-Analyse (Punkt 2) zeigt: woanders.

## 2) Alle Exit-Pfade einer laufenden Session, bewertet gegen „11,9 s + IDLE + failed 0 + dropped 0 + hyp 6"

Entscheidende Code-Tatsache: **`pcm`/`totalSamples`/Decode-Stats werden NUR im nächsten
`start()` geleert** (`DictationController.kt:217-237`), nie in `stop()`/`abort()`/`fail()`.
`buffer_s` ist der **letzte gemessene Decode-Stand** (`DictationController.kt:130,161,
183-191`) — nicht „unverarbeiteter Rest". Das ist die Basis für die Unterscheidung:

| # | Exit-Pfad (Datei:Zeile) | Erwartetes Export-Muster | Passt? |
|---|---|---|---|
| E1 | Mic-up (UP) → `stopDictationIfRunning()` → `controller.stop()` mit **Finale** (`ime/VoiceKeyboardService.kt:504,511,566`; `DictationController.kt:316-358`) | IDLE, **hyp_count 7** (Finale zählt via `noteHypothesis`), ggf. ctl_failed | **AUSGESCHLOSSEN** — hyp 6 |
| E2 | `fail()`-Pfade (ASR_ERROR, EDITOR_SYNC_TIMEOUT …) `DictationController.kt:405-410` | **ctl_failed 1** | **AUSGESCHLOSSEN** — failed 0 |
| E3 | **Mic-Halte-CANCEL** (ACTION_CANCEL) → `MicHoldAction.ABORT` → `abortDictation()` (`VoiceKeyboardService.kt:291,467,687-725`; `ime/MicToggle.kt:43-50`) | IDLE, failed 0, dropped 0, Puffer/Stats intakt, **kein `last_error`** | **Kandidat 1 — passt vollständig** |
| E4 | **Editor-/App-Wechsel** → `onStartInput` → `onEditorChanged()` → `abort()` (`VoiceKeyboardService.kt:307-318`; `DictationController.kt:376-384`) | wie E3 | **Kandidat 2 — passt vollständig** |
| E5 | **AudioRecord-Systemstopp** (`audio/AudioRecordSource.kt:129-132` `ERR_SYSTEM_STOP`/`ERR_INVALID_OP`) → `errorListener` → `coordinator.abort()` (`ime/DictationCoordinator.kt:146-149`) | wie E3, **aber `last_error` = „audioSource:systemStop…“** | **Kandidat 3 — nur wenn `last_error` gefüllt** |
| E6 | IME verschwindet (Back/Tab/Window) → `onFinishInputView`/`onWindowHidden` → `stopDictationIfRunning()` (`VoiceKeyboardService.kt:327-340`) | wie E1 (Finale) → hyp 7 | **AUSGESCHLOSSEN** (außer OOM im Finale, s. E9) |
| E7 | `onDestroy`/`shutdown` (Prozess-/IME-Tod) → `coordinator.abort()+shutdown()` (`VoiceKeyboardService.kt:344-353`; `DictationCoordinator.kt:445-477`) | IDLE, Puffer intakt — **aber danach `coordinator = null`**: der Collector würde alle `coordinator?`-Felder leer zeigen (`DebugInfoCollector.kt:83-108`) | **AUSGESCHLOSSEN** — im Export sind alle Felder live gefüllt ⇒ Prozess war beim Export am Leben |
| E8 | maxDuration 300 s (`AudioRecordSource.kt:38,108-110`) | bei 11,9 s unerreichbar; + `last_error` | **AUSGESCHLOSSEN** (bestätigt den „Geklärt"-Eintrag in HANDOFF) |
| E9 | OOM im Decode (feedAudio/Finale) → `abort()` + Rethrow, Worker-Catch → `last_error = „oom:…“`, `NoFinal` (`DictationController.kt:271-276,336-339`; `DictationCoordinator.kt:296-312,384`) | IDLE, failed 0, Puffer intakt, **`last_error` = oom** | **sehr unwahrscheinlich** (decode 583 ms, Modell 670 MB, p95=max=583 → kein Druck) — bleibt nur per `last_error` zu prüfen |
| E10 | Start-Watchdog „abandoned“ (Frist 15 s, `VoiceKeyboardService.kt:455-464`) | würde aborten, aber `starting`/`start_ms` (55) zeigen sauberen Start | **AUSGESCHLOSSEN** |
| E11 | FGS ohne POST_NOTIFICATIONS (`notif=no`) | s. Punkt 4 | **kein Abbruchkandidat** |
| E12 | Sprachwechsel während Diktat (F25) | nur Vormerkung, kein Abort (`VoiceKeyboardService.kt:1022ff`) | **AUSGESCHLOSSEN** |
| E13 | C-2b „Controller IDLE + aktiv → abort“ (`DictationCoordinator.kt:352-357`) | setzt voraus, dass vorher `fail()` lief → failed 1 | **AUSGESCHLOSSEN** (folgekonsequent zu E2) |

**Ergebnis:** Der Export signiert einen **Abort ohne Finale und ohne Fehlerflagge**.
Übrig bleiben genau drei nicht unterscheidbare Pfade: **E3 (Mic-CANCEL)**, **E4 (Editor-
Wechsel)**, **E5 (Audio-Systemstopp)** — und E5 lässt sich mit dem **bereits existierenden**
Feld `last_error` (`DebugInfoFormatter.kt:111`) sofort trennen: war im Export `last_error`
`-` (der Auszug zitiert das Feld nicht), sind E3/E4 übrig. **Hinweis für den Nutzer:** die
„gut gelaufenen mittleren Diktate" waren normale Mic-up-Läufe (E1); der Bruch lief über
einen CANCEL-/Wechsel-Ereignis-Typ, nicht über einen ASR-Fehler.

## 3) `hyp_gap_ms 7323`

`hyp_gap_ms` = Zeit seit der letzten **erzeugten** Hypothese (`DictationController.kt:
117,193-195,412-414`). Mit 2-s-Takt lag die 6. Hypothese bei ≈ 11,9 s; 7,3 s Vorlauf
vor dem Export ⇒ **Export ≈ 0,5–1 s nach Abbruch**, letzter Text ≈ bei **4,6–5 s**
(± Export-Verzögerung, Formel: `t_last = 11,9 + 7,3 − t_export_vor_abbruch`). Das passt
zur Nutzerwahrnehmung „Text kam ab ~12 s nicht mehr" (Session endete ~12 s; die letzten
Partial-Änderungen waren aber schon ~5 s alt — ab ~5 s kamen zwar Decode-Ergebnisse,
vermutlich aber ohne Editor-Änderung durch Commit-Policy/Delta-Typer: `hyp_count` zählt
erzeugte Hypothesen, keine garantiert geändeten Editor-Inhalte).

## 4) `permissions notif=no` (Android 13+)

Projektabwicklung ist **bewusst** so: `POST_NOTIFICATIONS` ist nur **optional** —
`ime/DictationPermissions.kt:16-27` (REQUIRED = nur `RECORD_AUDIO`; Optionale berechtigen
nicht) mit Live-Fehler-Notiz „FGS läuft laut CaptureForegroundService auch ohne sie".
`capture/CaptureForegroundService.kt:24` bestätigt: „Ohne `POST_NOTIFICATIONS` läuft der
Service trotzdem (Notification unsichtbar)". Manifest deklariert die Berechtigung
(`AndroidManifest.xml:19`). Folge: **kosmetisch** (keine sichtbare Aufnahme-Indikation;
die System-Mic-Indikation des OS bleibt). **Kein Abbruchkandidat** — und E7 zeigt
zusätzlich, dass kein FGS-/Prozess-Tod stattgefunden hat.

## 5) Vorschlag: 2 neue 🐞-Felder (schnellste Eingrenzung)

1. **`abort_reason`** (String, default `-`) — **wo:** `ime/DictationCoordinator.kt`,
   `abort()` (Z. 445) bekommt ein `reason`-Parameter, gesetzt an den 4 Aufrufern:
   `abortDictation()` (`VoiceKeyboardService.kt:691`, Ursprung: Mic-CANCEL →
   `reason = "michold_cancel"`) / `onEditorChanged()` (`DictationController.kt:376`,
   → `editor_change`) / `errorListener` (`DictationCoordinator.kt:146`, →
   `audio_error:<code>`) / `shutdown()` (`DictationCoordinator.kt:470`, → `ime_destroy`).
   Zusätzlich in `stop()` (`DictationCoordinator.kt:362-390`) den `StopOutcome`-Zweig
   mitschreiben (`final` / `no_final:<code>` / `idle_noop`). Durchreiche
   `debug/DebugInfoCollector.kt` (neben Z. 94) + 1 Zeile in `DebugInfoFormatter.KEYS`
   (30. Feld, Katalog `docs/DEBUG_INFO_FIELDS.md`). **Mit diesem einen Feld ist der
   nächste Bruch in einem Export eindeutig.**
2. **`hyp_last_buffer_s`** (Double) — **wo:** `DictationController.applyPartial`/
   `noteHypothesis` (Z. 300-315, 412-414) speichert den `lastDecodeBufferSeconds`-Wert
   der **letzten akzeptierten** Hypothese; Collector wie `buffer_s` durchreichen.
   Trennt „Text stoppte bei 4,6 s" von „Session starb bei 4,6 s" und zeigt, ob die
   späten Hypothesen noch Editor-Änderungen ausgelöst haben (Commit-Policy-Verhalten).

(`last_error` braucht **kein** neues Feld — existiert bereits, `DebugInfoFormatter.kt:111`;
es genügt, es in der nächsten 🐞-Auswertung auszulesen.)

## 6) Empfehlung Gegenmassnahme-Stufe

**(a) nur Nachinstrumentierung** — 1–2 🐞-Läufe mit `abort_reason` +
`hyp_last_buffer_s` (je ≈ 1 Coder-Auftrag ≤ 4 Dateien: Coordinator, Controller,
Collector, Formatter/Katalog). **Begründung:** Die drei verbliebenen Kandidaten (E3/E4/E5)
sind UI-/Audio-Lebenszyklus-Ereignisse mit **bereits kontrollierten** Abort-Pfaden (kein
stilles Sterben, kein wachsender Kanal) — der eigentliche „Bug" ist, dass der Abbruch
**unsichtbar** war (Statuszeile hat ihn nur als stillen IDLE-Wechsel gezeigt). Erst wenn
`abort_reason` in 2 Läufen denselben Kandidaten benennt, ist ein Fix belegbar.

**(b) konkreter Fix-Kandidat: jetzt noch nicht** — eine Datei:Zeile wäre reine Spekulation
(E3 → CANCEL-Toleranz bei Haltegeste? E4 → Editorwechsel-Verhalten? E5 →
Audio-Source-Hardening?) und würde auf das falsche Ziel hauen.

**(c) windowed-Streaming bleibt nicht nötig** — Begründung mit den Zahlen dieses Exports:
Decode 583 ms bei 11,9 s Puffer (≈ 49 ms pro Audio-Second); 1-s-Schwelle bei ≈ 20 s Audio;
`rtf 0.15`, p95 = max (keine Ausreißer); F21-Takt hielt (1 Skip). Die quadratische
Ursache ist real, aber auf api-37-Hardware erst bei deutlich längeren Sessions relevant —
solange der Abbruch aus Punkt 2 stammt, wäre `KONZEPT` §5.1 ein Umbau gegen ein
unerreichtes Problem (und bleibt gesperrt, bis (a) den C2-Anteil in längeren Läufen
belegt: dann wäre der Auslöser `decode_ms_max > 1000` + steigendes `buffer_s`).

---
**Quellen:** `dictation/DictationController.kt` (start 217, feedAudio 243, stop 316,
abort 367, onEditorChanged 376, fail 405, window 416), `ime/DictationCoordinator.kt`
(errorListener 146, start 152, Consumer-Catch 296-357, stop 362, lastStopOutcome 404,
abort 445, shutdown 470), `ime/VoiceKeyboardService.kt` (onStartInput 307,
onFinishInputView 327, onDestroy 344, Watchdog 455, onMicUp 504,
stopDictationIfRunning 566, abortDictation 687, onMicHoldEnd 708, LANGUAGE 979),
`ime/MicToggle.kt` (29-50), `audio/AudioRecordSource.kt` (38, 108, 129),
`ime/DictationPermissions.kt` (16-27), `capture/CaptureForegroundService.kt` (24, 67),
`debug/DebugInfoCollector.kt` (61, 83-108), `debug/DebugInfoFormatter.kt` (108-117).
