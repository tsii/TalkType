# D10 – große Mic-Taste unten rechts und verständlicher Globus

## 0. Entscheidung und Zuständigkeit

D10 ergänzt D9; **der zuvor zurückgestellte Mic-Positionsumbau ist jetzt ausdrücklich
freigegeben**. Alle heutigen Aktionen/Zeichen bleiben erhalten, Status-/Diagnosezeile
und 🐞 bleiben unverändert oben. Kein ASR-Backend-, VAD-, Sprachmodell- oder
Commit-Algorithmus-Umbau in diesem Auftrag.

Übergebene Evidenz: 306 Unit-Tests grün, bei 650 px alle 38 statt 30 IDs und Skalierung
0,873. Dies wird als Fortschritt der parallelen S1/S2-Arbeit anerkannt, nicht hier
neu getestet. Gelesen wurden D9, aktuelle KeyboardHeight/KeyboardModel, Service-
Verdrahtung und KONZEPTs PTT-/Entkopplungsregeln; keine Builds/adb/Codeänderungen.

„Yeah.“, ausbleibende englische Ausgabe und häufig ignorierte Eingaben sind reale
Nutzerberichte, aber **keine durch ein besseres Icon behobenen ASR-Fehler**. RTF 0,80,
first_hyp_ms 1116 und Peak 2 % beweisen weder sichere Live-Ausgabe noch ausreichenden
Mikrofonpegel noch eine falsche Spracheinstellung. Ursachen und Qualitätsschranken
bleiben beim parallelen Analyseauftrag; Layoutsprache steuert die ASR-Sprache nicht.

## 1. Reflow: Enter rückt vor, Mic bekommt den rechten Rand

**Verbindlicher Footer:** `?123/ABC · 🌐 · 😊 · Space · ‹ · › · Enter · Mic`.
Enter räumt **die äußerste rechte Position**, bleibt aber als vorletzte Taste erhalten;
es wird nicht nach oben verschoben und keine Buchstabentaste verdrängt. Mic wandert
von vor Enter nach ganz rechts. Space gibt **eine Gewichtseinheit** ab, damit Mic
von Gewicht 1 auf **2** wächst; Space 5→**4**, Gesamtsumme weiterhin **13**.

| Taste | Gewicht | Anteil | Bei W=1080 px / 411,43 dp |
|---|---:|---:|---:|
| ?123 bzw. ABC | 1,5 | 11,538 % | 124,6 px / 47,47 dp |
| 🌐 | 1 | 7,692 % | 83,1 px / 31,65 dp |
| 😊 | 1 | 7,692 % | 83,1 px / 31,65 dp |
| Space, lokalisiertes Label | 4 | 30,769 % | 332,3 px / 126,59 dp |
| ‹ | 1 | 7,692 % | 83,1 px / 31,65 dp |
| › | 1 | 7,692 % | 83,1 px / 31,65 dp |
| Enter | 1,5 | 11,538 % | 124,6 px / 47,47 dp |
| **Mic** | **2** | **15,385 %** | **166,2 px / 63,30 dp** |

Raster DE, vier Reihen: `[?123 11,54%][🌐 7,69%][😊 7,69%][Space 30,77%][‹ 7,69%][› 7,69%][⏎ 11,54%][Mic 15,38%]`
Raster PT, fünf Reihen: `[?123 11,54%][🌐 7,69%][😊 7,69%][Space 30,77%][‹ 7,69%][› 7,69%][⏎ 11,54%][Mic 15,38%]`
In symbols/symbolsShift steht an erster Stelle ABC. Die volle PT-Akzentreihe bleibt
oberhalb erhalten. Prozentzahlen sind Anzeige-Rundungen; gerechnet wird mit Gewichten,
die letzte rechte Kante ist exakt W. Keine zusätzlichen Footerlücken im D9-Stock-Look.

Die Tabelle ist keine neue Breitenmessung am Pixel: **ANNAHME W=1080 px nutzbar** für
das Rechenbeispiel. Bei anderem W gelten dieselben Anteile; Mic erreicht 48 dp Breite
ab W=312 dp. Andere kleine Aktionen erreichen 48 dp Breite **nicht**; dies wird wie
in D9 offen ausgewiesen und nicht durch überlappende Ziele schöngerechnet.

