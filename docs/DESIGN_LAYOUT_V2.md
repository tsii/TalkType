# D-LAYOUT-V2 – kleinere Tastatur, Aktionspositionen und sichere Höhenkette

**Status: Teilfreigabe des Entwurfs; keine uneingeschränkte Geometrie-Absegnung.**
Die vertikale Zielgeometrie und die vier gewünschten Aktionspositionen sind entschieden.
Die zusätzliche harte Forderung **jede Touchfläche mindestens 48 × 48 dp** ist mit
allen bestehenden Zeichen gleichzeitig auf einer Smartphone-Hochformatseite nicht
vereinbar. Dies wird unten ausgerechnet, nicht durch überlappende Trefferflächen
oder erfundene Gerätebreiten kaschiert. Kein Coder soll einen solchen Widerspruch
als „48 dp erfüllt“ ausliefern.

Nur dieses Dokument wird erstellt. Kein Build, kein adb, keine Quell-/Assetänderung.
Gelesen: PLAN_AUDIO_UND_LAYOUT §2, LAYOUT_REQUIREMENTS vollständig, DESIGN §7,
KeyboardView, ImeRootView, KeyboardModel sowie die Struktur sämtlicher 15 JSON-Assets.
Zeilenangaben beziehen sich auf den gelesenen Stand, nicht auf spätere Paralleländerungen.

## 1. Abschneiden beim zweiten Öffnen: Befund statt erfundener Gewissheit

### Nachweisbare Höhenkette

| Stelle | Gelesener Befund | Konsequenz |
|---|---|---|
| `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:135–147` | `heightMeasureSpec` wird nicht ausgewertet; Höhe immer `max(240dp, model.heightPx)`. | Die View kann mehr Höhe melden als das aktuelle IME-Fenster anbietet; unten liegende Inhalte sind dann abschneidbar. **Das ist die konkrete vertragswidrige Stelle.** |
| `KeyboardView.kt:141,146` | Rebuild-Guard vergleicht `lastMeasuredHeight` mit dem bisherigen eigenen `measuredHeight`, nicht mit dem neu angebotenen Platz. | Ein geändertes Elternbudget beim Wiederöffnen löst darüber keinen verlässlichen Rebuild aus. |
| `KeyboardView.kt:108–120,156–160` | Modell erhält konstante 52-dp-Tasten und 6-dp-Reihenabstand; `onSizeChanged(w,h)` baut nur mit w neu. | Auch ein erfolgter Callback passt die Geometrie nicht an ein kleineres Höhenangebot an; allein „öfter rebuilden“ reicht nicht. |
| `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt:53,65–76,105–112` | Statusinhalt 28 dp; vertikaler Stack mit WRAP_CONTENT-Keyboard und alternativem Emoji-Block; Root ist **FrameLayout**, nicht selbst LinearLayout. | Elternbudget muss Status und aktuell sichtbaren Inhalt gemeinsam berücksichtigen; Kimi beschreibt hier den Stack, nicht exakt den Root-Typ. |
| `ImeRootView.kt:116–120` | Navigationsinsets werden als absolutes unteres Root-Padding gesetzt. | Platzbedarf besteht zusätzlich; die gelesene Zuweisung addiert **nicht** wiederholt auf paddingBottom. Doppelberücksichtigung im Elternfenster bleibt eine zu prüfende Hypothese. |
| `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:240–243` | `onStartInputView` ruft nur `updateStatus()` auf. | Hier wird kein expliziter neuer Sichtbarkeits-/Platzabgleich angestoßen; normale Android-Messungen können trotzdem stattfinden. |

**Warum erst beim zweiten Mal? ANNAHME:** Das erste sichtbare Fenster bietet ausreichend
Platz; beim Wiederöffnen ist das verfügbare Budget oder die Inset-Verrechnung anders.
Die View ignoriert diesen Unterschied und bleibt inhaltsgroß. Das erklärt das Muster,
beweist ohne Messung beider Aufbauten aber weder den auslösenden Systemzustand noch
„exakt eine halbe Reihe“. Ein gleichbleibendes Elternbudget würde durch den Guard
allein nicht plötzlich kleiner; diese Einschränkung ist wichtig.

