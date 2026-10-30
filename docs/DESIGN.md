# Design: Offline-Sprachtastatur für GrapheneOS

## 0. Verbindlichkeit und Produktgrenze

Dieses Dokument spezifiziert eine sideloadbare Android-IME für Android 16 / API 36.
Sie ist eine vollständige Bildschirmtastatur und bietet zusätzlich gehaltenes
Push-to-Talk mit Parakeet TDT 0.6B v3, ONNX Runtime Android und ausschließlich CPU-EP.
Nach dem einmaligen Modelldownload ist Diktat vollständig offline möglich.
Keine Cloud, kein AccessibilityService, keine Abhängigkeit von Google Play Services.

**Quellenrang:** `TECH_FACTS.md` ist die verifizierte Umgebung-/Artefaktbasis;
`KONZEPT.md` liefert erprobte Desktop-Mechanismen, aber keine Android-Leistungszusage.
Alle nachfolgenden Zeit-, Fenster- und Speicherbudgets sind Designstartwerte und
auf einem realen GrapheneOS-Zielgerät zu validieren, keine Messwerte.
Die Produktübersicht beschreibt bekannte Architekturansätze, keinen hier getesteten
Versionsvergleich. Releaseabhängige Fähigkeiten sind ausdrücklich so gekennzeichnet.

Wichtige Präzisierungen gegenüber dem Desktop-Konzept:
- Die aktive IME schreibt über ihre InputConnection auch in fremde Ziel-Apps.
- UI-Ereignisse bleiben nur dann reaktionsfähig, wenn native Inferenz nie im UI läuft.
- TDT erzeugt Tokens autoregressiv im Prediction-Netz; Greedy ist nicht parallel
  über alle Tokens. Die Encoder-Berechnung und Durationsprünge beschleunigen es.
- Desktop-Whisper-Optionen wie `temperature`, `beam_size` oder
  `condition_on_previous_text` sind keine vorausgesetzten ONNX-Parameter.
- CPU-EP ist gesetzt; NNAPI/GPU sind weder Beschleunigungsversprechen noch Fallback.
- Loslassen beweist auf Android keinen unveränderten Cursor. Finale Korrektur braucht
  dieselben Besitzprüfungen wie jede andere Änderung.

### Konzept-Treue

KONZEPT.md ist verbindliche Erfahrungsbasis. Die Tabelle benennt Übernahmen und
bewusste Änderungen; Android-Sicherheitsgrenzen rechtfertigen keinen stillen
Wechsel des erprobten Streaming-Defaults.

