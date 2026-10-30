# D9 – AOSP-Stock-Look für Voice Keyboard

## 0. Entscheidung, Referenz und Grenzen

**Ziel:** flache AOSP-LatinIME-Anmutung in Hell/Dunkel, nicht Gboard/Pixel-Material-You-Pills.
Alle jetzigen Zeichen und Aktionen bleiben erreichbar und sichtbar; Status/Diagnose bleibt,
🐞/Info bleibt **in der Leiste oberhalb** der Zeichentasten. Keine noch nicht benannte
„spätere Ausnahme“ wird vorweggenommen. Keine Stock-Bereinigung durch Entfernen von
Umlauten, Cursortasten, Mic, Emoji oder einer ganzen Reihe.

Gelesen: aktueller KeyboardView, KeyboardModel, KeyboardHeight, ImeRootView,
EmojiPanelView, Service-Debug-Button, themes.xml und Struktur aller 15 Layoutassets.
`keyboard/KeyboardTheme.kt` und `res/values/colors.xml` existieren im gelesenen Stand
**nicht**. Die tatsächlichen Layouts liegen unter `app/src/main/assets/layouts/`,
nicht `keyboard/assets/`. Es wurden nur Quellen gelesen und dieses Dokument erstellt;
kein Build, kein adb, keine Änderung am parallel bearbeiteten T19.

### Tatsächliche AOSP-Referenz