Nicht gefunden: ein `setKeyboardView`-Größensummierer, kumulatives Padding oder eine
StatusRow-VISIBLE/GONE-Umschaltung im gelesenen Pfad. Diese Ursachen werden deshalb
**nicht** als erwiesen behauptet. Kimi §2.1 ist eine plausible Arbeitsdiagnose,
kein bereits erbrachter Zweitöffnungs-Messtest.

### Verbindliche Forderung

**„Die Höhe wird bei jedem Sichtbarwerden aus dem aktuellen Zustand neu bestimmt.“**
Dazu gehören aktuelle verfügbare Breite/Höhe, tatsächliche Insets, Statussichtbarkeit,
aktive Ebene/Zeilenzahl und Tastatur-/Emoji-Modus. Kein Wert vom ersten Öffnen ist
allein gültige Quelle; kein alter Rect-Satz darf nach geändertem Angebot weiterleben.

- Root entscheidet **einmal**, welcher Platz nach Status und korrekt berücksichtigter
  Navigation für das sichtbare Inhaltskind verbleibt; kein doppelter Insetabzug.
- Keyboard-Messung respektiert EXACTLY/AT_MOST; Modellhöhe, Viewhöhe und sichtbare
  Clipgrenze werden konsistent. Nur `resolveSize` ohne passenden Modellaufbau wäre falsch.
- Rebuild-Schlüssel sind tatsächliche Breite, Inhaltsbudget, Ebene/Rows und Dichte;
  kein Selbstvergleich mit einer vorherigen eigenen Messung.
- Insets, Moduswechsel, Feldwechsel und erneutes Sichtbarwerden veranlassen einen
  neuen Layoutabgleich; keine LayoutParams-Margenrechnung in `onLayout`.
- Reicht die Fläche nicht für die Mindestziele, ist dies ein expliziter Fit-Konflikt;
  nicht heimlich unter 48 dp skalieren und nicht unten clippen. Kein künstliches
  „Erfolg“-Layout, solange die letzte Taste nicht vollständig im sichtbaren Bereich liegt.

## 2. Größenentscheidung: aktuelle Werte und vertikales Ziel

Der gelesene Istwert ist **52 dp**, nicht 62 dp: `KeyboardView.kt:42–44`.
Vier Reihen ergeben 4×52 + 3×6 = 226 dp Modellinhalt, durch das Minimum jedoch
**240 dp Viewhöhe**; plus nominal 28 dp Status = **268 dp** ohne Systemnavigation.
PT base/shift hat fünf Reihen: 5×52 + 4×6 = 284 dp + 28 = **312 dp**.

**Neues Ziel:** normale Tastenhöhe **48 dp**, Fußreihe mit Mic **52 dp**,
Reihenlücken **2 dp**, nicht-interaktive Statuszeile nominal **24 dp**.
Touchfläche und Zeichnung behalten dieselbe Geometrie; keine 44-dp-Zeilen mit nur
behaupteten 48-dp-Zielen. 48 dp ist die verlangte Untergrenze und das Minimum für
Daumenbedienung; die häufig gehaltene Mic-Taste wird absichtlich etwas höher/breiter.

| Fall | Tasteninhalt | Mit 24-dp-Status, ohne Systemnavigation | Änderung |
|---|---|---|---|
| Vier Reihen | 3×48 + 52 + 3×2 = **202 dp** | **226 dp** | **268 → 226 dp**, 42 dp bzw. rund 16 % weniger |
| Fünf Reihen, PT base/shift | 4×48 + 52 + 4×2 = **252 dp** | **276 dp** | **312 → 276 dp**, 36 dp bzw. rund 12 % weniger |

Die Navigation wird auf beiden Seiten separat mit demselben Verfahren betrachtet;
ein weggefallenes Inset zählt nicht als Tastaturverkleinerung. Große Systemschrift
kann die Statuszeile höher benötigen; dann tatsächliche Höhe ausweisen, nicht Text
abschneiden. Die Zielwerte sind Designmaße, noch keine Pixel-Gerätemessung.
KONZEPT-Treue: PTT halten/loslassen, Idempotenz und entkoppelte Aufnahme bleiben
unverändert; die kleinere Darstellung ändert keine ASR-/Commitregel.

