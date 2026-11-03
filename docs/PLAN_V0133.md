# PLAN V0133 — Zuverlässigkeit Diktat, Ziffern-Overlay, Backspace

**Planer-Stand:** 2026-11-02 · **Rolle:** Planung, read-only auf Code; keine git-Operationen  
**Zielversion:** `0.1.33` / **versionCode 34**  
**Basis:** Debug **546/0**, Release **482/0**  
**Grundlage:** Gerätebefunde 18:56–19:04 (`/tmp/vk_phone_sess*.log`, `/tmp/vk_phone_window.log`) und die verifizierten Codepfade.  
**Inhaltsverzeichnis:** §0 Befunde/Leitplanken · §1 Schnitt A (A1–A4) · §2 Schnitt B (B1–B3) · §3 Schnitt C (Messplan/Abnahme) · §4 Testziele/Risiken/Release.

---

## 0. Befunde und verbindliche Leitplanken

### 0.1 Verifizierte Ausgangslage

| Befund | Beleg | Konsequenz |
|---|---|---|
| Kaltstart setzt drei Watchdog-Uhren **vor** `engine.load()`; der erste leere 50-ms-Poll nach 4,42 s Ladezeit löst sofort `ERR_STALL` aus. | `DictationCoordinator.kt:258–267,300,319–330,444,494–495`; `AudioRecordSource.kt:155–158`; `/tmp/vk_phone_sess.log:2–4` | A1: Zeitbasen erst nach erfolgreichem Quellstart setzen. Echte Quelltotheit bleibt ein Systemfehler. |
| Fall 2 zeigt **weiterlaufende ASR trotz sichtbarem Textstopp**: bei 8/10/12 s wachsen die Tokenzahlen 55/70/85; Decodes dauern nur 442/537/627 ms. | `/tmp/vk_phone_window.log:3270,3616,3942` | Keine pauschale CPU-/TDT-/Pacing-Therapie. A2 muss den Schreibpfad W1/W3d behandeln. |
| Fall 3 beweist ohne `VK-ASR`-Zeilen nur eingefrorenes `out`, nicht „kein Decode“. | `/tmp/vk_phone_sess2.log:3–7` | A4 liefert den fehlenden Decode-/Schreibnachweis im Release. |
| W1 und W3d verwerfen Schreibungen ohne Sessionfehler. Das Finale benutzt denselben blockierbaren `writeHypothesis()`-Pfad. | `DictationController.kt:368–390,485–530`; `DeltaTyper.kt:74–90,137–148` | W3d darf nicht permanent blockieren; das Finale braucht eine eigene, besitzgesicherte Gesamtrevision. |
| `seg_final` vor `start_ok` entsteht durch alten `sessLastSeg=1` und neuen Sessionzähler 0, nicht durch ein verspätetes Mini-Finale. | `VoiceKeyboardService.kt:196,1394–1397` | A3: nur echte Zählerzunahme loggen und Sessionbaseline zurücksetzen. |
| Export hat derzeit **53 Felder = Kopf + 53 Zeilen = 54 Zeilen**. | `DebugInfoFormatter.kt:175–193`; `DebugInfoFormatterTest.kt:55–62` | A4 hängt Felder nur an; exakte neue Zeilenzahl testen. |
| Reale r0-Geometrie ergibt `top=-168`; Sichtbarkeit hängt vom Zeichnen außerhalb der `KeyboardView` ab. | `LongPressMenu.kt:82–139`; `KeyboardView.kt:548–558` | B1 wählt den kleinen, lokalen Fix innerhalb des IME-Fensters, keine Fenstererweiterung. |
| Backspace wird nur beim UP einmal ausgeführt; Selektion wird nicht gesondert behandelt. | `KeyboardView.kt:939–997`; `VoiceKeyboardService.kt:1497–1504` | B2: Repeat-Lebenszyklus + Auswahlzweig. |

### 0.2 Nicht verhandelbar

1. **Nutzer-Vertrag §6.3c:** Halten bedeutet kontinuierliche Aufnahme bis UP/CANCEL, definiertes Systemereignis oder Obergrenze. Transiente Decode-, Editor-, Pacing- oder Hypothesenlatenz erzeugt **Diagnose/Hint, niemals einen Watchdog-Abort**. Echte >3-s-Quelltotheit (`ERR_STALL`) bleibt als Systemereignis erhalten.
2. **Regel 9:** Logs/Export enthalten nur Zahlen, Dauern, Längen und geschlossene Codes. Keine Transkripte, Hypothesentexte, Audiodaten, Pfade, URLs, Paketnamen oder freien Exceptiontexte. `:` in Werten wird zu `=` sanitisiert.
3. **Besitzsicherheit:** Keine Revision ohne erneuten Suffix-/Längenbeweis. Fremdtext wird nie gelöscht. `SAME` erzeugt keinen Sink-Zugriff.
4. **TDD je Auftrag:** Lauf 1 = neuer/geänderter Test nachweisbar rot; Lauf 2 = Fix und grüne Debug-/Release-Tests. Kein Produktionscode vor dem roten Test.
5. **Budget je Auftrag:** höchstens **4 Dateien** und **2 Testläufe**. Emulator-Screenshot/OCR ist Abnahme desselben zweiten Laufs, kein zusätzlicher Codeauftrag.
6. **Keine spekulative B3-Änderung:** Erst A4-Messung; F43-/Gate-Verhalten nur bei belegter Wurzel ändern.

### 0.3 Reihenfolge

`A1 ∥ A2a ∥ B1 ∥ B2` → `A2b` → `A4a` → `A4b` → Geräte-Messung C → `B3 nur bei belegter Wurzel`. `A3` ist unabhängig und darf parallel zu A2 laufen.

---

## 1. Schnitt A — Zuverlässigkeit Diktat

### A1 — Kaltstart: Watchdog-Baselines an den echten Capture-Start binden

- **Dateien (2):**
  1. `app/src/main/java/de/voicekeyboard/ime/DictationCoordinator.kt`
  2. `app/src/test/java/de/voicekeyboard/ime/DictationCoordinatorTest.kt`
