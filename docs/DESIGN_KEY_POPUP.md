# D12 – Langdruckmenü für Alternativzeichen

## 0. Entscheidung und gelesene Nähte

**Entscheid:** Langdruck auf eine Zeichentaste öffnet ein kleines Auswahlmenü;
Finger zur Alternative schieben, dort loslassen → genau dieses Zeichen einfügen.
Kurzes Tippen bleibt unverändert. Kein automatisches Großbuchstaben-Preview bei jedem
DOWN: D11s Verzicht auf das Kurz-Tipp-Popup bleibt, das jetzt ausdrücklich gewünschte
**Langdruckmenü** ist eine andere Interaktion.

Nur dieses neue Dokument; kein Code-/Asseteingriff, Build, Emulator/adb oder Commit.
Gelesen: `keyboard/KeySurface.kt`, `KeyboardTheme.kt`, `KeyboardView.kt`,
`layouts/LayoutAsset.kt`, `LayoutParser.kt`, aktuelle Root-Struktur, DE-Assets und
Alternates-Prüfung in `LayoutCompletenessTest.kt:265ff`.
Präfixe `keyboard/` und `layouts/` stehen unter `app/src/main/java/de/voicekeyboard/`.

- `KeySurface.decide` bleibt **einzige** Naht für Grundtastenflächen und deren
  Priorität: micRecording > CAPS_FILL > pressed > normal. Nicht duplizieren.
- Theme bleibt **einzige** Farb-/Maßquelle; Popup-Maße sind dort künftig zusätzliche
  benannte Designwerte, keine neuen Farben und keine zweite Palette.
- `LayoutKey.alternates` ist bereits `List<String>`, Parser liest die Liste; die
  vorhandenen Tests verlangen höchstens **8** Einträge. Kein verschachteltes JSON
  und keine neue Interpretationssprache im Asset.
- Die neue reine Logiknaht `LongPressMenu` ist ein **Implementierungsvorschlag**,
  kein heute vorhandener Android-Handler. Sie liefert Zustand/Geometrie/Auswahl;
  Android-Ereignisse/Timer bleiben Adapteraufgabe des vorhandenen KeyboardView.

## 1. Popup-Gestalt mit Maßen

Bezugsmaß: density 420 = **2,625 px/dp**, Referenz **1080×2400 px**.
Normalfall **eine Reihe**, maximal **8 Alternativen**, Grundzeichen bleibt auf seiner
wirklichen Taste und ist **keine zusätzliche neunte Menüzelle**.

| Maß/Token | Verbindlicher Wert | Referenzpixel |
|---|---|---|
| Zellbreite/-höhe | **48×48 dp**, nicht die schmale Grundtastenbreite | **126×126 px** |
| Innenpadding des Menüs | **4 dp** auf allen Seiten | 10/11 px, Rundungsreste verteilt |
| Menügröße | `48×n + 8 dp` breit, **56 dp** hoch | n=1: **147×147 px**; n=3: **399×147**; n=7: **903×147**; n=8: **1029×147** |
| Abstand zur Grundtaste | **8 dp** | **21 px** |
| Abstand zum Keyboard-Cliprand | **8 dp** | **21 px** links/rechts/oben/unten |
| Außenradius | **4 dp**, nur Menü-Außenkante | etwa **11 px** |
| Rahmen / Schatten / Zeiger-Spitze | **keine / 0 dp / keine** | Kein neuer Sprechblasenpfad |
| Zeichengröße | **24 sp**, normaler Typeface, kein erzwungenes Uppercase | 63 px bei fontScale=1 |
| Menüfläche | **palette.keyPressed** deckend | Hell **#FFA3ABB0**, Dunkel **#FF676F74** aus vorhandener Komposition |
| Nicht ausgewähltes Zeichen | **palette.textPrimary** | Hell **#FF37474F**, Dunkel **#CCFFFFFF** |
| Ausgewählte Zelle / Zeichen | **palette.accent / palette.micRecordingGlyph** | Hell **#FF4DB6AC / #FF37474F**; Dunkel **#FF80CBC4 / #FF263238** |

Keine einzelnen Kachelschatten, keine neue Helligkeits-/Akzentfarbe. Der Name
`micRecordingGlyph` ist nur der bereits vorhandene Gegenfarbtoken und löst im Menü
keine Mic-Semantik aus. Zellhighlight bleibt innerhalb der Menü-Außenkante geclippt.
Die normale gedrückte Ursprungstaste wird weiterhin ausschließlich über KeySurface
bestimmt; eine ausgewählte Menüalternative ist kein CAPS_FILL-/Recording-Zustand.
Die zusätzliche Menüzeichnung allein löst kein requestLayout aus; eine erneute
Messung mit identischem Layout/Rects gilt nicht als Größenwechsel oder Abbruch.

### Position, erste Reihe und Statusschutz

Eine Zeichenebene **zuletzt im bestehenden KeyboardView zeichnen**, innerhalb seines
aktuellen Clips; kein Dialog/PopupWindow, kein neues FrameLayout-Messkind, kein
WRAP_CONTENT-Overlay, keine neue Root-Höhe und kein Editor-/Windowwechsel.
Der Popupzustand wächst nicht in den S1/T20-Höhenvertrag hinein.

Standard: Menüunterkante = Grundtastenoberkante minus 21 px; horizontal Mittelpunkt
über Grundtastenmitte, dann innerhalb `[21, W−21]` verschieben. Für n=8 bei W=1080
sind nur 9 px Verteilungsspielraum übrig: 1029 px Menü + 42 px Außenabstand = 1071 px.
**48-dp-Zellen können bei acht Alternativen nicht zugleich ein sehr schmales Menü sein.**