## 3. Reihen und Breiten – harte Machbarkeitsgrenze

W bezeichnet die **tatsächlich nutzbare Breite in dp**, nach Seiteninsets.
Feste Ziele mindestens 48 dp breit; verbleibende Zeichentasten gleichmäßig verteilen,
jedoch ebenfalls niemals unter 48 dp. Prozentwerte werden deshalb aus W abgeleitet,
nicht als geratenes „width 1.5“ ohne Bezug zur Displaybreite behauptet.

| Reihe | Reihenfolge / Inhalt | Breitenvorgabe in % von W | Höhe |
|---|---|---|---|
| Oberste Zeichenreihe | Debug-Teilen, Schutzlücke, bisherige erste Zeichenreihe vollständig, Backspace | Teilen und Backspace je `100×48/W %`; Schutzlücke `100×8/W %`; Zeichen teilen den Rest nach den übrigen 2-dp-Lücken | 48 dp |
| Zweite Zeichenreihe | Bisherige Zeichen vollständig, kein neuer Backspace mitten im Block | Gleichanteile des verfügbaren Zeilenrests, je mindestens `100×48/W %` | 48 dp |
| Letzte Zeichenreihe vor Fußreihe | Shift, bisherige Zeichen, **Enter an bisheriger Backspace-Stelle** | Shift/Enter je mindestens `100×48/W %`; Zeichen teilen Rest | 48 dp |
| Zusätzliche PT-Akzentreihe | Erhalten, einschließlich ihrer Buchstaben; Shift/Enter auf der letzten Zeichenreihe | Gleiche Mindestregel, keine Akzente entfernen | 48 dp |
| Fußreihe | ?123/ABC, 🌐, 😊, Space, ‹, ›, **Mic ganz rechts** | Die fünf kleinen Aktionen je `100×48/W %`; Mic `100×96/W %`; Space `100×(W−348)/W %`; sechs Lücken à 2 dp | 52 dp |

Fußreihe: 5×48 + 96 + 6×2 = 348 dp ohne Space; Space erhält den Rest und benötigt
mindestens 48 dp. **Mindestbreite Fußreihe 396 dp.** Rechenbeispiel W=411 dp:
kleine Aktionen je 11,68 %, Mic 23,36 %, Space 15,33 %, Lücken zusammen 2,92 %.
Damit bleibt die Space-Taste erreichbar, aber bewusst schmaler als bisher; für lange
Wörter gilt die bestehende SPACE-Label-Kompaktregel statt abgeschnittener Beschriftung.

### Der nicht wegzuzeichnende Breitenkonflikt

Bei DE enthält die erste Reihe elf Buchstaben. Mit Teilen und Backspace sind das
13 Ziele: **13×48 + 8 + 11×2 = 654 dp Mindestbreite** einschließlich Schutzlücke.
CZ hat in der letzten Zeichenreihe **13 Buchstaben** plus Shift und Enter:
**15×48 + 14×2 = 748 dp**. Dieses reale Asset ist die härteste hier betrachtete Reihe.
Schon ohne die neuen Aktionen sind elf nebeneinanderliegende 48-dp-Ziele auf einer
rund 411-dp-breiten Smartphone-Tafel unmöglich. Überlappende virtuelle Ziele lösen
das nicht: Sie können nicht gleichzeitig eindeutig in ihren eigenen 48-dp-Breiten liegen.

**ANNAHME:** Das Pixel-Hochformat liegt unter diesen 748 dp Nutzbreite; seine aktuellen
dp-Maße wurden hier nicht gemessen. Die früheren Emulatorwerte um 411 dp werden nicht
als neue Pixel-Messung ausgegeben. Bei kleinerer Breite als dem Zeilenminimum ist
obige Einseiten-Anordnung **nicht vollständig freigegeben**.

