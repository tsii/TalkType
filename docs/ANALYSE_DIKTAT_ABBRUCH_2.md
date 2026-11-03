# Analyse Diktat-Abbruch #2 (0.1.17/18, api 37) + Layout-Korrektur F27b

**Methode:** nur lesen; jeder Befund mit Datei:Zeile. Nicht Bewiesenes ist als
**Hypothese** gekennzeichnet. REGEL 9: nur Zahlen, Tasten, Codes, Pfade.

## 0. Aus dem Export rechnerisch zwingend

`first_hyp_ms` wird im **ersten flush()** gesetzt — unabhängig davon, ob eine Hypothese
entsteht (`DictationCoordinator.kt:296-297`). flush() läuft nur bei (a) tick-Vollstand
(`consume()`, `DictationCoordinator.kt:263-269`; tick = `chunkMs` 2000 → 32 000 Samples,
`DictationCoordinator.kt:47`) oder (b) **Schließen des Kanals** (Schluss-flush
`DictationCoordinator.kt:276`). Der Takt-flush braucht 2,0 s Audio — bei `audio_s 1,0`
**unmöglich**. Also: erster flush = Schluss-flush ⇒ **Kanal wurde bei t ≈ 1,201 s
geschlossen ⇒ `stop()` lief bei t ≈ 1,2 s** (`ch.close()`, `DictationCoordinator.kt:375`).
Passiert ist dann: `stop()` → `controller.stop()` → Finale über 1,0 s (121 ms) →
`hyp_count 1`, `abort_reason "final"` (`DictationCoordinator.kt:388-393`); alle Partials
vom 2,0-s-Guard blockiert (`DictationController.kt:257,470-471`). `hyp_gap_ms 2692` ⇒
Export ≈ 3,9 s nach Start — **die ganze Episode ist ~4 s, nicht 20 s**.
Zusätzlich: `abort(reason)` **überschreibt** den Grund (`DictationCoordinator.kt:477`) —
hätte nach dem Finale ein weiteres Release-Event (Abort `michold_cancel`,
`VoiceKeyboardService.kt:581/583`) oder ein zweiter Stop stattgefunden, stünde dort nicht
`final`. **Kein Mic-Event hat die App nach dem 1,2-s-Stop mehr erreicht.**

## 1) Pfade, die die Aufnahme nach ~1 s beenden können

- **Kein 1-s-Timer existiert im Code.** maxDuration = 300 s (`AudioRecordSource.kt:38,
  108-110`); Start-Watchdog = 15 s (`VoiceKeyboardService.kt:455-464`); FGS hat kein
  Zeitlimit im Projektcode.
- Einziger Weg zu `abort_reason "final"` = **`stopDictationIfRunning()` →
  `coordinator.stop()`** (`VoiceKeyboardService.kt:566-585` → `DictationCoordinator.kt:
  366-393`). Aufrufer:
  1. **Mic-UP**: `KeyboardView.kt:820` `onMicHoldEnd(cancelled=false)` →
     `VoiceKeyboardService.kt:708-718` (STOP) → `stopDictationIfRunning()`; alternativ
     Tap-UP: `VoiceKeyboardService.kt:504-512` `onMicUp()`.
  2. **Nachgereichtes Loslassen** während Start: `VoiceKeyboardService.kt:506-509` →
     450-469 (`wantedStop` → `stopDictationIfRunning()`).
  3. **IME verschwindet**: `onFinishInputView`/`onWindowHidden`
     (`VoiceKeyboardService.kt:327-338`) → `stopDictationIfRunning()`.
- **Ausgeschlossen durch den Export:** `ACTION_CANCEL` (`KeyboardView.kt:820` →
  `MicToggle.kt:47-50` → ABORT) würde `michold_cancel` schreiben
  (`VoiceKeyboardService.kt:720-725`); Audio-Systemstopp/InvalidOp
  (`AudioRecordSource.kt:129-132`) → `audio_error:…` (`DictationCoordinator.kt:355`);
  Editor-Wechsel → `editor_change` (`DictationCoordinator.kt:516`); OOM/Consumer-Tod →
  `no_final:…` bzw. `lastError` (`DictationCoordinator.kt:311-341`).
