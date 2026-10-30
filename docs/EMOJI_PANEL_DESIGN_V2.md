# E10.1-NEU – Emoji-Panel V2: Moduswechsel statt Overlay

**Status:** Verbindliche neue Designentscheidung, noch nicht implementiert oder am Gerät abgenommen.
**Geltung:** Ersetzt die Emoji-Flächen-/Schließentscheidung aus E9.4/E10.1; das historische Dokument `docs/LANGUAGE_EMOJI_DECISIONS.md` bleibt unangetastet.
**Arbeitsgrenze:** Nur dieses Dokument erstellt. Keine Code-/XML-/Assetänderung, kein Gradle, kein adb, keine neue Geräteprüfung.

## 1. Entscheidungsgrundlage und Verantwortung

Vollständig gelesen: `docs/LANGUAGE_EMOJI_DECISIONS.md` einschließlich E9.4 und E10,
`docs/SETUP_UI_DECISIONS.md` einschließlich E3, `docs/DESIGN.md`,
`ime/ImeRootView.kt` und `keyboard/EmojiPanelView.kt` unter dem bestehenden Java-Quellpfad.
Zusätzlich ausschließlich lesend: relevante Service-Verknüpfungen, KeyboardView-Messung und String-Ressourcen.
Vorhandene Screenshots `/tmp/m8f_panel.png`, `/tmp/m8f_closed.png` und
`/tmp/m8g_after_emoji_key.png` angesehen; sie sind Evidenz des bisherigen Zustands,
**keine** Abnahmebeweise für V2.

Die drei Overlay-Anläufe sind gescheitert. Ich ziehe die Zusage zurück, dass eine
sichtbar freigelassene unterste Reihe automatisch einen zuverlässigen Rückweg liefert.
Die gemeldeten Touchbefunde – sichtbares 😊 bei (356,2272) schreibt Space,
🌐 bei (230,2272) wechselt tatsächlich die Sprache, zwei andere Taps fügen nichts ein –
werden als übergebene Gerätebeobachtungen übernommen, nicht hier neu vermessen.
Die Ursache Zeichnung/Hitbox/Touchabfang bleibt ausdrücklich offen.

### Entscheidungsprotokoll über meine bisherigen Aussagen

| Bisheriger Satz / Mechanismus | Hält oder fällt? | Verbindliche Neubewertung mit Evidenz |
|---|---|---|
| „Overlay nur über oberen Reihen; unterste Reihe bleibt unser Rückweg.“ | **FÄLLT** | Drei Gerätewiderlegungen: verdeckte Zeile, aufgeblähte Root, zuletzt optisch korrekt ohne funktionierendes Schließen. Keine weitere Margin-/Gravity-Reparatur. |
| „Sichtbares 😊 ist ein zuverlässiger Ein-Tipp-Toggle.“ | **FÄLLT** | Ein Tap auf die sichtbare Position kann Space statt Close auslösen; sichtbare Beschriftung beweist keinen Trefferbereich. V2 hat keinen 😊-Schließweg. |
| „Die unterste Reihe muss bei offenem Emoji-Panel sichtbar bleiben.“ | **FÄLLT** | Das setzt genau die fragliche KeyboardView-Geometrie voraus. V2 ersetzt bewusst die gesamte Tastenfläche; es bleibt keine teilweise sichtbare, vermeintlich bedienbare Reihe zurück. |
| „ABC ist die richtige Alternative?“ – früher verworfen | **Ablehnung HÄLT** | ABC verspricht Buchstaben, obwohl Rückkehr zu symbols/symbolsShift möglich sein muss. Neue Taste heißt **„Zur Tastatur“**, nicht ABC. |
| „Eine eigene Rücktaste wäre redundant zur sichtbaren 😊-Taste.“ | **FÄLLT** | 😊 ist im Emoji-Modus nicht sichtbar und nicht beteiligt. Eine eigene Taste ist jetzt der einzige reguläre UI-Rückweg, keine redundante zweite Togglefläche. |
| „× oben rechts kostet Grid-Platz und liegt ungünstig an der Ecke.“ | **Ablehnung HÄLT; pauschale Ablehnung eigener Schließtaste FÄLLT** | Kein × und keine Ecke: ein eigener vollbreiter nativer Button außerhalb des Emoji-Canvas. Er belegt einen expliziten Footer, nicht eine Emoji-Zelle. Weniger Emoji pro Seite wird bewusst akzeptiert. |
| „Gleiches IME-Fenster statt Dialog/Activity erhält den Eingabekontext.“ | **HÄLT** | V2 bleibt im selben View-Baum. Kein neues Window, kein restartInput/finishInput; Fokus muss trotzdem durch reales Weitercommit belegt werden. |
| „Rückkehr exakt zur bisherigen Ebene.“ | **HÄLT** | Bestehende KeyboardView-Instanz nur aus-/einblenden; keine setLayout-/resetShiftState-Aufrufe beim Emoji-Öffnen/Schließen. |
| „Schließen über System-IME-Ausblenden reicht.“ | **Ablehnung HÄLT** | Das schließt die ganze IME und verletzt den vorgesehenen unmittelbaren Rückweg. Es ist kein V2-Erfolgskriterium. |
| „237 grüne Unit-Tests beweisen Bedienbarkeit.“ | **FÄLLT als Freigabegrund** | Die Screenshot-/Touch-Evidenz widerspricht der UI-Zusage. JVM-Logik bleibt nützlich, ersetzt aber keine Geräteabnahme. |