Passt es oben nicht vollständig in den Keyboard-Clip, wird es **unter** die Grundtaste
mit demselben Abstand gesetzt. Insbesondere die erste Reihe darf nicht die Diagnose-
/Infoleiste überdecken oder außerhalb der IME ins Ziel-App-Fenster malen.
**Bewusste Ausnahme von „immer darüber“:** Die oberste Reihe öffnet nötigenfalls
unterhalb; ein garantiert oberhalb liegendes Menü und eine garantiert freie Leiste
sind dort geometrisch nicht gleichzeitig möglich. Keine versteckte Überdeckung.

Beispiel aus den übergebenen ungefähren Bildschirmkoordinaten: r0-Mitte 1884 px,
angenommene Zeilenhöhe 106 px → Grundtaste etwa 1831…1937. Ein 147-px-Menü darüber
läge bei 1663…1810 und überdeckte die Infoleiste um y≈1765. Deshalb unterhalb:
**1958…2105 px**. Für den Fußpunkt, Mitte etwa 2225 und beispielhaft 128 px Höhe:
Grundtaste 2161…2289, Menü darüber **1993…2140 px**.
Das sind hergeleitete Beispiele, keine neue Messung; echte Rects/Clipgrenzen entscheiden.

Bei schmaleren Fenstern: maximal vier Spalten und zwei Reihen sind als Platzfallback
zulässig (n=8: **200×104 dp = 525×273 px**), weiterhin 48-dp-Zellen. Nur wählen,
wenn die komplette Geometrie **oberhalb oder unterhalb**, ohne Überlappung mit der
Grundtaste, in den Keyboard-Clip passt. Nie über die Diagnoseleiste ausweichen.
Passt weder Variante: Menü nicht öffnen, Grundtaste bleibt bedienbar, Fit-Konflikt
für diese Konfiguration melden; keine Alternativen verstecken oder Ziele verkleinern.
Die 1080-px-Referenz nutzt die einreihige Variante; schmale Fenster sind separat abzunehmen.

## 2. Verhalten, Zeiten und Kollisionsregeln

| Ereignis | Entscheidung / Zahlen |
|---|---|
| DOWN auf Texttaste mit nichtleeren alternates | KeySurface-Pressfeedback sofort, Ursprungstaste/Layoutgeneration/Pointer merken; **450-ms**-Timer starten, noch kein Zeichen einfügen. |
| UP vor 450 ms innerhalb Ursprungstaste | Normales Grundzeichen genau einmal über bestehenden Textpfad einfügen; Timer löschen. |
| Bewegung vor Menüöffnung | Entfernung vom DOWN größer **8 dp / 21 px** beendet den Langdrucktimer für diese Geste; ein späteres Zurückfahren startet ihn nicht neu. Normales Grundzeichen nur bei anschließend gültigem UP auf der Ursprungstaste. Kein Slide-Typing in Nachbartasten. |
| 450 ms erreicht, Finger weiter gültig gehalten | Menü öffnen, noch keine Alternative automatisch auswählen; Grundzeichen bleibt als Ausgang markiert. Kein vorzeitiges Basis-Commit, das später gelöscht werden müsste. |
| Nach Öffnen UP auf tatsächlicher Grundtaste | Grundzeichen genau einmal, keine zusätzliche Alternative; Menü schließen. |
| Nach Öffnen Finger in Menüzelle | Diese Zelle markieren; **4-dp**-Wechselhysterese an inneren Zellgrenzen, damit der Rand nicht flackert. Am UP zählt die tatsächliche Zelle ohne veraltete Hysterese-Auswahl. |
| UP innerhalb einer Alternative | Genau deren sichtbaren Wert einmal committen; kein Grundzeichen davor/danach; Menü schließen. |
| UP außerhalb von Grundtaste und Menüzellen | **Abbruch, 0 Zeichen**; auch im 8-dp-Zwischenraum kein „letzten Treffer trotzdem übernehmen“. |
| ACTION_CANCEL, Pointerverlust, Hide, Editor-/Layout-/Größenwechsel | Timer und Menü sofort verwerfen, **0 Zeichen**, kein spätes Timer-Commit. Bei Capture-/Editorwechsel gelten bestehende Besitzregeln. |
| Nach Öffnung nach oben/unten wischen | Dient nur zur räumlichen Auswahl: oben beim normalen Menü, unten beim Flip-Fallback; keine neue Aktion/Seite, kein Scrollen. Zur Grundtaste zurück → Grundzeichen; außerhalb loslassen → Abbruch. |
| Mic DOWN/UP/CANCEL | **Mic zuerst klassifizieren, nie Langdrucktimer.** Vorhandene `onMicHoldStart` / `onMicHoldEnd(cancelled)` unverändert nutzen; 450 ms auf Mic öffnet nichts und darf PTT weder verzögern noch stoppen. |
| Backspace halten | Kein Alternativenmenü, bestehendes/separat vorgesehenes Repeat-Verhalten und dessen Rate unverändert. Dieser Auftrag führt keinen neuen Backspace-Timer ein. |
| Shift/Caps | Actions sind nicht popupfähig; **DOUBLE_TAP_MS=350** unverändert. Text-Langdruck verwendet 450 ms, zählt nicht als Shift-Tap und ändert Caps nicht. |
| Zweiter Pointer bei offenem Menü | Kein zweites Menü/Zeichen und kein PTT-Start aus einem versehentlichen Überstreichen; nur Ursprungspointer entscheidet. Bereits aktive Mic-Haltegeste wird nie vom Popup verdrängt. |
| Texttaste ohne Alternativen / Space / Globe / Info / Enter / Deadkey | Bisheriger Pfad; keine generische „lange halten = Menü“-Regel für Action-/Deadkeys erfinden. Info-/Globe- und PTT-Semantik bleibt getrennt. |