**Entscheidung:** Ich segne keinen scheinbar fertigen Pixel-Entwurf mit unterschrittenen
48×48-Zielen ab. Die vertikale Korrektur kann unabhängig umgesetzt werden; vor dem
vollständigen Reflow ist eine Produktentscheidung über **mehr Zeichen-Seiten/andere
Zeilenverteilung** oder eine ausdrücklich gelockerte Mindestbreite nötig. Da die
Mindestgröße hier hart ist, wird sie nicht eigenmächtig gelockert. Ebenso wird nicht
ungefragt eine mehrseitige Buchstabentastatur als vermeintliche kleine Layoutkorrektur
eingeführt. Keine Rückfrage in diesem Auftrag; der Konflikt ist ein dokumentierter
Geometrie-Blocker, keine fehlende Werkzeugfreigabe.

## 4. Vier neue Aktionspositionen und Interaktionsregeln

- **Backspace:** äußerste rechte Taste der **ersten** Zeichenreihe, nicht „Reihe 2“
  aus Kimis Vorschlag, wenn damit die zweite Zeichenreihe gemeint ist; Status zählt
  nicht als Tastenreihe. Halten/Repeat und graphemsicheres Löschen erhalten.
- **Enter:** äußerste rechte Taste der letzten Zeichenreihe vor Space; in DE/EN
  die bisherige dritte, in PT base/shift die bisherige vierte Zeichenreihe.
  Editoraktion/Zeilenumbruch bleibt semantisch unverändert. Kein zusätzliches Enter unten.
- **Mic:** ganz rechts in der Fußreihe, **96×52 dp Zielmaß**, gefüllte Akzentfläche,
  großes Mikrofon-Piktogramm; IDLE klar abgesetzt, STARTING sichtbares Warten,
  RECORDING gefüllt plus unverwechselbare „Aufnahme“-Anzeige, FINALIZING als
  Verarbeitung/gesperrter Neustart. Farbe allein ist kein Zustandsbeweis.
- **Teilen:** links vor dem ersten Zeichen, bei DE/EN direkt links neben Q, bei
  FR links neben A, RU links neben й – keine künstliche Q-Taste für andere Alphabete.
  Icon-Idee neutrales Teilen-Symbol, deutsche Bedeutung **„Debug-Infos teilen“**;
  kompakte sichtbare Legende **„Info“**, vollständiger Accessibility-Name.

### Genau eine PTT-Fläche

Der bisherige kleine Mic-Button der Statuszeile entfällt; der alte Fußreihen-Mic wird
an die neue ganz rechte Stelle versetzt und nicht dupliziert. Status bleibt als
nicht-interaktiver Zustandstext, verschwindet beim Idle nicht unvorhersehbar.

**Wichtiger realer Befund:** Der Status-Mic hat heute einen DOWN/UP-Touchlistener
(`VoiceKeyboardService.kt:208ff`); der Keyboard-Mic geht dagegen über normalen
Key-Release (`KeyboardView.kt:225ff`) zum Mic-Action-Zweig (`VoiceKeyboardService.kt:575ff`).
Bloß das Icon umzuziehen würde PTT deshalb nicht zuverlässig umziehen.
Die neue einzige Fläche muss DOWN→Start und UP/CANCEL→geordnetes Stop/Abort liefern;
niemals beim Loslassen erst starten oder auf einen zweiten Toggle warten.
Neue Gesten-/Zustandsverträge sind zu implementierende Schnittstellen, keine hier
behaupteten vorhandenen Methoden. Main-Thread bleibt frei, kein Inferenz-Warten.

Emoji-V2 bleibt ein vollständiger alternativer Inhaltsmodus mit nativem Rückbutton.
Bei offenem Emoji gibt es keine versteckte Mic-Trefferfläche der GONE-Tastatur;
für Aufnahme zuerst „Zur Tastatur“. Kein erneuter Status-Mic als zweiter Ausgang.

### Teilen nicht versehentlich auslösen

48×48-dp-Ziel, **8 dp nicht interaktive Schutzlücke** zum ersten Buchstaben;
die bestehende nächstgelegene-Taste-Logik darf diese Lücke nicht als Teilen-Hit auffüllen.
Kurztipp zeigt nur den Hinweis „Halten Sie Info, um Debug-Infos zu teilen“;
Langdruck ab **600 ms** öffnet die bewusste Auswahl **„Kopieren“ / „Teilen“**.
Die nachfolgende UP-Geste darf keinen weiteren Klick/Zeicheneintrag erzeugen.
Während Aufnahme/Finalisierung keine externe Share-Aktion, normal tippen bleibt möglich.