## 2. Genau ein Mechanismus: gegenseitig ausschließende Stack-Kinder

**Entscheidung:** Der vorhandene vertikale `stack`-LinearLayout ordnet sämtliche
Inhaltsflächen untereinander an. Emoji ist **kein überlagerndes FrameLayout-Kind** mehr,
sondern ein alternativer Inhaltsblock im Stack. Es gibt genau zwei Modi: Tastatur oder Emoji.

Die äußere ImeRootView darf als bestehender FrameLayout-Typ erhalten bleiben, besitzt
aber nur noch den Stack als inhaltliches Kind. Keine Emoji-Zeichen-/Touchfläche liegt
als zweites FrameLayout-Kind darüber. Die jetzige Root-`onLayout`-Positionierung und
`activeRowCount` werden entfernt, nicht durch eine andere Koordinatenrechnung ersetzt.

### Baum und Sichtbarkeiten

| Teil | Tastaturmodus | Emoji-Modus |
|---|---|---|
| `statusRow` im Stack | VISIBLE, normaler Status und Mic | VISIBLE an derselben obersten Stelle; Fehlertext bleibt erhalten |
| vorhandene `keyboardView` | VISIBLE, unveränderte eigene Messung/Touchlogik | **GONE vollständig**, einschließlich unterster Reihe; keine unsichtbaren Keyboard-Trefferflächen |
| neuer Emoji-Inhaltsblock im Stack | GONE | VISIBLE; eigener vertikaler LinearLayout |
| vorhandene `emojiPanel` im neuen Block | Nicht sichtbar durch Elternsichtbarkeit | Gewichtet verbleibende Fläche für Emoji und bestehende Blätteranzeige |
| neuer nativer Button „Zur Tastatur“ im selben Block | Nicht sichtbar durch Elternsichtbarkeit | VISIBLE, volle Breite, mindestens 48 dp hoch, unter dem EmojiPanel und außerhalb seines Canvas/Hit-Tests |

„Emoji-Inhaltsblock“ ist eine **neu anzulegende Gruppierung**, kein behauptetes
bestehendes Feld. Dafür ist keine neue Quelldatei erforderlich. Der Rückbutton ist
Teil dieses Blocks und wird von Android als echte Button-View gemessen und getroffen;
er ist keine im Canvas gezeichnete Taste und kein KeyboardModel-Eintrag.