Der Ursprungspointer bleibt bis UP/CANCEL im Keyboard-Touchpfad; Auswahlgesten werden
nicht als neuer DOWN auf darunterliegenden Tasten interpretiert und beim Schließen
nicht an Info, Mic oder Enter weitergereicht. Beim aktiven Mic wird kein neuer
Text-Langdruck begonnen; Diktat/Ownership nicht durch diese Zusatzgeste unterlaufen.

Commit ausschließlich über **listener.onTextInput → bestehende InputConnection-
commitText(text,1)**-Kette, nicht über einen Popup-eigenen Editor-Schreibweg.
Die aktuell dargestellte Ebene bestimmt die Alternate-Liste; Shift/Caps nicht durch
pauschales uppercase() ersetzen (TR, DE, EL). Temporäres Shift wird nach erfolgreichem
Grund-/Alternative-Commit einmal verbraucht, nicht beim Timer oder abgebrochenen Menü.
Keine Normalisierung/Korrektur vorhandenen Nutzertexts, kein VAD-/ASR-Eingriff.

## §2b Obergrenze der View (Reihe 1 der Tastatur)

**D12-K, entschieden:** Dieser Nachtrag hat für die oberste Reihe Vorrang vor dem
Flip-Fallback aus §1 und dem entsprechenden bisherigen Test in §6. „Reihe 1“ meint
hier die erste sichtbare Tastenreihe, also Modellindex **0**, nicht Modellindex 1.
Übergebener Gerätebefund: View **1080×576 px**, Ursprungstaste top=0;
Auswahl in der nächsten Reihe funktioniert, in der obersten Reihe bisher keine
Zelle. Das ist keine neue Messung dieses Dokumentauftrags.

### §2b.1 Auswahl muss richtungsunabhängig sein

**Entscheid:** Nicht `keyTop` ist das Auswahl-Tor, sondern das **gesamte echte
Grundtasten-Rechteck mit nach außen erweiterter 8-dp-Zitterzone**. Innerhalb dieser
Schutzzone gewinnt **nie** eine Popupzelle; UP dort liefert das Grundzeichen genau
einmal. Erst außerhalb kann die tatsächlich unter dem Finger liegende Menüzelle
gewinnen – nach links, rechts, oben **oder** unten. Außerhalb ohne Menüzelle bleibt
es beim Abbruch ohne Zeichen; eine alte Auswahl darf nicht nachlaufen.

Die Zone wird je Seite nach **außen**, nicht nach innen erweitert; ihr Rand gehört
noch zum Schutzbereich. Keine verkleinerte Innenzone als vermeintliche Zittertoleranz.
Der gelesene F22-M-Ansatz mit abgezogenem slop wäre deshalb nicht dieser Entscheid:
er würde Alternativen bereits innerhalb der ursprünglichen Taste gewinnen lassen.
Die vor dem Öffnen geltende Bewaffnungslogik ist davon getrennt.

**Begründung:** Bei top=0 verlangt das bisherige Aufwärts-Tor eine negative
View-Koordinate. Die sechs sichtbaren Zellen werden damit trotz Bewegung nie zur
Auswahl freigegeben. Der Rechteckabstand ermöglicht dagegen die reale seitliche
Auswahl und erhält „halten, ohne wegzuschieben → Grundzeichen“.

### §2b.2 Platzierung jetzt (a), Fensterlösung (b) nur bedingt später

**Jetzt verbindlich (a):** Passt die übliche Position über der Grundtaste nicht,
Menü an die **View-Oberkante 0** klemmen, innerhalb derselben View zeichnen und die
oberste Reihe vorübergehend überdecken. Kein zusätzlicher Höhenbedarf, keine neue
Fensterebene, keine Überdeckung der außerhalb liegenden Info-/Diagnoseleiste.
Die Grundtasten-Geometrie darunter bleibt unverändert; es gibt keine UP-Weitergabe.

**Ehrliche Konsequenz:** Die direkt über der Grundtaste liegende Zelle kann durch
Grundtaste plus Zitterzone gesperrt sein; ausgewählt wird über die **seitlich
außerhalb** liegenden Zellbereiche. Diese Freigabe ist richtungsunabhängig, aber
kein Versprechen, dass jede sichtbare Zelle bei jeder Überlappung erreichbar ist.
Ist eine Zelle vollständig vom Schutzbereich überlagert, ist sie **nicht bedienbar**;
das muss der Erreichbarkeitstest offen melden. Nicht die Schutzzone verkleinern,
um den Test scheinbar grün zu bekommen. Insbesondere auch Ein-Alternativen-Menüs
in der obersten Reihe prüfen: ohne seitlich erreichbaren Bereich ist das Feature
für diese Taste noch nicht abgenommen.

**Später ausdrücklich zulässige Ausnahme (b):** Ein echtes More-Keys-Popupfenster
oberhalb der Tastatur kann diese Überlappungsgrenze lösen; es ist nicht grundsätzlich
verboten. Es wird aber **erst nach fertigen Ziffern-Hints L1** und einem eigenen
Fokus-/Window-/Höhen-Abnahmeschritt umgesetzt: gleicher Ziel-Editor, kein Neustart
der Eingabe, vollständige Auswahl-/Abbruchgeste, kein unbeabsichtigtes PTT und kein
verlorenes UP. AOSPs separates More-Keys-Fenster rechtfertigt diese Ausnahme, nicht
eine ungeprüfte zweite Fensterverwaltung im aktuellen Korrekturschritt.

### §2b.3 Alternativenzahl und horizontale View-Grenzen

**Datenobergrenze bleibt acht pro Taste, nicht acht pro Zeile geteilt durch elf.**
Das Menü nutzt die ganze Viewbreite, nicht nur die etwa 1080/11≈98 px breite
Ursprungstaste. Das korrekte äußere Zielmaß ohne innere Zelllücken beträgt
48·n+8 dp: bei n=8 **392 dp = 1029 px**, also kleiner als 1080 px; mit zweimal
8-dp-Seitenabstand insgesamt **1071 px**, ebenfalls passend.

