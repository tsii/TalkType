# E11 – SPACE-Beschriftung und Accessibility für v0.1.x

**Status:** Entscheidungsreif, keine Implementierung. Einzige neue Datei ist dieses Dokument.
Keine Code-/Assetänderung, kein Gradle, kein adb; parallele Arbeiten an KeyboardView/LayoutRepository bleiben unberührt.
Grundlage: übergebene Asset-/Gerätemessungen und die begrenzte persönliche Sideload-Freigabe aus `FINAL_REVIEW_M8.md`.

## A – Verbindliche SPACE-Werte in kanonischer Reihenfolge

**Wort-Label aus dem Layout gewinnt.** Die acht vorgegebenen Übersetzungen bleiben erhalten; die übrigen sieben werden wie folgt festgelegt.

| Layout-ID | Kanonisches Wort-Label | Begründung – jeweils ein Satz |
|---|---|---|
| de-de | **Leer** | Vorgegeben und gegenüber „Leertaste“ ausreichend kurz. |
| en-us | **Space** | Übliche kurze englische Beschriftung statt des falschen deutschen Labels. |
| fr-fr | **Espace** | Bereits korrektes vorgegebenes Asset-Label bleibt erhalten. |
| es-es | **Espacio** | Bereits korrektes vorgegebenes Asset-Label bleibt erhalten. |
| it-it | **Spazio** | Bereits korrektes vorgegebenes Asset-Label bleibt erhalten. |
| pt-pt | **Espaço** | Bereits korrektes vorgegebenes Asset-Label einschließlich ç bleibt erhalten. |
| nl-nl | **Spatie** | Bereits korrektes vorgegebenes Asset-Label bleibt erhalten. |
| pl-pl | **Spacja** | Kurze polnische Bezeichnung für das Leerzeichen ohne das längere Tastaturwort. |
| tr-tr | **Boşluk** | Kurze türkische Bezeichnung mit korrektem ş statt eines deutschen Fremdlabels. |
| ru-ru | **Пробел** | Übliche kompakte russische Bezeichnung, die kürzer als eine Beschreibung der ganzen Taste ist. |
| cs-cz | **Mezera** | Kurze tschechische Bezeichnung des Leerzeichens statt „Leer“. |
| sv-se | **Mellanslag** | Übliche schwedische Bezeichnung ohne eine frei erfundene Wortabkürzung. |
| da-dk | **Mellemrum** | Das ausdrücklich vorgegebene korrekte dänische Asset-Label wird nicht umbenannt. |
| fi-fi | **Väli** | Bewusst kurze finnische Beschriftung statt des längeren „Välilyönti“. |
| el-gr | **Διάστημα** | Das vorgegebene griechische Wort einschließlich Akzent bleibt erhalten. |

### Platzregel – auch für eine tatsächlich nur eine Gewichtseinheit breite Taste

**Ein Wort darf nicht still abgeschnitten, mit Ellipse versehen oder vom Coder frei abgekürzt werden.**
Als einzige freigegebene Kompaktbeschriftung gilt für **alle 15 Sprachen `␣`** (U+2423).
Bei einer nur eine Gewichtseinheit breiten SPACE-Taste ist diese Kompaktform verbindlich;
bei breiteren Tasten gilt das kanonische Wort, sofern es vollständig lesbar hineinpasst,
sonst ebenfalls `␣`. Kein beliebiges Schrumpfen der Schrift bis zur Unlesbarkeit.
Insbesondere gilt RU **Пробел**, nicht eine erfundene Kürzung; bei Platznot **␣**.

Das trennt Übersetzung von Platzdarstellung: Die Wortwerte bleiben im Asset erhalten,
die Kompaktform ersetzt ausschließlich die sichtbare Beschriftung, niemals den
SPACE-Aktionswert oder den eingefügten Text. Es werden keine neuen Layoutbreiten verlangt.

**ANNAHME:** „1-weitig“ meint wirklich nur eine normale Tasten-Gewichtseinheit, nicht
die heute breitere Leertaste. Die acht fest vorgegebenen Wörter, insbesondere
„Mellemrum“ und „Διάστημα“, können ohne konkrete Font-/Breitenmessung nicht seriös als
alle auf einer solchen schmalen Taste passend zugesichert werden; die verbindliche
Kompaktform löst diesen Zielkonflikt, statt korrekte Übersetzungen heimlich zu verstümmeln.
Die Glyphendarstellung von `␣` ist vor Verwendung zu belegen.

## B – symbols und symbolsShift: Wort-Label, nicht blank

**Alle vier Ebenen derselben Sprache erhalten dasselbe kanonische Wort-Label aus A;
`" "` und leere Labels sind keine beabsichtigte Gestaltung.** Die Platzregel aus A
gilt auf jeder Ebene identisch; bei gleicher Breite ergibt sich dieselbe Beschriftung.

