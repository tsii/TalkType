# M7 – abschließende Design-/Geräteabnahme

## Prüfrahmen und Grenzen

- Datum: 28.10.2026, Prüfhandlungen ab 01:33 UTC; Bericht anschließend innerhalb des 20-Minuten-Budgets.
- Tatsächlich geprüft: **<device>**, API **36**, Modellkennung **sdk_gphone64_x86_64**, 1080 × 2400 px, 420 dpi. Das ist **kein physisches GrapheneOS-Gerät**.
- Emulator laut Übergabe mit `-no-audio`, also ohne nutzbares Mikrofon. Echte Aufnahme, Erkennungsqualität, Live-Commit, ARM64-Leistung und Bluetooth-Routing sind hier **nicht beweisbar auf diesem Gerät**.
- Installiert: `de.voicekeyboard`, Version **0.1.0**, targetSdk 36, x86_64, DEBUGGABLE, letzte Aktualisierung **01:32:52**. Paketbeweis: `/tmp/m7_package.txt`.
- Release-Signatur und Modellhashprüfung wurden vom Orchestrator übergeben, nicht unabhängig kryptografisch nachgeprüft. Setup bestätigt das aktive Modell sichtbar.
- Aktive IME per adb bestätigt: `de.voicekeyboard/.ime.VoiceKeyboardService`.
- Eingabe-Ziel: Settings-Suche (`com.google.android.settings.intelligence:id/open_search_view_edit_text`). Alle dokumentierten Zeichen wurden durch Taps auf die geprüfte IME eingegeben, **nicht** mit `adb input text`.
- Keine Builds, kein Gradle, kein git, keine Code-/Ressourcen-/Asset-Änderungen. Einige vorhandene Quelldateien wurden ausschließlich gelesen, um erfolglose UI-Aktionen einzuordnen. Einzige neue Projektdatei ist dieser Bericht; Beweise liegen unter `/tmp/m7_*`.
- `am start -S` wurde nur für Setup/Settings verwendet, anschließend IME erneut gesetzt. Ein anfänglicher Setup-Dump während des Splashscreens schlug fehl; erfolgreicher Dump und Screenshot wurden nach vollständigem Start wiederholt.

**Bewertung:** P0 = akute Integritäts-/Absturzgefahr; P1 = blockierende Produktfunktion; P2 = UX-/Diagnosemangel. Fehlender Hardwarebeweis ist keine bestandene Prüfung und wird separat als Freigabegate ausgewiesen.

## Abnahmetabelle