**Abweichung im gelesenen F22-Stand:** Die Breitenfunktion zählt zusätzlich ein
4-dp-Polster zwischen den Zellen. Acht Zellen benötigen damit **420 dp** statt
392 dp; mit derzeit abgerundetem 10-px-Polster sind es **1098 px**, also zu breit.
Das darf nicht als „acht passen schon“ freigegeben werden. Mit dieser vorhandenen
Innenlückenrechnung passen auf der Referenz **höchstens sieben sichtbare Zellen**;
ohne Innenlücken nach dem ursprünglichen Außenmaß höchstens acht.

Links/rechts an die verfügbare Viewfläche klemmen; passt die Menübreite trotzdem
nicht, sichtbare Zellenkapazität aus **tatsächlichen** Zell-/Polstermaßen reduzieren,
nicht Zellen abschneiden, zusammendrücken oder außerhalb malen. Das Einpassen
beseitigt allerdings keine Überlappung mit dem Grundtasten-Schutzbereich aus §2b.1.
Mehr Alternativen als Kapazität werden nach §3 bewusst auf der zweiten Ebene
untergebracht; **keine stille Kürzung der Liste**. Bis zur Umverteilung gilt die
betroffene Taste nicht als vollständig bedienbar abgenommen. Menübreite und
Picker müssen dieselben tatsächlichen Polster/Intervalle verwenden.

### §2b.4 Vorhandene Tokens und gezielte Abnahme

Beleg: **KeyboardTheme, Zeilen 84–96**, lesend geprüft:
- `POPUP_CELL_DP = 48f`, `POPUP_HEIGHT_DP = 56f`, `POPUP_PAD_DP = 4f`.
- `POPUP_GAP_DP = 8f`, `POPUP_RADIUS_DP = 4f`, `LONG_PRESS_MS = 450L`.
- `POPUP_MOVE_CANCEL_DP = 8f` steht dort ebenfalls als bestehendes Bewegungsmaß;
  für die Zitterzone keine neue abweichende Zahl einführen.

Keine neuen Maße, Farben, Inhalte oder Exportdaten. Testen: UP auf Grundtaste und
Zitterzone jeweils genau ein Grundzeichen; Finger jenseits jeder der vier Seiten
plus echte Zelle → genau eine Auswahl; Rand der Zone → keine Zellenauswahl;
CANCEL → keine Eingabe. Bei top=0 mindestens die seitliche Auswahl beweisen und
jede vollständig gesperrte Zelle ausdrücklich zählen. Der frühere Test
`topRowFlipsBelow` ist für diesen Nachtrag durch **topRowClampsToZero** zu ersetzen.
576-px-Viewhöhe und L1-Hints dürfen dadurch nicht anwachsen oder verschwinden.

## 3. Alternativenbudget und DE-Inhalt

**Maximal acht Alternativen, keine stille Kappung.** Das entspricht dem bestehenden
Testvertrag und auf der Referenzbreite acht echten 48-dp-Zellen. Die Grundtaste
belegt keine Menüzelle; der direkte Ausgang bleibt ihre reale Fläche.

Für DE bekommen die ersten zehn Tasten der QWERTZ-Reihe diese Zusatzzeichen:
**q→1, w→2, e→3, r→4, t→5, z→6, u→7, i→8, o→9, p→0**.
Nicht versehentlich y statt z zuordnen. Diese Ziffern gelten in base/shift gleich.
Neue Komfort-Aliase **a→ä, o→ö, u→ü, s→ß** und ihre sprachrichtigen Shiftformen
kommen hinzu. **R8-Entscheidung (2026-10-30):** der Satz „die vorhandenen direkten
ä/ö/ü/ß-Tasten bleiben erhalten“ ist **aufgehoben** — de-de bekommt **keine** eigenen
Tasten für ä/ö/ü/ß; der Umlaut/das ß ist die **erste Alternative** von a/o/u/s
(Zielbild mit Tastenliste: `docs/DESIGN_DEDE_ZIELBILD.md` §1; Requirements-Ausnahme:
`docs/LAYOUT_REQUIREMENTS.md` §2.2 de-de-Notiz). Kein Komfort-Doppelpfad, sondern die
belegte Pro-Sprache-Ausnahme (AOSP `rowkeys_qwertz1.xml`/`rowkeys_qwertz3.xml` führen
ebenfalls keine Umlaut-/ß-Direkttasten). Der Grundsatz „eigene Taste plus Alternate“
bleibt für die anderen 14 Sprachen unberührt.

Reihenfolge: Ziffer, falls vorhanden, dann der gewünschte Umlaut/ß-Komfortalias,
danach bisherige Alternativen in bisheriger Reihenfolge, ohne doppelte Einträge.
Beispiel e: **[3, é, è, ê, ë, ę, €]**, also **7**, nicht 9 Alternativen.
Die übergebene Liste hat sechs Einträge; das zusätzliche Zeichen „3“ ist **ein**
Eintrag, nicht drei. Ein tatsächlich neun Einträge langes Asset bleibt ein eigener Stressfall.

Bei wirklich neun oder mehr: seltene bereits auf symbols/symbolsShift direkt erreichbare
Zeichen zuerst aus dem Komfortmenü dorthin verweisen (etwa €), **nicht** aus dem Layout
löschen. Sonst muss der Autor die zusätzliche seltene Alternative als eindeutige Taste
auf der zweiten Symbolebene unterbringen und die Pflichtzeichen-/Spiegelregeln prüfen.
Kein „nur erste 8 anzeigen“, kein verstecktes zweites Langdruckmenü, kein automatisches
Paging während gehaltenem Finger. Lässt sich die Umverteilung nicht regelkonform
belegen, wird das betreffende Asset nicht freigegeben statt Daten still abzuschneiden.