### Höhen und Trefferfläche – density 420 = 2,625 px/dp

Der Footer erhält ein eigenes Wunschmaß **56 dp = 147 px**, unter Druck mindestens
**48 dp = 126 px**. Damit ist die neue Mic-Fläche nicht nur optisch größer, sondern
real breiter und im geprüften Budget hoch genug. Die oberen Reihen teilen den Rest;
ein einheitlicher Skalierungsfaktor über alle Reihen ist hierfür nicht ausreichend.

D9-Root-Ziel 268 dp plus tatsächliche Navigation bleibt. Für die folgenden konkreten
Zahlen: Status unverändert 48 dp = 126 px, Navigation beispielhaft 8 dp = 21 px,
Root-Kappe 276 dp auf **725 px gerundet**. K ist ausschließlich der Tastenblock.
Nominal K=578 px; Footerhöhe = auf ganze Pixel gerundete `147×K/578`, mindestens
126 px, höchstens 147 px. Oberreihen teilen K−Footer exakt mit verteilten Rundungsresten.

| Root-Angebot | Root effektiv | K | Footer/Mic-Höhe | DE: drei obere Reihen | PT: vier obere Reihen |
|---|---:|---:|---:|---:|---:|
| **650 px = 247,62 dp** | 650 px | 503 px | **128 px = 48,76 dp** | je **125 px = 47,62 dp** | 93/94 px, Mittel **35,71 dp** |
| **829 px = 315,81 dp** | 725 px | 578 px | **147 px = 56 dp** | 143/144 px, Mittel **54,73 dp** | 107/108 px, Mittel **41,05 dp** |

Mic bei W=1080: x ungefähr **914…1080 px**, Breite 166 px.
Bei 650-px-Root: y **501…629 px**, Trefferfläche rund **63,30×48,76 dp**.
Bei 829-px-Angebot/725-px-Root: y **557…704 px**, rund **63,30×56 dp**.
Diese y-Werte sind **Root-lokal**; die Bildschirmposition ist Root-Oberkante plus y.
Die Navigation beginnt erst bei 629 bzw. 704 px, nicht innerhalb des Mic-Ziels.
Da K und Footer vom Budget und nicht von DE/PT abhängen, bleibt die Fußreihe bei
beiden Layouttypen unten verankert; mehr PT-Reihen verkleinern nur den oberen Block.

**Wichtige Budgetgrenze:** Sind die gemeldeten 650/829 px bereits ein Child-Angebot
nach Status/Navigation, dürfen diese Anteile nicht erneut abgezogen werden. Dann ist
K aus der tatsächlichen S2-Zuordnung zu übernehmen; die Root-Beispielzahlen sind nicht
blind als Child-Höhe einzubauen. Der berichtete Faktor 0,873 ist ein Fit des damaligen
Stands, kein für jeden neuen Footer verbindlicher Faktor; das Beispiel liefert K/578≈0,870.

**Ehrlicher Preis:** Bei PT/650 px sind obere Tasten nur rund 36 dp hoch. Alle bleiben
vollständig sichtbar, aber eine allgemeine 48-dp-Zusage wird **nicht** erfüllt. Die
Mic-Taste erfüllt sie; echte Bedienbarkeit kleinerer Oberreihen ist gesondert abzunehmen.
Wenn selbst 126 px Footer plus ein sinnvoll lesbarer Rest nicht passen, Fit-Konflikt
melden statt Reihe streichen/halbe Tasten zeichnen. Keine unbemerkte neue Zeichenseite.

### Daten und PTT

Reflow für alle 15×4 Ebenen als gemeinsame **Darstellungskomposition**: ID, Aktion,
Label und Alternates beibehalten, Footer umordnen/gewichten. **0 JSON-Änderungen**
empfohlen. Dieselbe komponierte Quelle liefert Zeichnung, Hit-Test und Accessibility;
keine nur gemalte große Mic-Fläche mit weiter kleiner Trefferfläche.