Nur der begrenzte technische Debug-Snapshot aus PLAN §3, kein Feldtext, Audio, Logcat,
Stacktrace, Modellpfad oder Konto-/Gerätebezeichner wie Seriennummer. Erst die gewählte
Aktion kopiert bzw. öffnet den Android-Sharechooser; kein automatisches Clipboard.
Ein explizites externes Teilen kann den Editorfokus ändern – anders als Emoji wird
dafür keine unveränderte InputConnection über den Activitywechsel zugesichert.
Die App selbst sendet dabei nichts ins Netz; Signal o. Ä. ist Nutzerentscheidung.

## 5. Gemeinsame Definition statt 15 repetitiver Assetumbauten

**Empfehlung: gemeinsame Aktionsrahmen-Komposition; 0 Änderungen an den 15 Sprach-JSONs.**
Die 15 Dateien wurden gelesen; base/shift haben gleiche Struktur, PT base/shift
haben fünf statt vier Reihen, CZ/PL/RU deutlich mehr Zeichen in einzelnen Reihen.
Alle vier Ebenen enthalten ihren bisherigen Aktionsrahmen; diese Daten bleiben
für den bestehenden Parser und die Sprach-/Pflichtzeichenprüfungen unangetastet.

Eine **neu zu implementierende**, reine gemeinsame Komposition übernimmt den
geparsten Layer: Sprachzeichen samt IDs, Reihenfolge, Alternates, Case-Metadaten und
lokalisiertem Space-Label behalten; alte Aktionspositionen herauslösen und die
oben beschriebene gemeinsame Aktionsanordnung genau einmal ergänzen.
Der Renderer und die Geometrietests verwenden ausschließlich dieses Ergebnis,
nicht einen gleichzeitig weiter wirksamen alten Aktionsrahmen. Auch `sourceKeys`,
Touch-Dispatch und Accessibility-Knoten müssen dieselbe komponierte Quelle verwenden:
eine nur gezeichnete Share-/Mic-Taste ohne passenden Aktionsdatensatz ist ein Fehler.
Keine zweite gezeichnete Footer-/Mic-Schicht und keine transparenten Overlay-Touches.

| Vertrag | Erhaltung / notwendige Anpassung |
|---|---|
| 15 Assets × 4 Ebenen | Alle **60 effektiven Ebenen** müssen neue Positionen besitzen; die Roh-JSONs bleiben bytegleich. Tests unterscheiden ausdrücklich Rohdatenvollständigkeit und komponierte Darstellung. |
| VK-HIT | Weiter reale lokale Rects und Touchkoordinaten desselben Modells wie onDraw; zusätzlich Eltern-/sichtbare Grenzen und Aufbaunummer für den Höhenbeweis protokollieren, keine Feldinhalte. Alte Screenshots/Rects gelten nicht als V2-Beweis. |
| Shift/Caps | Gleiche Zustandslogik, gleiche Taste/ID; kein Reset beim Relayout; Zweittipp bleibt Caps, symbols↔symbolsShift bleibt Toggle. |
| Regel 4a / Symbole | Rohasset-Prüfung bleibt; effektive Shift-Taste sitzt weiterhin links in der letzten Zeichenreihe, **keine** neue falsche layer-base-Aktion. ABC bleibt links in der Fußreihe. Dokumentarisch feste Zeilenindizes durch semantische Position ergänzen. |
| Zeichen-/Case-Prüfungen | Unverändert: keine Buchstaben, Ziffern oder Pflichtsymbole als Platzgewinn löschen oder in unerreichbare Alternates verschieben. |
| Neue Aktion | `share-debug` ist **neu**; in `layouts/LayoutAsset.kt`/KeyActions-Whitelist und den Parser-Vertrag aufzunehmen, nicht als schon existierende API ausgeben. |
| Emoji | V2-Komposition bleibt ohne Overlay; Rückkehr stellt aktuelle gemessene Tastatur vollständig wieder her, einschließlich PT-Fünfzeilenfall. |