- **Fazit:** Der Stop kam über einen **echten UP-Wege** (1./2./3.). **Hypothese H-A
  (führend):** der Finger war bei 1,2 s tatsächlich losgelassen (Nutzer-Wahrnehmung
  „die ganze Zeit gehalten" unzuverlässig — die Zahlen zulassen nichts anderes).
  **Hypothese H-B:** F27-Pointer-Umbau/View-Ereignis lieferte bei 1,2 s ein **spurloses
  ACTION_UP** (Pointer-Capture-Verlust); das späte echte Loslassen kam dann nicht mehr
  als Gesture-Event an. **Hypothese H-C:** kurzer IME-Wechsel (System) bei 1,2 s.
  Alle drei sind mit dem Export kompatibel; nur H-A erklärt zusätzlich die
  Status-Reset-Folge (s. 4).

## 2) Warum 0 Pegel / −56,2 dBFS

- **Wo gemessen:** pro rohem Capture-Block, **vor Queue und vor jeder Verarbeitung**
  (kein Resampling — Aufnahme läuft direkt 16 kHz): `AudioRecordSource.kt:121`
  (`levelListener?.onLevel(rms(copy), block.seq)`); `LevelPeak.note(rms)` = Max über
  Block-RMS (`debug/LevelPeak.kt:20-24`), `percent()` `LevelPeak.kt:26`, `dbfs()`
  `LevelPeak.kt:33-48`. ⇒ **kein Messfehler in der Pipeline**: −56,2 dBFS ist der
  Digitizer-Grundrauschen-Boden — die Sprach-Signale sind nie in der Aufnahme
  angekommen.
- **Kandidaten (Hypothesen, gerätebezogen):**
  h1) **Systemseitiges Mikro-Entzug/Drossel nach ~1 s** (Android 13+/api 37,
  FGS-microphone-Berechtigung ohne `POST_NOTIFICATIONS` — die Projektabwicklung „läuft
  auch ohne sie" ist für api 37 **unverifiziert**: `DictationPermissions.kt:16-27`,
  `capture/CaptureForegroundService.kt:24`). Passt sowohl zum 1-s-Ende als zum
  Stillepegel.
  h2) Hardware-/Routing-Zustand des Geräts in der Session (Mic-Mute, anderes Mikrofon,
  do-not-disturb) — Route intern / src VOICE_RECOGNITION sind **identisch** zur guten
  F13-Messung (−22,0 dBFS / 7 %), also Konfiguration nicht der Unterschied.
- **Code-Lücke (bewiesen):** liefert `AudioRecord.read()` dauerhaft 0/keine Blöcke,
  dreht der Loop endlos **ohne Fehler, ohne Timeout, ohne Statuswechsel**
  (`AudioRecordSource.kt:126-128` „kein Daten — loop"; `DictationCoordinator.kt:241-251`
  `n==0` → implizit weiter). Stille/Mikro-Tod ist so **ununterscheidbar** von
  „nutzt gerade das Mikro nicht" → s. 4 und 6.

## 3) rtf 0.00 bei decode_ms_max 121

**Plausibel, kein Zählerfehler, aber Mess-Blindspot.** `rtf = processingMs/audioMs`
(`DictationCoordinator.kt:104`, Guard `audioMs > 0` sonst „-"); `processingMs` wird
**nur in flush()** akkumuliert (`DictationCoordinator.kt:294`) — d. h. nur für
Coordinator-`feedAudio`-Aufrufe. Der 1,2-s-flush lief **ohne Decode** (2,0-s-Guard) ⇒
`processingMs ≈ 0`, `audioMs = 1000` ⇒ 0,00x → `%.2f` → `0.00`. Die 121 ms Finale-Decode
laufen in `controller.stop()` **außerhalb** von flush() und zählen also **nicht** in rtf
(`DictationCoordinator.kt:388`; Decode-Messung `DictationController.kt:183-191`).
Zusammen mit 0: `first_hyp_ms` misst **ersten flush, nicht erste Hypothese**
(`DictationCoordinator.kt:296-297`) — F28 soll beide Semantiken sauber trennen.

## 4) Warum Status „Diktat" bleibt (trotz audio_s 1.0)

Anzeige: `updateStatus()` (`VoiceKeyboardService.kt:885-915`) →
`StatusBarText.select(recording = state==RECORDING, finalizing = …, recordingText
„Diktat… (…)")`. Solange der Controller `RECORDING` ist, steht „Diktat" — **korrekt**,
solange eine Session läuft. Der **fehlende Übergang** ist ein **Stall-Erkennungs-Event**:
kein Code-Pfad macht „keine Audio-Blöcke / Dauerstille" zu Fehler oder Stop (Beweis 2).
Zwei Fälle: (a) Stop lief bei 1,2 s (Beweis 0) → `stopDictationIfRunning` setzt
`micRecording=false` + `updateStatus()` synchron (`VoiceKeyboardService.kt:584-585`) und
`onStopped` ruft `updateStatus` erneut (`DictationCoordinator.kt:406-411`) → „Diktat"
dürfte nur **kurz** (Finale 121 ms) sichtbar sein; das von Nutzer erlebte „bleibt bis
Loslassen" ist dann **Hypothese H-B/D** (Main-Thread-Update verspätet/stuck — F9-P0-Klasse,
`VoiceKeyboardService.kt:887-890` postet auf Main) oder Wahrnehmung. (b) H-B: Session
lief formal weiter ohne Daten → „Diktat" **sollte** stehen — exakt der beobachtete
Zustand. → F28-Feld `status_shown` (s. 5) korreliert Anzeige mit Controller-Zustand.