Die bisherige obere Mic-Aktion wird in diesem Auftrag **nicht still gelöscht**:
die Nutzerfreigabe nennt Position/Größe der unteren Taste, nicht ausdrücklich den
Wegfall jeder bisherigen Aktion. Obere Fläche bleibt unauffällig und unverändert;
beide Einstiege teilen denselben idempotenten PTT-Controller, nie zwei Aufnahmen.
Es kommt kein dritter Mic hinzu. 🐞 und Diagnosetext bleiben unangetastet.

Die untere Taste braucht echte DOWN→Start-, UP→Stop- und CANCEL→sicheres-Abbruch-
Ereignisse; der heutige normale Action-Key-Releasepfad allein genügt dafür nicht.
Ausschließlich der Mic-Pointer besitzt die Haltegeste, ein zweiter Pointer auf 🌐
darf ihn weder ersetzen noch ein synthetisches Mic-UP verursachen. Bei Rotation,
Editor-/IME-Wechsel Aufnahme sicher beenden, keine Session an den neuen Editor hängen.

## 2. Mic-Zustände ohne Umbau der Diagnoseleiste

Eigener Feedbackbereich ist **nur die Mic-Zelle**: 26-dp-Mikrofon, kleiner Pegel-/Rand-
indikator und kurzlebiges Commit-Häkchen. Kein neuer Streifen oberhalb, keine neue
Statuszeilenlogik/Diagnoseformatänderung. Bestehende Fehlermeldungen bleiben erhalten.
Icongröße 26 dp entspricht rund 68 px bei density 420; Zustandsbadge 10 dp rund 26 px.
Alle Zustände verwenden dieselben unveränderten Zell-Rects, keine Layoutanimation.

| Zustand | Hell: Fläche / Glyphe bzw. Marker | Dunkel: Fläche / Glyphe bzw. Marker | Bedeutung und dezenter Mechanismus |
|---|---|---|---|
| Bereit | **#FFECEFF1 / #CC37474F** | **#FF263238 / #CCFFFFFF** | Mic 26 dp, 1-dp-Akzentkontur; kein Erfolgssignal allein wegen Bereitschaft |
| DOWN/STARTING | Normalfläche, Ring **#FF00796B** | Normalfläche, Ring **#FF80CBC4** | Sofort Pressfeedback plus kleiner Ring; erst reale Aufnahme als RECORDING darstellen |
| Aufnahme läuft | **#FF4DB6AC / #FF263238** | **#FF80CBC4 / #FF263238** | Gefüllter Mic-Indikator, zusätzlicher kleiner Aufnahmepunkt und dezenter 2-dp-Pegelrand; Farbe nicht einziges Unterscheidungsmerkmal |
| „Pause“ = gehalten, gerade leise | **#FFDDE8E7 / #FF37474F** | **#FF344946 / #CCFFFFFF** | Kontur bleibt als Aufnahme aktiv; Pegel sinkt, kleiner ruhender Punkt statt vermeintlichem Pause-Button; Mikrofon läuft weiter |
| FINALIZING | Normalfläche, Ring **#FF00796B** | Normalfläche, Ring **#FF80CBC4** | Aufnahmepunkt weg, kleiner Verarbeitungsring; neuer Start gesperrt, keine heimliche Toggle-Aufnahme |
| Fehler / keine Textausgabe nach abgeschlossenem Lauf | **#FFFCE8E6 / #FFB3261E** | **#FF601410 / #FFFFB4AB** | Kleines ! oder ?, 26-dp-Mic bleibt erkennbar; Zustand nach UP nicht sofort löschen |
| Gerade erfolgreich eingefügt | bestehender Zustand plus Häkchen **#FF00796B** | bestehender Zustand plus Häkchen **#FF80CBC4** | 10-dp-Häkchen innerhalb der Zelle für **600 ms**, kein Vollflächenblitz und kein Größenwechsel |

