# FINAL REVIEW M8 – Endverdict zur Sideload-Übergabe

**Datum:** 28.10.2026  
**Verantwortung:** Design- und Freigabeentscheidung auf Basis vorliegender Evidenz, kein neuer Gerätejob.  
**Verdikt: Freigabe zum Sideload an den Nutzer zur persönlichen Erprobung von v0.1.0.**

Die vier in M7 bestätigten P1 werden als erledigt akzeptiert. Die offenen Punkte
L-01/UI-01 und L-03 sind **P2 und nicht übergabeblockierend**. L-01 muss nicht vor
der Übergabe repariert werden. Die Freigabe ist keine Zusicherung einer vollständig
auf physischem GrapheneOS geprüften, uneingeschränkt alltagstauglichen Diktierlösung.

## 1. Beweismaßstab und überprüftes Artefakt

Ich habe `docs/REVIEW_STAGE8_UI.md` vollständig gelesen, die sechs unten genannten
Screenshots selbst angesehen und bestehende Quellen punktuell ausschließlich lesend
abgeglichen. Keine adb-Nutzung, kein Gradle/Build, kein Commit und keine
Code-/Assetänderung. Einzige neu erstellte Projektdatei ist dieser Bericht.

Die Übergabe berichtet zusätzlich 237 Unit-Tests ohne Fehler, 6/6 Instrumented-Tests
mit echtem Engine-WAV-Lauf und Golden-Endtext sowie eine maschinelle
15/15-Sprachenstichprobe. Diese Ergebnisse akzeptiere ich als **Orchestrator-Protokoll**;
ich habe die Tests hier nicht erneut ausgeführt und behaupte keine eigene Sichtung
sämtlicher Rohlogs. Gleiches gilt für die Prefs-Ablesungen und die protokollierte
Symbol-Umschaltfolge. Der DeepSeek-Review meldet 0 P0 / 0 P1 / 1 P2 im UI-Prüfumfang;
das ist ein zusätzlicher Befund, keine vollständige System-/Hardwarezertifizierung.

Das freigegebene lokale Artefakt wurde von mir **read-only** mit `stat` und
`sha256sum` nachgeprüft:

| Merkmal | Verbindlicher Wert |
|---|---|
| Datei | `dist/voice-keyboard-0.1.0.apk` |
| Größe | **81 148 054 Byte** |
| SHA-256 | **54c74a45280aaa7a60549add2f5c5b25d08d88b60b1eb762aac4481c124abd8f** |
| Download | HTTP 200, `Content-Disposition: attachment` und Range 206 laut Orchestrator; hier nicht neu abgerufen |

Die Freigabe gilt genau diesem Hash, nicht beliebigen späteren Builds. Die
Hashprüfung identifiziert die Datei; sie ersetzt keine unabhängige Signaturprüfung.

**ANNAHME:** Der Orchestrator hat die angeführten funktionalen Ergebnisse dem
entsprechenden aktuellen Implementierungsstand dieses dist-Artefakts zugeordnet.
Screenshots allein beweisen diese Build-Zuordnung nicht. Es liegt keine Meldung
über einen abweichenden oder nachträglich funktional geänderten dist-Stand vor.

## 2. Entscheidung über die vier früheren P1