| Prüfpunkt | Erwartung | Ist (mit Beweisdatei) | Befund (P0/P1/P2/ok) |
|---|---|---|---|
| 1 – DE sichtbar und tippbar | DE-QWERTZ sichtbar; q/w/e tippen ergibt `qwe` | `/tmp/m7_1_de.png` zeigt DE mit `q w e r t z u i o p ü`; `/tmp/m7_1_de.xml` enthält im fokussierten EditText exakt **`qwe`**. | **ok** |
| 2 – Sprachwechsel EN/RU | Sprache über vorgesehenes UI wechseln, passende Beschriftung, je drei Zeichen tippen | Tap auf Globus bei ca. (165,2295), erneuter Tap und 900-ms-Langdruck öffnen kein Menü und ändern das DE-Layout nicht. Vorher `/tmp/m7_2_language_menu.png`, nach Wiederholung `/tmp/m7_2_language_nochange.png` + `.xml`; Feld bleibt `qwe`. Setup bietet ebenfalls keine Sprachwahl. EN- und RU-Beschriftung/Zeicheneingabe deshalb **nicht beweisbar über den ausgelieferten Bedienweg**, nicht bestanden. | **P1-01** – Sprachwechsel ohne Wirkung |
| 3a – einfacher Shift | Ein Großbuchstabe, anschließend wieder klein | Shift (65,2140), q, w erzeugen **`qweQw`**. `/tmp/m7_3_shift_on.png` zeigt aktivierte Großbuchstaben; `/tmp/m7_3_shift.xml` + `.png` beweisen das Ergebnis und Rückkehr zu Kleinbuchstaben. | **ok** |
| 3b – Shift-Doppeltipp/Caps | Zwei unmittelbar aufeinanderfolgende Shift-Taps aktivieren dauerhafte Großschreibung | Zwei Shift-Taps im selben adb-Shellaufruf, danach q/w: Ergebnis **`qweQwqw`**, nicht `qweQwQW`. `/tmp/m7_3_caps_on.png` und `/tmp/m7_3_caps.png` zeigen Kleinbuchstaben, `/tmp/m7_3_caps.xml` den Feldtext. Kein alternativ sichtbarer CapsLock-Schalter. | **P1-02** – Caps-Doppeltipp funktioniert nicht |
| 4a – ?123 und Rückkehr | ?123 öffnet Symbole; erneuter Tap auf dieselbe physische Taste, nun ABC, führt zurück | `/tmp/m7_4_symbols.png` zeigt Ziffern/Symbole und ABC. `/tmp/m7_4_abc.png` zeigt nach ABC wieder DE-Buchstaben und ?123. | **ok** |
| 4b – Symbols ↔ SymbolsShift | Shift in der Symboltafel schaltet sichtbar auf zweite Symboltafel und zurück | Shift (65,2140) bleibt ohne sichtbare Änderung: weiterhin `1 2 3 …`, kein `¹ ² ³ …`. `/tmp/m7_4_symbols_shift.png` zeigt unveränderte Symbole. Nach Eingabe des ersten Symbols, Shift, erneutem Symbol, weiterem Shift und erneutem Symbol endet der Feldtext auf **`111`** statt einer alternierenden Folge; `/tmp/m7_4_toggle_failed.xml` + `.png`: **`qweQwqw111`**. ABC-Rückkehr funktioniert separat. | **P1-03** – angeblich behobener Symbol-Shift-Bug weiterhin reproduzierbar |
| 5 – Emoji | Emoji-Taste öffnet Panel; ausgewähltes Emoji erscheint im Feld | Tap auf 😊 (254,2295) öffnet **kein Panel**. `/tmp/m7_5_emoji.png` zeigt unveränderte Buchstabentafel; `/tmp/m7_5_emoji.xml` weiterhin **`qwe`**, kein Emoji. Einfügen daher nicht möglich, nicht bestanden. | **P1-04** – Emoji-Aktion ohne Wirkung |
| 6 – Setup | Titel, aktive Zustandszeile, Datenschutztext und abgeschalteter Download-Schalter | `/tmp/m7_6_setup.png` + `.xml`: **„Voice Keyboard – Einrichtung“**, **„Modell aktiv – Sprachfunktion freigeschaltet.“**. Switch **„Netzwerk für Downloads erlauben“**, `checked="false"`. Datenschutz enthält exakt **„Nach dem Download deaktiviert die App ihren Download-Schalter automatisch; es findet dann kein weiterer Netzwerkzugriff statt.“** | **ok** für sichtbaren Setup-Zustand; automatischer Abschaltvorgang/Netzwerkverkehr nicht separat bewiesen |
| 7a – PTT ohne Mikrofon | 3 s halten: sichtbarer Audiofehler, kein Crash | Stationärer 3000-ms-Swipe auf (1006,1742) erzeugt **„Audio-Start fehlgeschlagen – erneut versuchen“**. `/tmp/m7_7_after_release.png`. App-PID vor/nach Test identisch **11053**. `/tmp/m7_7_logcat_review.txt`: kein `FATAL` im Prüfzeitfenster. | **ok** für erwarteten Negativpfad |
| 7b – R-2 / weiter tippen | Fehler bleibt nach Loslassen sichtbar; Tastatur bleibt nutzbar | Fehler nach UP um 01:37:11 und weiterhin um 01:37:26 sichtbar: `/tmp/m7_7_after_release.png`, `/tmp/m7_7_persistent.png` (**ca. 16 s auseinander**). Kein Zurückspringen auf „Bereit“. Anschließender e-Tap: `/tmp/m7_7_typing.xml` enthält **`qweQwqw111e`**, zusätzlich `/tmp/m7_7_typing.png`. | **ok** – R-2 bestanden im geprüften Szenario |
| 7c – Fehlerdifferenzierung | Fehlender Aufnahmeweg wird möglichst verständlich unterschieden | Meldung ist lesbar und handlungsorientiert, aber **generisch Audio-Start**, nicht spezifisch „kein Mikrofon“. Die tatsächliche Abgrenzung zwischen fehlendem Gerät, Audiokonflikt und Initialisierungsfehler ist damit nicht bewiesen. `/tmp/m7_7_after_release.png`. | **P2-01** – Diagnose zu unspezifisch, kein Crashbefund |
| 7d – echtes Diktat/Headsets | Audio → ASR → sichere Live-/Finalausgabe, interne und externe Mikrofone | Wegen `-no-audio` **nicht beweisbar auf diesem Gerät**. Dieser Test belegt ausschließlich den Fehlereinstieg und die anschließende Bedienbarkeit; keine erfolgreiche Modellinferenz, keine Audio-/BT-Freigabe. | **P1-Gate**, kein zusätzlich gezählter Implementierungsdefekt |
| 8 – Layoutqualität | Konsistente Reihen, gut erreichbare Sondertasten, klare Statusdarstellung | Reihen schließen horizontal konsistent ab, Tastenflächen sind klar getrennt, ABC/?123 sitzt stabil; breite Leertaste, Enter und Backspace visuell erkennbar. Gleichzeitig drei konkrete Qualitätsmängel, unten aufgelistet. Beweise `/tmp/m7_2_language_menu.png`, `/tmp/m7_1_de.png`, `/tmp/m7_7_after_release.png`. | **P2-02 bis P2-04** |