### Größenvertrag ohne Overlay-Rechnerei

- Der Stack bleibt inhaltshoch, nicht MATCH_PARENT über die angebotene Bildschirmhöhe.
- Der neue Emoji-Inhaltsblock erhält als **Designmaß 240 dp Gesamthöhe**, einschließlich
  Rückbutton. Das ist ein eigener Größenvertrag, keine aus `kv.bottom`, Zeilenzahl,
  Keyboardhöhe oder Bildschirmkoordinaten berechnete Position. Die vorhandene
  KeyboardView nennt in `onMeasure` bereits 240 dp Mindesthöhe; V2 hängt aber nicht
  davon ab, dass alle künftigen Layouts exakt diese Höhe haben.
- Im Emoji-Inhaltsblock reserviert der LinearLayout zuerst den normalen Footerbutton;
  das EmojiPanel erhält `height=0` mit Gewicht 1. Damit verteilt **der Layout-Manager**
  den Rest. Ein WRAP_CONTENT-Panel darf nicht die gesamte angebotene IME-Höhe reklamieren.
- Keine obere/untere Panel-Margin, keine Gravity-Ankerrechnung, keine gespeicherte
  Keyboard-Pixelhöhe, kein `translationY`, keine Ableitung der letzten Tastenreihe,
  keine LayoutParams-Umschreibung während `onLayout`.
- Die Wurzel berücksichtigt System-Navigationsinsets als normalen unteren Innenabstand
  einmal für den gesamten Stack. Dies ist kein Panelpositionierungs-Hack; Footer und
  Keyboard liegen im jeweiligen Modus oberhalb dieses sicheren Bereichs.
- EmojiPanel muss das tatsächlich vom LinearLayout übergebene Höhenlimit respektieren.
  Nur die **interne** Raster-/Seitengröße wird aus seiner eigenen Fläche berechnet.
  Diese Berechnung ist legitim: sie platziert kein Overlay über Keyboard-Tasten.
- Der jetzige `coerceAtLeast(cellMinPx)`-Ansatz darf bei zu wenig Eigenfläche keinen
  künstlich größeren Grid-Bereich erzeugen. Keine Zeichnung/Touchzone außerhalb der
  eigenen View; kein Grid-Hit im Footer. Leere Daten/zu wenig Grid-Platz zeigen keinen
  auswählbaren Eintrag, der native Rückbutton bleibt bedienbar.
- Kein Scrollcontainer um den Rückbutton: dieser kann nicht aus dem Bild gescrollt werden.
  Ist im tatsächlichen Window nicht einmal Platz für den vollständig sichtbaren Footer,
  darf Emoji nicht aktiv bleiben: zurück zum Tastaturmodus, erklärender Status statt
  gestrandeter Teilansicht. Kein Verkleinern von Keyboard-Tasten als Ersatzlösung.

**Bewusster Preis:** Die Buchstabentafel ist im Emoji-Modus nicht gleichzeitig sichtbar.
E3 bedeutet hier: keine modale Sackgasse, Emoji-Eingabe ist möglich und ein sicherer
Ein-Tipp-Rückweg stellt normale Eingabe wieder her. Es bedeutet **nicht**, dass zwei
komplette Eingabeoberflächen gleichzeitig auf derselben Fläche liegen müssen.
Wer im Emoji-Modus Buchstaben möchte, tippt einmal „Zur Tastatur“, dann normal weiter.
Keine Behauptung, normale Buchstabentasten seien während GONE weiter tippbar.

### Warum der Fokus bleibt