Parser und Assets bleiben das Datenformat: fehlende/ungültige Listen dürfen nicht
die normale Tastatur blockieren. Runtime soll den Konflikt eindeutig behandeln;
Tests/Importvalidierung weisen `>8` aus, sie dürfen den Fehler nicht nur im Renderer verstecken.
D12 ändert diese Dateien nicht; der spätere Assetauftrag ergänzt DE-Ziffern/Aliase und
beide Ebenen gezielt, keine globalen Texteingriff-/Sprachregeln für alle 15 erfinden.

## 4. Fußzeile VOR/NACH – Punkt ersetzt beide Cursorpfeile

Die Entfernung **beider** Cursoraktionen ist hier durch „‹ › weg, Punkt dafür“
explizit autorisiert; sonst bleibt der Tastenbestand erhalten. D10s separate
Entscheidung **Mic ganz rechts** bleibt gültig. Enter steht deshalb vor Mic,
auch wenn die übergebene Istzeile noch Mic vor Enter zeigt.

**VOR, Summe 13:**
`[?123 11,54%][🌐 7,69%][😊 7,69%][Space 38,46%][‹ 7,69%][› 7,69%][Mic 7,69%][⏎ 11,54%]`

**NACH, Summe 13:**
`[?123 11,54%][🌐 7,69%][😊 7,69%][Space 30,77%][. 15,38%][⏎ 11,54%][Mic 15,38%]`

Gewichte nachher: **1,5 / 1 / 1 / 4 / 2 / 1,5 / 2**.
Die bisherigen beiden Pfeilplätze finanzieren den **2,0 breiten Punkt**; die bereits
in D10 entschiedene Space-Verkleinerung 5→4 finanziert Mic 1→2. Keine zusätzliche Reihe.
Bei 1080 px: Punkt und Mic je rund **166 px / 63,30 dp** breit; Punkt etwa
x=623…789 px, Enter 789…914, Mic 914…1080. Randpixel gemeinsam verteilen.

Punkt ist **kind text, value/label "."**, kurz = Punkt, lang = **[, ! ?]**;
kein neues Action-Kommando „dot-popup“. Drei Alternativen ergeben **399×147 px**.
Punkt/Mic teilen D10s Footerhöhe: 650-px-Rootbeispiel **128 px / 48,76 dp**,
829-px-Angebot mit Root-Kappe **147 px / 56 dp**; Ziel ≥48 dp hier erfüllt.
Wenn der aktuelle integrierte Footer in PT noch auf 44 dp skaliert wird, ist dieses
Ziel **noch nicht erreicht**: D10-Höhenvertrag zuerst integrieren, nicht nur das Icon vergrößern.

Bei symbols existiert bereits eine Punkt-Taste: **dieselbe ID/Zeichenfunktion in die
Fußzeile verlagern**, kein zweiter Punkt in derselben Ebene. Bei Ebenen ohne Punkt
gezielt ergänzen; Paare base/shift und symbols/symbolsShift müssen ihre Zeilensummen
behalten. Der spätere Asset-/Kompositionsauftrag prüft alle 15×4 Ebenen; keine leere
Geistertaste am alten Punkt-Ort und keine verlorenen Pflichtsymbole.
Die Popup-Aliase , ! ? sind beabsichtigte Komfort-Doppelpfade zu den direkten Symboltasten.

**Teilenschaste / Entscheidung #8:** 🐞/Info bleibt oben in ihrer **48×48-dp**-Fläche,
kein Ersatz im Footer und keine Verkleinerung auf Iconmaß. Ein Popup darf die Leiste
weder zeichnend überdecken noch ihre Ereignisse beim UP auslösen. Diese Trennung ist
wichtiger als die unmögliche Zusage „erste Reihe immer nach oben“.

## 5. Messplan – ausschließlich für den Orchestrator

Keine Emulatorbedienung in diesem Dokumentauftrag. Referenzdaten r0≈1884, r1≈1990,
Shiftreihe≈2100, Footer≈2225 und Info≈1765 sind Anker, **keine** universellen Tap-
Koordinaten; reale KeyRects/Root-Position und 650/829-px-Budget beim jeweiligen Bild mitführen.

### Screenshotfolge aus derselben APK

1. Hell/Dunkel normales q und gehaltenes q bei **100 ms**: nur Pressfläche, kein Menü.
2. Dieselben Zustände bei **500 ms**: Menü mit „1“, Grundzeichen noch nicht im Feld.
3. e-Menü nach Assetergänzung: sieben Einträge, **903×147 px**, vollständig im Clip;
   nahe linkem Rand mindestens **21 px** Abstand, keine Überdeckung der Infoleiste.
4. a/Umlaut- sowie Punkt-Menü, Finger auf einer Alternative: genau eine Akzentzelle;
   für Punkt **399×147 px**, Bild vor UP und danach geschlossen mit einem Testzeichen.
5. MENU→UP Grundtaste, UP außerhalb und ACTION_CANCEL: genau Grundzeichen bzw.
   unverändertes leeres Testfeld; nie doppelte Eingabe oder ein nachlaufendes Menü.
6. Oberste/rechte/unterste Ursprungstaste, beide Höhen, DE/PT, zweite Öffnung, Caps:
   Popup geclippt/gefaltet wie beschrieben, alle normalen Tasten nach Schließen erhalten.
7. Mic **900 ms** halten: kein Menü, Aufnahmezustand unverändert; Shift-Doppeltipp
   innerhalb **350 ms** bleibt Caps, kein Alternative-Handler und keine zweite Session.