Eine Forderung „Position muss wörtlich in allen 15 Roh-JSONs geändert sein“ wäre mit
der gemeinsamen Komposition unvereinbar und unnötig. Fachlich relevant sind alle
15 **gerenderten** Layouts. Bei Verzicht auf gemeinsame Komposition wären dagegen
15 Assets/60 Ebenen zu ändern; beide Strategien nicht mischen.

## 6. Verworfen

- 44-dp-Zeilen aus PLAN §2.2: unterschreiten die hier harte Touch-Untergrenze.
- `min(inhalt, Platz)` ohne neue Rects: kaschiert nur die Messung, schneidet weiter Tasten ab.
- Den zweiten Aufbau mit immer größeren festen Höhen oder negativen Insets „reparieren“:
  verdeckt den Messvertragsfehler und kann andere Fenster/Emoji erneut aufblasen.
- Mic unten rechts und Enter daneben am alten Footerort behalten: widerspricht dem
  gewünschten Enter-Umzug und nimmt der prominenten Aufnahmefläche unnötig Platz.
- Share-Debug ohne Schutzlücke/bei Kurztipp direkt extern öffnen: zu fehleranfällig neben Q.
- Pauschale Vier-Reihenannahme: PT und dichte CZ/PL/RU-Daten widerlegen sie.
- 15 kopierte Aktionsrahmenänderungen: vermeidbare Drift; gemeinsame Komposition bevorzugt.
- Unangekündigtes Paging oder Überlappen von 48-dp-Hitboxes: keine redliche Erfüllung
  der gleichzeitig verlangten kompakten Einseiten-Tastatur.

## 7. TDD-Aufträge – Reihenfolge, jeweils höchstens vier Dateien

Alle neuen Dateinamen unten sind **Vorschläge**, keine behaupteten existierenden Dateien.
Quellpfade mit Kurzpräfixen: `main` = `app/src/main/java/de/voicekeyboard/`,
`test` = `app/src/test/java/de/voicekeyboard/`, `androidTest` analog.
Tests zuerst; erfolgreiche JVM-Tests ersetzen keine Pixel-/Zweitöffnungsabnahme.