| Mechanismus | Vorgabe KONZEPT.md | unsere Entscheidung | Begründung | Risiko |
|---|---|---|---|---|
| Push-to-Talk | Idempotent, halten/starten, loslassen/finalisieren | Übernommen; zusätzlich STARTING/STOPPING | Android-Routing/Service starten asynchron | Spätes UP vormerken |
| Determinismus | temperature=0 | Argmax ohne Sampling übernommen | ONNX-TDT hat keinen vorausgesetzten Temperaturparameter | Native Reproduzierbarkeit testen |
| LocalAgreement | Zwei Bestätigungen, volle Wortgrenze | Übernommen | Erprobter Halluzinations-/Flackerschutz | Erste Ausgabe verzögert |
| Committed-Präfix | Live unantastbar | Übernommen | Kein stabiles Textflackern | Frühe Fehler bleiben bis Final |
| Revision | max_revision_chars=60; sonst nur anhängen | 60 Unicode-Codepoints, Graphemgrenzen; bei Unsicherheit stoppen statt blind anhängen | Android-Cursor kann extern verschoben sein; Doppeltext vermeiden | Weniger automatische Korrekturen |
| Finale Transkription | Voll korrigierend, committed_len=0 | Vollständiger Whole-buffer-Finaldecode; eigene Spanne ganz revidierbar bei bewiesenem Besitz | UP beweist auf Android keine Cursorposition | Bei Besitzverlust explizite Übernahme |
| Finales Revisionslimit | force_revision für Vollkorrektur | Kein 60-Zeichen-Limit für sichere finale eigene Spanne | Finale Vollkorrektur nicht künstlich kappen | Große Änderungen nur bei sicherem Besitz |
| Tokenbudget | 12*s+64, min 256, max 4096 | Übernommen, gesamte Eingabe inklusive Kontext | Verhindert kontextbedingte Kappung | Limit: sichtbar fehlschlagen/retry |
| Erstes Partial | min_audio_s mindestens 1, besser 2,5–3 | **2,0 s** — gemessen (ASR_CONTRACT §4.4): 1,0 s halluziniert („Oh yes."), ab 2,0 s 0 Fehlfälle in 23 Proben; 3,0 s bringt keinen messbaren Vorteil. Kurze Finals trotzdem decodieren | Erste Ausgabe ~1 s früher als 3 s, ohne belegtes Halluzinationsrisiko | Kurze Finals halluzinationsanfällig |
| VAD | Nur kurze Reste unter 2 s bei False verwerfen | Kein VAD-Gate in Version 1; nichts wegen VAD verwerfen | Optionalen Filter weglassen verhindert Sprachverlust | Mehr Inferenz auf Stille |
| Stop | join → Queue drainen → neuester Rest | Semantisch übernommen; read entblockieren, join, Queue, letzter Read, EOS | AudioRecord.stop liefert keinen Restpuffer | Race-/Sequenztests erforderlich |
| Aufnahmelimit | 300 s | Übernommen | 9,6 MB Roh-PCM vertretbar | Tensor-/Inferenzbedarf größer |
| Streaming | whole_buffer; windowed gescheitert | Whole-buffer verbindlicher Default; Overlap nur deaktiviertes Experiment | Keine Android-Messung rechtfertigt den gescheiterten Default | Hoher CPU-/RAM-Bedarf bleibt Release-Risiko |
| Normalisierung | Keine nachträgliche Textnormalisierung | Übernommen; ASR-Ausgabe unverändert | Großschreibung/Interpunktion bewahren | Unicode-Vergleich muss exakt sein |
| Tests | Fakes ohne I/O; Pausen-WAV gegen Einzeldecode | Übernommen; zusätzlich Editor-/Routingtests | Desktopregression plus Android-Datenintegrität | Fakes belegen keine Modellqualität |
| Ausgabeweg | Desktop-Tastenemulation; Androidhinweise teils Accessibility | Ausschließlich aktive IME-InputConnection | TECH_FACTS korrigiert Desktopübertragung | Host-Editor kann unzuverlässig sein |
| Beschleunigung | NNAPI/GPU als Chance, Desktop 8 Threads | CPU-EP; Threadzahl messen | Produktvorgabe und fehlende Operatorgarantie | Echtzeitfähigkeit unbewiesen |
| Auto-Leerzeichen | append_space=true | Kein pauschales Endleerzeichen | IME schreibt zwischen vorhandenen App-Zeichen | Verhalten weicht vom Desktop ab |
| Barrierefreiheit | Kein Toggle | Optional explizit aktivierter Start/Stop-Modus nur als Bedienhilfe | Halten ist mit Screenreader/motorischer Einschränkung nicht immer möglich | Modus klar kennzeichnen; Default bleibt Halten |

## 1. Stand der Technik und Konsequenzen

### 1.1 Android-Tastaturen

| Tastatur | Layouts und Mehrsprachigkeit | Diktat / Offline-Einordnung | Konsequenz |
|---|---|---|---|
| Gboard | Breites Sprach-/Layoutangebot, sprachübergreifende Vorschläge, Sonderzeichen und Emoji | Spracheingabe und erweiterte On-Device-Funktionen hängen von Gerät, Sprache und Google-Komponenten ab; kein übertragbarer GrapheneOS-Baustein | Gute Mic-/Sprachwechsel-Ergonomie übernehmen, nicht dessen Dienstarchitektur |
| Microsoft SwiftKey | Mehrsprachige Vorhersage/Autokorrektur, viele Layouts, Personalisierung | Spracheingabe ist von verfügbaren Sprachdiensten und Produktstand abhängig; keine garantierte eigenständige Offline-ASR für unser Ziel | Layoutsprache und Erkennung nicht koppeln; keine Vorhersageinfrastruktur nachbauen |
| HeliBoard | AOSP/LatinIME-nahe vollständige Tastatur, mehrere Sprachen, Wörterbücher und Anpassungen | Offline-Tastatur ist nicht gleich Offline-Diktat; Spracheingabe kann einen externen Provider benötigen | Normale Tastatur muss ohne geladenes ASR-Modell voll benutzbar sein |
| FlorisBoard | Modularer, anpassbarer IME-Ansatz mit Sprach-/Layoutdaten; Funktionsumfang releaseabhängig | Kein belastbarer Nachweis einer integrierten Parakeet-Pipeline aus unseren Quellen | Layoutdaten und Editoraktionen klar vom ASR-Kern trennen |
| FUTO Keyboard / Voice Input | Tastatur und lokaler Voice-Input-Ansatz, Mehrsprachigkeit modell-/releaseabhängig | Beispiel für lokal ausgelieferte bzw. heruntergeladene Sprachmodelle; keine Gleichsetzung mit unserem Modell/Runtime-Vertrag | Modelleinrichtung, Downloadstatus und Offline-Vertrauen als eigenständige UX behandeln |
| AnySoftKeyboard | Erweiterbare Sprach-/Layoutpakete, Wörterbücher und klassische IME-Funktionen | Sprachinput gegebenenfalls über installierten Provider; kein vorausgesetzter eigener Offline-ASR-Stack | Datengetriebene Erweiterungen statt hardcodierter Sprachzweige |

Einordnung: „offline“ kann nur die Tasten-/Wörterbuchfunktion meinen. Ein vorhandenes
Mic-Symbol belegt weder lokale Erkennung noch Unabhängigkeit von Systemdiensten.
Produktdetails sind vor einem späteren Vergleich an konkreten Releases zu prüfen.
Als Ausgangspunkte dienen offizielle Produktdokumentation sowie die Projekte
`github.com/Helium314/HeliBoard`, `github.com/florisboard/florisboard`,
`github.com/AnySoftKeyboard/AnySoftKeyboard` und die FUTO-Produktdokumentation.
Diese Quellen sind Recherchehinweise, hier keine zusätzlich verifizierten Messdaten.

### 1.2 On-Device-ASR

| Ansatz | Technischer Stand / Grenze | Entscheidung |
|---|---|---|
| whisper.cpp | Native lokale Whisper-Inferenz, Quantisierung und Android-Integration; Live-Ausgabe häufig über wiederholte Audiofenster statt echtes zustandsbehaftetes Streaming | Vergleichsmaßstab für Offline-UX und WAV-Tests, kein zweiter Backendpfad |
| ONNX Runtime Android | AAR, explizite Tensor-/Sessionverwaltung, CPU-EP; Modellgraph entscheidet über verfügbare Optimierungen | Gewählter portabler Runtimepfad; drei Sessions für Mel, Encoder und Decoder/Joint |
| NNAPI | Hardware-/Treiber-/Operatorabhängig, auf neueren Android-Ständen kein zukunftssicherer Universalpfad | Nicht verwenden; keine automatische Providerwahl, kein CPU/NNAPI-Mischbetrieb |
| Parakeet-ONNX-Ports | Export von Encoder, Prediction/Joint und Vorverarbeitung ermöglicht native lokale Ausführung; Export allein ist noch kein Streaming-ASR | Den in TECH_FACTS belegten Export nutzen, Decodingvertrag zuerst prüfen |
| Native Streaming-Transducer | Bei passend trainiertem Modell Encoderzustand/Lookahead statt kompletter Neuberechnung | Unser Encoder ist nicht streamingfähig; keine erfundenen Cache-Eingänge oder verlustfreie Chunk-Gleichwertigkeit |

Konkreter Port: `istupakov/parakeet-tdt-0.6b-v3-onnx`, Artefakte ausschließlich
wie in TECH_FACTS. Andere Ports/Frameworks sind Referenzen, keine weiteren Abhängigkeiten.
Sprachautomatik entsteht aus dem multilingualen Modell, nicht aus einem behaupteten
ONNX-Parameter `language=auto`. Tastaturlayoutwechsel ändert ASR nicht automatisch.
Alle 15 gewünschten Sprachen müssen trotz multilingualer Modellwahl separat getestet werden.

## 2. Architektur und Lebenszyklen

### 2.1 Module und Zuständigkeiten

| Modul | Verantwortung / Grenze |
|---|---|
| `setup` | IME aktivieren/auswählen, Mikrofonfreigabe erklären und anfordern, Modell installieren, Audioquelle testen |
| `keyboard-ui` | Eigene View, Touch-/Pointerzustand, Accessibility, Layouts, Emoji, Shift und Editoraktionen |
| `layout-core` | JSON-Validierung, Ebenenauflösung, Deadkeys, sprachspezifisches Case-Mapping |
| `dictation-controller` | Zustandsautomat, Session-/Editorgeneration, Start/Stop/Abbruch und Deadlines |
| `capture-service` | ForegroundService für Mikrofon, AudioRecord, Routing und Audio-Zeitachse |
| `audio-core` | PCM-Blöcke, Resampling bei Bedarf, sequenzierte Ablage und Fensterplanung |
| `asr-core` | Mel-/Encoder-/Decoder-Sessions, Tensorvertrag, deterministisches TDT-Greedy |
| `commit-core` | Zeitnormalisierung, Overlapabgleich, LocalAgreement, Commit-Horizont |
| `editor-core` | DeltaTyper, InputConnection-Besitzprüfung, UTF-16-/Graphemgrenzen |
| `model-store` | Download/Resume, Prüfsummen, atomare Installation, Lizenzen und Kompatibilität |

Reine Kernmodule kennen weder Android-Views noch konkrete ORT-Sessions.
Schnittstellen sind eigene Abstraktionen, keine behaupteten Android-APIs:
`AudioSource → PcmBlock`, `AsrEngine(window) → Hypothesis`,
`EditorSink.apply(EditPlan, editorGeneration) → Result`.
Hypothesen enthalten Token-IDs, Text, globale Audiopositionen, Lauf-ID und Session-ID.

### 2.2 Service-Topologie und Berechtigungen

- InputMethodService und ein **gebundener plus gestarteter** Microphone-ForegroundService
  laufen im selben App-Prozess. Kein separater Inferenzprozess, kein Audio über Binder.
- IME besitzt die InputConnection; der Capture-Service erhält sie niemals.
- Der FGS lebt nur für Aufnahme/kurzes geordnetes Drain. Nach Freigabe des Mikrofons
  endet der Mikrofon-FGS; Finalinferenz bleibt ein prozessgebundener Job der sichtbaren IME.
- ORT-Modelle sind prozessweit geteilt, nicht an die Aufnahme gebunden. Nach Freigabe
  durch alle Nutzer bei Speicherdruck entladen; keine dauerhaft laufende Warmhalte-FGS.
- Manifest: `RECORD_AUDIO`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, Servicetyp `microphone` und `INTERNET` für Download.
  IME-Service mit systemgeschützter Bindung über `BIND_INPUT_METHOD` deklarieren.
- `POST_NOTIFICATIONS` ab API 33 im Setup erläutern/anfordern. Ablehnung verhindert
  nicht grundsätzlich einen FGS, ersetzt aber nicht die erforderliche Notification.
- Die Laufzeitfreigabe für Mikrofon erfolgt in einer sichtbaren Setup-Activity,
  nicht durch einen Dialog aus der Tastatur während des Haltens.
- Start des Mic-FGS nur aus bewusstem Nutzerereignis bei sichtbarer IME, kein Bootstart.
  Android-Startregeln und While-in-use-Berechtigung sind getrennte Prüfungen.
  Die systemgebundene sichtbare IME ist ein relevanter Sonderfall; dessen tatsächliche
  Zulässigkeit auf API 36/GrapheneOS wird als Implementierungsgate getestet.
- Bei verweigertem Start (z. B. SecurityException/ForegroundServiceStartNotAllowedException)
  bleibt die Tastatur verfügbar; Hinweis zum Öffnen der Einrichtung, kein unsicherer Retry.
  Falls dort nötig: sichtbare Activity zur expliziten Aktivierung verwenden, nicht heimlich aufnehmen.
- Der Service veröffentlicht seine Notification unverzüglich im vorgeschriebenen
  Startfenster, bevor Modelle geladen oder Audio initialisiert werden.
- Keine pauschale Batterieoptimierungs-Ausnahme; nur nach belegtem Geräteproblem
  optional erklären. Weder Accessibility noch allgemeiner Paket-Zugriff erforderlich.

### 2.3 Threading, Datenfluss und Rückstau

1. Main/UI: Touchereignisse, Controller-Actor und alle InputConnection-Operationen.
   Nur kurze Ereignisse; kein Audio-read, kein Tensoraufbau, kein Join auf Native-Code.
2. Eigener Capture-Thread mit blockierendem AudioRecord.read. Er schreibt monotone
   Blöcke mit Startsample, Sampleanzahl und Sequenznummer in eine begrenzte Queue.
3. Ein Audio-Consumer kopiert in die sitzungsbezogene PCM-Ablage und plant Snapshots.
4. Ein dedizierter serieller ASR-Worker verarbeitet Mel → Encoder → TDT-Decoder.
   Kotlin-Coroutines koordinieren; ORT darf intern CPU-Threads nutzen.
5. Unveränderliche Resultate gehen an den Controller; dieser verwirft veraltete
   Session-/Fenster-/Editorgenerationen und lässt Commit/Delta am Main-Thread anwenden.

Datenfluss: Mikrofon → PCM16 → normalisiertes Float-PCM → `nemo128.onnx`
→ Encoder → Prediction/Joint-Greedy → Zeit-/Textabgleich → LocalAgreement
→ DeltaTyper-EditPlan → `deleteSurroundingText` / `commitText` der aktuellen Verbindung.

Nur ein ASR-Lauf gleichzeitig; ausstehende Zwischenläufe werden auf den neuesten
Snapshot zusammengefasst. Finalauftrag ersetzt ausstehende Partials, nicht den
laufenden Native-Aufruf. Keine unbeschränkte Coroutine-/Audio-/Tensorqueue.
CPU-Threadzahl auf Gerät messen (Startkandidaten 2 und 4), nicht Desktop-8 übernehmen;
CPU-Heterogenität, UI-Reaktionszeit und thermisches Drosseln berücksichtigen.
Bei Capture-Queue-Überlauf Aufnahme mit Fehler beenden statt unbemerkt Samples zu verlieren.
Ein 300-s-Limit benötigt ungefähr 9,6 MB Roh-PCM; Float-/Mel-/Tensor-Kopien zusätzlich
budgetieren. Gesamtes Diktat nicht ständig als Float duplizieren.

### 2.4 Zustandsmaschine

Modellzustand separat: ABSENT → DOWNLOADING → VERIFYING → READY; Fehler → ABSENT/BROKEN.
Diktatzustand: IDLE → STARTING → RECORDING → STOPPING → FINALIZING → IDLE.
STARTING/RECORDING/STOPPING/FINALIZING können nach CANCELLING → IDLE wechseln.
Ein Fehler führt über geordnetes Cleanup nach IDLE mit sichtbarer Fehlerursache.

- DOWN ist idempotent, erzeugt eine Session-ID und merkt genau einen aktiven Pointer.
- UP während STARTING merkt Stop sofort vor: keine verspätete ungewollte Aufnahme.
- UP in RECORDING setzt Stopanforderung ohne auf laufende Inferenz zu warten.
- Pointerverlust, ACTION_CANCEL, Wisch-Abbruch und Notification-Abbruch nutzen
  dieselbe Cancel-Funktion. Doppeltes UP/Cancel ist wirkungslos.
- Weitere Mic-Starts während FINALIZING werden abgelehnt; Status bleibt sichtbar.
  Normale Tasten bleiben nutzbar, invalidieren aber vorher die ASR-Editierberechtigung.
- 300 s ab erstem Sample: automatisches Stop/Finalisieren mit deutlichem Hinweis.
- Editorwechsel, IME-Ausblenden und Prozess-/Serviceverlust stoppen Capture;
  keine nachträgliche Ausgabe in einen anderen Editor, kein automatisches Wiederanlaufen.

## 3. Audioaufnahme und Reihenfolge

AudioRecord erhält bevorzugt 16 kHz mono PCM16; Puffer mindestens anhand
`getMinBufferSize()` dimensionieren, Chunkstartwert 20–40 ms. Hardware-/Routenrate
prüfen: wenn 16 kHz nicht zuverlässig geliefert werden, bei unterstützter Rate aufnehmen
und mit getestetem zustandsbehaftetem Resampler auf 16 kHz normalisieren.
Das Modell bekommt Float-PCM mit passender Skalierung, nicht rohe PCM16-Bytewerte.

Routing ist Bestandteil der Aufnahme, nicht der Mic-Touch-Erkennung:
- Standardquelle eingebautes Mikrofon; verfügbare Eingabegeräte bei Bedarf auswählbar.
- Kabel-/USB-Headset und Bluetooth-Kommunikationsmikrofon unterstützen.
- Bluetooth-A2DP-Ausgabe ist kein Mikrofoneingang. Für BT-Kommunikationsrouten
  AudioManager-Kommunikationsgerätewahl verwenden (`setCommunicationDevice`),
  Verfügbarkeit und tatsächliches Routing beobachten, nicht bloß erfolgreiche Auswahl annehmen.
- AudioRecord-Gerätepräferenz (`setPreferredDevice`) ist eine Präferenz, keine Garantie.
  Ob Audioquelle und MODE_IN_COMMUNICATION benötigt werden, ist auf Zielgeräten zu testen;
  nur während eigener Session setzen und eigenen Audiomodus danach zurücksetzen.
- Kommunikationsroute vor Aufnahme bestätigen; STARTING zeigt „Mikrofon verbinden“.
  Nach erstem Sample sichtbare Quellenanzeige; bei anderer Quelle Nutzer informieren.
- `BLUETOOTH_CONNECT` nur für tatsächlich verwendete geschützte Bluetooth-Operationen
  anfordern; keine unnötige Scan-/Standortfreigabe. Verweigerung lässt das interne Mic nutzbar.
- Routewechsel, Headsetabzug, anderer Recorder/Anruf oder dauerhaft stumme Aufnahme:
  Session kontrolliert beenden, Fehler anzeigen, nicht still auf anderes Mic umschalten.

Stopprotokoll: Stopmarker setzen → AudioRecord.read kontrolliert beenden/entblockieren
→ Capture-Thread auslaufen lassen → alle älteren Queue-Blöcke nach Sequenz einsammeln
→ eventuell letzter erfolgreicher Read-Block → End-of-stream-Marker → Finalinferenz.
AudioRecord hat keinen allgemein nutzbaren `stop()`-Rückgabepuffer wie der Desktop-Wrapper.
Der konkrete unblock/stop-Ablauf muss Race-Tests bestehen; keine neuen Reads nach EOS.
PCM wird nur einmal je Sample verarbeitet. Mikrofongerät und Route sofort danach freigeben.

## 4. Modellinstallation und ONNX-Vertrag

### 4.1 Verbindliche Artefakte

Aus `istupakov/parakeet-tdt-0.6b-v3-onnx`:
`encoder-model.int8.onnx` (652,2 MB), `decoder_joint-model.int8.onnx` (18,2 MB),
`nemo128.onnx` (~0,1 MB), `vocab.txt` (~0,1 MB), zusammen rund 671 MB.
Speicherort: `filesDir/models/parakeet-tdt-0.6b-v3-int8/`.
Keine zusätzliche Modell-URL oder ORT-Versionsnummer wird hier angenommen.
Build-/Releasekonfiguration pinnt eine getestete ORT-Version und eine Modellrevision.

Setup zeigt Gesamtdownload, freien Speicher, WLAN-Empfehlung, Fortschritt und Abbruch.
HTTP-Resume muss Range-/ETag-Verhalten prüfen; bei geändertem Objekt neu beginnen.
Zunächst `.part` herunterladen, SHA-256 aller Dateien prüfen, Tensor-Smoke-Test,
komplette Version atomar aktivieren. Keine Mischung alter und neuer Graphen.
Prüfsummen und Revisionskennung müssen aus einem vertrauenswürdig freigegebenen,
mit der APK signierten Manifest kommen; TECH_FACTS enthält noch keine Hashwerte.
Ohne diese Releasevorarbeit kein Produktionsdownload mit behaupteter Integritätsprüfung.
Temporärdateien und eventuell alte Version beim Speicherbedarf mitrechnen.
Abbruch/Netzfehler beschädigen keine aktive Version; normale Tastatur bleibt nutzbar.
Lizenz-/Weiterverteilungsbedingungen von Modell, Vokabular und Runtime vor Release prüfen.

### 4.2 Tensorvertrag als obligatorisches Implementierungsgate

TECH_FACTS bestätigt Dateien, aber keine Tensornamen, Achsen oder blank-ID.
Vor Decoderimplementierung kleines Referenzprotokoll gegen den gepinnten Export erzeugen:
- ONNX-Inputs/-Outputs, Datentypen, dynamische Achsen und Längentensoren auslesen.
- Mel-Layout, Padding, Normalisierung und Encoder-Subsampling samt Frame-Zeit prüfen.
- Prediction-Eingabe (letztes Symbol/SOS), rekurrente Zustände und deren Initialisierung prüfen.
- Joint-Ausgänge identifizieren: getrennte Token-/Duration-Logits oder ein kombinierter
  Vektor mit explizit verifiziertem Split. Batch-/Singletonachsen nicht blind wegwerfen.
- Vokabular, blank-ID, Sondertokens, SentencePiece-Detokenisierung sowie die
  tatsächlich trainierten Durationwerte und deren Reihenfolge prüfen.
- Referenzdecode desselben WAVs in ursprünglicher Exportumgebung gegen Android vergleichen.

Adapter normalisiert auf `tokenScores[V]`, `durationScores[D]`, `nextPredictorState`.
Bei kombiniertem Ausgang erfolgt Split nur nach geprüftem Vertrag, nicht anhand
erratener Zahlen. Nicht passende Dimensionen/NaN/fehlendes Vokabular → Modellfehler.
Klassische RNNT-Joint-Ausgabe ohne Durationkopf reicht für dieses TDT-Design nicht.

## 5. TDT-Greedy, Fenster und Commit

### 5.1 Decoderregeln

Pro Fenster Encoder einmal über alle Mel-Frames rechnen; gültige Encoderlänge beachten.
Predictionzustand und letztes Label werden pro unabhängig decodiertem Fenster neu
initialisiert. Kein fortgeschriebener Decoderzustand über doppelt enthaltenen Overlap.

Für Encoderposition t und aktuellen Predictionzustand:
1. Prediction/Joint mit Encoderframe, letztem Label und Zustand auswerten.
2. Token-Argmax und Duration-Argmax deterministisch wählen; Gleichstände nach fester
   Indexregel. Logits benötigen für Argmax kein Softmax. Exakte Kopfinterpretation
   und gegebenenfalls exportierte Maskierung anhand des Referenzdecoders übernehmen.
3. Nicht-blank: Label ausgeben, Predictor entsprechend dem akzeptierten Label
   aktualisieren; Zeitpunkt t auf globale Samplezeit abbilden.
4. Blank: kein Label ausgeben, semantischen Label-/Predictionzustand nicht fortschreiben.
   Bei fusioniertem Graph sind zurückgegebene Zustände nur nach dessen verifiziertem
   Aufrufprotokoll zu übernehmen; blank darf kein neues gesprochenes Label erzeugen.
5. t um die gewählte **Duration in Encoderframes**, nicht um deren Klassenindex erhöhen.
   Nicht-blank mit Duration 0 erlaubt mehrere Labels am selben Frame.
6. Blank mit Duration 0 darf keine Endlosschleife erzeugen: Verhalten des
   Referenzdecoders (etwa Maskierung oder Mindestfortschritt 1) exakt reproduzieren.
   Bis geklärt ist dies ein blockierendes Vertragsgate, keine frei erfundene Modellregel.
7. Bei t außerhalb der gültigen Encoderlänge stoppen. Keine separaten Whisper-EOS-
   oder Temperaturregeln hinzufügen; exportierte Sondertokens korrekt ausfiltern.

Zusätzliche unabhängige Sicherheitsgrenzen: maximal zunächst 10 nicht fortschreitende
Emissionen pro Frame und ein globales Schrittlimit proportional zu Frames plus
Tokenbudget. Diese Startwerte sind zu validieren. Erreichen markiert den Lauf als
unvollständig/fehlerhaft; keine heimlich gekappte Hypothese als final behandeln.
TDT-Framezeiten sind Modellalignments, keine garantierten Wortgrenzen; Randtokens
bleiben deshalb innerhalb des Sicherheitskontexts uncommittet.

### 5.2 Budget und realistische Latenz

Startregel aus KONZEPT: `B(s) = max(256, min(4096, ceil(12*s)+64))` Tokens.
s ist die **gesamte** an den Encoder übergebene Fensterlänge inklusive Overlap.
Blank-Schritte separat begrenzen; sie verbrauchen kein Texttokenbudget.
Bei Budgettreffer einmal kontrolliert mit größerem Budget wiederholen; danach
sichtbarer Fehler statt stiller Textkappung. Fensterverkleinerung gehört nur zum
Chunk-Experiment. Das 4096-Limit bleibt Default auch für 300-s-Whole-buffer;
Sättigung niemals als vollständigen Erfolg ausgeben. Der größere Retry ist eine
explizite Ausnahme, keine stille Änderung der Defaultformel.

Startparameter: erste Teilhypothese nach 3 s Audio; neu planen alle 1 s neuen Audios.
LocalAgreement braucht zwei unterschiedliche Audiostände; derselbe Snapshot zählt nicht doppelt.
Zielbudget nach Warm-up: Mel/Encoder zusammen ≤650 ms, Decode ≤250 ms,
Abgleich/Edit ≤50 ms pro geplanter 1-s-Aktualisierung, Rest als Reserve.
Das ist ein **Messziel**, nicht die Behauptung, 0,6B passe auf jeder CPU in 950 ms.
Erster stabiler Text frühestens etwa 4–5 s nach Start, bei Last später;
UP→Final-Ziel ≤2 s bei kurzen Diktaten und warmem Modell; für lange Whole-buffer-
Diktate gibt es ohne Gerätemessung keine belastbare Final-Latenzzusage.
Kaltladen separat anzeigen. RTF, p50/p95, Peak-RSS, Temperatur und Akku auf realer
ARM64-Hardware messen; Desktopwerte und x86-Emulatorwerte nicht übertragen.
Bei Überlast nur Updatefrequenz reduzieren, nicht Audio verlieren. Bleibt Inferenz
langsamer als der Zufluss, ehrliche „Verarbeitung läuft“-Anzeige und Release-Risiko.

### 5.3 Whole-buffer-Default und experimentelle Chunk-Strategie

Verbindlicher Default ist Whole-buffer: jeder Lauf encodiert vollständiges Audio
[0, jetzt] neu, ohne Encoderzustands-Streaming; ebenso der Finaldecode nach UP.
Zwischenläufe bei Rückstau zusammenfassen; Agreement braucht zwei Audiostände.
Wachsende Rechenarbeit und lange Finalzeiten sind offene Release-Risiken, keine
Berechtigung zum stillen Wechsel auf das nachweislich problematische windowed.

Die folgende Chunk-Strategie ist ein **deaktiviertes Experiment**, kein Default.
Aktivierung erst nach belegter Android-Ressourcennotwendigkeit, bestandenen Pausen-/
Qualitätstests und aktualisierter Abweichungsentscheidung in Konzept-Treue.
Startwerte: Fenster W=16 s, Hop H=8 s, Overlap O=8 s; rechter Commitabstand R=2 s.
Diese Werte sind Hypothesen, nicht aus dem Modell abgeleitete optimale Parameter.

- Zu Beginn wächst [0, jetzt] bis 16 s; danach feste Epochen [8,24], [16,32] usw.
  In der neuen Epoche wächst das Ende wiederum bis zur Fenstergrenze.
- Fensterfortschritt folgt ausschließlich aufgenommenen Samples und einem
  Inferenz-Wasserstand, niemals `committed_s`, Wortzahl oder VAD-Ausgabe.
- Beim Übergang enthalten alte und neue Epoche 8 s identisches Audio. Neue Epoche
  zunächst ohne Linkskontext verwerfen? **Nein:** komplett decodieren und im Overlap
  anhand globaler Tokenzeiten plus Token-/Wortsequenzen zuordnen.
- Zweiteilige Übergabe: Alte letzte Hypothese und neue erste Hypothese auf gemeinsamen
  Audiobereich projizieren; nur eindeutig zugeordnete Sequenz als Anker verwenden.
  Tokens im Linkskontext nie pauschal ein zweites Mal ausgeben.
- Ein vorhandener Agreement-Cache bleibt für den gemeinsamen Audiobereich erhalten;
  nur außerhalb des Overlaps liegende Historie fällt weg. Fensterwechsel allein
  erzeugt weder einen neuen Commit noch einen zweiten Agreement-Treffer.
- Commitkandidat ist gemeinsamer vollständiger Wortpräfix zweier Hypothesen,
  zeitlich höchstens bis `Fensterende − R`, konsistent mit bereits committetem Text.
  Commit-Horizont begrenzt Berechtigung, erzwingt aber keinen Commit bei Unsicherheit.
- Lange Pausen lassen den Audio-/Fensterwasserstand fortschreiten. Sie dürfen den
  Textcommit unverändert lassen; spätere Sprache muss trotzdem verarbeitet werden.
- Keine stabile Übergabe vor Auslaufen des Overlaps: betroffenen Text uncommittet
  halten und betroffene Audiospanne für Reparatur merken. Bei Loslassen diese Spanne
  in größeren Referenzfenstern erneut decodieren und eindeutig abgleichen.
- Bleibt die Zuordnung mehrdeutig oder überschreitet die Reparatur das Gerätebudget,
  keine ungesicherte Löschung/kein blindes Zusammenkleben: automatisches Editieren
  stoppen, erhaltenen Text stehen lassen, restliches Ergebnis zur expliziten Übernahme
  in der IME anzeigen. Dieser Fall ist ein Qualitätsfehler, kein normaler Erfolgsweg.

Begrenzte Reparaturläufe und sequenzieller Wasserstand verhindern die im Desktop
beobachteten Commit-/Pause-/Agreement-Deadlocks. Ob Fensterdekodierung genügende
Qualität erreicht, bleibt ein hohes Risiko; Freigabe nur nach den Tests in Abschnitt 10.

### 5.4 LocalAgreement und Finalisierung

Zuerst Tokenstücke korrekt nach Modellvertrag detokenisieren.
Keine nachträgliche Normalisierung oder Regex-Großschreibungsreparatur.
ASR-Text exakt vergleichen, Präfixgrenzen auf Grapheme ausrichten; keine NFC-,
Case- oder Interpunktionsnormalisierung des Transkripts durchführen.
Wortgrenzen sprachsensitiv ermitteln; nie ein angebrochenes Wort committen.
Zwei kompatible Hypothesen stabilisieren nur den gemeinsamen Präfix hinter dem
bereits geschützten Präfix. Widerspruch zum geschützten Präfix → keinen Commit erzwingen.
Partials dürfen als begrenzter revidierbarer Tail schon in der Ziel-App erscheinen.

Nach EOS läuft im Default ein deterministischer Finaldecode des gesamten Audios;
nur im deaktivierten Chunk-Experiment wären letzte Epoche und Reparaturspannen relevant.
Finalisierung setzt den Commit-Schutz für die eigene Sitzung auf null und darf
sämtlichen eigenen Text korrigieren, **nur** bei weiterhin verifizierter Besitzspanne.
Das Live-Limit von 60 Zeichen begrenzt diese sichere finale Vollkorrektur nicht.
Andernfalls geschützten Text erhalten und Ergebnis explizit anbieten. LocalAgreement
ist Stabilität, keine Gewähr für Erkennungsrichtigkeit.

## 6. Sichere Textausgabe: DeltaTyper für Android

InputConnection ist ein kurzlebiger Editorzugang, kein transaktionaler Dokumenteditor.
Besitzprotokoll je Session: Editorgeneration, Startselektion, erwartete Cursorposition,
eingefügte Spanne, erwarteter Suffix, ausstehende eigene Selektions-Callbacks.
Keine Annahme, dass Java-Stringlängen Zeichen oder Codepoints entsprechen.

- Bei Mic-Start nichtleere Selektion: ersten ASR-Text **hinter** der Selektion einfügen,
  sofern Cursorumsetzung bestätigt wird; ursprünglichen markierten Nutzertext nicht
  automatisch ersetzen. Bei fehlender Unterstützung Diktat dort ablehnen/erklären.
- Vor jeder Revision `onUpdateSelection` und begrenzten `getTextBeforeCursor`-Abgleich
  auswerten. Eigene erwartete Callbacks separat erkennen. Null, gekürzte Antwort,
  unerwartete Selektion oder Editorwechsel bedeuten „Besitz nicht bewiesen“.
- `beginBatchEdit` / `endBatchEdit` gruppieren Anzeige, sind keine atomare CAS-Operation.
  Direkt vor Löschung erneut prüfen; bei Unsicherheit nicht schreiben.
- Delta = längster gemeinsamer Präfix von altem und neuem Tail nach geschütztem Commit;
  auf Graphemgrenze zurücksetzen, danach nur eigenen Suffix löschen und neuen ergänzen.
- `deleteSurroundingText(beforeLength, 0)` zählt UTF-16-Codeeinheiten. Löschgrenzen
  an Graphemclustern ausrichten, keine Surrogatpaare, Akzente oder Emoji-ZWJ zerlegen.
- `commitText(delta, 1)` hält Cursor hinter neuem Text; danach Erwartungszustand prüfen.
  Rückgabewerte auswerten. Scheitert der zweite Schritt nach erfolgreicher Löschung,
  nicht blind wiederholen: Zustand neu lesen, Fehler anzeigen, keine fremden Daten ändern.
- Revisionsgrenze: live maximal 60 zurückzunehmende Unicode-Codepoints;
  Graphemgrenzen zusätzlich beachten. Die Grenze betrifft Löschumfang, nicht Taillänge.
  Überschreitung verwirft den Editplan, statt wie auf dem Desktop einfach doppelt
  anzuhängen. Append-only nur bei erneut bewiesenem Cursor direkt hinter eigenem Text.
- Manuelles Tippen, Emoji, Shift-unabhängige Editoraktion, Cursorbewegung oder Appwechsel
  beendet ASR-Besitz vor der Aktion. Späte Resultate niemals darüber hinweg schreiben.
- Kein `setText`, keine Zwischenablage als Injektionsweg, kein AccessibilityService.
  Kein Composing-Span für ASR; erwartbares Verhalten auch bei einfachen Editoren.
- Bei Abbruch nur den noch revidierbaren Tail entfernen, wenn sicher nachweisbar;
  bereits stabiler Text bleibt. Vollständiges „Diktat rückgängig“ ist eigene bestätigte
  Aktion und nur bei noch intakter Besitzspanne möglich.

PIN-/Passwortfelder: Mic deaktiviert, kein Kontextlesen, keine Textlogs; numerische
und telefonische Editorarten bekommen geeignete Layouts. Ziel-App kann Inhalte selbst
transformieren oder unzureichende InputConnection implementieren: dann Revision abschalten,
bei unklarer Position auch Appends stoppen. Fremden Text nicht zugunsten von Live-UX riskieren.

## 7. Vollständige Tastatur und Layoutmodell

### 7.1 Bedienumfang und Autokorrekturentscheidung

**Keine Autokorrektur und keine lernenden Wörterbücher in Version 1.** Damit bleiben
15 Sprachen ohne Wörterbuchdownloads vollständig tippbar und ASR-Revisionen kollidieren
nicht mit einer zweiten Textkorrektur. Keine stillen Ersetzungen, keine Auto-Punkt-Regel.
Manuelles Shift, einmaliges Shift bis nächstem Buchstaben, Doppeltipp-CapsLock,
Backspace mit Repeat, Space, Enter bzw. Editoraktion, Ziffern und zwei Symbolebenen.
Optionaler Auto-Shift bleibt zunächst aus; Status von Shift/CapsLock immer sichtbar.
CapsLock betrifft Buchstaben, nicht die Ziffern-/Satzzeichenebene. Türkisches i/ı und
mehrzeichenfähiges Case-Mapping explizit testen, nicht nur Unicode-Uppercase pro Taste.
Cursorlinks/-rechts und Space-Wischen, lange Delete-Wiederholung, Layoutumschalter,
Emoji-Panel mit Kategorien/Suche aus lokalen Daten und Favoriten; keine Sticker-/GIF-Suche.
Emoji als vollständige Sequenzen committen, Darstellung/Fontabdeckung auf Android prüfen.
Editoraktionen anhand EditorInfo statt pauschalem Zeilenumbruch; mehrzeilige Felder beachten.

### 7.2 JSON-Schema: normativer Vertragsauszug

Das vollständige Schema wird bei Implementierung als Asset versioniert. Dieser kurze
Auszug zeigt die Struktur; nachfolgende Invarianten sind ebenfalls verbindlich.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["schemaVersion", "id", "language", "layers", "deadKeys"],
  "properties": {
    "schemaVersion": {"const": 1},
    "id": {"type": "string", "pattern": "^[a-z0-9-]+$"},
    "language": {
      "type": "object",
      "required": ["tag", "label", "caseLocale", "direction"],
      "properties": {
        "tag": {"type": "string"}, "label": {"type": "string"},
        "caseLocale": {"type": "string"}, "direction": {"enum": ["ltr", "rtl"]}
      },
      "additionalProperties": false
    },
    "layers": {
      "type": "object",
      "required": ["base", "shift", "symbols", "symbolsShift"],
      "additionalProperties": {"$ref": "#/$defs/layer"}
    },
    "deadKeys": {"type": "object"}
  },
  "$defs": {
    "layer": {
      "type": "object", "required": ["rows"],
      "properties": {
        "rows": {"type": "array", "minItems": 1,
          "items": {"type": "array", "minItems": 1,
            "items": {"$ref": "#/$defs/key"}}}
      }, "additionalProperties": false
    },
    "key": {
      "type": "object", "required": ["id", "label", "width", "kind", "value"],
      "properties": {
        "id": {"type": "string"}, "label": {"type": "string"},
        "width": {"type": "number", "exclusiveMinimum": 0},
        "kind": {"enum": ["text", "action", "dead"]},
        "value": {"type": "string"},
        "caps": {"type": "string"},
        "alternates": {"type": "array", "items": {"type": "string"}}
      }, "additionalProperties": false
    }
  },
  "additionalProperties": false
}
```

Weitere Schema-/Semantikregeln: `tag`/`caseLocale` sind gültige BCP-47-Kennungen;
`rows` beschreibt alle sichtbaren Tasten einschließlich Funktionstasten, Breiten sind
relative Einheiten. Buchstabentasten haben explizites Shift-Gegenstück und bei Bedarf
`caps`; keine stillschweigende QWERTY-Ergänzung. IDs sind pro Ebene eindeutig, gleiche
physische Tasten behalten über Ebenen ihre ID. Zeilensummen werden validiert.
`action.value` kommt aus Allowlist (shift, caps, layer-symbols, layer-base, backspace,
space, enter, language, emoji, mic, cursor-left, cursor-right); kein ausführbarer Code.
`dead.value` referenziert einen Eintrag in `deadKeys` mit `spacing`, optionalem
`combining` und expliziter `compose`-Tabelle von Basissequenz auf Ausgabesequenz.
Deadkey+Space gibt spacing aus; unbekannte Folge gibt spacing plus Original aus;
Backspace löscht zuerst ausstehenden Deadkey, Layoutwechsel verwirft ihn sichtbar.
NFC nur innerhalb neuer Komposition, nicht über bestehenden App-Text anwenden.

### 7.3 Fünfzehn vollständige Startlayouts

Jedes Asset enthält base/shift, Ziffern, Symbole, lokale Satzzeichen, Akzentalternativen
und alle Funktionstasten; keine Sprache wird nur durch ihr Flaggenlabel repräsentiert.
Die folgenden Varianten sind bewusste Produktentscheidungen, keine Behauptung eines
einzigen national verbindlichen Mobile-Standards. Andere Varianten später als eigene IDs.

| Sprache / Kennung | Primärlayout | Vollständigkeit / besondere Daten |
|---|---|---|
| DE / de-DE | QWERTZ | ä ö ü ß, Großformen, Akzentalternativen |
| EN / en-US | QWERTY | US-Zeichenbelegung, Apostroph und englische Satzzeichen |
| FR / fr-FR | AZERTY | é è ê ë à â ç ù û ü œ æ, Ziffern explizit auf Symbolebene |
| ES / es-ES | QWERTY | ñ, Akut/Vokalvarianten, ü, ¿ ¡ |
| IT / it-IT | QWERTY | à è é ì ò ó ù und Shiftformen |
| PT / pt-PT | QWERTY | ç ã õ á à â é ê í ó ô ú und Großformen |
| NL / nl-NL | QWERTY | ë ï é è ê ö ü, lokale Anführungszeichen |
| PL / pl-PL | QWERTY | ą ć ę ł ń ó ś ź ż jeweils direkt/langdruck erreichbar |
| TR / tr-TR | Türkisch Q | ğ ü ş ı ö ç, i→İ und ı→I explizit |
| RU / ru-RU | ЙЦУКЕН | alle kyrillischen Buchstaben einschließlich ё/Ё |
| CS / cs-CZ | QWERTZ | ě š č ř ž ý á í é ú ů ď ť ň und Großformen |
| SV / sv-SE | QWERTY | å ä ö, Akzent-/Anführungsalternativen |
| DA / da-DK | QWERTY | æ ø å und Großformen |
| FI / fi-FI | QWERTY | ä ö å, š ž als Alternativen |
| EL / el-GR | Griechisch | vollständiges Alphabet, finales ς/Σ, Tonos und Dialytika einschließlich ΐ/ΰ |

Aktivierte Layouts per Sprachmenü und Space-Langdruck wechseln, Favoritenreihenfolge
lokal speichern. Android-IME-Subtypes dienen Systemintegration und Sprachmetadaten;
importierte JSONs funktionieren zusätzlich im internen Umschalter, ohne Manifest-Neubau.
Import über System-Dateiauswahl, Kopie in privaten Speicher; keine Storage-Vollfreigabe.
Version, Größe, Zeilenzahl, Stringlängen, Referenzen und Aktionsallowlist prüfen.
Unbekannte Schemahauptversion ablehnen, defektes Layout isolieren, eingebautes DE/EN
als Rückfall erhalten. Keine Skripte, Netz-URLs, Fonts oder Plugins im Layoutformat.

## 8. UX und Datenschutz

- Mic ist dauerhaft auffindbar, hat getrennte große Touchfläche und Beschriftung
  für Screenreader. STARTING zeigt Laden/Quellenwahl; RECORDING zeigt Zeit, Quelle,
  klaren Aufnahmeindikator und „Loslassen zum Abschließen“. Keine Aufnahme ohne Anzeige.
- Aufnahme beginnt erst nach gültiger Freigabe/Route; Haptik für tatsächlichen Start,
  Loslassen und Abbruch optional. UI verwechselt „Finger unten“ nicht mit „Audio aktiv“.
- Nach links aus der Mic-Zone wischen (Startwert 64 dp) markiert „Abbrechen“;
  Überschreiten bestätigt Abbruch, Rückkehr vor UP darf die Markierung aufheben.
  Pointercapture/-verlust wird behandelt; keine Aufnahme bleibt nach ACTION_CANCEL hängen.
- FINALIZING zeigt „Verarbeite lokal“ und Abbruch, kein irreführendes Aufnahme-Icon.
- Screenreader-/motorische Alternative: ausdrücklich aktivierter barrierefreier
  Start/Stop-Modus mit denselben Zeitlimits; Default bleibt ausschließlich Halten.
- Ohne Modell: Mic öffnet Einrichtung statt Download im Hintergrund zu verstecken.
  Ohne Berechtigung: Erklärung + Setup öffnen; abgelehnt blockiert nur Spracheingabe.
  Kein Mikrofon/Routefehler: Quellenwahl/erneut versuchen, keine leere Erfolgsmeldung.
- Unter 3 s Audio: nach UP trotzdem einen Finalversuch machen, kurze Sprache nicht
  durch pauschales VAD verwerfen; Hinweis bei nicht sicher erkennbarer Eingabe.
- Kein optionales VAD in Version 1 als harte Schranke. Ein einfacher Pegelindikator
  ist keine Spracherkennung und darf nie allein echte Sprache löschen.
- Kein erzwungenes Leerzeichen nach jedem Diktat. Abstand nur am bewiesenen eigenen
  Rand und nach festgelegten Satzzeichenregeln, nie vorhandenen Text normalisieren.
- Datenschutzseite: „Audio und Text bleiben auf diesem Gerät; Netzwerk nur für
  Modelldownload“. Kein Analytics, Crashtext, Cloud-Fallback oder Kontozwang.
- PCM/Hypothesen nur im RAM, nach Sitzung referenzfrei freigeben; keine Zusage sicherer
  physischer RAM-Löschung in JVM/Native-Runtime. Keine Audiodumps im Produktbetrieb.
- Debug-WAV/Transkripte nur nach ausdrücklicher separater Zustimmung, klarer Löschfrist,
  privatem Speicher und bewusstem Export; niemals Passwortfelder erfassen.
- GrapheneOS-Netzwerkzugriff nach Download kann der Nutzer entziehen; Offlinefunktion
  einschließlich App-Neustart muss dann unverändert funktionieren. Androids INTERNET-
  Manifestrecht ist kein zur Laufzeit abschaltbarer Download-Schalter; appinterner
  Netzwerk-Aus-Schalter verhindert zusätzlich sämtliche neuen Downloadrequests.

## 9. Failure-Modes und Gegenmaßnahmen

| Fehler | Gegenmaßnahme / sichere Reaktion |
|---|---|
| UP während blockierender Inferenz | UI-Controller und Capture unabhängig; sofortiger Stopmarker |
| Doppeltes DOWN/UP, verwaister Pointer | Idempotenz, aktive Pointer-ID, CANCEL und Zeitlimit |
| Audiozeitachse verdreht / doppelte Schlussbytes | Sequenznummern, EOS, Drain vor Final, monotone Sampleprüfung |
| Queueüberlauf / Read-Fehler / Prozessverlust | Explizit abbrechen, Ressourcen schließen, kein stilles Fortsetzen |
| Kurzer Chunk halluziniert | Erster Partial ab 3 s, zwei Agreements, kurzer Final nicht pauschal verwerfen |
| Lange Pause blockiert Fenster | Audio-Wasserstand unabhängig von Commit/VAD; Pause-Regressionstest |
| Agreement-Cache bei jedem Shift gelöscht | Overlap-Historie erhalten, nach Audiostand statt Commitgeneration zählen |
| Doppelte Wörter / Satzteilverlust an Fenstergrenze | Zeit-/Sequenzanker, konservativer Commitabstand, Reparatur oder explizite Übernahme |
| Fixes Tokenlimit kappt Text | Budget mit voller Fensterlänge, Sättigung erkennbar, Retry/Fehler |
| TDT hängt bei Duration 0 | Referenzregel plus Schritte-/Emissionenlimit; Lauf niemals still abschneiden |
| Blank/Duration falsch interpretiert | Gepinnter Tensorvertrag und Referenzdecodervergleich als Gate |
| Shift/CapsLock verfälscht ASR | Text direkt committen; Tastaturmodifier gelten nur für manuelles Tippen |
| Cursor verschoben / Nutzer tippt | Editorgeneration/Besitz invalidieren, keine weitere automatische Revision |
| Emoji/Diakritika halb gelöscht | Graphemgrenzen plus UTF-16-Längenumrechnung |
| InputConnection null, App ändert Text | Keine Schreibversuche ohne Besitz; Teilfehler ohne blindes Retry |
| Finalkorrektur löscht stabilen/fremden Text | Gesamte eigene Spanne nur bei bewiesenem Besitz revidieren; sonst explizit anbieten |
| Download abgebrochen / Platte voll / Hash falsch | Temporärdatei, Resume-Prüfung, aktive Version unverändert, Platzprüfung |
| Graph passt nicht / ORT-Fehler / Native-OOM | Vorab-Smoke-Test, Sessions schließen, Tastatur weiter nutzbar; kein Retry-Sturm |
| Mikrofonrecht/Privacy-Schalter entzogen | Capture stoppen, verständlicher Hinweis, kein Umgehen des Systemschalters |
| FGS-Start verboten / Benachrichtigung abgelehnt | Startfehler explizit behandeln, sichtbare Einrichtung; Notificationpflicht bleibt |
| BT-Routing dauert / Headset getrennt / Anruf | STARTING mit Timeout, tatsächliche Route prüfen, kontrollierter Stop |
| Screen off / IME verborgen / Editor gewechselt | Aufnahme sofort beenden, fremde Editorupdates verwerfen |
| CPU thermisch gedrosselt / Akku-/RAM-Druck | Partials zusammenfassen, Updates seltener, Memory trim; keine falsche Echtzeitzusage |
| JSON-/Deadkey-/Case-Fehler | Importvalidierung, Referenztests je Sprache, eingebautes Fallback |
| Leeres/stilles Audio | Kein Dummytext, Ergebnis leer zulässig; keine lange Restaufnahme nur wegen VAD verwerfen |

## 10. Teststrategie und Freigabegates

### 10.1 Unit-Tests ohne Android, Mikrofon oder großes Modell

- FakeAudioSource liefert PCM mit Sequenzen, Pausen, verspäteten Reads und Fehlern;
  virtuelle Uhr steuert DOWN/UP/Timeout, kein Echtzeitschlafen.
- FakeAsrEngine liefert Hypothesen nach Fenster-/Audiostand, inklusive widersprechender
  Präfixe, Zeitdrift, Overlap-Duplikaten, Fehlern und Budgetüberschreitung.
- FakeEditorSink bildet Text, Selektion, UTF-16-Längen, externe Änderungen und einzelne
  fehlschlagende Calls ab; Eigentum und „kein fremdes Löschen“ als Invarianten prüfen.
- LocalAgreement: erstes Agreement, Wortende, Satzzeichen, Unicode, stabile Präfixe,
  kurze und 300-s-Aufnahmen, Fensterwechsel ohne Historienverlust.
- TDT über Fake-Joint: getrennte/kombinierte Köpfe, blank, Duration-Index ≠ Wert,
  Duration 0, Zustand nur bei korrekten Labelübergängen, ungültige Längen/NaN.
- Layoutschema und alle 15 Assets: vollständige Ebenen, Buchstabenabdeckung,
  Shift/Caps, TR-i, EL-Diakritika, Deadkey-Fallback, Emoji-Grapheme.
- Scheduler-Eigenschaften: Wasserstand schreitet in Stille fort, maximal ein Lauf,
  obsolete Ergebnisse unwirksam, Shutdown liefert Samples exakt einmal in Reihenfolge.

### 10.2 Instrumented-/Emulatortests auf API 36

Headless-AVD mit den in TECH_FACTS belegten SDK-/Emulatorpfaden; Test-IME aktivieren.
Test-Host-App mit ein-/mehrzeiligem EditText, Passwort, Nummer, Selektion, Unicode,
Editoraktionen und bewusst eingeschränkter/transformierender InputConnection.
UI-Tests tippen alle Ebenen, wechseln Layout, halten Mic, wischen ab und bewegen
Cursor während Fake-Inferenz. adb-Texteingabe allein beweist die IME nicht.
Fake-Audio-/ASR-/Download-Backends ausschließlich im Test-/Debug-Build injizieren;
Produktions-APK hat keine von außen aktivierbare WAV-/Textinjektionsschnittstelle.
ForegroundService, Permissions, Notificationverweigerung, Activity-/IME-Lifecycle,
Prozessabbruch und Wiederstart testen. Emulator kann echtes BT-/Thermalverhalten nicht belegen.

ORT-Integration ohne 671 MB: winzige selbst erzeugte ONNX-Testgraphen prüfen
Tensor-Lebensdauer, Datentypen und Fehlerpfade. Sie belegen nicht Parakeet-Qualität.
Fake-Downloadserver liefert kleine Dateien, Range, ETag-Wechsel, Verbindungsabbruch,
falsche Prüfsumme und Speicherknappheit. CI bleibt standardmäßig modellfrei/offline.

### 10.3 WAV-Injektion und echter Modellpfad

Separater opt-in Testjob nutzt einmal lokal gecachte, hashgeprüfte Originalartefakte;
kein automatischer Großdownload pro Testlauf. WAV-Dateien direkt an AudioSource
injizieren: PCM16/16 kHz mono sowie Raten-/Resamplerfälle mit bekannter Referenz.
Ein Fixture mit 4 s Pause reproduziert den Desktop-Fensterfehler; Referenzdecode
über das ganze kurze WAV gegen zusammengesetzte Fenster vergleichen, nicht nur Zeichenzahl.
Weitere Fälle: 0,3/1/3 s, 16-s-Fenstergrenzen, 300 s, lange Stille, Zahlwörter,
Code-Switching, alle 15 Sprachen, Wiederholungen und möglichst gleiche Wörter im Overlap.
WER/CER, ausgelassene/doppelte Wörter und Final-/Live-Latenzen getrennt berichten.
Fixture-Lizenzen und Einwilligung dokumentieren; keine Nutzeraudios in CI sammeln.

### 10.4 Releasegates auf echtem GrapheneOS-Gerät

- APK installieren, IME aktivieren, Modellsetup abschließen, Netzwerk entziehen,
  App/Telefon neu starten: Tastatur und ASR weiter offline nutzbar.
- Interne, kabelgebundene/USB- und Bluetooth-Mikrofone mit echter Route nachweisen;
  Hardwarekombinationen dokumentieren, kein „Bluetooth unterstützt“ nur aus Emulator ableiten.
- Sichtbare IME darf Mic-FGS legal starten; Loslassen, Privacy-Schalter, Anruf,
  Screen-off und Appwechsel geben Mikrofon frei, keine heimliche Nachaufnahme.
- Kein fremder Text wird in Cursor-/Lifecycle-Stresstests gelöscht; Nulltoleranz.
- Pausen-/Overlapfixtures verlieren/duplizieren keinen Referenzsatzteil; mehrdeutige
  Übergänge lösen den sicheren Fehlerpfad aus, keinen stillen Erfolg.
- Peak-RSS, Modellladezeit, warme p95-Latenzen, 5-min-Thermaltest und Akku messen.
  Falls das Gerät die Live-Ziele nicht erreicht, Risiko offen halten statt Werte erfinden.
- Release enthält echte signierte Modellhashes, geprüfte Tensorverträge und Lizenzen.

## 11. Nicht-Ziele und offene Risiken

Nicht-Ziele: Cloud-ASR, weitere Modelle/Backends, NNAPI/GPU, Always-on/Wakeword,
Audioaufnahme im Hintergrund ohne sichtbare Sitzung, Hardware-PTT-/MediaButton-Support,
Beam Search, Übersetzung, Sprechertrennung, lernende Autokorrektur, Swipe-Typing,
Clipboard-Synchronisation, GIFs, Plugins und komplexer Theme-Editor.
Bluetooth-Mikrofon ist Pflicht; Fernsteuerung über dessen Hardwaretaste ist es nicht.
Kein produktionsseitiger dauerhaft gespeicherter Audiorekorder und keine automatische
Wiederherstellung eines unterbrochenen Diktats nach Prozessverlust.

| Risiko | Schweregrad | Klärung / Freigabekriterium |
|---|---|---|
| CPU/RAM reichen für 0,6B und Live-Partials nicht | Hoch | ARM64-Profiler, Peak-RSS/5-min-Test; Pflichtmodell nicht still austauschen |
| Whole-buffer über 300 s überlastet CPU/RAM | Hoch | Gerätemessung; Default nicht ohne dokumentierte Freigabe ändern |
| Experimentelle Chunkqualität weicht vom Whole-buffer ab | Hoch | Deaktiviert lassen bis Pausen-/Grenzfallkorpus und Reparaturpfad bestanden |
| ONNX-TDT-Vertrag noch nicht dokumentiert | Blockierend | Graph-/Referenzdecode-Protokoll vor Decoderbau |
| Vertrauenswürdige Hashes/Revision/Lizenz fehlen | Blockierend für Release | Freigegebenes Manifest und Lizenzprüfung |
| Mikrofon-FGS-Start aus sichtbarer IME auf API 36 | Hoch | Früher Instrumented-/GrapheneOS-Spike, sichtbarer Setup-Fallback |
| Ziel-App liefert unzuverlässige InputConnection | Hoch | Konservative Besitzprüfung, automatische Ausgabe bei Unsicherheit stoppen |
| Bluetooth-Routen variieren stark | Hoch | Reale Geräte/Headsets testen, Zeitlimit und Quellenanzeige |
| 15 Layouts sprachlich unvollständig | Mittel | Muttersprachliche Abnahme plus automatisierte Zeichenabdeckung |
| 671-MB-Setup scheitert an Netz/Platz | Mittel | Resume, Platzprüfung, Tastatur unabhängig nutzbar |
| Kurze unvollständige Sätze halluzinieren | Mittel bis hoch | Mindestkontext, Agreement, explizit leer/unsicher statt erfundenem Text |

Empfohlene Umsetzungsschritte: zuerst Tensor-/FGS-/Performance-Spikes, danach
modellfreie Tastatur und sichere Editorlogik, dann WAV-Pipeline/LocalAgreement,
zuletzt echtes Capture/Routing und Geräteabnahme. Keine riskante Chunkheuristik
hinter schöner Live-Animation verstecken; Datenintegrität hat Vorrang vor Latenz.