Die folgenden Originalquellen wurden lesend unter `android16-release` geprüft:
Basis-URL **https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/android16-release/java/res/**
- `values/colors.xml:43–78`: LXX_Light/LXX_Dark-Farben, einschließlich Press-Overlays.
- `values/themes-lxx-light.xml:45–55`, `themes-lxx-dark.xml:45–55`: Zuordnung von Key-/Funktionshintergrund und Textfarben.
- `drawable/btn_keyboard_key_lxx_light.xml:34–43`, entsprechende Dark-Datei: normale Tasten mit flächigen Farben, Presszustand als Farbzustand; Funktionstasten entsprechende Selektoren.
- `xml/rows_qwerty.xml:27–50`: 10%-Zeichenraster, Shift 15 %, Delete rechts; `values/themes-lxx.xml:31–41`: normaler Typeface und größenabhängige Typografie.
- `values/config-common.xml:100–101`: seitliches Keyboard-Padding 0; LXX-Popupstil nennt horizontalGap 0, ist aber **kein Beleg für alle Haupttastenabstände**.

**Wichtig:** Android 16 allein definiert keine universelle feste dp-Tastenhöhe für alle
Bildschirme/OEMs; LatinIME arbeitet auch mit Ressourcenvarianten und Größenverhältnissen.
Unten stehen **Originalfarben und Rasterprinzipien**, ergänzt durch ausdrücklich eigene,
verbindliche dp-Designwerte für unseren Renderer. Das ist kein erfundener pixelgenauer
Screenshot-Nachbau eines hier nicht geöffneten AOSP-Geräts.

## 1. Konkrete Geometrie und Typografie

Alle dp-Zahlen sind Layout-/Darstellungsmaße; Text wird als sp mit gleichem Nennwert
bei fontScale=1 umgesetzt, statt wie bisher ausschließlich mit density zu skalieren.

| Bestandteil | Normalangebot / Zielwert | Begrenztes Angebot |
|---|---|---|
| Oberleiste mit Status, vorhandenen Mic-/Info-Aktionen | **48 dp**, immer sichtbar; Info-Touchziel 48×48 dp | Nicht auf 24 dp quetschen, solange dort interaktive 48-dp-Aktionen bleiben |
| Vier Tastenreihen | **55 dp je logischer Reihe**, einschließlich Fußreihe; 220 dp zusammen | Alle vier gleichmäßig aus dem tatsächlich verbleibenden Budget ableiten |
| PT base/shift: fünf Reihen | **44 dp je Reihe**, 220 dp zusammen; alle Akzenttasten bleiben | Alle fünf proportional erhalten, keine abgeschnittene oder gestrichene fünfte Reihe |
| Seitlicher Außenabstand / Abstand oben-unten im Tastenblock | **0 dp / 0 dp** | Keine zusätzlichen versteckten Mindesthöhen |
| Physische Modelllücken horizontal / vertikal | **0 dp / 0 dp** im Stock-Stil | Eindeutige aneinandergrenzende logische Zellen, keine überlappenden virtuellen Ziele |
| Normale Tastenfläche | Gleiche Farbe wie Keyboard-Hintergrund, keine sichtbaren Einzelkarten | Druckfläche innerhalb ihrer eigenen Zelle, keine Übermalung von Nachbarn |
| Radius | **0 dp** normale/gedrückte Zeichentasten; **18 dp** maximal für den bewusst eigenen Mic-Aufnahmeindikator | Mic-Indikator höchstens `min(36 dp, Reihenhöhe−4 dp)` Durchmesser |
| Buchstaben / Ziffern | **24 sp / 22 sp** | Bei engen Zeichenspalten bis 18 sp absenken, keine Zeichen kürzen oder verstecken |
| Funktionswörter ?123/ABC / Space / Status | **14 sp / 12 sp / 12 sp** | Lange Space-Wörter nur nach bestehender SPACE_LABEL-Entscheidung zu ␣ verdichten; kein „Leer“-Hardcode |
| Normale Icons | Backspace, Enter, Shift, Mic: **24×24 dp**, einfarbig; Strichstärke **2 dp** | Innerhalb jeder gemessenen Zelle zentriert, keine Emoji-Font-Boundingbox als Positionsgrundlage |
| Prominenter Fuß-Mic | Icon **26 dp**; Akzent/Recording-Indikator, nicht durch Entfernen des Enter-Platzes vergrößern | Größe auf echte Zelle begrenzen; kein neuer 96-dp-Breitenanspruch in diesem Stock-Auftrag |
| Elevation / Schatten | **0 dp / keine**, auch kein Textschatten | Keine künstlichen Reliefkanten oder systemabhängigen Button-Schatten |
| Trennlinien | Keine zwischen Buchstabentasten; **1 dp** unter der Diagnoseleiste | Einzige feste Linie, auch in Emoji-Ansicht konsistent |

Damit wird die vertikale Zielsumme **48 + 220 = 268 dp ohne Navigation**;
mit nominal 8 dp Navigation **276 dp**. Das ist eine Root-Gesamtsumme, nicht die
Höhe, die KeyboardView zusätzlich zur Leiste beanspruchen darf. 55 dp ist die logische
Tastenhöhe ohne den bisherigen 6-dp-Zwischenraum, keine 55+6-dp-Zeile.

### Breitenverhältnisse und tatsächliche Layouts

- EN-Referenz: Reihe 1 zehn Zellen à **10 %**; Reihe 2 neun à 10 % mit **5 %** Rand
  links/rechts; letzte Zeichenreihe Shift **15 %**, sieben Buchstaben à 10 %, Delete **15 %**.
- Diese Einrückung gilt nur bei passender vorhandener Struktur: zweite reine
  Zeichenreihe kürzer als erste → mittig im gleichen Grundraster; keine Texttaste löschen.
- DE elf Zeichen → **9,09 %** statt 10 %; FR/IT/CZ zwölf → **8,33 %** in reinen
  Zwölferreihen. Allgemein Anteil `key.width / sum(row.width)`; vorhandene Sondergewichte
  und Case-Positionen bleiben. Eine größere Reihe wird vollständig eingepasst, nicht beschnitten.
- Aktuelle Fußreihe bei Gewichtssumme 13: ?123/ABC und Enter je **11,54 %**,
  Space **38,46 %**, 🌐/😊/‹/›/Mic je **7,69 %**; nichts davon verschwindet für einen
  vermeintlich „saubereren“ Stock-Look. Rendering ohne sichtbare Kästchen lässt sie ruhiger wirken.
- Strikte **48×48 dp für jede Taste** ist mit diesen Zeichenmengen auf Smartphonebreite
  weiterhin unmöglich. D9 behauptet diese frühere harte Forderung **nicht** als erfüllt:
  beispielsweise neun oder elf schmale Buchstabenzellen sind keine 48-dp-Quadrate.
  Aktionsleiste/Info behalten ihr 48-dp-Ziel; die Texttasten folgen dem Stock-Raster.
  Falls die frühere Quadrat-Untergrenze unverändert bindend bleibt, ist dies ein
  offener Produktkonflikt, nicht durch ein Theme lösbar.

### Farben – ARGB, Hell/Dunkel

Normale Text-/Funktionsflächen sind flach, nicht weiß/hellgrau gegeneinander gekachelt.
Pressfarben mit Alpha sind **Overlays über der vorher gefüllten Tastaturfläche**, keine
alleinige transparente Fensterfarbe; dadurch scheint kein Ziel-App-Inhalt durch.

| Token/Zustand | Hell | Dunkel | Herkunft/Verwendung |
|---|---|---|---|
| Keyboard-/Status-Hintergrund | **#FFECEFF1** | **#FF263238** | AOSP key_background_lxx_*; Root und Canvas deckend füllen |
| Normale Tastenfüllung | **#FFECEFF1** | **#FF263238** | Identisch zum Hintergrund; keine Kartenoptik |
| Gedrückt, normales/funktionales Ziel | **#2637474F** Overlay | **#19FFFFFF** Overlay | AOSP key_background_pressed_lxx_* |
| Normaler Buchstaben-/Zifferntext | **#FF37474F** | **#CCFFFFFF** | AOSP key_text_color_lxx_* |
| Funktionsglyphen/-text, nicht aktiv | **#CC37474F** | **#CCFFFFFF** | AOSP key_functional_text_color_* |
| Akzent/Indikatorfläche | **#FF4DB6AC** | **#FF80CBC4** | AOSP highlight_color_lxx_* |
| Getoggelt: Shift/Caps-/Symbol-Glyphe | **#FF00796B** | **#FF80CBC4** | Eigene dunklere Hell-Variante für Lesbarkeit; keine grüne ganze Taste |
| Getoggelt: Füllung | Wie Normal; Press-Overlay nur während Druck | Wie Normal; Press-Overlay nur während Druck | Caps zusätzlich mit 2-dp-Marker, nicht allein Farbwechsel |
| Aufnahme-Mic: Fläche / Glyph | **#FF4DB6AC / #FF263238** | **#FF80CBC4 / #FF263238** | Eigene Recording-Semantik mit AOSP-Akzent, kein belegter AOSP-ASR-Zustand |
| Sekundärer Status-/Space-Text | **#B337474F** | **#B3FFFFFF** | AOSP sekundäre Text-/Space-Farben |
| Deaktiviertes Funktionsziel | **#6137474F** | **#61FFFFFF** | Eigene Disabled-Abstufung, nicht für Fehlermeldungstext |
| Trennlinie | **#2637474F** | **#26FFFFFF** | Eigener dezenter 1-dp-Separator |
| Fehlerstatus | **#FFB3261E** | **#FFFFB4AB** | Eigene gut erkennbare Fehlerfarbe, nicht AOSP-LXX-Quellwert |

Gedrückt verändert die Textfarbe nicht; getoggelt bleibt bis zum echten Zustandswechsel
sichtbar, nicht bis zum nächsten Frame. Farbzustände: normal → gedrückt → normal bzw.
normal → getoggelt; gedrückt+getoggelt behält die aktive Glyphe über dem Press-Overlay.
System Hell/Dunkel entscheidet die Palette; kein Thema aus der Ziel-App erraten und
keine Cloud-/Dynamic-Color-Abhängigkeit. Dark muss auch bei Root/Emoji/nativen Buttons
greifen, nicht nur bei den Buchstaben.

Monochrome Icons ersetzen nur die **Darstellung** der vorhandenen Aktionen: ⌫, ⏎,
Shift-Pfeil mit Caps-Marker und Mikrofon als konsistente Silhouetten/Strichpfade;
Globus/Emoji-Funktion ebenfalls einfarbig, nicht als bunte Font-Emoji. 🐞/Info darf
seine erkennbare Diagnosebedeutung behalten, 22-dp-Icon in einer 48-dp-Zelle.
Es wird keine Aktion umbenannt oder entfernt. Native Button-Elevation/Tints werden
explizit an die Palette gebunden, nicht vom stets hellen Activity-Theme übernommen.

Space zeigt das lokalisierte Asset-Wort dezent zentriert bei 12 sp; es wird weder
Spracherkennung noch Autokorrektur suggeriert. Keine neue Sprachbezeichnung anstelle
des vorhandenen Space-Labels. Bei gleicher Ebene/Breite bleibt die Beschriftung gleich.

## 2. Delta zum gelesenen Istzustand

Aufwand = grobe **geänderte/neu geschriebene Zeilen**, keine gemessene Arbeitszeit;
Werte überlappen, deshalb nicht blind addieren. Zeilenanker gelten vor Paralleländerungen.

| Aktuell → Ziel | Fundstelle / zu ändernde Datei:Zeile | Aufwand |
|---|---|---|
| keyHeight 52 dp + rowGap 6 dp → tatsächliches Budget/alle Reihen; nominal 55 dp bei vier, 44 dp bei fünf Reihen, Gap 0 | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:42–44,108–120,135–163`; `keyboard/KeyboardModel.kt:32–67` | ca. 35–65 |
| keyGap 4 dp, Padding 0 → Gap 0, optionale Stock-Einrückung rein kurzer Zeichenreihe | `keyboard/KeyboardView.kt:44`; `keyboard/KeyboardModel.kt:14–16,37–65` | ca. 15–30 |
| Radius 6 dp, gezeichnete Einzelkacheln → flache Normalfläche/Radius 0 | `keyboard/KeyboardView.kt:45,195–205` | ca. 10–20 |
| DKGRAY / Text 20×density → Hell/Dunkel-Tokens, 24/22/14/12 sp nach Tastentyp | `keyboard/KeyboardView.kt:65–70,205–210` | ca. 25–45 |
| Press/Toggle gemeinsam #FF4CAF50, funktional #FFE0E0E0, normal #FFF5F5F5 → getrennte Quellpaletten und Press-/Togglezustände | `keyboard/KeyboardView.kt:197–205`; **neu vorgeschlagen** `keyboard/KeyboardTheme.kt` | ca. 70–110 inkl. Tokens |
| ⌫/⏎/⇧/🎤 teils Text-/Emoji-Font → einheitliche 24-dp-Glyphen; semantische Labels behalten | `keyboard/KeyboardView.kt:214–234` plus Zeichenpfade im selben Renderer | ca. 40–70 |
| SPACE folgt bereits SpaceLabelRule → erhalten, sekundär/12 sp stylen, kein deutscher Override | `keyboard/KeyboardView.kt:222–227`; Labels in `app/src/main/assets/layouts/*.json` | ca. 5–12, **0 Assetzeilen** |
| Root ohne einheitliche Palette; StatusText 13 sp/28 dp, tatsächliche Leiste durch Debugziel 48 dp → explizit 48 dp, 12 sp, deckender Hintergrund/1-dp-Linie | `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt:48–69,105–120` | ca. 20–40 |
| Debug-Button wird im Service 48×48 dp rechts angehängt → dort belassen, Theme/State statt Standardbutton-Relief | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:350–368` | ca. 10–20 |
| T19 bestimmt Rasterhöhe und blendet letzte Reihen aus → ganze vorhandene Tafel ins Budget einpassen, kein Keyverlust | `keyboard/KeyboardHeight.kt:15–29,47–75`; `keyboard/KeyboardView.kt:157,182–190,422` | ca. 35–65, **zuerst mit T19 koordinieren** |
| Emoji #FFFAFAFA, Linie #FFC0C0C0, Text DKGRAY → dieselbe Palette/Separator/Navigation | `keyboard/EmojiPanelView.kt:48–60,145–180`; nativer Footer `ime/ImeRootView.kt:87–99` | ca. 15–30 |
| 15 Roh-Layouts mit 4 Ebenen, PT base/shift 5 Reihen → Daten unverändert, nur Darstellung/Geometrie auswerten | `app/src/main/assets/layouts/`: alle 15 Dateien; `keyboard/KeyboardModel.kt:41–65` | **0 Assets / 0 JSON-Zeilen** |
| `colors.xml`/`KeyboardTheme.kt` fehlen; Activity-Theme stets Material.Light → Rendererpalette eigenständig nach Nachtmodus; kein notwendiger Setup-Theme-Umbau | `app/src/main/res/values/themes.xml:3`; neue Theme-Datei statt nicht vorhandene colors.xml „ändern“ | ca. 50–80 im neuen Tokenvertrag, siehe oben |

Präfix `keyboard/...` in dieser Tabelle = `app/src/main/java/de/voicekeyboard/keyboard/...`.
Es werden keine erfundenen bestehenden Theme-Felder/API-Namen vorausgesetzt.

## 3. Statuszeile, Info und schwankende Höhenangebote

**Entscheidung: Oberleiste bleibt immer 48 dp sichtbar.** Nicht „nur bei Bedarf“,
nicht plötzlich GONE beim zweiten Öffnen. Text darin maximal zwei 14-dp-Zeilen bei
12 sp, vertikal zentriert; links Status/Diagnose, rechts vorhandene Bedienelemente
inklusive 🐞/Info. Fehler ersetzen den Status, bleiben gemäß R-2 stehen und werden
nicht durch Redraw/Themewechsel zu „Bereit“ zurückgesetzt.

Ein 24-dp-Textband wäre optisch ruhig, aber ein darin angeordneter 48-dp-Info-Button
würde die echte Leiste trotzdem auf 48 dp ziehen oder Nachbarzeilen überdecken.
Deshalb wird die tatsächliche Gesamthöhe gerechnet, nicht die 28-dp-TextView mit der
Elternhöhe verwechselt. Keine unsichtbaren überlappenden Touch-Rechtecke über Q.

### Ein einziger Höhenhaushalt – Integrationsvertrag für T19

Dichte 420 dpi entspricht **2,625 px/dp**. N = tatsächlich zum Root gehöriger
Navigationsinset, nicht immer 8 dp; 8 dp ist nur das nominale Rechenbeispiel.
Root-Gesamtziel = 268 dp + N. Root angebotene Höhe H; nutzbarer Tastenblock
= `min(H, 268 dp + N) − N − 48 dp`. Alle vorhandenen R Reihen teilen diesen Rest.

| Root-Angebot | Beispiel N | Effektiver Root / Tastenblock | Vier Reihen / PT fünf Reihen |
|---|---|---|---|
| **829 px = 315,81 dp** | 8 dp = 21 px | Kappe 276 dp = 724,5 px, ganzzahlig höchstens ca. 725 px; Keys 220 dp | **55 dp / 44 dp** |
| **650 px = 247,62 dp** | 8 dp = 21 px | Kein Erzwingen der 276 dp; Keys = (650−21−126)/2,625 = **191,62 dp** | **47,90 dp / 38,32 dp**, alle Reihen bleiben |
| **650 px**, wenn Navigation außerhalb des Angebots liegt | N wird hier **nicht erneut** abgezogen | Keys = (650−126)/2,625 = **199,62 dp** | **49,90 dp / 39,92 dp** |

**ANNAHME:** Die übergebenen Requested-Höhen sind ein Root-/Window-Angebot.
Falls sie tatsächlich die bereits um Status/Insets reduzierte Child-MeasureSpec-Size
von KeyboardView bezeichnen, gilt diese Tabelle **nicht direkt**: dann erhält die
Child bereits ihr Budget und darf nichts nochmals abziehen. T19 muss Ebene, Spec-Modus
und aktuelle Insets im Geometriebeweis mitloggen; Root und KeyboardView dürfen nicht
beide „zur Sicherheit“ Status und Navigation abziehen.

48 dp entspricht 126 px; **276 dp entspricht 724,5 px, nicht 727 px**. Rundungsreste
werden einmal auf Reihen verteilt; letzte Unterkante ist exakt das Inhaltsende.
EXACTLY/AT_MOST werden respektiert, kein Mindest-Reihenboden über dem Angebot.
Zeichnung, VK-HIT, Accessibility und reale sichtbare Zellen verwenden dieselben Rects.
Einfach eine kleinere View melden, die alten 52-dp-Rects aber beibehalten, ist kein Fix.

**Aktueller T19-Vertrag ist noch nicht D9-konform:** KeyboardHeight dokumentiert das
vollständige Entfallen einer nicht passenden Reihe; `visibleKeys()`/`keyAtVisible()`
setzen dies um. „Ganz weg statt halb weg“ verletzt **alle Tasten bleiben erhalten**
ebenfalls – gerade Space/Mic/Enter. Die passende Forderung lautet jetzt: **alle Reihen
neu in den aktuellen Platz einmessen, nie eine existierende Aktionsreihe entfernen**.
Die Änderung muss der T19-Coder übernehmen; dieser Dokumentauftrag editiert seine Datei nicht.

Für noch kleinere Angebote/große Schrift gilt: keinen erfolgreichen Fit erfinden.
Fehlende Lesbarkeit/Bedienbarkeit ist als solcher Grenzfall zu melden; weder Glyphen
abschneiden noch unbekannte neue Paging-Modi versteckt einführen. Die obigen 650/829-px-
Fälle sind konkrete Pflichtfälle, kein allgemeiner Beweis für alle Displaykonfigurationen.

## 4. Maximal vier Mikro-Aufträge, test-first

Gemeinsames Budget: **höchstens zwei APK-Builds insgesamt**, keine Builds in diesem
Designauftrag. Erst alle zusammenhängenden Änderungen/testbaren Verträge integrieren,
dann Build 1 und Geräteabnahme; Build 2 nur für eine gebündelte Korrektur. Kein
zusätzlicher kosmetischer dritter Build. T19 besitzt bis zum Handoff die Höhen-Dateien.
Neue Datei-/Testnamen unten sind Vorschläge, keine schon existierenden APIs.

| Reihenfolge | Maximal vier Dateien | Test zuerst, dann Änderung |
|---|---|---|
| **S1 – T19-Höhenvertrag abschließen** | `keyboard/KeyboardHeight.kt`; vorhandener `app/src/test/java/de/voicekeyboard/keyboard/KeyboardHeightTest.kt`; `keyboard/KeyboardModel.kt`; vorhandener `app/src/test/java/de/voicekeyboard/keyboard/KeyboardModelTest.kt` | Parameterisiert 650/829 px, 4/5 Reihen, Status nur einmal, Navigation nur einmal: kein Ziel entfällt, kein unterer Rand überschreitet sein Budget; falscher alter Row-Drop muss rot werden. Vollständige Rects aus dem Budget erzeugen. |
| **S2 – Messintegration und Zweitöffnung** | `ime/ImeRootView.kt`; `keyboard/KeyboardView.kt`; **neu** `app/src/androidTest/java/de/voicekeyboard/keyboard/StockKeyboardFitTest.kt` | Zweimal öffnen/Feldwechsel/Emoji-Rückkehr, DE und PT: alle vorherigen IDs vollständig gezeichnet, treffbar und im Parent sichtbar; 48-dp-Leiste bleibt, kein kumulativer Abzug. Einrückung darf keine ID verschieben/verlieren. |
| **S3 – Palette, Typografie und Glyphen** | **neu** `keyboard/KeyboardTheme.kt`; `keyboard/KeyboardView.kt`; `keyboard/EmojiPanelView.kt`; **neu** `app/src/test/java/de/voicekeyboard/keyboard/KeyboardThemeTest.kt` | Hell/Dunkel-Tokenmatrix und Press≠Toggle zuerst; dann AOSP-Farben, flache Flächen, 24/22/14/12-sp-Hierarchie und monochrome Pfade integrieren. Bilder nicht durch bloße Token-Tests als bestanden erklären. |
| **S4 – Leiste und gesamte Sichtabnahme** | `ime/ImeRootView.kt`; `ime/VoiceKeyboardService.kt`; `app/src/main/res/values/strings.xml`; **neu** `app/src/androidTest/java/de/voicekeyboard/keyboard/StockStatusBarTest.kt` | Status/Fehler persistent, Info über den Tasten 48×48 dp, kein Themewechsel-Reset, native Mic-/Rückbuttons ohne fremdes Relief; vor/nach Palette gleiche Aktionsanzahl. Danach Screenshots Hell/Dunkel, normal/pressed/Caps, zweite Öffnung und Emoji aus genau derselben APK. |

`keyboard/` und `ime/` sind jeweils unter `app/src/main/java/de/voicekeyboard/` zu lesen.
**Keiner der vier Aufträge ändert ein Layout-Asset: Ziel 0 von 15.** Keine UI-Reparatur
über beliebige JSON-Löschungen, Backspace-Umzüge oder neue Sprachvarianten im selben Build.
Die beiden vorhandenen JVM-Testpfade wurden lesend gegen das Projekt geprüft.

## 5. Konflikte und verbindliche Empfehlungen

| Konflikt | Empfehlung |
|---|---|
| AOSP-Minimalismus vs. alle heutigen Aktionen | Erhalt gewinnt: Cursor, Emoji, Globus, ?123/ABC, Space, Backspace, Enter und Mic bleiben; kein unveröffentlichtes „Ausnahme“-Budget. Stock-Farben/Hierarchie statt Löschen. |
| Unterer rechter Mic-Wunsch vs. aktueller Enter unten rechts | Gelesene Rohassets haben Mic **vor** Enter, nicht als allerletzte Taste. D9 ist ein Look-/Fit-Auftrag und autorisiert diesen zusätzlichen Positionsumbau nicht; beide bleiben vorerst an ihrem tatsächlichen Ort. „Mic ganz rechts“ benötigt einen getrennten, eindeutigen Reflow-Vertrag unter Erhalt aller Aktionen. |
| Zwei Mic-Darstellungen, oben und in Fußreihe | Keine stillschweigende Entfernung einer heutigen Taste; gleiche Aufnahmezustände themen, keinen dritten Mic hinzufügen. Eine spätere ausdrücklich benannte Ausnahme darf diesen Doppelweg vereinfachen. |
| 🐞/Info über den Tasten vs. frühere D-LAYOUT-Empfehlung neben Q | **Neuere Nutzerentscheidung gewinnt:** obere Leiste; kein Debugziel in der Buchstabenreihe. |
| Permanente Diagnose vs. Stock ohne Diagnosewerkzeug | Dauerhafte 48-dp-Leiste im selben Hintergrund, kleine Typografie, 1-dp-Trenner; nicht als Vorschlags-/Autokorrekturleiste tarnen. |
| Emoji-V2 vs. Stock-Emoji-Ebene | Stack-Ersatzmodus und nativer „Zur Tastatur“-Rückweg bleiben; Farben/Icons angleichen, keine Rückkehr zum Overlay. Kein Height-Fit durch Weglassen des Rückbuttons. |
| 15 Sprachen/Umlaute vs. starres 10%-EN-Raster | Lokale Buchstaben und Pflichtzeichen bleiben eigene Tasten; tatsächliche Gewichte/Zeilenzahl respektieren, nur passende kurze Reihen einrücken. PT bleibt fünfzeilig. |
| 48-dp-Quadrate vs. dichte Smartphone-Tafel | Nicht als erfüllt behaupten. D9 priorisiert den ausdrücklich verlangten vollständigen Stock-Rasterbestand; vor bindender Quadrat-Zusage bleibt der alte geometrische Konflikt separat offen. |
| T19 „Reihe entfällt“ vs. alle Tasten erhalten | Row-Drop ist **kein akzeptierter Fix**; T19-Hand-off vor Stock-Freigabe nach S1/S2 korrigieren. |
| Stock-Pixelperfektion vs. spezielle Fehler-/Aufnahmestati | Eigene klar markierte Zustandsfarben/Marker sind absichtliche Erweiterungen; Originalquellfarben und eigene Tokens getrennt halten. |

## Abschluss

**ABGESEGNET:** AOSP-LatinIME-LXX-Farb-/Rasterrichtung, konkrete Hell/Dunkel-Tokens,
flache Anmutung, konstante Diagnose-/Infoleiste und 0 Assetänderungen als Umsetzungsziel.
**NICHT ABGESEGNET:** ein Build, der bei 650 px die unterste Reihe vollständig statt
halb entfernt, Diagnose verschwinden lässt oder eine heutige Taste für Stock-Ästhetik löscht.
**OFFEN:** T19-Integration mit korrekter Root-/Child-Budgetebene und nachfolgende
Hell/Dunkel-/Zweitöffnungs-Screenshots auf dem Gerät; echte 48-dp-Quadrate sind nicht nachgewiesen.
**VERIFIKATION:** Nur lesende lokale/Original-AOSP-Quellenprüfung und dieses neue Dokument;
keine Builds, kein adb, keine parallelen Quell-/Assetdateien geändert.

## D11 — Rueckmeldung gedrückte Taste (entschieden 2026-10-30)

Dieser Nachtrag ersetzt die schwache Press-Deckkraft und die reine Caps-Glyphen-
Rückmeldung aus §1; sonst bleiben Palette, Geometrie und Bedienlogik unverändert.
Der übergebene Emulatorbefund (27/255 hell, 21/255 dunkel) trägt die Entscheidung;
hier wurden weder neue Screenshots gemessen noch Kotlin-Dateien geändert.

| Punkt | Verbindlicher Entscheid | Begründung |
|---|---|---|
| **1. Gedrückte Fläche** | **Ja, deutlich sichtbarer:** Hell Alpha **0x66** (40 %), Overlay **#6637474F** über **#FFECEFF1**; Dunkel Alpha **0x4D** (rund 30 %), Overlay **#4DFFFFFF** über **#FF263238**. Weiter SrcOver-Komposition zu einer deckenden Fläche; vorhandene Textfarben bleiben. | Dieselben vorhandenen RGB-Farben, nur höhere Deckkraft: das verbessert die erkennbare Druckreaktion, ohne die flache Stock-Anmutung durch Rahmen, Schatten, Rundungen oder neue Farbfamilien zu verlassen. |
| **2. Buchstaben-Popup** | **Bewusst kein Popup** in diesem Änderungsumfang, auch kein erzwungenes Großbuchstaben-Preview. | Der stärkere Tastendruckzustand liefert die unmittelbare Rückmeldung; ein Popup der obersten Reihe könnte die Diagnose-/Infoleiste verdecken und verlangte zusätzliche Positionierungs-/Clippinglogik, deren Ungefährlichkeit bei unseren schwankenden Höhen nicht belegt ist. |
| **3. Caps-Zustand** | **Die aktuelle reine Glyphenfärbung reicht nicht:** Bei tatsächlich eingerastetem **capsLock** wird die Shift-Tastenfläche dauerhaft mit dem vorhandenen **accent** gefüllt: Hell **#FF4DB6AC**, Dunkel **#FF80CBC4**; dazu die bereits vorhandene kontrastierende **micRecordingGlyph**-Farbe, Hell **#FF37474F**, Dunkel **#FF263238**. Der **⇪**-Caps-Marker bleibt. | Eine gefüllte bestehende Rechteckfläche mit kontrastierender Glyphe unterscheidet Caps klar vom kurzen Shift-Zustand; vorhandene Akzent-/Gegenfarben verhindern zugleich Akzent-auf-Akzent, ohne neuen Pfeilpfad, Custom-Form oder zusätzliche Farben. |

Die Press-Alphas sind eine **bewusste projektspezifische Verstärkung** der zuvor
belegten AOSP-LXX-Werte 0x26/0x19, keine Behauptung, AOSP Android 16 verwende diese
neuen Werte exakt. Der Gboard-Vergleich ist eine Wahrnehmbarkeitsreferenz, kein
Wechsel unseres visuellen Referenzprodukts.

Caps-Füllung wird ausschließlich aus dem echten Caps-Zustand abgeleitet, nicht aus
„letzte Eingabe war groß“: Shift+q→A allein beweist keinen eingerasteten CapsLock.
Temporäres Shift bleibt wie bisher akzentuiert; symbolsShift ist kein Caps-Zustand.
Gedrückt+Caps behält diese Akzentfüllung mit Gegenfarbe, ohne zusätzlichen Overlayauftrag;
nach Loslassen bleibt sie bei Caps erhalten, nach Deaktivieren verschwindet sie. Keine Änderung an
Doppeltipp, Eingabetext, Touchflächen oder Mic-Aufnahmepriorität.

**Abnahme für GLM:** Hell/Dunkel jeweils normal/gedrückt und Caps an/aus aus derselben
APK vergleichen: klar sichtbare flächige Druckreaktion ohne Rahmen/Schatten, kein
Popup über der Leiste, Caps-Füllung bleibt nach UP und verschwindet nach Abschalten.
Keine eingegebenen Inhalte in Diagnose-/Logtexten. Umsetzung klein halten: zwei
Overlay-Alphas und zustandsabhängige Wiederverwendung vorhandener Akzent-/Gegenfarben;
kein neuer Theme-/Window-/Layoutmechanismus. Gerätebeweis steht noch aus.