| Auftrag | Dateien, maximal 4 | Zuerst roter Test / Prüfkriterium |
|---|---|---|
| **G1 Höhenvertrag, unabhängig vom Breiten-Reflow** | `main/ime/ImeRootView.kt`; `main/keyboard/KeyboardView.kt`; `main/keyboard/KeyboardModel.kt`; **neu** `androidTest/keyboard/KeyboardFitTest.kt` | **secondShowKeepsFullFooter**: dieselbe IME-Instanz zeigen→verbergen→zeigen, aktuelles Angebot/Insets variieren; unterste Rect-Unterkante liegt vollständig in View **und** sichtbarem Parent. DE sowie PT, Status/Emoji-Wechsel mitprüfen; Pixel-Screenshots beider Aufbauten sind Pflicht. |
| **G2 Gemeinsame Komposition und Positionen** | **neu** `main/keyboard/LayoutChrome.kt`; **neu** `test/keyboard/LayoutChromeTest.kt`; `main/layouts/LayoutAsset.kt`; `docs/LAYOUT_REQUIREMENTS.md` | **all15EffectiveLayoutsHaveNewAnchors** über alle 60 Ebenen: Share erstes Ziel, Backspace oben rechts, Enter letzte Zeichenreihe rechts, Mic Fußreihe rechts genau einmal; gleiche Zeichenmengen/Shift-Paare wie vorher. Neue share-debug-Aktion zulässig. |
| **G3 Größen-Machbarkeit, vor Rendererfreigabe** | `main/keyboard/LayoutChrome.kt`; `main/keyboard/KeyboardModel.kt`; `test/keyboard/KeyboardModelTest.kt`; **neu** `test/keyboard/KeyboardTargetSizeTest.kt` | **everyTargetIs48By48OrReportsUnfit**: alle 60 Ebenen bei realem W prüfen; Ziele ≥48×48 und nicht überlappend, sonst explizites Unfit. Der 411-dp/CZ-Fall muss derzeit **Unfit**, nicht „grün durch kleinrechnen“ liefern. **Vor vollständigem Reflow ist die Breitenentscheidung Pflicht.** |
| **G4 Rendering, Aktionenschutz und Zustände** | `main/keyboard/KeyboardView.kt`; `main/keyboard/LayoutChrome.kt`; `main/ime/ImeRootView.kt`; **neu** `androidTest/keyboard/KeyboardInteractionV2Test.kt` | **drawnAndHitRectsAgree** inkl. Teilen-Schutzlücke; **shiftAndSymbolRoundTripSurvivesRelayout**; **micShowsRecordingState**: Mic-Rect 96×52, Aufnahmefüllung/Status statt nur generischer Pressfarbe. Noch keine parallele zweite PTT-Fläche. |
| **G5 Einziger echter PTT-Einstieg** | `main/keyboard/KeyboardView.kt`; `main/ime/VoiceKeyboardService.kt`; `main/ime/ImeRootView.kt`; **neu** `androidTest/keyboard/MicGestureV2Test.kt` | **holdStartsReleaseStopsOnce**: DOWN startet genau einmal, UP/CANCEL stoppt genau einmal; Repeat/Relayout/Hide ohne zweite Session; Recording/Fertigstellung unabhängig von Inferenzlast. |
| **G6 Privater Debug-Snapshot** | **neu** `main/debug/DebugInfo.kt`; **neu** `test/debug/DebugInfoPrivacyTest.kt` | **snapshotContainsNoInput**: maximal 25 Zeilen, nur erlaubte technische Felder/Codes, kein diktierter oder getippter Inhalt, keine Rohlogs/Pfade. |
| **G7 Bewusstes Kopieren/Teilen** | `main/ime/VoiceKeyboardService.kt`; **neu** `main/debug/DebugShare.kt`; `app/src/main/res/values/strings.xml`; **neu** `androidTest/debug/DebugShareTest.kt` | **shortTapDoesNotExport** und **confirmedShareUsesTextPlain**: kurzer Tap kein Clipboard/Chooser; Langdruck plus bewusste Auswahl erzeugt genau eine Aktion; kein Export während Diktat, kein Zeichen im Feld. |

Nach jedem integrierten Layoutstand: zweite/dritte Öffnung, Feldwechsel, Emoji-Rückkehr,
DE/EN/RU/PT/CZ, beide Symbolebenen, Gesten-/3-Tasten-Navigation und große Schrift
prüfen. Sichtbarkeit braucht Screenshot plus Rect-/Clipbeweis; uiautomator allein
sieht die IME nicht zuverlässig. Keine Gerätebefehle werden in diesem Auftrag ausgeführt.

## 8. Absegnung und offene Grenze

**FERTIG:** Ursache als konkreter Messvertragsfehler lokalisiert; zweite-Öffnung-Trigger
als Hypothese statt erfundener Messung benannt. Zielhöhe und vier Positionen entschieden,
0 Roh-Assetänderungen durch gemeinsamen Aktionsrahmen empfohlen, TDD-Aufträge begrenzt.

**ABGESEGNET:** Höhenkettenkorrektur und vertikales Ziel 268→226 dp / PT 312→276 dp;
Backspace oben rechts, Enter am alten Backspace-Ort, Mic unten rechts, Info links vor
Q bzw. lokalem ersten Buchstaben. Diese Teilfreigabe erlaubt keine ungeprüfte Auslieferung.

**BLOCKER:** Eine Smartphone-Einseite mit unverändert allen Zeichen und echten
48×48-dp-Zielen ist unterhalb der berechneten Mindestbreiten unmöglich. Keine
Gesamtfreigabe der Pixel-Geometrie vor einer offen dokumentierten Auflösung dieses
Produktkonflikts; die Tastatur darf nicht nur für den Test künstlich breiter werden.

**ANNAHME:** Aktuelles Pixel-Platzbudget/zweite Insets sind nicht gemessen. Neue
Positions-/Mindestbreiten sind Designvorgaben, keine bereits getesteten Rects.
**VERIFIKATION:** Nur lesende Prüfung und dieses neue Dokument; kein Build/adb/Commit.