## 5) Minimale Instrumentierung F28 (4 neue 🐞-Felder, nur Zahlen/Codes)

| Feld (Name) | Typ | Wo gemessen | Formatter-Position |
|---|---|---|---|
| `cap_stall_ms` | Long | `DictationCoordinator.captureLoop` (Zeile 238-259): Zeitstempel bei jedem `n>0`; Getter `now − lastBlockAtMs` | nach `decode_skips` (30.→31. Schlüssel) |
| `cap_blocks` | Int | ebenda: Zähler je `n>0`-Block (erwartet ≈ 100 für 1,0 s @ 10-ms-Blöcke; gefrorenes Zähler = Capture-Ende) | 32. |
| `mic_rel` | String (kodiert) | `VoiceKeyboardService.kt`: `onMicUp` (504) = `up:<ms>`, `onMicHoldEnd` (708) = `holdup/holdcancel:<ms>`, `onFinishInputView` (327) = `hidden:<ms>`, `onStartInput` (307) = `editor:<ms>` — Zeitstempel relativ Session-Start; via Coordinator-Durchreiche (`DebugInfoCollector.kt` neben Zeile 94) | 33. |
| `status_shown` | String (kodiert: `ready/dictat/finalizing/error`) | `VoiceKeyboardService.updateStatus` (885): letzter an `statusText` gegebener Zustand als Enum-Code | 34. |