Öffnen/Schließen ändert ausschließlich Kind-Sichtbarkeiten im bereits aktiven IME-Fenster.
Keine Activity, kein Dialog/PopupWindow, keine neue Editorinstanz, kein `restartInput`,
kein `finishInput`, kein IME-hide/show, kein eigener Fokusaufruf auf den Rückbutton.
Native Buttons dürfen Touch-/Accessibility-Bedienung bieten, aber keinen neuen
Texteingabefokus anfordern. Eine Relayout-Anpassung des Ziel-App-Ausschnitts ist kein
Editorwechsel. Dass derselbe Editor weiter beschrieben wird, ist ein Abnahmegate,
keine allein aus einer nicht-null InputConnection abgeleitete Erfolgsmeldung.

## 3. Schließen, Einfügen und Diktatsperre

### Verbindlicher Schließweg

**Eigener nativer Button „Zur Tastatur“ im Panel-Footer. 😊 als Schließweg entfällt.**
Die Beschriftung ist ebenenneutral und für alle 15 Tastatursprachen dieselbe
UI-Aktion, nicht eine zusätzliche Layouttaste. Deutsche Ressourcenbasis; keine
neu erforderlichen 15 Übersetzungsdateien und keine Layout-Assetänderung.
Der Footer liegt über dem System-Navigationsbereich und nicht in dessen Ecke.

Ein Tap führt über das bereits vorhandene `EmojiPanelView.Listener.onDismissRequested()`
zur bestehenden Root-`hideEmojiPanel()`-Operation. Der Root kann dafür den vorhandenen
`emojiPanel.listener` nutzen; es braucht keinen erfundenen zweiten Editorkanal.
`hideEmojiPanel()` ist idempotent und stellt zusammenhängend her: Emoji-Block GONE,
Keyboard VISIBLE, `closeAndResetPage()` einmal; kein Textcommit durch Schließen.
Ein MotionEvent wird nicht nach dem Sichtbarkeitswechsel an die darunter zurückkehrende
Tastatur weitergereicht. Der Footer-Button verarbeitet seine Geste vollständig selbst.

Das bisherige unsichtbare „Tap auf die Mitte der Seitennummer schließt“ entfällt.
Die Mitte bleibt ausschließlich Kategorie-/Seitenanzeige. Damit existiert genau ein
sichtbarer App-Schließknopf. System-Back/Lifecycle-Cleanup sind keine benötigten
Alternativen für den Nutzungsablauf; auch ohne sie muss der Footer funktionieren.

### Zustandsregeln

1. Öffnen weiterhin über die vorhandene EMOJI-Aktion; diese setzt den Emoji-Modus,
   sie ist kein sichtbarer Close-Toggle mehr. Eine verspätete zweite Öffnungsaktion
   im bereits offenen Modus ist idempotent und schreibt nichts.
2. Unabhängig von Modell-/Downloadzustand ist Emoji offline nutzbar. Bei ungeladenen/
   fehlerhaften Emoji-Daten bleibt stattdessen die normale Tastatur sichtbar; kein
   leerer Vollflächen-Ersatz ohne funktionierenden Ausgang.
3. Beim Schließen Kategorie behalten, Seite auf 1 zurücksetzen. Vorhandene Methoden
   `closeAndResetPage()` / `openWithFreshPage()` verwenden; keine Lernhistorie.
4. Exakte Ebene **base, shift, symbols oder symbolsShift** und Shift-/Caps-Zustand
   bleiben durch Erhalt der KeyboardView-Instanz unverändert. Kein neues savedLayer-
   System und kein erzwungenes base. Ein gewollter Sprachwechsel darf wie bisher
   danach das neue Layout setzen; Emoji wird zuvor geschlossen.
5. Ein Emoji folgt ausschließlich dem vorhandenen Listenerpfad:
   `emojiPanel.listener.onEmojiSelected` → `VoiceKeyboardService.onTextInput` →
   `currentInputConnection.commitText(text, 1)` in dessen bestehender Implementierung.
   Kein Direktcommit vom Root, kein setText, kein Clipboard. Panel bleibt nach Einfügen offen.