| Früherer P1 | Status | Genannter Beweis und eigene Prüfung | Trägt der Beweis für mich? |
|---|---|---|---|
| **P1-01 – Sprachwechsel ohne Wirkung** | **erledigt** | Selbst angesehen: `/tmp/m8b_de.png` zeigt „Deutsch aktiv“, 11 Tasten in Reihe 1 einschließlich ü und DE-QWERTZ; `/tmp/m8b_en2.png` zeigt „English (US) aktiv“, 10 Tasten und z in Reihe 3. Laut Orchestrator wechselt ein Globus-Tap auch `vk_layout/current_language` de-de ⇄ en-us. 15/15 Geräte-Stichproben liefern je Sprache das erwartete erste Zeichen, darunter FR a, EL ς, RU й. Setup mit 15 Checkboxen, Mindestanzahl und Aufnahmeguard sind umgesetzt laut Übergabe. | **Ja.** Sichtbarer Layoutwechsel plus berichtete Persistenz und reale Zeicheneingabe beheben den früheren unerreichbaren Sprachwechsel. Ein Zeichen je Sprache beweist nicht deren gesamte sprachliche Qualität; die Stichprobe ist kein Volltest aller Tasten. |
| **P1-02 – Caps-Doppeltipp ohne Wirkung** | **erledigt** | `/tmp/m8a_caps.png` selbst angesehen: Feld **QwQW**, Großbuchstabentafel und grün markiertes Caps-Symbol. Orchestrator-Sequenz: einfacher Shift q/w → Qw, Doppeltipp → QwQW. Lesender Codeabgleich bestätigt Doppel-Tipp-Zweig vor Abschalt-Zweig, `DOUBLE_TAP_MS=350`. | **Ja.** Der vormals reproduzierte zweite-Tap-Abschaltfehler ist mit sichtbarer fortgesetzter Großschreibung widerlegt. |
| **P1-03 – symbolsShift unerreichbar** | **erledigt** | Orchestrator-Geräteprotokoll: **2 → 2² → 2²2** bei zweimaligem Shift. Laut T3 sind alle 30 Ebenen (15 × symbols/symbolsShift) auf `id:shift`, `label:⇧`, `value:shift` korrigiert. Lesender Abgleich zeigt Umschaltpfad für `KeyActions.SHIFT`; UI-Review bestätigt die Korrektur. Hier wurde kein separat benannter Symbol-Screenshot übergeben. | **Ja, auf Basis des berichteten Geräteprotokolls plus Implementierungsreview.** Nicht als selbst beobachtete neue adb-Messung ausgegeben. Die Zeichenfolge belegt beide Richtungen, nicht bloß eine geänderte Beschriftung. |
| **P1-04 – Emoji-Aktion ohne brauchbaren Bedienweg** | **erledigt** | `/tmp/p1_emoji.png` und `/tmp/v2_probe.png` selbst angesehen: Status, Emoji-Raster, **„Gesichter 1/2“**, vollbreiter nativer **„Zur Tastatur“**-Button, keine Keyboard-Unterreihe darunter. `/tmp/v2_closed.png` zeigt wieder die vollständige Vier-Reihen-Tastatur ohne Overlayreste. Das anschließende z im Feld ist als Orchestrator-Eingabebeobachtung berichtet; das gelesene closed-Bild allein zeigt diesen Text nicht. Service-Leseprüfung bestätigt den bestehenden `onEmojiSelected → onTextInput → commitText(text,1)`-Pfad und die Aufnahmeguards; UI-Review bestätigt den nativen Rückweg. | **Ja, für den früheren fehlenden Panel-/Rückweg.** Kein weiterer Overlay-Trick, sondern der entschiedene V2-Mechanismus mit eigenem Rückbutton. Vollständiges Emoji-Rendering und Einfügeverhalten auf dem physischen Zielgerät bleiben Restpunkt R7, nicht pauschal „alles Emoji getestet“. |

**Zählung:** Von den vier früheren bestätigten P1 sind **4 erledigt, 0 offen**.
In der vorgelegten Evidenz ist kein neuer bestätigter P0/P1 enthalten. Nicht geprüfte
Hardwarebereiche sind Unsicherheiten und keine automatisch bestandenen Tests.

### Korrigierte Geometrieeinordnung

Die frühere „eine Zeile zu hoch“-Meldung wurde vom Orchestrator als Ablesefehler
zurückgenommen. Gemeldete Trefferrechtecke und der UI-Review sprechen für dieselbe
Modellbasis in Zeichnung und Hit-Test; ich halte den alten Verdacht daher **nicht**
als bestätigten Defekt weiter offen. Ich habe keine neue Touchvermessung durchgeführt.

`pt-pt` hat **fünf** Zeilen und laut Messung 745 statt 630 px View-Höhe. Dafür sind
andere Reihenpositionen korrekt; eine gegenüber DE/EN weiter oben liegende Taste
ist allein kein Offsetfehler. Die 15-Sprachenstichprobe ist mit diesem Sonderfall
vereinbar. V2 darf weiterhin keine aus einer pauschalen Vier-Reihen-Annahme
abgeleitete Overlay-Geometrie verwenden.

## 3. Warum die Freigabe gegenüber M7 jetzt möglich ist

M7 lehnte einen Build mit vier tatsächlich ausgefallenen zugesagten Tastaturfunktionen
ab. Diese Funktionen sind nun erreichbar beziehungsweise der frühere Fehlerfall ist
mit nachvollziehbarer Geräte-/Review-Evidenz behoben. Eine deutsche Space-Beschriftung
ist nicht gleich schwerwiegend wie eine nicht funktionierende Sprachwahl oder ein
Panel ohne Rückweg. Deshalb wäre ein Festhalten am alten P1-Verdikt sachlich falsch.