Nur kontrollierte Testfelder verwenden; keine Nutzerinhalte sammeln/exportieren.
Für Haltebilder kann der Orchestrator sein bestehendes Touch-/Instrumentierungsfixture
verwenden, während des Haltens `adb exec-out screencap -p` erfassen und mit
`adb logcat -d -s VK-HIT` Grundtasten-Rects zuordnen. Popup-Rect/-Index dürfen als
zusätzliche Debug-Metadaten geloggt werden, **nicht der eingegebene Inhalt**.
Ein bloß nach UP aufgenommenenes Bild beweist das kurzzeitig geöffnete Menü nicht.

### Pixelbeweis statt grober Delta-Schwelle

Aus **KeyboardTheme.compose mit ganzzahliger Division** folgen exakt:
- Hell normal RGB **(236,239,241)**, gedrückt **(163,171,176)** = #FFA3ABB0;
  Kanaländerungen **(73,68,65)**, Maximum **73**.
- Dunkel normal **(38,50,56)**, gedrückt **(103,111,116)** = #FF676F74;
  Kanaländerungen **(65,61,60)**, Maximum **65**.

Das sind **maximale RGB-Kanaländerungen, keine Luma-/Helligkeitsmetriken**.
Im PNG je einen **10×10-px-Innenpatch** ohne Glyphe/Kante/Antialiasing vergleichen:
pro Kanal Sollwert ±**2**; nicht nur „irgendein Delta >50“. KeySurface bestimmt
Ausnahmen Caps/Recording, die deshalb nicht mit dem Normal-Presswert verglichen werden.

Für das Popup: Außen-Rect aus der Geometrie, deckende Menüfläche in diesen Theme-
Werten, mindestens ein lesbares Alternate-Glyphbild plus 48-dp-Auswahlzelle im gehaltenen
Screenshot. Größen/Positionstoleranz **±1 px**, Radius-Rand nicht als Farbpatch verwenden.
Nach UP: Popup-Rect wieder normale Tastatur; Diagnosetext/Info-Rect unverändert.
Mehr veränderte Pixel allein beweisen **kein** Menü – Glyphen, korrektes Rect und
selektierte Zelle sind gemeinsam erforderlich.

## 6. Reine JVM-Naht – Testliste mit konkreten Ergebnissen

`LongPressMenu`/Geometriefunktionen sind neu vorzuschlagende reine Funktionen;
Eingaben Zahlen, Listen, IDs, Zeitwerte, Zustandswerte – **keine Android-Typen**.
Für zwei Reihen muss `pick` x **und y** berücksichtigen; offsetX allein reicht nicht.
Zellintervalle sind links/oben geschlossen, rechts/unten offen. Außerhalb → keine Auswahl.

| Testname | Erwartung |
|---|---|
| `doesNotOpenAt449ms` | Zeit 449 ms → Menü geschlossen, 0 Commitereignisse |
| `opensAt450ms` | 450 ms, gültiger Text-Key/Pointer → offen, noch 0 Commits |
| `emptyAlternatesStayNormal` | Leere Liste → kein Menü, normales UP unverändert |
| `movementCancelsArming` | Entfernung >8 dp vor Timer → Timer endgültig abgebrochen |
| `sameGestureDoesNotRearm` | Bewegung weg/zurück und 900 ms warten → kein späteres Popup |
| `geometry3AtDensity420` | 3 Alternativen → **399×147 px**, Zellen je **126×126 px** |
| `geometry8Fits1080` | 8 Alternativen → **1029×147**, mit 21-px-Rand innerhalb 1080 |
| `leftEdgeIsClamped` | Gewünschtes Menü-left <21 → left=21, nicht negative Position |
| `topRowFlipsBelow` | Beispiel key-top=1831, bottom=1937, sicherer top>1663 → below-top=1958 |
| `doesNotOverlapSourceOrToolbar` | Kein Kandidat vollständig passend → fit=false, kein geclipptes Menü |
| `pickThirdCell` | Menü-Innenkoordinate x=120 dp, y=24 dp bei 48-dp-Zellen → Index **2** |
| `rightEdgeIsOutside` | 3 Zellen, Innen-x=144 dp → **keine** Auswahl, nicht Index 2 |
| `twoRowPickUsesY` | 4 Spalten, zweite Reihe, dritte Zelle → Index **6** |
| `paddingIsNotACharacter` | UP im 4-dp-Padding → kein Alternate, 0 Commit |
| `upOnBaseAfterOpen` | UP im separaten Ursprungstasten-Rect → genau **1 Basis-Commit** |
| `upOnAlternateOnly` | UP auf Index 2 → genau Listeneintrag 2, **kein zusätzlicher Basis-Commit** |
| `outsideAndCancelNeverCommit` | UP außerhalb bzw. CANCEL → geschlossen, Timer gelöscht, **0** Commit |
| `lateTimerHasNoEffect` | Timer einer alten Layout-/Editorgeneration → geschlossen, **0** Commit |
| `micNeverArmsMenu` | Mic DOWN plus 900 ms → **1 HoldStart, 0 Menüevents**; UP genau 1 HoldEnd |
| `shiftDoubleTapUntouched` | Shift-Taps im bestehenden 350-ms-Vertrag → Caps wie bisher, **0 Menüevents** |
| `oneShotShiftConsumedOnlyOnCommit` | Abbruch erhält Shift; Alternate-Commit verbraucht es einmal |
| `sevenIsNotNine` | [é,è,ê,ë,ę,€] plus 3 ohne Duplikate → **7** Einträge |
| `nineAlternatesAreNotTruncated` | 9 Einträge → eindeutiger Validierungs-/Freigabefehler, nicht still erste 8 |
| `footerReflowKeepsRequiredKeys` | Gewichtssumme **13**, Punkt/Mic Gewicht **2**, Enter vor Mic, Cursor-left/right bewusst entfernt; keine andere Aktion verloren |
| `surfacePriorityUnchanged` | Existing KeySurface-Regressionsfälle: Mic vor Caps vor Press vor Normal; Pressfarben weiterhin **73/65** Kanalmaximum |