## Maximal drei visuelle Dinge, die billig/unfertig wirken

1. **P2-02 – Statusleiste ohne sauberen eigenen Hintergrund:** Im Screenshot `/tmp/m7_2_language_menu.png` läuft Ziel-App-Inhalt („Search Support for …“) sichtbar über den Bereich von „Bereit“. Das wirkt wie eine überlagerte Testoberfläche statt einer klar abgegrenzten IME. Aussage betrifft die sichtbare Kombination dieser Ziel-App mit der IME, nicht pauschal alle Apps.
2. **P2-03 – Zwei unterschiedlich gestaltete Mikrofone:** Eine sehr flache Mic-Taste oben und zusätzlich ein großes Emoji-Mikrofon unten. Der primäre PTT-Button wirkt wie ein kleiner Standardbutton; der zweite suggeriert gleichwertige Bedienung. Die Statuszeile ist visuell nur etwa 28 dp hoch, kein großzügiges Halteziel. `/tmp/m7_1_de.png`, `/tmp/m7_7_after_release.png`.
3. **P2-04 – Sehr schmale Ziele und uneinheitliche Symbolsprache:** Elf Tasten pro Buchstabenreihe auf etwa 411 dp Displaybreite ergeben nur ungefähr 37 dp Spaltenbreite; Cursor-/Emoji-Sondertasten sind ebenfalls schmal. Farbige Emoji-Icons neben dünnen Shift-/Enter-Glyphen und der Texttaste „Leer“ wirken zusammen improvisiert. Taps per adb funktionierten; reale Fingertreffsicherheit ist damit ausdrücklich nicht bewiesen. `/tmp/m7_1_de.png`.

## Einordnung durch ausschließlich lesende Prüfung

Die folgenden Quellbefunde erklären die Gerätebeobachtungen, ersetzen sie aber nicht:

- `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`: `onCreateInputView()` lädt fest `de-de`; `onAction()` hat keine LANGUAGE-/EMOJI-Behandlung und fällt für diese Aktionen auf `else -> Unit`. In der sichtbaren Setup-Activity gab es keinen alternativen Sprachwechsel.
- `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt`: Beim zweiten Shift-Tap trifft zuerst der Zweig für bereits aktives temporäres Shift zu und deaktiviert es. Das passt zum beobachteten Caps-Ausfall.
- `app/src/main/assets/layouts/de-de.json`: Die ⇧-Taste in `symbols` und `symbolsShift` hat `value="layer-symbols"`, während der spezielle Umschaltpfad im View `KeyActions.SHIFT` behandelt. Das passt zur trotz Tap unveränderten ersten Symboltafel.
- Keine dieser Dateien wurde geändert; kein Reparaturversuch und kein Neubuild wurden durchgeführt.

## Logcat-Beweis und Reichweite

Angefordert wurde `logcat -d`, gefiltert auf `AndroidRuntime|FATAL|VK-`:

