# Tastatur – verbindlicher finaler Bauplan

## 0. Geltung

Dieser Plan legt Sollwerte fest; bei widersprechenden älteren Geometrieentwürfen gilt er.
Alle 15 heutigen Sprachen, vorhandenen Zeichen/Alternatives und vier Ebenen bleiben nutzbar.
Die bereits genehmigte Entfernung von ‹/› bleibt; Enter und Backspace werden versetzt, nicht gelöscht.
Status und 🐞 bleiben oberhalb der Tastatur; kein Debugziel neben Q, kein Popup-Höhenwachstum.
Nur dieses Dokument wird erstellt; kein Code, Build, Git oder neuer Gerätebeweis.

## 1. Zeilenraster und Mittigkeit

Referenz: **W=1080 px**, density **2,625 px/dp**; tatsächlich nutzbare Viewbreite verwenden.
Zeilen r0–r2 haben **12u**, Footer **13u**; Einheiten je Zeile neu aus W berechnen.
Oben: **1u=90 px=34,286 dp**; Footer: **1u=83,077 px=31,648 dp**.
Keine Rundung je Taste aufsummieren: Kanten aus kumulierten Einheiten, letzte Kante exakt W.
Margins sind nicht gezeichnete, **nicht treffbare** Flächen; kein nearest-key-Treffer darin.

| Reihe | Verbindliche Belegung in u | Pixel bei W=1080 |
|---|---|---|
| r0 Standard | zehn Zeichen **0…10**, je 1u; ⌫ **10…12**, 2u | Zeichenfeld **0…900**, ⌫ **900…1080** |
| r1 Standard | Rand **0…0,5**; neun Zeichen **0,5…9,5**, je 1u; @ **9,5…10,5**; Rand **10,5…12** | Rand 45; Zeichen **45…855**; @ **855…945**; Rand 135 px |
| r2 Standard | ⇧ **0…1,5**; sieben Zeichen **1,5…8,5**, je 1u; ⏎ **8,5…10,5**, 2u; Rand **10,5…12** | ⇧ **0…135**; Zeichen **135…765**; ⏎ **765…945**; Rand 135 px |
| r3 Footer | ?123 **1,5**, Sprache **1**, Emoji **1**, Space **6,5**, Punkt **1**, Mic **2** | Vollständige **13u**, keine zusätzlichen Seitenränder |

**Bestätigt:** 1,5+7+2+1,5=12; Buchstabenmitte r2=(1,5+8,5)/2=**5u=450 px**.
Damit sitzt r2 exakt unter der Mitte von Q…P **0…10u**, nicht unter der ganzen View (540 px).
Das bisherige Muster ⇧1 + sieben Zeichen + ⏎2 + Rand2 ist linksversetzt und wird ersetzt.
r1s neun Zeichen sind ebenfalls um **5u** zentriert; @ gehört nicht zum Zentrierblock.

Footer-Kanten: **0 / 124,615 / 207,692 / 290,769 / 830,769 / 913,846 / 1080 px**.
Footer-Anteile: **11,538 / 7,692 / 7,692 / 50 / 7,692 / 15,385 %**.
Mic liegt ganz rechts, Punkt unmittelbar davor; Enter steht ausschließlich in r2.
Punkt/Sprache/Emoji sind je31,648dp breit; eine allgemeine48-dp-Zusage wird nicht behauptet.
Die Space-Mitte liegt bei **560,769 px**, also nicht links von der Viewmitte.
Die Beschwerde „zu weit links“ wird mit korrektem r2-Anker und eigenem 13u-Footermaß behoben;
Space wird **nicht** gegen die vorgegebene 6,5u-Breite verkleinert. Keine pauschale Rechtsverschiebung.

### Sonderfälle – dieselbe Verankerung, vollständiger Bestand

N bezeichnet Texttasten ohne @/Margins/Funktionstasten; keine Umordnung ihrer Zeichenfolge.
r0: Zeichenfeld bleibt **10u**, bei N=11 jede Zeichentaste **10/11u**; ⌫ bleibt 2u.
r1: Zeichenfeldbreite B=min(N,9)u, Start **(10−B)/2**, jede Zeichentaste B/Nu;
danach @1u und rechts Rest bis 12u. Somit passen auch zehn/elf Zeichen ohne negative Ränder.
r2: B=min(N,7)u, Start **(10−B)/2**, ⇧ belegt den Startbereich, Zeichen je B/Nu;
danach ⏎2u und Rand bis12u. Alle Zentren bleiben **5u**, auch bei dichterem Alphabet.
Bei sechs Zeichen: ⇧2u, Zeichen **2…8**, ⏎2u, Rand2u; nicht links bei1u beginnen.
Die Verdichtung ist sichtbar ehrlich: sie garantiert **keine 48-dp-Breite** jeder Zeichentaste.