Keine Audio-/Textinhalte (Regel 9). Damit ist im **nächsten** Export: Stop-Auslöser
(`mic_rel`), Capture-Stall (`cap_stall_ms`/`cap_blocks`), Anzeige-Wahrheit
(`status_shown`) und H-A/H-B-Scheidung in einem Lauf ablesbar.
Nebenfix (kostenlos, Semantik): `first_hyp_ms` bleibt, aber Doku-Korrigendum in
`docs/DEBUG_INFO_FIELDS.md` („= erster feedAudio-flush, nicht erste Hypothese") oder
F28-Feld `first_real_hyp_ms` (in `noteHypothesis`, `DictationController.kt:412-414`).

## 6) Ursachen-Rangfolge + kleinster wirksamer Fix

| # | Ursache (Wahrscheinlichkeit) | Begründung | Kleinster Fix |
|---|---|---|---|
| 1 | **Mikro wurde systemseitig nach ~1 s stummgelegt/gestoppt** (api 37 FGS-mic/`POST_NOTIFICATIONS`, h1) | erklärt **beide** Anomalien (1-s-Ende + Stille) in einem Mechanismus; Projektannahme dafür unverifiziert (`DictationPermissions.kt:16-27`) | zuerst **F28** messen; dann FGS-Start-Ordnung/Typ prüfen (`VoiceKeyboardService.kt:401,793-800`, `CaptureForegroundService.kt:67`) — falls h1 bestätigt: FGS + Notification **vor** `AudioRecord`-Start garantieren und `notif=yes` als A/B-Vergleich |
| 2 | **Kein Stall-/Stille-Watchdog** (Code-Lücke, sicher vorhanden) | Beweis 2: `read()=0`-Ewigkeitsloop ohne Ausweg (`AudioRecordSource.kt:126-128`) | 10-Zeilen-Fix: in `DictationCoordinator.captureLoop` (238-259) `if (now − lastBlockAtMs > 3000)` → `errorListener.onError("stall", …)` → existierender abort-/Statuspfad (sichtbarer Fehler statt „Diktat-Ewigkeit") |
| 3 | **Spurloses ACTION_UP nach F27-Pointer-Umbau** (H-B) | nur dann, wenn H-A ausgeschlossen ist (F28 `mic_rel`) | Messung vor Fix: Debug-Pointer-Log an der Mic-Taste (VK-HIT-Stil, `KeyboardView.kt:820/837`); Fix erst nach Beleg |
| 4 | Main-Thread-Update-Verzögerung (H-B/D) | F9-P0-Klasse (`VoiceKeyboardService.kt:887-890`) | nur falls `status_shown` ≠ Controller-Zustand zeigt: onStopped-Update mit Timeout-Resync — andernfalls nichts |
| — | windowed-Streaming (`KONZEPT` §5.1) | **bleibt gesperrt** — dieser Lauf liefert keinen C2-Anteil (decode 121 ms, kein Buffer-Wachstum) | — |

---

## 7) Layout-Korrektur F27b — Pop-up-Top je Reihe (Spezifikation, keine Implementierung)

Basis (Dichte 2,625): Statusleiste = **48 dp = 126 px**, oben im Root
(`ImeRootView.kt:98-99`); KeyboardView darunter (Root-y 126..702, 576 px); Gap
`POPUP_GAP_DP` 8 dp = **21 px** (`KeyboardTheme.kt:90`, `KeyboardView.kt:195`); Menühöhe
`POPUP_HEIGHT_DP` 56 dp = **147 px** (gemessen 146, `KeyboardTheme.kt:87`); Pop-up heute
nur im KeyboardView-Canvas gezeichnet (`KeyboardView.kt:423`) → **nach oben aus
KeyboardView heraus clippt**.

| Taste/Reihe | Ist (F27b, `LongPressMenu.kt:79-83`) | Gewünscht | Neue top-Formel | Zahlenbeispiel |
|---|---|---|---|---|
| r3 (Fuß, keyTop 432, Höhe 144) | `keyTop − rowHeight − gap − menuH` = **132** (2 Reihen über) | **1 Reihe darüber** (0.1.16-Position) | **`keyTop − gap − menuH`** (kein rowHeight-Abzug) | 432 − 21 − 147 = **264 px** (0.1.16: 276 mit altem Gap 10 px; Differenz 12 px = neuer 8-dp-Gap) |
| r1 / r2 | `keyTop − rowHeight − gap − menuH` (1 Reihe höher) | unverändert (Nutzer bestätigt „eine Zeile höher") | **bleibt** | — |
| r0 (Zahlen, keyTop 0) | Flip: `keyTop + rowHeight + gap` = **154** (UNTERHALB) | **DARÜBER**, sichtbar über dem Finger | **Root-Overlay:** `top_root = max(0, statusLine(126) + keyTop_view − gap − menuH)` | 126 + 0 − 21 − 147 = **−42 → geclampt 0**; Menü belegt Root **0..147** → überdeckt 21 px der r0-Reihe (126..147), liegt aber **über dem Finger** (Tastenmitte ≈ Root 198); **kein Window-Clip** (top 0). In KeyboardView-Koordinaten wäre top = −126 → unmöglich im Canvas |

**Implementierungs-Hinweis:** das r0-Overlay muss von **`ImeRootView`** gehostet werden —
die Statusleiste (48 dp, `ImeRootView.kt:63-115`) und das `KeyboardView` sind Kinder
eines Stack-Containers (`ImeRootView.kt:168`); dort einen Pop-up-Host (eigenes Child-View
oder eigener Draw-Pass in Root-Koordinaten, `left` unverändert via
`LongPressMenu.menuLeftPx`, `KeyboardView.kt:504`) vorsehen. r1/r2/r3 bleiben im
KeyboardView-Canvas. `menuTopPx` (LongPressMenu) bekommt dann nur noch die r3-Formel
ohne rowHeight; der r0-Fall zieht aus dem Canvas heraus.

---

## 8. Nachtrag F30/F31 (v0.1.23, versionCode 24, 01:38)

- **F30 — Fake-Audio-Quelle (Debug-only):** `app/src/debug/java/de/voicekeyboard/audio/
  FakeWavAudioSource.kt` — zeitgetreue **10-ms-Blöcke** aus WAV, konfigurierbare
  Szenarien (durchgehend / Stille-Abschnitte / Aussetzer-`inGap` / `stopAfterMs`,
  `FakeWavAudioSource.kt:29/40/144`). Dazu `app/src/testDebug/java/de/voicekeyboard/ime/
  FakeAudioScenarioTest.kt` mit 8 Fällen (a–h, VirtualClock-getaktet). **Der
  Nutzerfall ist damit deterministisch nachstellbar:** Fall d „Ende nach 1,2 s“
  (`stopAfterMs = 1_200` → `abort_reason final`, `audio_s ≈ 1,2`) reproduziert die
  Zahlen von Export #2 im Labor. **Nicht gedeckt:** Engine-Decode auf dem Emulator —
  weiterhin blockiert durch den x86_64-`libonnxruntime`-SIGSEGV (s. §1/
  `docs/EMULATOR_DIKTAT_HARNESS.md` §1).
- **F31 — Stille-Erkennung:** `lastLoudAtMs` (`ime/DictationCoordinator.kt:282/206/313`);
  neue Regel im Watchdog: **Blöcke laufen** (`capBlocks > 0`) **aber seit > 3000 ms kein
  lautes Signal** → `ERR_SILENT` `"silence"` mit `no_signal` (`DictationCoordinator.kt:
  337-338`, neue Konstante `ERR_SILENT` `:78`); Nutzer-Meldung
  `dictation_error_no_signal` (`res/values/strings.xml:57`); neues 🐞-Messfeld
  **`loud_s`** (Sektunden mit lautem Signal, `DictationCoordinator.kt:284-287`,
  `debug/DebugInfoFormatter.kt:134/194`). **3 F31-Tests** (FakeAudioScenarioTest
  Fälle e/f/g): durchgehend still → genau ein `ERR_SILENT`, Session beendet; still→laut
  → **kein** `ERR_SILENT`, `loud_s` wächst; keine Blöcke → weiterhin `ERR_STALL`
  (Vorrang, `DictationCoordinator.kt:327-329`) — die zwei Fälle sauber getrennt.
  **Bedeutung für Export #2:** der Zustand „Blöcke laufen, aber nur Rauschen (−56,2 dBFS)"
  würde heute (statt „Diktat-Ewigkeit") nach 3 s in einen sichtbaren `audio_error:silence`
  münden.

## 9. Entscheidungsbaum Export #3 (Nutzer-Telefon, 🐞-Export mit F26/F28/F31-Feldern)

Leseregel: die Felder `cap_blocks`/`cap_stall_ms`/`loud_s`/`mic_rel`/`status_shown`/
`abort_reason`/`hyp_count` trennen die Schichten (Quelle → Pegel → Engine → Commit → UI)
**von unten nach oben**. Erste nicht passende Zeile = die Schicht mit dem Fehler.

| # | Messlage im Export #3 | Wahrscheinliche Ursache | Zuständiger Code-Ort (Datei:Zeile) | Nächster Schritt / Fix-Typ |
|---|---|---|---|---|
| a | `cap_blocks = 0` **und** `cap_stall_ms` wächst | **AudioRecord liefert nichts** (FGS-Mikro/Permission/Routing) | Capture-Loop `audio/AudioRecordSource.kt:113-141` (n==0-Loop `:127`); Watchdog `ime/DictationCoordinator.kt:327-329` (`ERR_STALL no_audio`); FGS-Start `ime/VoiceKeyboardService.kt:401/793` + F28c-Handshake `:183-185` | FGS-/Permission-Pfad prüfen (api 37, `POST_NOTIFICATIONS`), ggf. AudioSource-Wechsel an der Injektionsnaht `VoiceKeyboardService.kt:651` |
| b | `cap_blocks > 0`, **`loud_s = 0`** | **Mikro liefert nur Rauschen** → Hardware-Mute, Mic-Besetzung durch andere App, oder die **VOICE_RECOGNITION-Quelle** ist tot | F31-Check `ime/DictationCoordinator.kt:337-338`; Pegel vom rohen Capture `audio/AudioRecordSource.kt:121` (`LevelPeak`) | **Nutzerfrage zuerst** (andere Apps/Mikro-Mute — s. §10); dann AudioSource-Experiment (SOURCE-Mic vs. VOICE_RECOGNITION, Param an `AudioRecordSource`/`VoiceKeyboardService.kt:651`) |
| c | `cap_blocks > 0`, `loud_s > 0`, **`hyp_count = 0`** | Signal da, **Engine/VAD liefert nichts** (Decode-Fehler, Modell, 2,0-s-Guard + kein Final) | `dictation/DictationController.kt:250ff` (`feedAudio`), Guard `:470-471`, `lastError`-Felder; Watchdog-Fehler in `ime/DictationCoordinator.kt:311-341` | Decode-Pfad-Debug: `last_error` im Export lesen; OOM/ISE-Logs; ggf. Mini-WAV aus `loud_s`-Zeitfenster als Pipeline-Testfixture (Regel 9: nur wenn Nutzer einwilligt) |
| d | **`hyp_count` wächst**, aber **Text fehlt** | **Commit-/Editor-Pfad** (CommitPolicy verweigert, Ownership verloren, Editor-InputConnection tot) | `commit/CommitPolicy.kt`/`LocalAgreement.kt`, `editor/InputConnectionSink.kt`/`DeltaTyper.kt`/`OwnershipTracker.kt`; Sink-Verschaltung `ime/VoiceKeyboardService.kt:585ff` (buildCoordinator) | Commit-/Editor-Pfad-Debug: Hypothesen-Zähler vs. Sink-Operationen (Diktat-Ausgabe-Zähler zählt nur Zeichen, dort belegt); `dictation_error_editor_busy`-Pfad `VoiceKeyboardService.kt` |
| e | **`mic_rel = up:…` < 1,5 s** | **Echter Loslass-Pfad** (Finger/Sensorik/Halt-Geste endet früh) — **kein Produktfehler** im Diktat | `keyboard/KeyboardView.kt:820` (`onMicHoldEnd`), `ime/MicToggle.kt:47-50`, `ime/VoiceKeyboardService.kt:708-718/504-512` | Kein Code-Fix; UX-Option: Halt-Schwelle/Visuelle Rückmeldung (Design-Entscheidung), ggf. H-B-Pointer-Log zur Abgrenzung spurloses UP |
| f | **`mic_rel = hidden:…` / `editor:…`** | **IME-/Fokusverlust** (System versteckt Tastatur, Editor-Wechsel) | `ime/VoiceKeyboardService.kt:327-338` (`onFinishInputView`/`onWindowHidden` → stop), `:307-318` (`onStartInput` → `onEditorChanged`/abort) | Lifecycle-Fix: Verhalten bei kurzem Hide (Gnadenfrist?) oder Abbruch sichtbar machen; dann F28-`status_shown`-Kohärenz prüfen |
| g | **`status_shown` ≠ Controller-Zustand** (Export-Zeitraum) | **Anzeige-Fix** (F9-Klasse: Main-Thread-Update verspätet/stuck) | `ime/VoiceKeyboardService.kt:885-915` (`updateStatus`, Main-Post `:887-890`), `ime/StatusBarText.kt`/`StatusRouting.kt` | Anzeige-Fix: Resync-Timeout in `updateStatus`/`onStopped` (`DictationCoordinator.kt:406-411`); Messung bleibt (`status_shown`-Feld) |

**Lesebeispiel (Export-#2-Annahme nachgerückt):** `abort_reason final` + `mic_rel up:≈1200`
+ `cap_blocks ≈ 120` + `loud_s 0` ⇒ Zeile e (echter UP) **plus** Zeile b (Rauschen-Mikro)
als paralleler Befund — zwei unabhängige Geschichten in einem Export.

## 10. Was am Telefon zu prüfen ist (offene Nutzerfrage)

Bevor/während des nächsten Diktat-Versuchs mit 0.1.23 (🐞-Export #3): 
1. **Mikro in anderen Apps:** kurze Testton-App (oder Sprachnotiz) starten — ist dort
   der Pegel normal? → nein: **Gerät/Mikro-Mute/Besetzung** (Zeile b, Hardware-/Frei-
   gabe-Ebene, kein App-Fix). 
2. **GrapheneOS-Freigaben:** explizit prüfen, ob für die IME **`RECORD_AUDIO`** und
   **`POST_NOTIFICATIONS`** (Benachrichtigung) aktiv sind — GrapheneOS handhabt
   FGS-Mikro-Berechtigungen strikter; `notif=-`/`notif=no` im Export ist das Indiz
   (`dictation/…`, Feld `permissions` im 🐞-Export). 
3. **Wiederholung mit 0.1.23:** F31 macht den Rauschen-Zustand sichtbar (`audio_error:
   silence` statt Endlos-Diktat), F28/F30-Felder (`cap_blocks`, `cap_stall_ms`,
   `loud_s`, `mic_rel`, `status_shown`) trennen danach die Schichten nach §9 — **ein
   Export reicht für die Einordnung**, sofern der Nutzer 1./2. dokumentiert.
4. **Nicht tun:** keine echten Sprachinhalte in Logs/Exports (Regel 9); Export =
   Zahlen + Codes + Tastennamen, nichts anderes.