- **Rote-Test-Expektation (2 Tests):**
  1. Fake-`engine.load()` schiebt die injizierte Uhr um **4 500 ms** vor; `src.start()` kehrt erfolgreich zurück, erster PCM-Puffer kommt nach **100 ms**. Erwartung: `isActive=true`, `lastError=null`, kein `audio_error:stall`, kein `audio_error:silence`. Vor Fix: `ERR_STALL` nach dem ersten 50-ms-Timeout.
  2. Quelle liefert nach erfolgreichem Start **3 000+ ms** keine Blöcke. Erwartung: genau ein `audio_error:stall`, `onAborted` genau einmal, Controller IDLE. Bestehender Stall-Vertrag bleibt rot-grün abgesichert.
- **Fix-Umriss:** Nach erfolgreichem `src.start()` und unmittelbar vor `captureThread.start()` **einen** Wert `captureStartedAtMs=nowMs()` nehmen und `lastBlockAtMs`, `startRefMs`, `lastLoudAtMs` gemeinsam damit setzen. Startdauerdiagnose darf weiterhin `engine.load()` messen; nur die Watchdog-Baselines wandern. Keine Schwellwertänderung.
- **Acceptance:** Kaltstart 4,5 s + 100 ms Startlatenz bleibt RECORDING; `cap_stall_ms<3 000` vor dem ersten Block. Reale Quelltotheit feuert frühestens nach >3 000 ms genau einmal. Kein neuer Abort bei transienter Latenz.
- **Budget:** 2 Dateien, 2 Läufe, **+2 Tests**.

### A2a — `DeltaTyper`: Grundrückgabe, W3d-Recovery, Gesamtrevision

- **Dateien (3):**
  1. `app/src/main/java/de/voicekeyboard/editor/DeltaTyper.kt`
  2. `app/src/test/java/de/voicekeyboard/editor/DeltaTyperTest.kt`
  3. `app/src/test/java/de/voicekeyboard/commit/CommitE8Test.kt`
- **Rote-Test-Expektation (4 Tests):**
  1. `reviseOutcome()` liefert geschlossene Ergebnisse `SAME`, `W1`, `W3d`, `DELTA`; `SAME` bei identischem Ziel; bisherige Null-Mehrdeutigkeit ist im neuen Produktionspfad verboten.
  2. W3d-Fall mit **75 Codepoints**, `committedLength=70`: erster blockierter Versuch meldet `W3d`; die nächste Hypothese revidiert bei erneut bewiesenem Besitz die eigene Spanne atomar und meldet `DELTA`. Kein dauerhafter Stillstand.
  3. `reviseFinal(target)` revidiert nach einer W3d-Sperre die **gesamte eigene Spanne** in genau einem Sink-Schritt und lässt einen Fremdpräfix unangetastet.
  4. **100** identische Hypothesen nach einem initialen Delta liefern 100× `SAME`; Sink-Calls und geschriebene Zeichen bleiben exakt auf dem Stand nach dem initialen Delta.
- **Fix-Umriss:** Neues Ergebnisobjekt mit `kind` und optionalem `TextDelta`; `reviseOutcome()` wird der einzige neue Produktionspfad. Ein W3d-Zustand ist einmalig sichtbar und wird beim nächsten besitzgesicherten Aufruf durch eine atomare Gesamtrevision der **eigenen** Spanne aufgelöst. `reviseFinal()` prüft Besitz ebenfalls zuerst und umgeht nicht die Fremdtextgrenze. Bestehende Tests werden auf die Grundrückgabe migriert; kein Kompatibilitäts-Null im Controller.
- **Acceptance:** W3d-Burst >60 Zeichen blockiert höchstens eine Hypothese; nächste Hypothese schreibt. Finale nach W3d ist vollständig und ohne Duplikat. SAME-Flut: **0 zusätzliche Sink-Calls**. W1 bleibt schreibgeschützt.
- **Budget:** 3 Dateien, 2 Läufe, **+4 Tests**.

### A2b — Controller: W1-Re-Baseline, Finale, Counter und Decode-Diagnose

- **Dateien (4):**
  1. `app/src/main/java/de/voicekeyboard/dictation/DictationController.kt`
  2. `app/src/main/java/de/voicekeyboard/ime/DictationCoordinator.kt`
  3. `app/src/main/java/de/voicekeyboard/editor/OwnershipTracker.kt`
  4. `app/src/test/java/de/voicekeyboard/ime/SchreibstoppTest.kt` (neu)
- **Rote-Test-Expektation (4 Tests):**
  1. Controller-Session mit W3d-Burst bleibt RECORDING; nächste Hypothese schreibt; `write_w3d=1`, danach `write_delta` wächst.
  2. Finale nach W3d committed den vollständigen finalen Zielstand; keine alte Teilhypothese bleibt stehen.
  3. W1 mit `actualBeforeCursor`-Suffixbeweis und `fresherExpected=sink.ownText()` darf re-baselinen und danach schreiben. Fremder/abweichender Editorinhalt ⇒ `W1`, kein Sink-Zugriff, `lost` bleibt gesetzt.
  4. E1c-Koordinate: frischer Sink-Text hat Vorrang nach Präfixregel vor älterer Tracker-Erwartung. Erst nach erfolgreichem Suffixbeweis wird `lost=false`; der `ownEditFresh`-Sonderfall allein gilt **nicht** als Suffixbeweis.
- **Fix-Umriss:**
  - `writeHypothesis()` nutzt `reviseOutcome()` und zählt `SAME/W1/W3d/DELTA` sowie `lastWriteAudioS`; `lastDecodeMs`, `hypLen`, `currentLen`, `committedLen`, `writeReason` werden als Zahlen/Codes lesbar.
  - W1-Rebaseline benutzt exakt die E1c-Koordinaten: Länge `max(currentText.length,sink.ownText().length)`, `actual=sink.textBeforeCursor(len)`, `fresherExpected=sink.ownText()`, dann `tracker.rebaseline(actual,fresherExpected)`. Nur nach dessen positivem Suffixbeweis darf dieselbe Hypothese einmal erneut versucht werden.
  - `OwnershipTracker.rebaseline()` löscht `lost` ausschließlich im erfolgreichen `stillOurs`-Zweig. Kein Löschen bei `actual==null`, Fremdtext oder bloß frischem Edit.
  - Controller-`stop()` verwendet `reviseFinal()`. Coordinator reicht ein unveränderliches Decode-Fenster-Diagnoseobjekt samt optionalem Callback nach außen; kein UI-/Android-Typ im Objekt.