| Layout | Soll N(r0/r1/r2), ohne @ | Verbindlicher Sonderfall |
|---|---|---|
| de-de | 10/9/7 | QWERTZ; Zahlenhints q…p; Umlaute/ß bleiben über bestehende Alternates erreichbar |
| en-us | 10/9/7 | QWERTY, Standardraster |
| fr-fr | 10/9/7 | AZERTY: a,z,e,r,t,y,u,i,o,p oben; r1 q,s,d,f,g,h,j,k,l; r2 w,x,c,v,b,n,m |
| es-es | 10/9/7 | QWERTY; vorhandenes ñ-/Akzentangebot erhalten |
| it-it | 10/9/7 | QWERTY; Akzentangebot erhalten |
| pt-pt | 10/9/7 | Aktueller Bestand vierzeilig; keine alte fünfte Akzentreihe neu erfinden, Alternates erhalten |
| nl-nl | 10/9/7 | QWERTY |
| pl-pl | 10/9/7 | QWERTY; polnische Alternates/Shiftformen erhalten |
| tr-tr | 10/9/7 | Türkische i/ı-/İ/I-Zuordnung aus dem Sprachdatenvertrag, kein Default-uppercase |
| ru-ru | **11/11/10** | r0 je10/11u; r1 je9/11u; r2 je7/10u; ⌫ oben ergänzen/versetzen, @ nach r1-Zeichenfeld |
| cs-cz | **10/10/6** | r1 zehn Zeichen je0,9u; r2 sechs Zeichen je1u bei Start2u |
| sv-se | 10/9/7 | QWERTY; å/ä/ö-Angebot erhalten |
| da-dk | 10/9/7 | QWERTY; æ/ø/å-Angebot erhalten |
| fi-fi | 10/9/7 | QWERTY; ä/ö/å-Angebot erhalten |
| el-gr | 10/9/7 | Erste Zeile enthält neun griechische Buchstaben plus vorhandenes Satzzeichen; Case-/Tonos-Regeln erhalten |

Die Zahlen beschreiben den lesend geprüften **heutigen** Basisbestand, nicht historische M7-Assets.
base/shift haben identische Kanten und IDs; Schriftwechsel verschiebt keine Taste.
Alle Symbolebenen erhalten denselben Footer, ⌫ oben und ⏎ rechts in r2; doppelte Punkte vermeiden.
Ihre vorhandenen Textmengen bleiben je Reihe erhalten, r0 in10u, r1 mittig in10u,
r2 zwischen ⇧1,5u und ⏎2u im7u-Feld; übrige Ränder ergeben jeweils12u.
RU-Ziffernreihe: symbols zeigt 1…0 samt bisherigem zusätzlichem Zeichen im10u-Feld,
⌫2u rechts; keine neue permanente Ziffernreihe oberhalb der kyrillischen Basis.
SymbolsShift bleibt echter Shift-Toggle; ABC im Footer führt zurück, kein Dead-End.

### Höhe und tatsächliche Touchflächen

Status **48 dp**; Ziel insgesamt **268 dp ohne**, nominal **276 dp mit 8-dp-Navigation**.
Root/Child-Budget und tatsächliche Navigation je genau einmal berücksichtigen; alle Reihen skalieren.
Footer bevorzugt56dp, mindestens48dp; bei W=1080 ist Mic **166,154px=63,297dp** breit.
Mit D10-Rootbeispielen: 650px → Footer128px=48,762dp; 829px-Angebot → Root725px,
Footer147px=56dp. Verbleibende Höhe vollständig auf obere Reihen verteilen, nichts abschneiden.
Diese Beispielwerte gelten nicht bei abweichender Child-/Inset-Zuordnung; aktuelle Größen neu ableiten.

## 2. Vektor-Icons – keine Tofu-Glyphen