Priorität Fehler > Verarbeitung > Aufnahme/leise > bereit; das Commit-Häkchen ist nur
zusätzliches Feedback, nicht ein neuer dauerhafter Aufnahmezustand. Es entsteht erst
nach tatsächlich akzeptiertem Edit im gültigen Editor, nicht bei einer bloßen Hypothese.
Kein Häkchen für verworfene Revision, verspätetes Resultat oder fehlende InputConnection.
Ein späterer Fehler überdeckt das Häkchen, nicht umgekehrt.

„Pause“ ist **kein neuer schaltbarer PTT-Modus** und keine ASR-VAD-Schranke. Eine leise
Phase darf Aufnahme/Puffer nicht stoppen oder Sprache verwerfen. Peak 2 % allein
rechtfertigt keine Diagnose „Stille“; Pegelregeln benötigen den vom Audioauftrag
geprüften Signalbezug. Fehlt ein frischer Pegel, unbekannten/statischen Pegel zeigen,
nicht einen erfundenen Ausschlag. Keine dauerhaft blinkende CPU-teure Animation.

Bleibt das Ergebnis nach dem Finalisieren leer: zusätzlich einmal die kurze Erklärung
**„Kein Text erkannt. Bitte erneut versuchen.“** anzeigen, z. B. als Toast, ohne die
Diagnose-/Fehlerzeile zu überschreiben. Kein „Erfolg“-Häkchen für einen leeren Lauf.
Ein laufender Job ist als Verarbeitung erkennbar; „leere Ausgabe“ und „noch in Arbeit“
sind verschiedene Zustände. Falschen erkannten Text korrigiert diese UI nicht automatisch.

## 3. Globus während Diktat: Empfehlung (b)

Globus heißt **Tastaturlayout**, nicht Spracheingabe erzwingen. Den bisherigen
Sperrgrund nicht mit einer angeblich auf die alte Tastatursprache festgelegten ASR
begründen: die Erkennung folgt dem Modell. Ein EN-Layout behebt keine ausbleibende
englische Erkennung; die verlangte Zuverlässigkeitsverbesserung bleibt separat nötig.

| Option | Exakter kurzer Meldungstext, Beispiel EN | Bewertung |
|---|---|---|
| **(a) Wie bisher ablehnen** | **„Layoutwechsel erst nach dem Diktat.“** (35 Zeichen) | Technisch konservativ, aber wiederholt genau die vom Nutzer als ignoriert erlebte Aktion; Erkennungsautomatik erklärt es nicht. Nicht empfohlen. |
| **(b) Für nächste Aufnahme vormerken** | **„Layout EN vorgemerkt; Diktatsprache Auto“** (40 Zeichen) | **Empfohlen:** bewusster Tap wird angenommen, laufende PTT-/Edit-Session bleibt unberührt; keine falsche Zusage einer EN-ASR-Einstellung. |
| **(c) Stoppen, wechseln, automatisch weiter** | **„Aufnahme wird für Layoutwechsel getrennt.“** (41 Zeichen) | Verworfen: zerlegt Äußerungen, erzeugt Final-/Start-Races und eine überraschende neue Aufnahme ohne neues DOWN; widerspricht der einfachen Halte-/Loslasssemantik. |

Die Meldungen sind keine neue Zeile im Diagnoseband: einmaliger kurzer Toast,
plus **persistente kleine `→EN`-Markierung in der Globus-Zelle** für (b).
Die Markierung verändert keine Zellenbreite und übermalt keine Nachbartaste;
Accessibility beschreibt „Nächstes Tastaturlayout Englisch, Erkennung automatisch“.
Bei engem Platz genügt ein klarer Punktmarker am Globus; der einmalige Text nennt das
Ziel. Das ist kein ASR-Sprachindikator und kein Beweis, welche Sprache erkannt wurde.

### Vormerkungsregeln

- IDLE: Globe wechselt sofort wie bisher, ohne zusätzlichen Aufnahmestart.
- STARTING/RECORDING/STOPPING/FINALIZING: nur Ziel-ID vormerken; Layout/Engine,
  Audiopuffer, Commitzustand und Sprachautomatik der aktuellen Sitzung bleiben gleich.