- **Acceptance:** Alle vier Tests grün; Session endet nicht durch W1/W3d selbst. Fremdtext bleibt byteweise unverändert. Counter sind monoton pro Session und werden beim Start genullt; `last_write_audio_s=-1` vor erstem DELTA.
- **Budget:** 4 Dateien, 2 Läufe, **+4 Tests**.

### A3 — `seg_final`-Logartefakt beseitigen

- **Dateien (2):**
  1. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
  2. `app/src/test/java/de/voicekeyboard/ime/SegmentSessionLogTest.kt` (neu)
- **Rote-Test-Expektation (2 Tests):**
  1. Sequenz alte Session `seg=1` → Mic-DOWN → neue Session `seg=0`: **kein** `seg_final`; danach `0→1`: genau ein Eintrag.
  2. `1→1` erzeugt keinen Eintrag; zweite Session wiederholt denselben Verlauf ohne Altartefakt.
- **Fix-Umriss:** `sessLastSeg=-1` beim Mic-DOWN; reine Entscheidung nur bei `previous>=0 && current>previous` loggen. Keine Änderung am Segmenter oder dessen Zähler.
- **Acceptance:** `/tmp`-Reprosequenz enthält nie `seg_final` vor `start_ok`; echte Mini-Finals bleiben genau einmal sichtbar.
- **Budget:** 2 Dateien, 2 Läufe, **+2 Tests**.

### A4a — Diagnose: Session-/Letztfenster-Felder in den Release-Export

- **Dateien (4):**
  1. `app/src/main/java/de/voicekeyboard/debug/DebugInfoCollector.kt`
  2. `app/src/main/java/de/voicekeyboard/debug/DebugInfoFormatter.kt`
  3. `app/src/test/java/de/voicekeyboard/debug/DebugInfoCollectorTest.kt`
  4. `app/src/test/java/de/voicekeyboard/debug/DebugInfoFormatterTest.kt`
- **Rote-Test-Expektation (4 Tests):**
  1. Collector liefert die A2b-Werte; Entfernen einer Collector-Zuordnung macht den Test rot.
  2. Export enthält exakt die zehn neuen Schlüssel: `last_decode_ms`, `hyp_len`, `current_len`, `committed_len`, `write_reason`, `write_same`, `write_w1`, `write_w3d`, `write_delta`, `last_write_audio_s`.
  3. Fehlende Werte werden `-`; keine Schreibung ergibt `last_write_audio_s=-1`; Werte mit `:` werden zu `=` sanitisiert.
  4. Bestehende **53 KEYS bleiben in Reihenfolge unverändert**, zehn neue werden angehängt: **63 Felder, Kopf + 63 = 64 Zeilen**, deterministisch bei leerem und vollem Snapshot.
- **Fix-Umriss:** Nur Durchreichen des A2b-Diagnoseobjekts; keine neuen Textquellen. Vorhandene Felder `audio_s`, `output_chars`, `decode_pace_ms`, `decode_skips`, `hyp_gap_ms`, `decode_ms_max/p95`, `buffer_s`, `owner_lost_at_s`, `sel_unexpected`, `restart_inputs`, `rebases` bleiben unverändert. Export ist main-source und damit release-wirksam.
- **Acceptance:** Export = Kopf + 63 Feldzeilen = **64 Zeilen** stabil; Sanitizer- und Rule-9-Tests grün; Release-Export zeigt reale Zahlen/Codes statt `-` nach mindestens einem Decode.
- **Budget:** 4 Dateien, 2 Läufe, **+4 Tests**.

### A4b — Kompakte `VK-ASR`-Fensterzeile im Release

- **Dateien (2):**
  1. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
  2. `app/src/test/java/de/voicekeyboard/ime/DecodeWindowLogTest.kt` (neu)
- **Rote-Test-Expektation (2 Tests):**
  1. Reine Formatierungsfunktion erzeugt genau eine Zeile mit `event=window` und allen **17 Metriken**: `audio_s,out,decode_ms,pace_ms,skips,hyp_len,current_len,committed_len,write,owner_lost_at_s,sel_unexpected,restart_inputs,rebases,hyp_gap_ms,decode_ms_max,decode_ms_p95,buffer_s`.
  2. Rule-9-Test: keine Text-/Audiofelder, keine Pfade/URLs/Paketnamen, nur geschlossene Write-Codes; kein Debug-Guard. `out` kommt synchron vom bestehenden `outputCounter`.
- **Fix-Umriss:** A2b-Callback im Service verdrahten; nach jedem Decode-Fenster genau eine kompakte `VK-ASR`-Zeile ausgeben. Die vorhandene Engine-Stufenzeile darf bleiben; die neue `event=window`-Zeile ist die eindeutige Decode→Schreib-Kette. Keine Dauerschleife und kein Log zwischen Fenstern.
- **Acceptance:** Pro Decode genau eine `event=window`-Zeile, auch im Release; bei 30/60/90 s deckt die Folge jedes Fenster ab und zeigt unmittelbar, ob Decode, Pacing, W1, W3d oder SAME für eine Ausgabelücke verantwortlich ist. Kein Abort durch diese Diagnose.
- **Budget:** 2 Dateien, 2 Läufe, **+2 Tests**.

---

## 2. Schnitt B — Nutzer-UI

### B1 — Reihe-0-Ziffernmenü wieder vollständig im IME-Fenster

- **Dateien (2):**
  1. `app/src/main/java/de/voicekeyboard/keyboard/LongPressMenu.kt`
  2. `app/src/test/java/de/voicekeyboard/keyboard/LongPressMenuTest.kt`