Begründung: SPACE behält beim Ebenenwechsel seine Bedeutung, und ein beschriftetes
Aktionsziel ist besser auffindbar als eine namenlose Fläche. Gegen „Die Leer-Taste
muss überall gleich heißen“ gilt: **gleiche Aktion und Konsistenz innerhalb einer
Sprache ja, deutsches „Leer“ über alle Sprachen nein**; außerdem hat bei echten
Breitenunterschieden vollständige Lesbarkeit Vorrang vor wortwörtlicher Gleichheit.
Ein absichtlich blankes Symbol-Label wird ausdrücklich verworfen.

Der gerade umgesetzte Fallback `R.string.key_space_label = "Space"` bleibt ein
**defensiver Notbehelf für defekte/leere Labels**, keine Übersetzungspolitik und kein
Ersatz für die Assetkorrektur. In den bereinigten 15 Assets darf er regulär in keiner
Ebene mehr benötigt werden; die SPACE-Ausgabe bleibt genau ein Leerzeichen.

## C – Accessibility in v1: **c3, bewusst zurückstellen in R8**

Für die persönlich erprobte v0.1.x bleibt die Übersetzung der fünf Accessibility-Namen
bewusst in **R8 offen**; weder die deutschen Hardcodes noch ein pauschaler englischer
Ersatz sind eine barrierefreie Abnahme. Ein 15-Sprachen-Satz wird erst zusammen mit
geklärter Ansagesprache und realer TalkBack-Prüfung beauftragt, statt jetzt 75
ungeprüfte Übersetzungen als erledigte Accessibility auszugeben.

**Folge:** Jetzt keine neuen `values-*/strings.xml` für diese fünf Namen und keine
neue englische Universalbezeichnung. Sichtbares SPACE-Wort und Screenreader-Name
sind getrennte Probleme; insbesondere bedeutet `␣` nicht, dass ein Screenreader nur
das Symbol vorlesen soll. „Löschen“, „Eingabetaste“, „Leertaste“, „Umschalt“ und
„Feststell shift“ bleiben bekannte deutsche Ansagen und ein ehrlicher i18n-/A11y-Mangel.
Dies blockiert nicht die bereits begrenzte persönliche Sideload-Erprobung, wohl aber
einen Claim „in allen 15 Sprachen barrierefrei geprüft“.

## D – Messmethode und exakt erwartete Beweise

Keine neue Geräteprüfung in diesem Auftrag; folgende Beweise gelten für den späteren
Coder-/Orchestrator-Nachweis. **uiautomator ist kein Nachweis der IME-Beschriftungen**,
weil die betreffenden IME-Inhalte darin nicht sichtbar sind.

| Prüfung | Erwarteter Sichtbarkeits-/Funktionsbeweis |
|---|---|
| Kanonische Werte | Screenshot der SPACE-Taste je aktiviertem Layout in base, mit eindeutig zugeordneter Layout-ID; jedes Wort entspricht A, insbesondere EN Space, PL Spacja, TR Boşluk, RU Пробел, CS Mezera, SV Mellanslag und FI Väli. |
| Längste/andere Schriften | DA Mellemrum, EL Διάστημα und RU Пробел auf Screenshots vollständig innerhalb ihrer Taste, ohne Übermalen der Nachbartaste oder abgeschnittene Buchstaben. |
| Ebenenregel | Screenshots von base, symbols und symbolsShift für DA und FI zeigen jeweils dasselbe Wort bei gleicher Breite, kein blankes Label und keinen stillen Wechsel zum englischen Space-Fallback. |
| Echte 1-weitige Taste / Platznot | Sofern eine solche Konfiguration geprüft wird: Screenshot zeigt ausschließlich das vollständig lesbare `␣` innerhalb der Taste; ein lesbarer Glyph fehlt nicht und wird nicht durch ein Kästchen ersetzt. |
| Funktion unverändert | SPACE tippen: Screenshot des Felds zeigt bei kontrollierter Einfügestelle `a b` statt `ab`; ergänzendes `VK-HIT`-Log ordnet den Tap der SPACE-Aktion zu, nicht dem Emoji-/Sprachwechsel. |
| Accessibility | Kein Titelbeweis wird für diesen c3-Umfang gefordert; ein späterer `VK-HIT`-Logwert kann die konfigurierte Ansage belegen, ersetzt aber keine echte TalkBack-Hör-/Bedienprüfung. |

Die Labelbeweise sind nicht mit einem Gerätebeweis über das Textfeld allein erledigt:
Ein eingefügtes Leerzeichen beweist die Aktion, nicht die sichtbar korrekte Sprache.
Umgekehrt beweist ein Screenshot mit „Пробел“ nicht die tatsächlich konfigurierte Ansage.

## Abschluss

**FERTIG:** 15 Wortwerte, einzige erlaubte Kompaktform, einheitliche Ebenenregel und c3-Umfang festgelegt.
**OFFEN:** Umsetzung/Assetbereinigung durch den Coder, Sichtbarkeitsbeweise und Accessibility-Restpunkt R8.
**ANNAHME:** In der Übergabe sind unter „sieben falsche Assets“ sechs IDs aufgezählt; fi-fi ist die siebte noch festzulegende Sprache und durch A ausdrücklich abgedeckt.
**FREIGABE_NOETIG:** Keine für diese reine Dokumententscheidung; keine Geräte-/Buildarbeit angefordert oder ausgeführt.