- Weitere Taps zyklieren von der **vorgemerkten** statt wieder von der aktuellen ID;
  Ziel bleibt auf aktive Sprachen begrenzt. Rückkehr zur aktuellen ID hebt die
  Vormerkung auf: **„Vormerkung aufgehoben. Erkennung Auto.“** (38 Zeichen).
- Ein aktives Layout: keine Phantom-Vormerkung, Hinweis **„Nur ein Layout aktiv.“**.
- Nach Finalisierung kein spontanes Umlayouten unter gerade tippenden Fingern.
  Beim **nächsten bewussten Mic-DOWN**, vor Beginn des neuen Captures, Ziel anwenden
  und Vormerkung entfernen. Footer-Mic bleibt durch layoutunabhängiges Budget/Anker
  am selben Ort; Editor und vorhandener Text bleiben unverändert.
- Wird während IDLE bewusst direkt ein anderes Layout gewählt, ersetzt diese Wahl
  die Vormerkung; kein späterer überraschender zweiter Wechsel.
- Vormerkung nur im Arbeitsspeicher, keine heimliche Änderung von current_language
  vor tatsächlicher Anwendung. Nach Prozessneustart kein altes unsichtbares Pending;
  nach einem Zielwechsel in den aktiven Spracheinstellungen ungültiges Pending löschen.

(b) gibt dem Nutzer unter den drei Optionen am ehesten eine sichtbare, sichere
Reaktion auf seinen Wechselwunsch. Es erfüllt ausdrücklich **nicht** die mögliche
Erwartung „Globus macht meine laufende Erkennung jetzt Englisch“. Diese Erwartung
wäre mit dem vorhandenen Modellvertrag unehrlich; sie wird mit „Diktatsprache Auto“
erklärt, nicht durch einen verdeckten Encoderneustart simuliert.

## 4. Geräteabnahme „sauber und flüssig“ – 12 Pflichtpunkte

Nur Messvorschrift für den Orchestrator, hier **keine adb-Ausführung**. Für Logs gilt:
VK-HIT ist vorhanden; zusätzliche monotone Zeitstempel DOWN/erste Darstellung,
Capture-Start/Stop und erfolgreicher Edit sind **anzulegende Debug-Messpunkte**, keine
behaupteten heutigen Logfelder. Ohne sie ist der entsprechende Latenzpunkt nicht bewiesen.
Logs enthalten IDs/Zeiten/Rects/Zustände, keine Transkripte oder Audiodaten.