- **Rote-Test-Expektation (3 Tests):**
  1. Reale Geometrie `keyTop=0,rowH=150,menuH=147,gap=21,pad=10,cell=126,spaceAbove=126`: erwartet `menuTopPx=171` (Fallback **unter** der Taste), nicht `-168`; `top>=0` und `top+147<=318`.
  2. Dieselbe Entscheidung steuert `stillArmed/menuBelowKey`: Zelle 0 bleibt per Schieben erreichbar; `spaceAbovePx` darf Sichtbarkeit nicht mehr über Root-Clipping retten.
  3. Mittlere Reihe/Fußzeile und Punkt-Menü behalten exakt ihre bisherigen Koordinaten/Aktionszellen.
- **Fix-Umriss:** r0 wählt den oberen Pfad nur, wenn die **komplette Menü- und Zellfläche innerhalb der KeyboardView** liegt (`above>=0`); sonst F38-Position unter der Taste. Kein IME-Fenster-Resize, keine Änderung an `clipChildren`. Dieselbe reine Funktion steuert weiter Geometrie und Arming-Zone.
- **Acceptance:** Emulator 1080×2404, Q lang: Ziffernmenü sichtbar, Zelle „1“ per OCR nachgewiesen und genau ein Commit; Punkt-Menü unverändert. Screenshot vor/nach; alle sichtbaren Menürechtecke erfüllen `0<=top` und `top+menuH<=keyboardHeight`.
- **Budget:** 2 Dateien, 2 Läufe, **+3 Tests**; Screenshot+OCR in Lauf 2.

### B2 — Backspace-Repeat und komplette Selektionslöschung

- **Dateien (4):**
  1. `app/src/main/java/de/voicekeyboard/keyboard/BackspaceRepeat.kt` (neu, reine Zeitlogik)
  2. `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt`
  3. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
  4. `app/src/test/java/de/voicekeyboard/ime/BackspaceTest.kt` (neu)
- **Rote-Test-Expektation (5 Tests):**
  1. Fake-Zeitplan: initialer Delete bei **0 ms**, Repeat bei **450,520,590,660,730,800,870,940 ms**; bei UP bei 1 000 ms exakt **9 Deletes**, danach keine weiteren.
  2. Kein Repeat vor 450 ms; UP/CANCEL/Detach stoppt deterministisch; ein kurzer Tap erzeugt weiterhin genau einen Delete.
  3. Nichtleere Selektion: genau ein `commitText("",1)`, **0** `deleteSurroundingText`, **0** DEL-KeyEvents; die komplette Selektion ist ersetzt.
  4. Leere/null-Selektion: bestehender Graphemcluster-Pfad bleibt; kein Doppeldelete durch DOWN+UP.
  5. Repeat nach weggefallener Selektion löscht weiter über den normalen Graphemcluster-/Fallbackpfad.
- **Fix-Umriss:** Backspace-DOWN führt genau eine Aktion aus und armiert den Timer; UP/CANCEL beendet ohne zweite Aktion. Startverzögerung **450 ms**, Wiederholperiode **70 ms** (Zielbereich 60–80 ms). Service prüft zuerst `getSelectedText(0)` und ersetzt eine nichtleere Selektion atomar durch leeren Text; sonst Bestandspfad. Repeat benutzt immer denselben Servicezweig.
- **Acceptance:** Fake-Zeit exakt 9 Deletes/1 000 ms und 0 nach UP; Gerät: gleichmäßiges Löschen, Loslassen stoppt sofort; Auswahl verschwindet vollständig. Kein Repeat-Timer nach Editorwechsel/Detach.
- **Budget:** 4 Dateien, 2 Läufe, **+5 Tests**.

### B3 — Toggle-Modus: erst ursachenfest messen, dann minimal fixen

- **Vorbedingung:** A4a+A4b und C-Läufe sind verfügbar. Symptom: Toggle bricht ab und zeigt abwechselnd „Kein Text erkannt“. F43 mit 2,5-s-Pause ist nur Kandidat, keine belegte Wurzel.
- **Messung ohne Codeänderung:** Drei Toggle-Zyklen mit durchgehender Sprache und drei mit definierter 3-s-Pause. Prüfen: `start_src=toggle`, `gate_at_stop=1`, `fgs_stopped_by=autostop` vs. `stop`, `abort_reason`, `stop_cause`, `audio_s`, `loud_s`, `hyp_gap_ms`, `output_chars`, `last_write_audio_s`, `write_reason`, `owner_lost_at_s`.
- **Dateien (0 für Messung; höchstens 4 bei belegtem Fix):**
  1. `app/src/main/java/de/voicekeyboard/ime/DictationCoordinator.kt`
  2. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
  3. `app/src/test/java/de/voicekeyboard/ime/DictationAutoStopTest.kt`
  4. `app/src/test/java/de/voicekeyboard/ime/MicToggleTest.kt`
- **Rote-Test-Expektation (nur nach Beweis, 2 Tests):** Test bildet die gemessene Toggle-Sequenz exakt nach und ist vor Fix rot; Gegenprobe Hold und normaler Toggle-Pausenstopp bleibt grün.
- **Fix-Umriss:** Nur die belegte Gate-/Stop-/Statuswurzel ändern. Keine pauschale Verlängerung der 2,5-s-Schwelle, kein Hold-Gate-Eingriff, kein Unterdrücken des „Kein Text“-Hinweises ohne nachgewiesene leere Ausgabe.
- **Acceptance:** Drei Toggle-Zyklen mit kontinuierlicher Sprache ohne vorzeitigen Stopp oder alternierende Leermeldung; bei echter 2,5-s-Pause weiterhin genau ein kontrolliertes Finale. `stop_cause` und `fgs_stopped_by` erklären jeden Lauf; keine Textinhalte im Log.
- **Budget:** Messung 0 Dateien; Fix 4 Dateien, 2 Läufe, **+2 Tests nur falls begründet**.

---

## 3. Schnitt C — Geräte-Messplan und BESTANDEN-Kriterien

### 3.1 Läufe