6. Zustand ≠ IDLE: Öffnen **und** Emoji-Einfügen gesperrt. Die bestehende
   Statusmeldung „Emoji während Diktat nicht möglich“ bleibt gültig. Der Rückbutton
   wird niemals wegen Diktat deaktiviert. Wenn Mic in der sichtbaren Statuszeile
   gedrückt wird: zuerst Emoji schließen, dann den vorhandenen PTT-Startpfad nutzen;
   kein aktives Emoji-Grid über einer Aufnahme. Späte Emoji-Touchereignisse verwerfen.
7. Editorwechsel/IME-Ausblenden setzt den Modus zurück auf Tastatur. Kein Panel-
   Wiedererscheinen mit alten Touchereignissen im nächsten Feld. Bestehende Audio-
   Fehler dürfen beim bloßen Schließen nicht zu „Bereit“ überschrieben werden (R-2).

**Nicht gelöst, nicht verschwiegen:** Die gemeldete Zeichnungs-/Hitbox-Abweichung der
untersten KeyboardView-Reihe wird durch V2 weder vermessen noch repariert. Sie kann
weiterhin das **Öffnen** über 😊 oder andere Tasten betreffen. V2 beseitigt nur die
Abhängigkeit des **Rückwegs** davon. Eine nicht erreichbare Öffnung ist ein eigener
fehlgeschlagener Abnahmepunkt, kein Anlass, V2 durch einen weiteren Overlay-Hack zu retten.

## 4. Harte Geräteabnahme – eine Zeile je Prüfpunkt

Diese Zeilen sind Sollvorgaben für den nachfolgenden Coder-/Gerätejob, keine hier
behaupteten Messergebnisse. Vorher/Nachher-Screenshots gehören zu **derselben** APK.
Screenshots beweisen das sichtbare Ergebnis; Fokus-/Touch-Erfolg braucht zusätzlich
mindestens die beschriebene Eingabesequenz, nicht bloß ein gezeichnetes Cursorbild.

| Prüfpunkt | Einzeilige Abnahmevorschrift |
|---|---|
| **Panel offen** | Nach Öffnen zeigt ein Screenshot oben die Statuszeile, darunter ausschließlich Emoji-Raster mit lesbarer Seitenanzeige und darunter den vollständig sichtbaren vollbreiten Button **„Zur Tastatur“**; keine Buchstabentaste und keine Keyboard-Unterreihe sind sichtbar, die System-Gesten-Pille überdeckt keinen Buttontext. |
| **Panel zu** | Ein Tap auf die Mitte von **„Zur Tastatur“** zeigt im nächsten Screenshot die vollständige vorherige Buchstaben- bzw. Symboltafel einschließlich unterster Reihe; Emoji-Raster und Rückbutton sind verschwunden, der Editorinhalt ist durch das Schließen unverändert. |
| **Weitertippen danach** | Im Feld `ab|cd` ein Emoji auswählen, mit **„Zur Tastatur“** schließen und ohne Feldtap `q` tippen: der Screenshot zeigt exakt `ab😀qcd` bei gewähltem 😀/q und dieselbe geöffnete IME; bei geöffnetem symbols-Modus entsprechend dessen sichtbar ausgewähltes Zeichen statt q. |
| **Nichts verdeckt die unterste Reihe** | Nach Schließen zeigt ein Screenshot die komplette unterste Keyboard-Reihe von links bis rechts mit allen Labels und Unterkanten oberhalb der Systemnavigation; kein Emoji-/Footer-Rest liegt darüber – bei offenem Panel fehlt diese Reihe bewusst vollständig, sie darf nicht teilweise darunter hervorschauen. |
| **Ebene erhalten** | Aus symbolsShift öffnen und schließen: Vorher-/Nachher-Screenshots zeigen dieselbe zweite Symboltafel, nicht base; Schließen fügt kein Leerzeichen und kein anderes Zeichen ein. |
| **Kein Höhen-Explodieren** | Im offenen Screenshot bleibt ein sichtbarer Editor oberhalb der IME; die Emoji-Fläche enthält ihren Footer, verdrängt nicht nahezu den ganzen Editor und besitzt keine bis zum oberen Bildschirmrand expandierte Leerfläche. |
| **Aufnahmesperre** | Während Aufnahme erzeugt ein Öffnungsversuch kein Emoji-Raster, sondern die sichtbare Sperrmeldung; Mic-Start aus offenem Panel zeigt anschließend Tastatur/Aufnahmezustand statt weiterhin bedienbarer Emoji. |