⌫, ⇧ und ⏎ werden **als Pfad/Vektor**, niemals über Fontzeichen oder Emoji gezeichnet.
Gemeinsame Zeichenbox **24×24 dp = 63×63 px**; optische Höhe **20 dp=52,5 px**.
Alle Linien **2 dp=5,25 px**, runde Linienenden/-verbindungen; keine Schatten oder Außenrahmen.
| Icon | Normative Kontur innerhalb der Zeichenbox |
|---|---|
| Backspace | Linke Pfeilspitze, geschlossener rechteckiger Körper mit X; optische Breite24dp/Höhe20dp, Verhältnis6:5; Outline, X ebenfalls2dp |
| Shift | Klassischer aufwärts gerichteter Pfeil mit breitem Kopf und erkennbarem Schaft/Sockel, optisch20×20dp; Outline statt bloßem dünnen Unicode-Pfeil |
| Enter | Rechtwinklig abknickender Rücklaufpfeil, Pfeilspitze links unten; optisch22×20dp, Linien ebenfalls2dp |

Alle drei am echten Zellmittelpunkt optisch zentrieren, nicht an einer Text-Baseline.
Normal: `palette.textFunctional` Hell **#CC37474F**, Dunkel **#CCFFFFFF**.
Temporäres Shift: `keyToggledGlyph` Hell **#FF00796B**, Dunkel **#FF80CBC4**.
Caps: vorhandene Akzentfläche, gefüllter Shift-Pfeil plus kleiner Caps-Marker in Gegenfarbe;
Hell **#FF37474F**, Dunkel **#FF263238**. KeySurface behält seine Zustandspriorität.
Press bleibt D11-Token, keine neue Farbe: Hell Overlay0x66, Dunkel0x4D, keine doppelte Komposition.

## 3. Statuszeile – immer sichtbar und wirklich befüllt

**48 dp =126px hoch**, links→rechts: **8dp Rand · statusText · Mic48dp · 4dp Abstand · 🐞48dp · 8dp Rand**.
statusText flexibel, Mindestbreite **96 dp**; bei1080px bleiben rund **295,43dp** für Text.
Mic/Debug jeweils mindestens **48×48dp**, Debug rechts mindestens **8dp** vom Viewrand.
Eltern und Kinder existieren, sind VISIBLE, gemessen >0 und liegen vollständig im sichtbaren Clip.
Die leere TextView ist kein Grundzustand: **„Bereit“** wird tatsächlich gesetzt, nicht nur reserviert.
Text **12sp**, bis zwei Zeilen, vorhandene Sekundär-/Fehlerpalette; kein ausgeblendeter Diagnosezugang.
Solltexte: ready **„Bereit“**; starting **„Mikrofon startet …“**; dictat **„Diktat …“**;
finalizing **„Verarbeite …“**; error verständliche Ursache, z. B. **„Mikrofonfreigabe fehlt“**.
Fehler bleibt nach UP mindestens5s ohne neue Aktion sichtbar; keine stille Rückkehr zu Bereit.
`status_shown` liefert ready/dictat/finalizing/error passend zur sichtbaren UI; Start ist erkennbar.
🐞 bleibt bedienbar, auch im Fehlerfall; Export nur Metadaten, keine Nutzerinhalte/Pfade/URLs.

## 4. Mikrofon – eine Haltesemantik, kein grüner Toggle