1. **Kaltstart einmal** nach natürlichem IME-Prozess-Kaltstart (kein `force-stop`, kein Log-Löschen): Modell-Load beobachten, 10 s sprechen. Erwartung A1: kein Stall 50 ms nach `start_ok`.
2. **Warmhold 30/60/90 s**, jeweils 1 Wort/s, Finger durchgehend unten. 60 s zusätzlich im problematischen Compose-Editor und in einem schlichten Editor als Kontrolle.
3. **B3-Toggle** erst nach Auswertung dieser Holds wie in §2 definiert.

Parallel für jeden Hold (`SECS` = 30/60/90):

```bash
S=<phone-host>:<adb-port>
SECS=60
adb -s $S logcat -v time -s VK-SESS:D VK-ASR:I VK-IME:W VK-Capture:I '*:S'

PID=$(adb -s $S shell pidof -s de.voicekeyboard)
adb -s $S shell "top -H -b -d 1 -n $((SECS+5)) -p $PID"
adb -s $S shell "dumpsys cpuinfo | grep -i -B2 -A2 voicekeyboard"
adb -s $S shell dumpsys thermalservice
# optional, read-only, falls simpleperf verfügbar/erlaubt ist:
adb -s $S shell "simpleperf stat -p $PID --duration 15"
```

**Export erst nach UP:** Der Teilen-Dialog verdeckt die IME und würde die Session beeinflussen. Kein `logcat -c`, kein Force-Stop, keine System-Properties als Releasevoraussetzung.

### 3.2 Export-Checkliste je Lauf

- Identität/Ende: `app_version=0.1.33 (34)`, `audio_s`, `start_ms`, `abort_reason`, `stop_cause`, `mic_rel`, `fgs_stopped_by`, `last_error`, `ctl_failed`.
- Audio: `cap_blocks`, `cap_stall_ms`, `loud_s`, `peak_dbfs`, `dropped`, `gate_at_stop`.
- Decode: `hyp_count`, `hyp_gap_ms`, `last_decode_ms`, `decode_ms_max`, `decode_ms_p95`, `decode_pace_ms`, `decode_skips`, `buffer_s`, `hyp_last_buffer_s`, `segment_finalizations`.
- Schreiben: `output_chars`, `hyp_len`, `current_len`, `committed_len`, `write_reason`, `write_same`, `write_w1`, `write_w3d`, `write_delta`, `last_write_audio_s`.
- Editor: `owner_lost_at_s`, `sel_unexpected`, `restart_inputs`, `rebases`, `status_res`.

### 3.3 BESTANDEN pro Warmhold (D = 30/60/90)

| Kriterium | Erwartungszahl/Code |
|---|---|
| Aufnahme bis echtes UP | `audio_s = D ± 1,5 s`; `stop_cause=stop:holdup`; `abort_reason=final`; `last_error=-`; `ctl_failed=0`; kein `audio_error:*` |
| Decode-Fluss | `hyp_count >= 5/10/15` für D=30/60/90; jeder Abstand zwischen `event=window`-Zeilen **<=7 000 ms**; `decode_pace_ms<=6 000` |
| Decode-Kosten/Fenster | `decode_ms_max<=2 000`; `hyp_last_buffer_s<=38,0`; `buffer_s = audio_s ± 0,2 s`; Segmenter nur 35-s-/Pausenregel |
| Kein sichtbarer Schreibstopp | `last_write_audio_s >= D−7,0`; `output_chars >= 30/60/90`; nach W3d folgt spätestens im nächsten Fenster `DELTA`; kein ungelöstes finales `W1/W3d` |
| Finale | Ausgabe wächst oder bleibt nur bei `SAME` gleich; finale W3d-Sperre ausgeschlossen; kein Duplikat, kein Fremdtexteingriff |
| Editor/CPU | Kein unerwarteter `owner_lost_at_s>=0`; `sel_unexpected=0` in der Kontrolle. `top`, `cpuinfo`, Thermal dienen nur der Erklärung: fehlt ein Fenster >7 s, muss ein Decode-/Threadbefund vorliegen; kein pauschaler CPU-Grenzwert. |
| Diagnose | Jede Decode-Zeile enthält alle 17 Metriken; Export hat Kopf + 63 Feldzeilen und keine Inhalte/Pfade/URLs/Paketnamen. |

Ein Lauf ist **nicht bestanden**, wenn Audio weiterläuft, aber eine Ausgabelücke >7 s ohne anschließende Recovery auftritt, wenn das Finale erneut durch W3d blockiert wird oder wenn eine transiente Latenz zum Session-Abort führt.

### 3.4 C-emu — Emulator-Vorabprüfung VOR der Telefon-Messphase

**Zweck/Reihenfolge:** E1 → E2 → E3 → E4 → E5. **E1 zuerst**, weil nur die **ERSTE App-Session nach Emulator-Kaltstart** echten Ton liefert (I5-Regel analog). Dieses Erste-Session-Budget nicht durch Probemikrofone, Toggle oder Smoke-Vorläufe verbrauchen. FakeWav umgeht dieses Gesetz für Engine-/Editorläufe, ersetzt aber **nicht** `AudioRecordSource` + FGS + echte AudioRecord-Kette.

**Gesamtbudget: 50 min + 5 min Reserve = <60 min:** E1 10 min · E2 5 min · E3 8 min · E4 8 min · E5 4 min · gemeinsamer Boot/Install/Puffer 15 min. Kein Gradle-Bau parallel zum Emulator; APKs und Modell liegen vor Start bereits fertig vor.

#### Gemeinsame Vorbereitung und Export-Richter