Zusätzlich vor Freigabe: die ersten vier Prüfungen auf DE, EN und RU sowie
Öffnen/Schließen auf allen 15 Layouts; Buchstaben- und Symbolmodus; Gesten- und
3-Tasten-Navigation. Erst Screenshots **plus echtes Tippen** bestehen die Abnahme.
Ein Unit-Teststand allein hebt keine dieser Zeilen auf.

## 5. Coder-Dateiliste – genau vier vorhandene Pfade

Jeder Pfad wurde hier per `grep` auf existierende Symbole geprüft. Zeilennummern
beziehen sich auf den gelesenen Stand; nach Umbau können sie sich verschieben.
**Neue Gruppierungen/Ressourcen unten sind Arbeitsaufträge, keine existierenden APIs.**

| Existierender Pfad | Konkreter grep-Beleg | Eng begrenzter Coder-Auftrag |
|---|---|---|
| `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt` | Z. 51 `val statusRow = LinearLayout(context).apply`, Z. 65 `val stack = LinearLayout(context).apply`, Z. 97 `override fun onLayout`, Z. 122/126 `fun showEmojiPanel()` / `fun hideEmojiPanel()` | Emoji als Stack-Alternativblock mit eigenem nativen Footer anlegen; Overlay-onLayout/activeRowCount entfernen; bestehende show/hide/visible-Verträge auf beide Modi umstellen; Insets nur als Root-Padding. |
| `app/src/main/java/de/voicekeyboard/keyboard/EmojiPanelView.kt` | Z. 36/37 `fun onEmojiSelected(emoji: String)` / `fun onDismissRequested()`, Z. 40 `var listener`, Z. 103 `override fun onMeasure`, Z. 167 `override fun onTouchEvent`, Z. 208/212 `closeAndResetPage()` / `openWithFreshPage()` | Fremde Footerfläche weder zeichnen noch treffen; begrenzte Eigenmessung/kleine Raster absichern; verstecktes Dismiss auf Seitenmitte entfernen; Kategorie und reale Seitenzahl innerhalb eigener Navfläche anzeigen. |
| `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt` | Z. 171 `view.emojiPanel.listener = object ...`, Z. 172/176 `onEmojiSelected` / `onDismissRequested`, Z. 250 `private fun onMicDown()`, Z. 495/497 `onTextInput` / `ic.commitText(text, 1)`, Z. 605 `private fun toggleEmojiPanel()` | Bestehende Listener-/Commitkette erhalten; idempotentes Öffnen, Aufnahme-/Eventguards und Schließen vor Mic/Lifecycle-/Sprachwechsel verdrahten; keine neue Editor- oder Persistenzarchitektur. |
| `app/src/main/res/values/strings.xml` | Z. 2 `<resources>`, Z. 52 `<string name="emoji_blocked_recording">Emoji während Diktat nicht möglich</string>`, Z. 55 `</resources>` | **Neu anzulegen:** ein String für „Zur Tastatur“ sowie die acht in E10.2 bereits benannten Kategorie-Keys; kein Assetumbau und keine weitere Ressourcendatei. |

### KeyboardView.kt: keine zwingende Änderung