Der spätere Android-Adapter muss Timerabbruch, echten Pointerbesitz, Textpfad und
Clipping zusätzlich instrumentiert belegen. TalkBack-Langdruckauswahl wird nicht
allein durch JVM-Picks als geprüft ausgegeben; Accessibility bleibt eigener Gerätepunkt.

## §7 Hint-Beschriftung (keyHintLabel)

**D12-H – Entscheidung:** `LayoutKey.hint` wird als kleine Zusatzlegende gezeichnet,
nicht als weiterer Tastentext oder eigener Trefferbereich. Null/leer/blank bedeutet
keine Zeichnung; das vorhandene tolerante Einlesen und der Text-/Popup-Commitpfad
werden durch diesen Auftrag nicht geändert. Keine Ableitung aus dem ersten Alternate:
Quelle ist ausschließlich das explizite Hint-Feld der gerade sichtbaren Taste.

### §7.1 Schriftgröße und Token

Vorhandener Hauptlabel-Token: **KeyboardTheme.SP_LETTER_SP = 24 sp**.
Gewünschter **neuer, noch nicht vorhandener** Theme-Token: **SP_KEY_HINT_SP = 12 sp**,
abgeleitet als **0,5 × SP_LETTER_SP**, nicht als verstreute zweite Schriftkonstante
im Renderer. Der Wert entspricht dem vorhandenen **SP_SECONDARY_SP = 12 sp**,
der neue Name trennt aber die Hint-Rolle von Status-/Space-Beschriftung.

Bei fontScale=1 sind das nominal **12 dp bzw. 31,5 px** bei density 420. Text bleibt
sp, nicht fest in Geräte-Pixeln. Wird das tatsächliche Hauptlabel durch den Fit
verkleinert, folgt der Hint im selben Verhältnis: **Hint-Paintgröße = 0,5 × tatsächliche
Hauptlabel-Paintgröße**; er wird nicht größer als der aus dem Theme abgeleitete Sollwert.
Nicht weiter bis zur Unlesbarkeit schrumpfen, um eine Kollision zu verstecken:
bei Platzmangel gilt die Priorität aus §7.5.

### §7.2 Position und AOSP-Beleg

**Rechts oben, Text am rechten oberen Anker zentriert**, nicht linksbündig und nicht
über der Mitte der ganzen Taste. Seitlicher Innenabstand ist der vorhandene
**POPUP_PAD_DP = 4 dp**; die rechte sichtbare Glyphenkante bleibt entsprechend innen.
Bei der 98-px-Taste und 18 px gemessener Hint-Inkbreite liegt die Textmitte bei
**98 − 10,5 − 9 = 78,5 px** relativ zur linken Tastenkante.

Vertikale Vorgabe: nominal **2 dp** Abstand zwischen Tastenoberkante und sichtbarer
Glyphenoberkante, abgeleitet aus **POPUP_PAD_DP / 2**, kein neues Abstands-Token.
Für die tatsächliche Reihenhöhe h und den vorhandenen Sollwert aus
**KeyboardTheme.rowHeightDp(rows)** gilt der Abstand
**(POPUP_PAD_DP / 2) × min(1, h / rowHeightDp(rows))** in dp.
Bei einer Sollhöhe 55 dp sind das 2 dp, bei 44 statt 55 dp etwa 1,6 dp; eine höhere
Taste vergrößert den Abstand nicht über 2 dp. Baseline erst aus den tatsächlichen
Font-/Inkmetriken bestimmen, nicht „Baseline = Oberkante + 2 dp“ setzen.
Bei schmalerer Taste folgen Anker und gemessene Inkbreite ihrer echten rechten Kante;
kein fixer Bildschirm-x-Wert, keine Veränderung der Touchfläche oder Reihenhöhe.

**Beleg lesend geprüft, AOSP android16-release:** `rowkeys_qwertz1.xml:24–71` führt
keyHintLabel 1…0 für q,w,e,r,t,z,u,i,o,p getrennt von additionalMoreKeys.
Der Telefon-Hint-Zweig in AOSPs `KeyboardView.java:473–480` platziert die Hint-Legende
rechts oben und zeichnet sie mit CENTER-Ausrichtung, unter Einbezug der gemessenen
Ziffer-/Textbreite. Das belegt die Anmutung, **nicht** unsere konkreten 4-/2-dp-Werte:
diese sind die hier entschiedene Ableitung aus vorhandenen Theme-Maßen.
AOSP hat für andere Hint-Arten eigene Zweige; unser Kurz-Ziffernhint ist kein Telefon-
Mehrbuchstabenlabel rechts neben der Hauptglyphe.

### §7.3 Farbe und Dunkelmodus

**Vorhandenes Feld palette.textSecondary**, keine erfundenen keyText-/keyLabel-Felder:
Hell **#B337474F**, Dunkel **#B3FFFFFF**. Die gewählte Palette erledigt den
Hell-/Dunkelunterschied; kein eigener Dark-Sonderpfad und kein neuer Farbtoken.
Der Hint ist eine Zusatzlegende, deshalb weder **palette.accent** noch
**palette.keyToggledGlyph**. Auch bei Caps bleibt die Ziffer eine Ziffer.

Grundtastenfläche weiterhin ausschließlich aus **KeySurface**; Hint-Paint verändert
keinen Flächen-/Presszustand und trägt das Overlay nicht nochmals auf.
Beim dunklen Press-Hintergrund **#FF676F74** bleibt die vorhandene sekundäre Textfarbe;
**#FF80CBC4** ist der Auswahl-/Akzenttoken, nicht die generelle Hint-Farbe.
Das Popup selbst zeichnet seine Alternativen wie bisher; dort keine zweite Hint-Legende.
Lesbarkeit auf normaler und gedrückter Fläche ist separat am Gerät zu prüfen,
nicht allein durch gleiche RGB-Token oder als bereits geprüfte Accessibility auszugeben.