```bash
cd <workdir>
export PATH="$PATH:/opt/android-sdk/platform-tools"
export XDG_RUNTIME_DIR="/run/user/$(id -u)"
unset DISPLAY                         # ausdrücklich KEIN DISPLAY
ADB="adb -s <device>"            # niemals 5558/anderer Port
PKG=de.voicekeyboard

# Google-Störer vor jeder IME-Bedienung ruhigstellen; kein pm clear!
for p in com.google.android.apps.messaging com.google.android.gms \
         com.google.android.calendar com.google.android.googlequicksearchbox; do
  $ADB shell pm disable-user --user 0 "$p"
done

focus_ime() {                         # nur Plain-/Debug-Editor, kein restart:20
  for k in 1 2 3; do
    $ADB shell "dumpsys input_method | grep -m1 mInputShown" | grep -q true && break
    $ADB shell input tap 540 300; sleep 0.6
    $ADB shell input text "x"; sleep 1.2
  done
  $ADB shell "dumpsys input_method | grep -m1 mInputShown" | grep -q true || return 1
}

export_clear() {                      # Debug: Datei-Hook + alter Stand weg
  $ADB shell setprop debug.vk.export_file 1
  $ADB exec-out run-as "$PKG" rm -f files/debug_export.txt
}

export_wait() {                       # $1 = Host-Ziel; Richter = BYTES>100
  local out="$1" bytes=0
  for i in $(seq 1 50); do
    bytes=$($ADB exec-out run-as "$PKG" stat -c %s files/debug_export.txt 2>/dev/null || echo 0)
    [ "${bytes:-0}" -gt 100 ] && break
    sleep 0.2
  done
  [ "${bytes:-0}" -gt 100 ] || { echo "E: Export <=100 Bytes"; return 1; }
  $ADB exec-out run-as "$PKG" cat files/debug_export.txt > "$out"
  [ "$(wc -c < "$out")" -gt 100 ]
}
```

**Runner-Vertrag für E2:** `tools/emu_c2_run.sh` besitzt die `mInputShown`-Asserts und nutzt Mic **`(880,1635)`**; niemals `x=166`. Die Mord-Zelle `restart:20` wird über den Watcher-Trigger `input text` ausgelöst. FakeWav/Engine-Hooks wirken erst nach dem Runner-`force-stop`. Kein paralleler Bau.

#### E1 — A1-Kaltstart mit der einzigen echten AudioSession

- **Zweck:** A1 gegen Modell-Last >4 s und die reale `AudioRecord`-/FGS-Kette prüfen; wichtigster Lauf zuerst.
- **Vorbereiten:** Keine App-Session vorher. `emulator_audio_up.sh` erledigt Kaltboot, Offscreen statt Headless, PulseAudio/Null-Sink und End-to-End-Toncheck. Erst danach APK installieren und IME öffnen.
- **Exakte Befehlssequenz:**

```bash
tools/emulator_audio_up.sh            # XDG_RUNTIME_DIR ist gesetzt; kein DISPLAY
$ADB wait-for-device
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell ime set "$PKG/.ime.VoiceKeyboardService"
$ADB shell am start -n "$PKG/.debug.DebugInputActivity"; sleep 3
$ADB shell input tap 540 300; focus_ime
export_clear
$ADB logcat -c
$ADB logcat -v time -s VK-SESS:D VK-ASR:I VK-IME:W '*:S' > /tmp/v0133_e1.log & LOGPID=$!
$ADB shell input -d 0 motionevent DOWN 880 1635   # erste und einzige App-AudioSession
paplay testdata/c2/k120.wav & PLAYPID=$!
sleep 35                                           # >30 s Halten
$ADB shell input -d 0 motionevent UP 880 1635
kill "$PLAYPID" 2>/dev/null || true; sleep 10; kill "$LOGPID" 2>/dev/null || true
$ADB shell input tap 1017 1635; sleep 2; $ADB shell input keyevent 4
export_wait /tmp/v0133_e1_export.txt
```

- **BESTANDEN:** `start_ms>4000` (sonst Kaltlast nicht getroffen), `audio_s>=30`, `cap_stall_ms<3000`, **kein** `audio_error:stall`, `hyp_count>=2`, `write_delta>0`, `output_chars>0`, `last_error=-`; nach UP `stop_cause=stop:holdup`. Kein zweiter Mikroversuch auf demselben Boot.
- **Nicht bewiesen:** Pixel-/GrapheneOS-Audio-HAL, reales Mikro/Routing, thermische Drossel und Phone-FGS-Politik. Der 30-s-Lauf ersetzt nicht die Phone-Holds 30/60/90 s.

#### E2 — A2-Schreibstopp gegen die `restart:20`-Mord-Zelle

- **Zweck:** W1/W3d-Recovery und Exportzähler über wiederholte Editor-Restarts mit echter Engine und deterministischem FakeWav.
- **Vorbereiten:** `k120.wav` = **121,248 s / golden_chars=896**. E1 ist beendet; FakeWav benötigt kein zweites Echtton-Budget.
- **Exakte Befehlssequenz:**

```bash
export_clear
# Runner: mInputShown-Asserts, Mic 880/1635, Watcher-Trigger input text, restart:20.
tools/emu_c2_run.sh restart:20 real testdata/c2/k120.wav v0133_e2
export_wait /tmp/v0133_e2_export.txt
$ADB logcat -d -s VK-SESS:D > /tmp/v0133_e2_sess.log
```

- **BESTANDEN:** Abnahme-4-Muster **130 s / 1 660 Zeichen** ist die Referenz; harte Gates für k120: `audio_s>=118`, `editor_len>=896` (1 660 ist kein verbindlicher exakter Sollwert), `restart_inputs>=1`, `rebases>=restart_inputs`, `write_delta>0`, `last_write_audio_s>=114`, `stop_cause=stop:holdup`, `abort_reason=final`, `last_error=-`, **kein Abort**. `write_w3d` wird numerisch dokumentiert; bei `write_w3d>0` muss danach `write_reason=DELTA` nachgewiesen sein. Export enthält alle A4-Zähler und >100 Bytes.
- **Nicht bewiesen:** Echte AudioRecord-Kette, reale Phone-Compose-Implementierung und Aussagefreiheit über jeden Fremdeditor. FakeWav beweist Coordinator/Engine/Editor, nicht das Mikro.

#### E3 — A3/B1/B2 UI- und Log-Vorabnahme

- **Zweck:** A3-Sequenz prüfen; r0-Menü sichtbar und OCR-belegt; Backspace-Repeat und Selektionslöschung ohne Telefon vorab verifizieren.
- **Vorbereiten:** Debug-APK und DebugInputActivity bleiben aktiv. UI-Helfer liefern Key-Koordinaten bzw. nur Längen (keine Textinhalte):