Die physische Audio-/GrapheneOS-Abnahme war in M7 ausdrücklich als Freigabegate für
eine umfassend geprüfte Auslieferung benannt. Dieses Gate erkläre ich **nicht** für
bestanden: Ich akzeptiere jetzt ausdrücklich den engeren Umfang **persönliche
Sideload-Erprobung mit offengelegten Lücken**. Das ist eine bewusste Risikoannahme,
keine nachträgliche Umdeutung von Emulator-WAV-Tests zu Mikrofontests.

Die persönliche APK-Übergabe braucht vorab weder einen neuen Gerätejob noch einen
L-01-Fix. Eine Bewerbung als vollständig auf GrapheneOS/Headsets geprüft oder als
zuverlässiger Ersatz für die bisherige Tastatur wäre von dieser Freigabe **nicht**
gedeckt. Bekannte Datenverluste, fortgesetzte Aufnahme nach Ausblenden oder ein neuer
P0/P1 würden das Urteil ändern; solche Fehler sind nicht mit „eigenes Risiko“ abgedeckt.

## 4. Ehrliche Restliste – genau acht Punkte

Die Liste enthält **zwei bekannte P2** und **sechs Zielgeräte-/Testlücken**. Die
Testlücken sind für die informierte persönliche Erprobung nicht blockierend, wohl
aber vor einer uneingeschränkten Qualitätszusage an weitere Nutzer zu schließen.