| # | Messstelle / Beweis | Sollwert |
|---|---|---|
| 1 | Root-/Fit-Log plus Screenshots bei 650/829 px für DE und PT; `adb logcat -d -s VK-HIT` für tatsächliche Treffer | Alle vorherigen IDs derselben Ebene erhalten; 0 halb gezeichnete Tasten; letzte Unterkante ≤ Inhaltsende, Mic außen rechts. Die Zahl 38 gilt für das gemessene Layout, nicht pauschal für jede Sprache. |
| 2 | Screenshotvergleich erste/zweite/dritte Öffnung, gleicher Editor/gleiches Angebot; `adb exec-out screencap -p > /tmp/d10_second.png` | Keine abgeschnittene Fußreihe; bei gleichem Budget identische Zellkanten innerhalb **1 px**, keine kumulativen Insets. |
| 3 | Mic-Mittentap mit VK-HIT-Rect; Breite aus `adb shell wm size`, Dichte aus `adb shell wm density` | Mic von der sichtbaren Tastatur mit **1 Haltegeste** erreichbar; im Beispiel rund 166×128 bzw. 166×147 px, jeweils ≥48×48 dp. Treffername mic, nicht enter/space. |
| 4 | Monotone DOWN→erster Press-/Starting-Frame-Messung, 20 Haltegesten unter laufender ASR | **Jede** sichtbare Reaktion <500 ms; kein Warten auf Encoder/Finalisierung für Pressfeedback. Nicht mit first_hyp_ms verwechseln. |
| 5 | Capture-/Controller-Zeitstempel für 20 DOWN/UP/CANCEL-Zyklen, darunter UP während Inferenz | Startanforderung genau einmal je DOWN, Stopanforderung sofort auf UP; Mikrofonfreigabe als Messziel ≤500 ms nach UP/CANCEL, andernfalls belegter Fehler, kein stilles Weiterlaufen. |
| 6 | Hell/Dunkel-Screenshots bereit/gedrückt/recording/leise/finalizing/Fehler; vorhandene Diagnose parallel sichtbar | Richtige Tabelle aus §2, kein RECORDING vor tatsächlichem Capture; Fehler bleibt nach UP mindestens **5 s ohne neue Aktion**, kein falscher „Bereit“-Reset. Status-/Infoleiste weder entfernt noch umgestaltet. |
| 7 | Erfolgreiches Einfügen im Testfeld plus Edit-Zeitstempel und nachfolgende leere/abgewiesene Läufe | Häkchen nur bei akzeptiertem Edit, Dauer **600±100 ms**; 0 Erfolgshäkchen ohne Edit. Leerer abgeschlossener Lauf erhält erklärende Meldung, verarbeitender Lauf bleibt klar unterscheidbar. |
| 8 | Während gehaltenem Mic eine lange Sprechpause und anschließend weiter sprechen; Pegel-/State-Log | Kein automatischer Aufnahmeabbruch/Neuaufbau durch Anzeige „leise“; Sprache nach Pause weiterhin erfasst. Pegel reagiert nur auf echte Messwerte, ausbleibender Text wird nicht als erfolgreiche Erkennung ausgegeben. |
| 9 | Zweiter Pointer tippt Globus während gehaltenem Mic; Vorher-/Nachher-Screenshots und Session-/Layout-ID-Log | Genau eine Session, kein Stop/Neustart, aktuelle Layout-ID unverändert; `→EN`/Marker und exakte (b)-Meldung sichtbar; 0 angeforderte ASR-Sprachänderungen. |
| 10 | UP→Final fertig→normal tippen→neues Mic-DOWN; weitere Taps bis Wrap testen | Kein Umlayouten während normalem Tippen; Ziel genau einmal vor nächstem Capture übernommen; Pending danach weg; gleiche Mic-Zellkante, Wrap hebt Pending auf. |
| 11 | Vor/nach normalem Tippen, Shift/Caps, symbols↔symbolsShift und Emoji-Rückkehr Rect-/Screenshotvergleich | Keine Taste wandert ohne bewusst gewählten Layout-/Ebenen-/Windowwechsel; Shift-Einzeltipp/Doppeltipp funktionieren, Emoji-Rückweg vollständig sichtbar, keine verlorene Aktion. |
| 12 | Screenshots Hell/Dunkel/Pressed/Caps/zweite Öffnung/Emoji plus `adb shell dumpsys package de.voicekeyboard` und dokumentierter APK-Hash | Alles aus **derselben APK** auf dem gemeldeten API-37-Telefon; 15 Layouts positionsgeprüft, DE/EN/RU/PT/CZ ausführlich. Keine Emulatorbilder als Telefonbeweis. Erkennungsqualität separat nach Analyse-Gates, nicht mit bestandener UI-Liste ersetzen. |

Die Bedienliste ist eine notwendige Freigabegrundlage, **keine** Zusicherung „Deutsch
und Englisch erkennen jetzt zuverlässig“. Neue Version erst nach dieser Liste **und**
der separaten ASR-/Audioabnahme; eine schöne Mic-Fläche kaschiert keine ignorierten Sätze.

## 5. Einordnung in D9-S1 bis S4 – kein zusätzlicher dritter Build

Keine fünfte Feature-Welle: D10 ergänzt die noch nicht ausgelieferte S-Kette.
Gemeinsames APK-Budget weiterhin **maximal zwei Builds**, nicht zwei weitere Builds.
Falls Build 1 bereits erstellt ist, alles D10 zusammen mit den Kimi-Befunden bündeln
und nur den noch verfügbaren Korrekturbau nutzen; ist das Budget verbraucht, transparent
stoppen/neu planen statt einen heimlichen dritten Build starten.
Neue Klassen-/Testnamen unten sind Vorschläge, bestehende Methoden werden nicht erfunden.