- Rohdump: `/tmp/m7_7_logcat_full.txt`.
- Vollständiger Filter einschließlich älterer Läufe: `/tmp/m7_7_logcat_filtered.txt`.
- Auf diese Abnahme ab 01:33 UTC begrenzter Filter: `/tmp/m7_7_logcat_review.txt`.
- Im ausgewerteten Prüfzeitfenster **0 FATAL-Treffer**. `AndroidRuntime` enthält normale Starts/Shutdowns der adb-/uiautomator-Kommandos; diese sind keine App-Crashes.
- Zum Fehlerzeitpunkt: `10-28 01:37:06.171 11053 11053 I VK-Capture: stop; durationMs=2`.
- `durationMs=2` widerspricht dem 3-s-Halten nicht: Aufnahmeinitialisierung scheitert sofort, während die Touchgeste weiter gehalten wird. Keine Behauptung einer erfolgreichen dreisekündigen Aufnahme.
- Ältere VK-ASR-Einträge vor dieser Abnahme sind **kein** ASR-Erfolgsbeweis für M7.

## Verdikt

**nicht freigabefähig**

**Zählung:** 0 beobachtete P0, **4 bestätigte P1-Defekte** (Sprachwechsel, Caps-Doppeltipp, Symbol-Shift, Emoji), 4 P2-Befunde (generische Audiodiagnose plus drei visuelle Punkte). Zusätzlich ein offenes P1-Freigabegate für echte Audio-/GrapheneOS-/Headset-Abnahme; dieses ist keine behauptete weitere Implementierungsstörung.

Die sichtbaren Grundfunktionen DE, einmaliges Shift, ?123/ABC, Setup und der persistente Audiofehlerpfad funktionieren. Das genügt jedoch nicht für das fixierte Produkt mit nutzbarer Mehrsprachigkeit, CapsLock, zweiter Symboltafel und Emoji. „Freigabe mit Auflagen“ würde hier wesentliche fehlende Bedienfunktionen als bloße Restpolitur behandeln und ist deshalb nicht angemessen.

### Vor einer erneuten Freigabe zwingend

1. Vier P1-Defekte an genau der danach installierten/signierten APK erneut prüfen: EN/RU tatsächlich umschalten und tippen, Caps via Doppeltipp, zweite Symboltafel sichtbar umschalten, Emoji-Panel öffnen und Emoji im Feld nachweisen.
2. Physisches GrapheneOS-/ARM64-Gerät mit internem Mikrofon sowie Kabel-/USB- und Bluetooth-Mikrofon prüfen: Aufnahmefreigabe, PTT-UP, Routing, Live-/Finaltext, Cursorbesitz, Offline-Neustart. Hier ausdrücklich **nicht beweisbar auf diesem Gerät**.
3. R-2 und Tastaturbedienbarkeit nach diesen Änderungen erneut absichern; Setup-Downloadschalter darf bei aktivem Modell nicht unbeabsichtigt eingeschaltet werden.

### Die drei wichtigsten Restpunkte für v0.2

1. **Vollständige Tastatur-Interaktion:** Sprachwahl aller 15 Layouts, CapsLock, Symbol-Shift und Emoji als zusammenhängenden, tatsächlich bedienbaren Produktumfang abnehmen; die derzeitigen P1 sind schon vor einer Freigabe dieses Builds zu schließen.
2. **Reale Offline-Diktierabnahme:** GrapheneOS/ARM64, interne/Headset-/BT-Routen, lange Pausen, Cursorwechsel und Warm-/Thermal-Latenzen mit echten Audios belegen.
3. **Produktpolitur:** Abgegrenzte Statusfläche, eindeutige große PTT-Taste, konsistente Icons/Touchflächen und präzisere Audiofehler statt generischem Retrytext.

## Beweisdateien und Aufbewahrung

Alle oben genannten Dateien liegen im aktuellen Container unter `/tmp/`; sie sind flüchtig und müssen für eine dauerhafte Abnahme extern gesichert werden. Es wurden keine Beweisdateien in Assets/Quellressourcen kopiert. Der Bericht benennt fehlende Beweise ausdrücklich und verwendet den No-Audio-Emulator nicht als Ersatz für eine erfolgreiche physische GrapheneOS-Abnahme.