```bash
ui_key() {                            # $1 = sichtbares Label/Description
  $ADB shell uiautomator dump /sdcard/vk.xml >/dev/null 2>&1
  $ADB shell cat /sdcard/vk.xml | python3 -c '
import sys,re,xml.etree.ElementTree as E
want=sys.argv[1].lower()
for n in E.fromstring(sys.stdin.read()).iter("node"):
    if (n.get("text","").lower()==want or n.get("content-desc","").lower()==want):
        x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")))
        print((x1+x2)//2,(y1+y2)//2); break
' "$1"
}
editor_len() {
  $ADB shell uiautomator dump /sdcard/edit.xml >/dev/null 2>&1
  $ADB shell cat /sdcard/edit.xml | python3 -c '
import sys,html,re
x=sys.stdin.read(); print(max([len(html.unescape(t)) for t in re.findall(r"text=\"([^\"]*)\",x)]+[0]))'
}

# A3: kein seg_final vor dem ersten start_ok der frischen Session.
$ADB logcat -d -s VK-SESS:D > /tmp/v0133_a3.log
awk '/start_ok/{seen=1} /seg_final/ && !seen {bad=1} END{exit bad}' /tmp/v0133_a3.log

# B1: Q 700 ms halten; Screenshot + OCR der Ziffer 1.
read -r QX QY <<< "$(ui_key q)"
$ADB shell input swipe "$QX" "$QY" "$QX" "$QY" 700
$ADB exec-out screencap -p > /tmp/v0133_row0.png
tesseract /tmp/v0133_row0.png stdout -l eng 2>/dev/null | grep -q "1"

# B2a: 30 Zeichen, Backspace ca. 2 s halten; nur Längendifferenz bewerten.
$ADB shell input keyevent 280
$ADB shell input text "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234"
read -r BX BY <<< "$(ui_key Löschen)"
BEFORE=$(editor_len)
$ADB shell input -d 0 motionevent DOWN "$BX" "$BY"; sleep 2
$ADB shell input -d 0 motionevent UP "$BX" "$BY"
AFTER=$(editor_len); sleep 0.5; AFTER2=$(editor_len)
[ $((BEFORE-AFTER)) -ge 10 ] && [ "$AFTER" = "$AFTER2" ]
$ADB exec-out screencap -p > /tmp/v0133_backspace.png

# B2b: Alles auswählen, ein Backspace => Länge 0.
$ADB shell input text "ABCDEFGHIJ"; $ADB shell input keyevent 280
$ADB shell input tap "$BX" "$BY"; sleep 0.3
[ "$(editor_len)" = 0 ]
```

- **BESTANDEN:** A3 hat **0** `seg_final` vor `start_ok`; B1-OCR findet „1“, Menürechteck vollständig im Screenshot/IME (`0<=top`, `top+menuH<=keyboardHeight`); B2 löscht in 2 s **>=10 Zeichen** und nach UP **0 weitere**; Selektion+⌫ ergibt exakt Länge **0**.
- **Nicht bewiesen:** Finger-/Systemgesten des Pixel, abweichende Dichten/OEM-Clips und Hardware-Tastatur-KeyEvents. OCR ersetzt nicht die Phone-Sichtkontrolle.

#### E4 — B3-Toggle messen, bevor ein Fix kodiert wird

- **Zweck:** Sechs ursachenlesbare Toggle-Exporte mit FakeWav: 3× kontinuierlich, 3× mit >2,5-s-Pause. Kein Produktionsfix in diesem Lauf.
- **Vorbereiten:** DebugInputActivity, `debug.vk.fakewav=/data/local/tmp/k.wav` wie vom Runner; FakeWav startet je Session am Korpusanfang. Funktion stoppt nur dann per zweitem Tap, wenn die aktuelle Session noch RECORDING ist:

```bash
$ADB push testdata/c2/k120.wav /data/local/tmp/k.wav
$ADB shell setprop debug.vk.fakewav /data/local/tmp/k.wav
$ADB shell setprop debug.vk.engine real
$ADB shell am force-stop "$PKG"
$ADB shell am start -n "$PKG/.debug.DebugInputActivity"; sleep 3
focus_ime
$ADB logcat -c
run_toggle() {                       # $1=Index, $2=Sekunden
  focus_ime
  $ADB shell input -d 0 motionevent DOWN 880 1635; sleep 0.08
  $ADB shell input -d 0 motionevent UP 880 1635     # Toggle
  sleep "$2"
  if $ADB logcat -d -s VK-SESS:D | awk '/state=/{s=$0} END{print s}' | grep -q state=RECORDING; then
    $ADB shell input -d 0 motionevent DOWN 880 1635; sleep 0.08
    $ADB shell input -d 0 motionevent UP 880 1635
  fi
  sleep 5; export_clear
  $ADB shell input tap 1017 1635; sleep 2; $ADB shell input keyevent 4
  export_wait "/tmp/v0133_toggle_$1.txt"
}
run_toggle 1 8; run_toggle 2 8; run_toggle 3 8
run_toggle 4 22; run_toggle 5 22; run_toggle 6 22   # enthält 4-s-Korpus-Pause
$ADB logcat -d -v time -s VK-SESS:D VK-ASR:I '*:S' > /tmp/v0133_toggle.log
```

- **BESTANDEN als Messung (nicht als Fixabnahme):** 6 Exporte >100 Bytes; jeder enthält `start_src=toggle`, `gate_at_stop`, `stop_cause`, `abort_reason`, `fgs_stopped_by`, `audio_s`, `last_write_audio_s`, `write_reason`; jede Session hat `event=window`-Zeilen. Kontinuierlich: `audio_s=8±1,5`; Pausenläufe: `audio_s<=22` und Ursache eindeutig `autostop`/Nutzerstopp statt `unknown`. Erst danach B3-Wurzel und roten Test festlegen.
- **Nicht bewiesen:** Alltags-Toggle auf echtem Mikro, Finger/IME-Wechsel des Nutzers und Phone-FGS. **Toggle im Alltag bleibt Telefon-Pflicht.**

#### E5 — A4a-Exportinhalt der Release-APK