| Nr. | Offener Punkt | Einordnung / bremst diese Sideload-Freigabe? | Konkreter Abschlussnachweis |
|---|---|---|---|
| **R1** | **L-01/UI-01: Space heißt überall „Leer“** | **P2, nein.** Sichtbarer i18n-Mangel, keine ausgefallene Space-Eingabe. Vor Übergabe klar benennen; nicht als vollständig lokalisierte Oberfläche verkaufen. | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt`, `displayLabel`: Layout-Label statt pauschal SPACE→Leer; anschließend EN „Space“/PT „Espaço“ sichtbar und Space fügt unverändert ein Leerzeichen ein. |
| **R2** | **L-03: availableIds() kennt nur DE/EN** | **P2, nein für die fest ausgelieferten 15 Sprachen.** Der kanonische Sprachzyklus und direkte Assetladung funktionieren laut 15/15-Probe unabhängig davon. Dynamische JSON-Erweiterbarkeit bleibt unvollständig; neue Assets dürfen noch nicht als automatisch auffindbar zugesagt werden. | `app/src/main/java/de/voicekeyboard/layouts/LayoutRepository.kt`: Asset-Scan; Test zeigt alle 15 IDs und ein zulässiges zusätzliches Layout ohne neue Hardcode-Liste. |
| **R3** | Echtes internes Mikrofon auf physischem GrapheneOS | **Nicht beweisbar auf diesem Emulator; nein für Erprobung.** WAV-Engine-Erfolg beweist weder Aufnahmequelle noch reale Berechtigungs-/FGS-/Privacy-Integration oder ARM64-Latenz. | Auf dem Zielgerät Aufnahme durch Halten, Ende bei UP, verständliche Fehler bei Privacy-Entzug, realer Feldtext; Offlinebetrieb nach Modelleinrichtung und Neustart sowie Latenz/thermisches Verhalten dokumentieren. |
| **R4** | Headsets und insbesondere Bluetooth-Routenwechsel | **Nicht geprüft; nein für Erprobung.** Erfolgreiches Internmikrofon wäre kein BT-Beweis. | Kabel-/USB- und BT-Mikrofon jeweils als tatsächliche Quelle belegen; Trennen/Wechsel während Aufnahme beendet kontrolliert oder meldet Fehler, statt heimlich auf anderes Mic umzuschalten. |
| **R5** | Diktat beim App-/Editorwechsel bzw. im Hintergrund | **Nicht geprüft; nein für Erprobung, sicherheitsrelevante Testpriorität.** Gewollte Hintergrundaufnahme ist kein Produktziel. | Ziel-App wechseln, IME verbergen und Bildschirm sperren: Mikrofon wird freigegeben, keine weitere Aufnahme und kein verspätetes Commit in einen fremden Editor. Ein Verstoß wäre P1/P0 je Auswirkung und Freigabewiderruf. |
| **R6** | Langer real gesprochener Satz mit Live-/Finalkorrektur | **Nicht durch den genannten Golden-Endtext vollständig belegt; nein für Erprobung.** Endtextgleichheit allein beweist nicht jede Zwischenrevision oder alle Fremdtextschutzfälle. | Längerer Satz mit Pause, Live-Tail und Finalkorrektur im Ziel-App-Feld; danach Cursor extern bewegen: keine Löschung vor eigenem Bereich und keine Ausgabe nach Besitzverlust; vorhandenen Text erhalten. |
| **R7** | Emoji-Glyphen und tatsächliches Einfügen auf dem Zielgerät | **Nur teilweise durch Emulatorbilder belegt; nein für Erprobung.** Die angesehenen Glyphen sind sichtbar; daraus folgt keine vollständige Prüfung aller 384 Einträge/Fonts/Größen auf GrapheneOS. | Kategorien/Seiten durchsehen, repräsentative Emoji zwischen Alttext einfügen, Cursorposition und unveränderten Alttext prüfen, Rückbutton tippen und weiter schreiben; keine abgeschnittenen Glyphen, kein Fokusverlust, PT-Fünfzeilen-Rückkehr gesondert ansehen. |
| **R8** | TalkBack / barrierefreie Erreichbarkeit | **Nicht geprüft; nein für die nicht als barrierefrei abgenommene Erprobung.** Ein sichtbarer Button oder Accessibility-Code beweist keine brauchbare Screenreader-Bedienung. | Emoji öffnen, Kategorie/Seite verstehen, Emoji auswählen, „Zur Tastatur“ finden und auslösen, normale Tastatur bedienen; sinnvolle Ansagen, Reihenfolge und kein Fokusfang. |

L-01 und L-03 sind damit **zwei unterschiedliche P2**, auch wenn der nachgeholte
UI-Review nur L-01 als Fund seines engeren Prüfumfangs zählt. Ich addiere daraus
keinen neuen P1. R3–R8 bleiben tatsächlich ungeprüft; „auf eigenes Risiko“ macht
sie weder grün noch bedeutungslos.

## 5. Darf die APK jetzt übergeben werden?

**Ja.** Die APK mit dem oben bestätigten Hash darf dem Nutzer jetzt zum Sideload
übergeben werden. **Ich fordere L-01 nicht vorher.** Ein kurzfristiger Label-Fix
wäre willkommen, rechtfertigt aber keinen weiteren ungeprüften Build vor dieser
Übergabe. Wird die APK dennoch geändert, gilt die Hashbindung dieses Urteils nicht
mehr automatisch für das neue Artefakt.

Der bloße Satz „für eigenes Risiko, ungeprüfte Areas“ wäre zu unspezifisch. Erlaubte,
ehrliche Übergabeformulierung:

> „Voice Keyboard 0.1.0 ist für deine persönliche Sideload-Erprobung freigegeben.
> Tastaturfunktionen wurden am Android-16-Emulator geprüft, die lokale Engine mit WAV.
> Echtes GrapheneOS-Mikrofon, Headsets/Bluetooth, Stoppen bei App-/Hintergrundwechsel,
> lange Live-Korrekturen, vollständige Emoji-Darstellung und TalkBack sind noch nicht
> auf deinem Gerät abgenommen. Die Leertaste heißt derzeit auch in anderen Sprachen
> ‚Leer‘; zusätzliche JSON-Layouts werden noch nicht dynamisch aufgelistet.
> Behalte zunächst eine andere Tastatur als Rückfall und teste Diktat zuerst in einem
> unkritischen leeren Feld, nicht direkt in wichtigen oder sensiblen Texten.“

Kein zusätzlicher Gerätebeweis ist für **diesen begrenzten Übergabeentscheid** nötig;
daher wird hier keine neue adb-Befehlsfolge angefordert. Für eine spätere umfassende
Abnahme sind R3–R8 als eigenständige Geräteprüfungen zu protokollieren.

## 6. Abschlussprotokoll

**FERTIG:** Vier alte P1 erledigt; Freigabe zum persönlichen Sideload erteilt; zwei P2 nicht blockierend; genau acht Restpunkte einschließlich ehrlicher Hardware-/Accessibility-Lücken dokumentiert.

**OFFEN:** R1–R8. Insbesondere physisches GrapheneOS-/Audioverhalten nicht als bestanden ausgeben.

**ANNAHME:** Orchestrator-Ergebnisse gehören zum aktuellen dist-Implementierungsstand; keine neuen gegenläufigen P0/P1-Beweise liegen vor.

**FREIGABE_NOETIG:** Keine zusätzliche Werkzeugfreigabe für dieses Urteil; keine Gerätebedienung oder Codearbeit vorgenommen.

**VERIFIKATION:** Sechs vorhandene Screenshots selbst angesehen, UI-Review vollständig gelesen, relevante bestehende Codepfade lesend abgeglichen, APK-Größe und SHA-256 selbst bestätigt; Tests/Prefs-/Touchprotokolle als übergebene Evidenz klar gekennzeichnet.