### §7.4 Ebenen

**base und shift zeigen denselben expliziten Ziffernhint**, einschließlich Caps:
q/Q→1 … p/P→0; kein Großschreiben/Transformieren von hint und kein Umschalten der
Ziffer in das Shift-Sonderzeichen. Beide Ebenen bekommen die passenden Hint-Daten,
kein Render-Fallback aus dem Layout einer anderen Ebene.

**symbols und symbolsShift zeichnen in diesem F23-Umfang keine solchen Hints**:
dort sind die Ziffern bereits direkt sichtbare Zeichentasten, ein geerbtes 1…0 wäre
redundant oder falsch. Gleiches gilt für Action-Tasten, Space, Mic und die Popupzellen.
Der AOSP-QWERTZ-Beleg trennt Buchstaben-Hints und eigentliche Zeichen; er beweist
kein universelles Verbot anderer Symbol-Hints – diese Begrenzung ist unsere bewusst
kleine Umsetzungsentscheidung, nicht eine behauptete AOSP-Regel für alle Layouts.

### §7.5 Kollision und Priorität

**Hauptlabel samt eigentlichen Akzenten/Umlautpunkten > notwendiger Deadkey-Marker
> Hint.** Ein Akzentpunkt als Teil der Hauptglyphe darf niemals für einen Hint
verschwinden. Ein vorhandener separater Deadkey-/Modusmarker behält ebenfalls Vorrang.

Mit den gemessenen Ink-Rechtecken prüfen, nicht mit Stringlänge oder allein den
98 px Zellbreite. Der Hint muss innerhalb der Taste bleiben und darf das Hauptlabel
oder den Marker einschließlich eines Abstands **POPUP_PAD_DP / 2 = nominal 2 dp**
nicht schneiden. Dieser Abstand folgt bei kleinerer Höhe derselben relativen
Ableitung wie oben. Reicht es nicht: **nur den Hint dieser Taste ausblenden**,
keine Hauptglyphe verschieben/verkleinern, keinen Punkt übermalen und keine neue
Reihe einführen. Alternative und Langdruck funktionieren trotzdem unverändert.
Damit wird insbesondere für stark geschrumpfte PT-Reihen keine Sichtbarkeit vorgetäuscht.

### §7.6 Kleinste Test-/Zeichnungsnaht

Keine neue Stateful- oder Farbarchitektur. Eine kleine reine Platzierungsfunktion
im Zeichnungsadapter genügt: Eingaben sind Key-Rect, Zeilen-Sollhöhe, tatsächliche
Textgröße und vom Adapter gemessene Ink-/Marker-Rechtecke; Ausgabe Hint-Rect bzw.
„nicht zeichnen“. Sie benötigt weder Android-Typen noch einen neuen Editorpfad.

| Minimaler Test | Erwartung |
|---|---|
| `hintUsesHalfMainLabel` | 24 sp Hauptlabel → **12 sp Hint**; effektive 20 sp → **10 sp**, kein Festhalten an 12 |
| `hintTracksKeyGeometry` | 98 px Breite/18 px Hint-Ink, density 420 → Anker **78,5 px**; 55-dp-Sollhöhe → oberer Abstand **2 dp**, bei h=44 dp **1,6 dp** |
| `hintIsLayerExplicit` | Gleiche gesetzte Ziffer in base/shift/Caps unverändert; null/blank und symbols/symbolsShift → **keine Hint-Zeichnung** |
| `mainAndDeadkeyWinCollision` | Hint schneidet geschütztes Ink-/Marker-Rect → **Hint unsichtbar**, Hauptlabel/Marker/Alternates unverändert |
| `hintKeepsExistingPaletteAndSurface` | Hell/Dunkel textSecondary wie §7.3; KeySurface-Ergebnis vor/nach Hintplanung identisch |

Reale Fontmetriken/Rasterisierung bleiben Gerätebeweis: je ein Normal-/Pressed-
Screenshot in Hell/Dunkel sowie base/shift/Caps der obersten DE-Reihe aus derselben
APK; 1…0 auf den passenden zehn Tasten, keine Kollision bei 650-/829-px-Fit und
zweiter Öffnung. Auf der elften Taste nicht automatisch eine weitere Ziffer erfinden.
Kein uiautomator-Ersatz für Glyphenbilder, keine Nutzertexte oder neuen Exportfelder.
Dieser Auftrag ergänzt nur §7; keine Zeichnung, keine Modell-/Parseränderung und kein Build.

## Abschluss

**ENTSCHIEDEN:** 450-ms-Menü, maximal 8 Alternativen, 48-dp-Zellen/56-dp-Höhe im
Referenzfall, Palette/KeySurface unverändert wiederverwenden, kein neuer Fenster-/Höhenmechanismus.
**BEWUSSTE AUSNAHMEN:** Top-Reihe darf unterhalb öffnen; Cursorpfeile entfallen nach
Nutzerauftrag; Umlaut-/Ziffern-/Satzzeichenaliase sind dokumentierte Komfort-Doppelpfade.
**OFFEN:** Spätere Umsetzung/Assetergänzung, echte gehaltene Screenshots, schmale Fenster
und TalkBack-Abnahme. Parser-/Testgrenze >8 nicht heimlich aufweichen.
**VERIFIKATION:** Nur Quellen gelesen und dieses Dokument geschrieben; keine App-/Asset-
Änderung, kein Build, keine Emulator-/adb-Aktion und kein Export von Nutzerinhalten.