- **Zweck:** Nachweis, dass die zehn neuen KEYS nicht nur im Debug-Export existieren.
- **Vorbereiten:** Kein Bau parallel; Release installieren und in einem schlichten Editor die IME zeigen. **Release-Hinweis:** `debug.vk.export_file`/`run-as` ist dort absichtlich wirkungslos; deshalb Richter über 🐞 → Share schließen → Einfügen → UI-Dump.
- **Exakte Befehlssequenz:**

```bash
$ADB install -r app/build/outputs/apk/release/app-release.apk
$ADB shell ime set "$PKG/.ime.VoiceKeyboardService"
$ADB shell am start -a android.settings.SETTINGS; sleep 2
$ADB shell input tap 540 160; sleep 1; $ADB shell input tap 540 300; focus_ime
$ADB shell input tap 1017 1635; sleep 2; $ADB shell input keyevent 4; sleep 1
$ADB shell input tap 540 300; $ADB shell input keyevent 123
$ADB shell "for i in \$(seq 1 240); do input keyevent 67; done"
$ADB shell input keyevent 279; sleep 1
$ADB shell uiautomator dump /sdcard/release_export.xml >/dev/null 2>&1
$ADB shell cat /sdcard/release_export.xml | python3 -c '
import sys,html,re
x=sys.stdin.read()
for m in re.finditer(r"text=\"([^\"]*)\",x):
    t=html.unescape(m.group(1))
    if "audio_s" in t and "cap_blocks" in t: print(t)
' > /tmp/v0133_release_export.txt
for k in last_decode_ms hyp_len current_len committed_len write_reason \
         write_same write_w1 write_w3d write_delta last_write_audio_s; do
  grep -q "^$k:" /tmp/v0133_release_export.txt || exit 1
done
grep -q '^build: release$' /tmp/v0133_release_export.txt
grep -q '^app_version: 0.1.33 (34)$' /tmp/v0133_release_export.txt
python3 -c 'p="/tmp/v0133_release_export.txt"; assert len(open(p).read().splitlines()) == 64'
```

- **BESTANDEN:** `build=release`, `app_version=0.1.33 (34)`, alle **10 neuen KEYS**, insgesamt **Kopf + 63 Feldzeilen = 64 Zeilen** (`splitlines()` unabhängig vom finalen Zeilenumbruch); keine Pfade/URLs/Paketnamen/Textinhalte.
- **Nicht bewiesen:** Reale Decode-Werte im Release; diese beweisen E2/Debug und anschließend die Phone-Läufe. E5 prüft Format und Release-Sichtbarkeit, nicht die Engine.

**Telefon-Pflicht trotz grünem C-emu:** **Echt-Mikro-Kaltstart** auf Pixel/GrapheneOS und **Toggle im Alltag** bleiben unverändert Pflicht. Ebenso die echten Warmholds 30/60/90 s nach §3.1–3.3. C-emu ist eine Vorab-Falle für Regressions- und Harnessfehler, kein Ersatz für C-Telefon.

---

## 4. Testziele, Risiken und Release

### 4.1 Erwartete Testzahlen

| Paket | Neue Tests |
|---|---:|
| A1 | +2 |
| A2a + A2b | +8 |
| A3 | +2 |
| A4a + A4b | +6 |
| B1 + B2 | +8 |
| **Summe ohne B3** | **+26** |
| B3, nur bei belegter Wurzel | zusätzlich +2 |

**Ziel ohne B3:** Debug **572/0**, Release **508/0**.  
**Ziel mit B3:** Debug **574/0**, Release **510/0**.  
Screenshot/OCR und Geräte-Holds sind zusätzliche Abnahmen, keine JUnit-Zahlen.

### 4.2 Hauptrisiken und Mitigation

1. **W3d-Gesamtrevision löscht zu viel:** Nur nach `ownershipVerified()`/Suffixbeweis und ausschließlich eigene Spanne; Fremdpräfix-Test und atomarer Sink-Schritt sind Releaseblocker.
2. **W1-Rebaseline akzeptiert App-/Nutzeränderung:** `lost` nur im `stillOurs`-Zweig öffnen; `ownEditFresh`, `actual==null` oder reine Längengleichheit reichen nicht. Bei Zweifel bleibt die Session schreibgeschützt, aber aktiv.
3. **API-Migration `DeltaTyper`:** A2a und A2b getrennt halten; alle alten Null-Erwartungen zuerst migrieren. Kein stiller Kompatibilitätspfad im Controller.
4. **Exportdrift/Rule 9:** Neue KEYS nur anhängen; exakt 64 Zeilen, Sanitizer und Collector-Kette testen. Keine Ziel-App oder Textlängen-Rückschlüsse über Inhalte hinaus.
5. **B1-Menü überdeckt Reihe 1:** Bewusster, sichtbarer Fallback statt unsichtbarem negativen Top; Touch-Arming und Punkt-Menü müssen unverändert bleiben. Kein größeres IME-Fenster als Seiteneffekt.
6. **B2-Doppeldelete/Timer-Leak:** DOWN/UP-Vertrag und Fake-Zeit sind Pflicht; CANCEL, Detach und Editorwechsel stoppen denselben Timer.
7. **B3-Fehltherapie:** Ohne A4-Beweis kein Fix. F43 bleibt für Toggle grundsätzlich erhalten; Hold-Gate nicht als Nebenwirkung ändern.
8. **Performance-Messung verändert Verhalten:** `top`/`simpleperf` nur parallel und read-only; keine starre CPU-Prozentannahme. Entscheidend sind Decode-Zeit und Fensterabstände.

### 4.3 Release-Gate

1. Alle Aufträge einzeln TDD-grün; Review P0/P1=0, insbesondere A2 und B2.
2. `app/build.gradle.kts`: **versionCode 34**, `versionName="0.1.33"`; erwartete Debug-/Release-Testzahlen aus §4.1.
3. Kaltstart + Warmhold 30/60/90 nach §3 bestanden; B1-Screenshot/OCR und B2-Geräteabnahme belegt.
4. B3 nur mit dokumentiertem Messbefund ausliefern; andernfalls bleibt es als offener Messpunkt dokumentiert und blockiert die übrigen Fixes nicht.