**Grüne/akzentuierte Mic-Fläche nur bei tatsächlich laufendem Capture dieser Session**,
nicht schon bei DOWN, Ladebeginn, gewünschter Aufnahme oder einem vergangenen Erfolg.
Beide vorhandenen Mic-Einstiege teilen denselben idempotenten Controller, nie zwei Sessions.
| Ereignis | Zustand vorher | Aktion | Zustand nachher | Sichtbare Rückmeldung |
|---|---|---|---|---|
| DOWN, Voraussetzungen erfüllt | IDLE | Start genau einmal asynchron anfordern, Pointer merken | STARTING | Pressfeedback ≤500ms, Starttext; **nicht grün** |
| Start fertig, Finger noch unten, Capture aktiv | STARTING | Aktuelle Generation bestätigen | RECORDING | Mic-Akzent und „Diktat …“ |
| Kurzer Tipp: UP bereits während Start | STARTING | wantedStop merken; verspäteter erfolgreicher Start erhält genau1Stop, kein Doppel-Abort | FINALIZING→IDLE oder ERROR | Nie verspätet grün; Ergebnis oder klarer Hinweis, kein stilles Nichts |
| Weiter halten | RECORDING | Audio/ASR weiterführen, keine Wiederholstarts | RECORDING | Akzent solange Capture aktiv, wahrer Pegel |
| UP | RECORDING | Capture sofort stoppen, Rest geordnet finalisieren | FINALIZING | Grün aus, „Verarbeite …“, Neustart bis Abschluss gesperrt |
| Erfolgreiches Finale | FINALIZING | Nur aktuelle gültige Ergebnisse anwenden | IDLE | „Bereit“; Häkchen höchstens600ms, **keine grüne Aufnahmefläche** |
| Leeres Finale | FINALIZING | Leerresultat erklären, kein Erfolg vorspiegeln | ERROR-Anzeige | „Kein Text erkannt“, kein bloßes Bereit |
| CANCEL/Fokusverlust | STARTING/RECORDING/FINALIZING | Genau1Abbruch, Generation invalidieren, Mic/FGS freigeben | IDLE bzw. ERROR | Akzent aus; Abbruchgrund gesetzt, keine Geisterausgabe |
| Capture-/Stall-/Silencefehler | aktiv | Session sicher beenden, Fehler speichern | ERROR-Anzeige | Akzent sofort aus, Ursache bleibt sichtbar |
| Berechtigung/Modell fehlt | IDLE | Nicht aufnehmen; begründeten Einrichtungsweg anbieten | ERROR/Einrichtung | Kein grüner Zustand; nach Freigabe neuer bewusster DOWN erforderlich |

Ein kurzer Tipp ist kein Aufnahme-Toggle; Halten braucht keinen zweiten Druck und keinen Langdrucktimer.
Start maximal **15000ms**, dann sichtbarer Fehler; UP/CANCEL darf nie hinter Inferenz warten.
Späte Callbacks werden verworfen bzw. räumen Ressourcen auf, sie starten keine neue Aufnahme.
Kalter Erstversuch wird ausdrücklich getestet: kein Warm-up-Tipp als Bedienvoraussetzung.

## 5. Abnahme – Soll/Ist statt Testanzahl

1. Alle15×4 Ebenen: normgerechte Reihensummen, keine verlorene Text-/Aktions-ID; Margins erzeugen **0 Treffer**.
2. DE/EN r2: erster Buchstabe x=135px, letzter rechter Rand765px; Mittelpunkt450px, Abweichung **≤1px**.
3. CS: sechs Buchstaben180…720px; RU: vollständige11/11/10-Zeichenfelder ohne verdeckte Taste.
4. Footer endet exakt1080px; Punkt830,769…913,846 und Mic913,846…1080; kein Enter im Footer.
5. Icon-Screenshots Hell/Dunkel: kein Kästchen, alle drei2dp-Strich und20dp optische Höhe, gezeichnete Box63px.
6. Erste/zweite/dritte Öffnung bei650/829px: alle Reihen vollständig, gleicher Zustand → gleiche Rects **±1px**.
7. Root-Test plus Gerätebild: statusText/Mic/🐞 stets sichtbar; Bereit/Diktat/Verarbeite/Fehler tatsächlich lesbar.
8. Frische Installation mit/ohne Freigaben: erster zulässiger Halteversuch startet; keine Aufnahme nach UP im Start.
9. Je20 Start-/UP-/CANCEL-Folgen: höchstens1Session/1Stop oder1Abort; Mic-Akzent nach Ende **≤500ms aus**.
10. Kein Signal/keine Blöcke >3000ms: sichtbarer silence/stall-Fehler, Ende≤3500ms; keine hängenbleibende Diktat-Anzeige.
11. 2-s- und30-s-Diktat sowie Soak gemäß TESTPLAN_DIKTAT K01–K10; Ergebnis oder erklärter Fehler, kein stiller Verlust.
12. Tastendruckfeedback<500ms; Caps/Symboltoggle/Popup/Emoji-Rückkehr funktionieren aus derselben geprüften APK.

Nur Codes, Zeiten, Zähler und Rects protokollieren; keine Eingabeinhalte, Dateipfade oder URLs in Logs.
Dieser Plan ist verbindliches Soll, kein Beleg, dass der aktuelle Build die Kriterien bereits erfüllt.