| Phase | Dateien, höchstens vier pro Mikro-Auftrag | D10-Ergänzung / zuerst roter Test |
|---|---|---|
| **S1 Geometrievertrag** | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardHeight.kt`; `keyboard/KeyboardModel.kt`; vorhandene `KeyboardHeightTest.kt` und `KeyboardModelTest.kt` unter `app/src/test/java/de/voicekeyboard/keyboard/` | Unterschiedliche Footer-/Oberreihenhöhen und Footer-Reihenfolge als gemeinsame Darstellungsregel; 650/829 px, 4/5 Reihen, alle IDs, Mic-Mindestziel, gleiche Footer-Unterkante testen. Keine globalen Fits aus alter Sitzung. |
| **S2 Touch/Messintegration** | `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt`; `keyboard/KeyboardView.kt`; neuer/aus D9 geplanter `app/src/androidTest/java/de/voicekeyboard/keyboard/StockKeyboardFitTest.kt` | Komponierten Footer tatsächlich zeichnen/treffen; DOWN/UP/CANCEL-Vertrag für unteren Mic vorbereiten, unabhängiger Mic-Pointer, keine zweite-Aktion auf Release. Zweitöffnung und Globe-Zweitpointer zuerst testen. Leiste/🐞 nicht umgestalten. |
| **S3 Stock-Palette/Feedback** | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardTheme.kt` (neu/geplant); `keyboard/KeyboardView.kt`; `keyboard/EmojiPanelView.kt`; geplanter `KeyboardThemeTest.kt` unter dem JVM-Testpfad | §2-Palette/Marker/Häkchen, Zustandspriorität und konstante Rects; vorhandenen D9-Themejob erweitern, kein zusätzlicher isolierter Kosmetikbuild. |
| **S4 Service-/Pending-Verdrahtung** | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`; neuer `keyboard/PendingLayout.kt`; `app/src/main/res/values/strings.xml`; neuer `app/src/test/java/de/voicekeyboard/keyboard/MicAndGlobeStateTest.kt` | Vorhandene Controller-Ereignisse an unteren Mic binden; akzeptierte Edits statt Hypothesen als Feedback; Vormerkungsautomat, exakte Texte, 1 Session trotz Globus und nächster Start mit Ziel. Alte Ablehnung ersetzen, keine automatische Aufnahmefortsetzung. |

Abgekürzte `keyboard/...`-Produktionspfade stehen unter
`app/src/main/java/de/voicekeyboard/`; alle Roh-Layoutassets bleiben unverändert.
Wenn ein notwendiger Test-/Integrationspfad das Vier-Dateien-Limit überschreitet,
Auftrag teilen **ohne** zusätzlichen APK-Build; nicht still Dateien oder Gerätejobs anhängen.
Der spätere Kimi-Befund wird vor dem letzten gemeinsamen Build integriert oder als
expliziter verbleibender Releaseblocker geführt, nicht durch diesen Designauftrag vorweggenommen.

## Abschluss

**FERTIG:** Mic ganz rechts/doppelte Breite, Enter davor, Space 5→4; vollständige
Footer-Zeile und alle Zeichen erhalten; konkrete 650/829-px-Geometrie samt Grenzen;
Mic-Zustände, Globus (b), 12 Abnahmepunkte und S-Kettenzuordnung entschieden.
**OFFEN:** Implementierung und Telefonabnahme; ASR-/Audioanalyse zu „Yeah.“, Englisch
und ignorierten Eingaben; Root-/Child-Budgetzuordnung und reale Nutzbreite bestätigen.
**ANNAHME:** Rechenbeispiele verwenden 1080 px Nutzbreite, Root-Angebot und 21 px
Navigation; abweichende reale Insets werden genau einmal berücksichtigt.
**VERIFIKATION:** Nur Quellen/D9 gelesen und Maß-/Textlängen rechnerisch geprüft;
kein adb/Build und keine Code-/Assetänderung, einzige neue Datei dieses Dokument.