**V2 benötigt keine Änderung an `keyboard/KeyboardView.kt`.** Kein Aufteilen ihrer
Reihen, kein zusätzlicher Zeichen-/Hit-Test-Offset, keine Verkleinerung oder neue
Layout-Assetkonvention. Beim Schließen wird dieselbe Instanz lediglich wieder sichtbar.
Die vier Dateien oben sind die vollständige Implementierungsobergrenze dieses Auftrags.

Falls die separate Touchvermessung einen Defekt in KeyboardView nachweist, ist das
**ein eigener Reparaturauftrag**, nicht stillschweigend eine fünfte Datei in V2.
Die kleinste fachlich denkbare spätere Reparatur wäre eine gemeinsame, identische
Koordinatenbasis für Zeichnung und Hit-Test; ohne Messung wird hier weder Ursache
noch konkrete Methode behauptet. Das ist keine zum V2-Mechanismus notwendige Änderung.

## 6. Ausführungsschranke für den späteren Coder

- Höchstens **4 geänderte existierende Dateien**, exakt die Liste oben; keine neuen
  Klassen-/Asset-/Testdateien und keine Layoutänderung unter den 15 Sprachassets.
- Höchstens **2 Builds**: erster Build nach zusammenhängendem Vier-Dateien-Umbau;
  danach harte Geräteprüfung. Bei Fehler genau eine zusammengefasste Korrekturrunde
  und zweiter Build. Danach stoppen und Evidenz melden, kein vierter Overlay-Anlauf.
- Vorhandene Tests dürfen ergänzend ausgeführt werden, aber weder deren Anzahl
  noch ihre Farbe gilt als Geometrie-/Touch-Abnahme. Notwendige neue automatisierte
  Testdateien sind separat zu beauftragen, nicht heimlich außerhalb des Dateibudgets.
- Unverändert offline, 384 Emoji/8 Kategorien aus vorhandenem Asset; weniger Plätze
  je Seite sind zulässig. Seitenzahl nach tatsächlichem Raster, nicht fest „1/3“.
  E10.2-Kategorienamen bleiben, Suche/Favoriten werden hier nicht nachgezogen.
- Kein Rückgriff auf Activity, Dialog, IME-Neustart, System-IME-Ausblenden oder
  Keyboard-Unterzeilenkoordinaten als notwendiger Ausgang.

## ABSCHLUSSPROTOKOLL

**FERTIG:** Ein Mechanismus entschieden: LinearLayout-Stack mit gegenseitiger Sichtbarkeit von vollständiger Tastatur und Emoji-Block; eigener nativer Footer „Zur Tastatur“. E10.1-Overlay und 😊-Close sind ausdrücklich aufgehoben.

**OFFEN:** Implementierung und harte Geräteabnahme; separate Vermessung des bestehenden Zeichnungs-/Hitbox-Fehlers der untersten Keyboard-Reihe. Letzteres wird hier nicht als repariert ausgegeben.

**ANNAHME:** Das Ziel-IME-Fenster bietet im normalen Hochformat Platz für den 240-dp-Emoji-Inhaltsblock plus Status/Insets. Bei kleineren Angeboten hat ein vollständig erreichbarer Rückbutton Vorrang; bei nicht darstellbarem Footer bleibt der Tastaturmodus aktiv. Die Aktivierung darf niemals einen geclippten Ausgang akzeptieren.

**BLOCKER:** Keine offene Designentscheidung. Für Auslieferung blockieren fehlende Gerätebeweise gemäß Abschnitt 4; die neue Mechanik ist noch nicht implementiert. Ein weiter nicht erreichbares 😊-Öffnen wäre ein gesonderter Bedienfehler, kein bestandener Test.

**VERIFIKATION:** Pflichtdokumente und beide Views gelesen, vorhandene Screenshots angesehen, alle vier Coder-Pfade mit grep und konkreten Symbolen belegt. Keine Code-/Gradle-/adb-Arbeit durchgeführt; V2-Geräteverhalten nicht behauptet.
