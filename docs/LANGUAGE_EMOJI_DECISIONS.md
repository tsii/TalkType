# Entscheidungen E9: Sprachwechsel & Emoji (v1)

**Stand:** 2026-10-28 · **Rolle:** Entscheidung/Regeln (keine Code-/Gradle-/adb-Arbeit)
**Grundlage:** `docs/KONZEPT.md` (15 Sprachen, offline, keine Autokorrektur/Lernwörterbücher),
`docs/DESIGN.md` §7.1 (Tasten, „Tastaturlayoutwechsel ändert ASR nicht automatisch",
Emoji „als vollständige Sequenzen committen"), `docs/LAYOUT_REQUIREMENTS.md` Regel 4a,
`docs/SETUP_UI_DECISIONS.md` E3 („Tastatur blockiert nie"),
`keyboard/KeyboardView.kt` (`setLayout`, `activeLayerName`),
`ime/VoiceKeyboardService.kt` (`onCreateInputView` lädt fest `de-de`; `onAction` kennt
`language`/`emoji` nicht), `layouts/LayoutRepository.kt` (`availableIds()` liefert
hartkodiert nur `de-de`/`en-us`).

**Ist-Stand (bestätigt):** `🌐` und `😊` existieren in allen 15 Assets, fallen aber in
`onAction -> else -> Unit`. 14 von 15 Sprachen sind damit unerreichbar.
Zusatzbefund: `LayoutRepository.availableIds()` ist hartkodiert — die Vermarktung
„15 Sprachen" ist auch auf Repository-Ebene noch nicht wahr.

---

## E9.1 — Bedienweg Sprachauswahl: **(a) Kurztipp = nächste aktive Sprache, Langdruck = In-IME-Panel**

**Entscheidung:** Kurztipp auf `🌐` schaltet zyklisch zur nächsten **aktiven** Sprache;
Langdruck (≥ 400 ms) öffnet das Sprach-Panel **innerhalb des IME-Fensters** (E9.3).

**Gegen (b) „nur Setup"**: Der Nutzer müsste für jeden Sprachwechsel die App verlassen —
die Tastatur schließt, das Eingabefeld verliert den Fokus, der Wechsel kostet 3–5 s und
einen Activity-Wechsel. Bei gemischtsprachigem Diktat (DER Regelfall) ist das unbrauchbar.
**Gegen (c) „Wisch"**: Wischen ist auf der Space-Taste bereits Cursorbewegung
(DESIGN §7.1); ein zweiter Wisch-Handler auf `🌐` kollidiert mit Kurz-/Langdruck-
Semantik und ist ohne Sichtfeedback nicht entdeckbar. **(b)+(a) kombiniert** ist der
gewählte Weg: **Auswahl** (welche Sprachen sind aktiv) passiert in der Setup-Seite,
**Umschalten** im Alltag per Kurztipp.

**Regeln (verbindlich):**

1. **„Aktiv"** = Mehrfachauswahl in der Setup-Seite (Checkbox-Liste aller 15 Assets),
   persistiert als **geordnete** Liste. Mindestens **1** Sprache ist Pflicht
   (Abwahl der letzten wird verweigert, Checkbox bleibt gesetzt).
2. **Default aktiv:** `de-de`, `en-us` (entspricht dem heutigen Funktionsumfang; keine
   Verhaltensänderung für Bestandsnutzer).
3. **Reihenfolge beim Durchschalten** = **kanonische Reihenfolge der §2.2-Tabelle**
   (`de-de, en-us, fr-fr, es-es, it-it, pt-pt, nl-nl, pl-pl, tr-tr, ru-ru, cs-cz,
   sv-se, da-dk, fi-fi, el-gr`), gefiltert auf die aktiven. **Nicht** alphabetisch und
   **nicht** nach zuletzt benutzt (keine Lernreihenfolge — KONZEPT: kein Lernverhalten).
   Am Ende wird umgebrochen (wrap).
4. **Nur eine aktive Sprache:** Kurztipp ändert **nichts** am Layout und zeigt für 2 s
   die Statuszeile `Nur eine Sprache aktiv – in Einrichtung ändern`. Kein stiller No-Op.
5. **Layout passt immer zur Sprache:** Der Wechsel lädt das Asset der Zielsprache
   (`repository.load(id)`, Fallback `de-de`) und setzt `activeLayerName = "base"`.
   Es gibt **keinen** Zustand „ru-Layout mit de-Beschriftung" — Sprache *ist* Layout
   (kein separater Layout-Schalter in v1).
6. **IME-Eingang nie blockieren (E3):** Der Wechsel ist ein reiner
   `setLayout(...)`-Aufruf im IME-Fenster; keine Activity, kein Dialog-Window, kein
   `finishInput`. Tippen ist vor, während und nach dem Wechsel möglich.
7. **Persistenz (exakte Namen, damit Coder und Test dasselbe meinen):**
   SharedPreferences-Datei **`vk_layout`** (MODE_PRIVATE), Schlüssel:
   - `active_languages` — `String`, kommagetrennt, **in kanonischer Reihenfolge**,
     z. B. `"de-de,en-us,ru-ru"` (bewusst kein `StringSet`: das ist ungeordnet).
   - `current_language` — `String`, zuletzt aktive ID (Default `de-de`).
   Beim Start: `current_language`, falls nicht in `active_languages` enthalten →
   erste aktive Sprache.
8. **Repository:** `availableIds()` muss die Assets **scannen** (15 IDs), nicht
   hartkodieren; die Setup-Liste und der Zyklus benutzen dieselbe Quelle.

---

## E9.2 — Sprache ↔ Editor: **global, nicht pro Feld**

**Regel:** Die Sprache ist eine **globale Einstellung** (`current_language`), die für
alle Editoren gilt. Sie wird **nicht** pro `EditorInfo`/Paket gemerkt.

Begründung: (i) es gibt keine Autokorrektur und keine Wörterbücher (DESIGN §7.1), also
keinen sprachabhängigen Textzustand, der pro Feld geschützt werden müsste; (ii) die
ASR-Sprache ist **auto** und wird vom Layoutwechsel **nicht** verändert (DESIGN §3:
„Tastaturlayoutwechsel ändert ASR nicht automatisch") — eine feldweise Layoutsprache
würde eine Kopplung suggerieren, die es nicht gibt; (iii) feldweise Erinnerung ist
beobachtbares Verhalten mit Fehlerpotenzial (falsches Layout nach App-Wechsel), das
wir nicht testen können.

**Während aktiver Aufnahme (Zustand ≠ IDLE) ist der Sprachwechsel gesperrt:**
`onAction("language")` wird ignoriert, Statuszeile `Sprachwechsel während Diktat nicht
möglich`. Grund: Der committete Text steht in der Orthographie der alten Sprache, die
laufende Hypothese ist in der alten Sprache dekodiert; ein Wechsel mitten im Lauf
würde gemischt-schriftige Revisionen und Rollbacks über Sprachgrenzen erzeugen und die
Commit-Invarianten (E8) verletzen. Nach dem Loslassen (IDLE) ist der Wechsel sofort
wieder erlaubt.

---

## E9.3 — Auswahlliste: **In-IME-Overlay, kein Dialog, kein Activity-Wechsel**

| Aspekt | Entscheidung |
|---|---|
| UI | Overlay-`FrameLayout` **im** IME-Fenster über der Tastatur (eigenes Child-View), **kein** `Dialog`/`PopupWindow` mit eigenem Window-Token, **keine** Activity |
| Fokus | bleibt erhalten, weil kein neues Window entsteht: `currentInputConnection` bleibt gültig, `onStartInputView` wird nicht erneut ausgelöst; das Panel konsumiert nur Touch innerhalb seines Bereichs |
| Einträge | **15** (alle Assets), auch nicht-aktive: je Zeile `Endonym` + ISO-Tag, z. B. `Deutsch · de-DE`, `Русский · ru-RU` — Endonym ist Pflicht (der Nutzer sucht seine Sprache in seiner Schrift) |
| Sprache der Beschriftung | Endonyme sind **nicht** übersetzt; die Panel-Überschrift kommt aus Android-Ressourcen (Gerätesprache) |
| Anordnung | Raster **3 Spalten × 5 Reihen** in kanonischer Reihenfolge; Touch-Ziel ≥ **48 dp × 48 dp** (Barrierefreiheit), `contentDescription` = Endonym + Tag |
| Auswahl | Tap = `current_language` setzen, `setLayout(...)`, Panel schließen, `sendAccessibilityEvent(TYPE_WINDOW_CONTENT_CHANGED)`; **kein** `commitText`, kein Textverlust |
| Abbruch | Tap außerhalb, Zurück-Taste (`onKeyDown(KEYCODE_BACK)`), `onFinishInputView`, Editorwechsel oder Sprachwechsel per Kurztipp → Panel schließt ohne Änderung |
| Aktive Sprache | im Raster markiert (Häkchen + `selected`-State), nicht-aktive ausgegraut mit Hinweis „in Einrichtung aktivieren" |

---

## E9.4 — Emoji-Umfang v1: **384 Zeichen, 8 Kategorien, ein JSON-Asset**

**Entscheidung:** **8 Kategorien × 48 = 384 Emoji**, offline, ohne Suche, ohne
Hautfarben, ohne Verlauf/Favoriten.

| # | Kategorie (`id`) | Anzahl |
|---|---|---|
| 1 | `smileys` (Gesichter/Emotionen) | 48 |
| 2 | `people` (Personen/Gesten, ohne Hautton-Varianten) | 48 |
| 3 | `animals` (Tiere/Natur) | 48 |
| 4 | `food` (Essen/Trinken) | 48 |
| 5 | `travel` (Reisen/Orte) | 48 |
| 6 | `activities` (Sport/Freizeit) | 48 |
| 7 | `objects` (Gegenstände/Werkzeug) | 48 |
| 8 | `symbols` (Symbole/Pfeile/Herzen; **ohne** Flaggen) | 48 |

**Speicherung:** `app/src/main/assets/emoji/emoji_v1.json`

```json
{"schemaVersion":1,
 "categories":[
   {"id":"smileys","labelKey":"emoji_cat_smileys","emoji":["😀","😃","…"]},
   …]}
```

- `labelKey` verweist auf `strings.xml` (übersetzbar), `emoji` ist eine flache Liste
  **je genau einem Graphem-Cluster** pro Eintrag.
- Validierung im Test: genau 8 Kategorien, je 48 Einträge, keine Duplikate, NFC,
  **keine** ZWJ-Sequenzen, **keine** Hautton-Modifier (U+1F3FB–U+1F3FF), keine
  Regional-Indicator-Paare (Flaggen) — damit ist jedes Zeichen ein Codepunkt-Paar
  oder Einzelcodepunkt und die Einfüge-Länge ist deterministisch.

**Darstellung:** Panel mit 8 Kategorie-Tabs (Endonym-frei, Icon/Label aus Ressourcen),
Raster **8 Spalten × 6 Reihen = 48 pro Seite** (eine Seite pro Kategorie), Blättern
durch die Kategorie-Tabs; kein Wischen nötig (v1). Über der Tastatur, gleiche
Overlay-Technik wie E9.3 (Fokus bleibt).

**Einfügen:** über den **bestehenden** Pfad `listener.onTextInput(emoji)` →
`VoiceKeyboardService.onTextInput` → `ic.commitText(text, 1)`. Kein neuer
Editor-Schreibweg, keine `setText`/`setComposingText`-Nutzung; das Emoji landet an der
Cursor-Position und der Cursor steht danach dahinter. Während einer laufenden Aufnahme
(Zustand ≠ IDLE) ist das Emoji-Panel **gesperrt** (wie E9.2), weil ein manueller
Fremd-Edit die Ownership des Diktats beendet (DESIGN §6) — Statuszeile
`Emoji während Diktat nicht möglich`.

**Ausdrücklich NICHT in v1:** Hautton-/Gender-Varianten, Suche, „zuletzt benutzt"/
Häufigkeits-Historie (KONZEPT: keine Lernwörterbücher, kein Lernverhalten), Sticker,
GIFs, Zwischenablage-Einfügen.

---

## E9.5 — Sechs prüfbare Aussagen (Testmatrix)

| # | Test | Aussage |
|---|---|---|
| T1 | JVM | `active_languages`-Default ist `"de-de,en-us"`; nach Umschalten in der Setup-Liste bleibt die **kanonische** Reihenfolge erhalten; Abwahl der letzten Sprache wird verweigert |
| T2 | JVM | `nextLanguage(current, active)` liefert bei `["de-de","en-us","ru-ru"]` die Folge `en-us → ru-ru → de-de` (wrap); bei nur einer aktiven Sprache kommt dieselbe ID plus Hinweis-Flag zurück |
| T3 | Instrumented | Nach Wechsel auf `ru-ru`: `view.layout.id == "ru-ru"`, `activeLayerName == "base"`, eine Base-Taste trägt ein kyrillisches Label (z. B. `ф`), und `currentInputConnection` ist weiterhin nicht `null` (Fokus erhalten) |
| T4 | Instrumented | `onAction("language")` **während** RECORDING ändert weder Layout noch `current_language` und setzt die Statuszeile `Sprachwechsel während Diktat nicht möglich` |
| T5 | Instrumented | Emoji-Tap fügt **genau** das erwartete Zeichen an der Cursorposition ein (`textBeforeCursor` wächst um 2 UTF-16-Einheiten bei Surrogatpaaren) und verändert den vorhandenen Alttext **nicht** (Präfix byte-gleich) |
| T6 | JVM | `emoji_v1.json` validiert: 8 Kategorien × 48, keine Duplikate, NFC, keine ZWJ-/Hautton-/Flaggen-Sequenzen, jeder Eintrag genau 1 Graphem-Cluster |

**Zusätzliche Abnahme (manuell, Emulator):** Sprachwechsel bei geöffneter Tastatur in
einem `EditText` — der Cursor bleibt im Feld, es wird kein Zeichen eingefügt, und die
nächste getippte Taste schreibt in der Zielsprache.

---

## ANNAHME (dokumentiert, weil der Auftrag es offen ließ)

- `ANNAHME: Flaggen sind in v1 nicht enthalten — warum: Regional-Indicator-Paare sind
  keine Einzelcodepoints und würden die deterministische Einfüge-Länge (T5) und die
  Asset-Validierung (T6) verkomplizieren; 8 Kategorien ohne Flaggen erfüllen
  „vollwertige Tastatur" für Diktat-Korrekturen.`
- `ANNAHME: Das Sprach-Panel listet alle 15 Sprachen (auch inaktive) — warum: sonst
  müsste der Nutzer zum Aktivieren die App verlassen; inaktive Einträge sind sichtbar
  markiert und verweisen auf die Einrichtung.`

---

# E10 — Emoji-Panel: Zurück-Weg, Kategorien, Gestenleiste (Nachtrag 2026-10-28)

**Entscheidungsgrundlage:** E9.4 dieses Dokuments (384 Glyphen, 8 × 48, Raster,
Einfügen über `onTextInput → commitText(text,1)`), `docs/SETUP_UI_DECISIONS.md` E3
(„die Tastatur blockiert den Eingang nie"), `docs/KONZEPT.md` (offline, keine
Lernfunktionen).

**Gemessener Ist-Stand (<device>, 1080×2400, IME ab y≈1512):**
(1) Das Panel deckt die **gesamte** IME-Fläche inklusive der 😊-Taste → kein Rückweg;
nur der **System-Button „Tastatur ausblenden"** hilft, und der schließt die *ganze*
Tastatur (E3-Verletzung). (2) `labelKey` wird geladen, aber **nirgends angezeigt**;
die Anzeige sagt nur „1.1/2". (3) Die Blätterzeile liegt unter der **Gesten-Pille**.
(4) Reale Seitengröße **4 × 8 = 32** (nicht 48) — die Anzeige ist dazu konsistent.

**Zusatzbefund (Asset/Codeprüfung):** `emoji_v1.json` ist korrekt (8 Kategorien × 48,
`labelKey` = `emoji_cat_smileys` … `emoji_cat_symbols`), aber **keiner dieser acht
String-Keys existiert in `res/values/strings.xml`**. Deshalb ist die Beschriftung
nirgends sichtbar — es fehlt nicht die Anzeige-Logik allein, sondern die Quelle.

## E10.1 — Zurück-Weg: **(c) unterste Tastaturzeile bleibt sichtbar**

**Entscheidung:** Das Emoji-Panel zeichnet **nur über der Tastenzeilen-Region** und
lässt die **unterste Tastaturzeile** (ABC/`?123`-Umschalter, `🌐`, `😊`, `Leer`, `⏎`,
Mic) unverändert sichtbar. Die **😊-Taste ist ein Toggle**: offen → ein Tipp schließt
das Panel und kehrt in die vorherige Ebene zurück (Buchstaben oder Symbole, exakt die
Ebene, aus der das Panel geöffnet wurde).

- Panel-Unterkante = **Oberkante der untersten Tastaturzeile** (kein Überdecken).
- **Ein** Tipp genügt; Tastatur bleibt sichtbar; `currentInputConnection` bleibt
  unverändert (kein `finishInput`, kein Activity-/Dialog-Wechsel).
- Die Rückkehr-Ebene wird beim Öffnen gemerkt (`savedLayer`) und beim Schließen
  wiederhergestellt — nicht hart auf `base`.

**Verworfen:**
- **(a) ABC-Taste links in der Blätterzeile:** falsche Beschriftung, sobald das Panel
  aus der **Symbolebene** geöffnet wurde („ABC" verspricht Buchstaben, liefert aber
  `symbols`); außerdem ein **zweiter** Rückweg neben der ohnehin sichtbaren
  Tastenzeile (zwei Wege = zwei Fehlerquellen).
- **(b) × oben rechts:** kostet einen Grid-Platz, liegt in der trefferunsichersten
  Ecke (Edge-Gesten) und ist ein **dritter** Schließweg neben 😊 und System-Back.
- **(d) erneuter Tipp auf die System-IME-Leiste:** nicht unsere UI, geräteabhängig,
  und laut Messung schließt er die **gesamte** Tastatur — genau die E3-Verletzung.

## E10.2 — Kategorien sichtbar: **Name in der Blätterzeile, Quelle `strings.xml`**

**Entscheidung:** Die Blätterzeile zeigt in der Mitte **`<Kategoriename> <k>/8 · <s>/<S>`**
(z. B. `Tiere 3/8 · 1/2`), links `‹`, rechts `›`. Kein Tabs-Streifen, keine neue feste
Zeile (Platz: 32 Glyphen/Seite bleiben unangetastet), kein Overlay beim Blättern.

- **Quelle der Namen: `res/values/strings.xml`** über den im Asset gespeicherten
  `labelKey` (`emoji_v1.json → categories[].labelKey`). Damit ist die Anzeige
  **gerätessprachig** (E9.3-Regel) und das Asset bleibt locale-neutral. Der
  `labelKey` **muss** exakt dem String-Namen entsprechen — der Test prüft diese
  Verbindung (E10.5/T3), damit Coder und Test dieselbe Quelle meinen.
- **Fehlende Keys sind ein Fehler**, kein stiller Fallback: fehlt ein String, zeigt
  die Zeile den Kategorie-`id` in Klammern und der Test schlägt fehl.
- Textstil: 12 sp, einzeilig, `ellipsize=end`, `contentDescription` =
  „Kategorie <Name>, Seite <s> von <S>, Kategorie <k> von 8".

**Die 8 Anzeigenamen (deutsch, verbindlich — exakt diese Strings):**

| `id` | `labelKey` | Deutsch |
|---|---|---|
| `smileys` | `emoji_cat_smileys` | `Gesichter` |
| `people` | `emoji_cat_people` | `Personen` |
| `animals` | `emoji_cat_animals` | `Tiere & Natur` |
| `food` | `emoji_cat_food` | `Essen & Trinken` |
| `travel` | `emoji_cat_travel` | `Reisen & Orte` |
| `activities` | `emoji_cat_activities` | `Aktivität` |
| `objects` | `emoji_cat_objects` | `Objekte` |
| `symbols` | `emoji_cat_symbols` | `Symbole` |

(Die acht Keys sind **neu anzulegen** — sie fehlen heute in `strings.xml`.)

## E10.3 — Gestenleisten-Abstand und Seitengröße

- **Untergrenze:** Die gesamte Tastatur (und damit die unterste Zeile) erhält unten
  **≥ 24 dp** Innenabstand zu den System-**Gesten-Insets**
  (`WindowInsetsCompat.Type.systemGestures()` bzw. `navigationBars()`), gemessen ab
  Oberkante der Gesten-Pille. Die **Blätterzeile** liegt mit **≥ 8 dp** Abstand über
  der Oberkante der untersten Tastaturzeile. Damit ist die Pille konstruktiv **nie**
  über der Blätterzeile (Ist-Fehler 3 behoben). Auf 3-Tasten-Navigation ist der
  Inset 0 — die Regel bleibt gültig (nur der Puffer entfällt).
- **Wenn eine Grid-Zeile wegfällt** (kleine Displays, große Schrift, Display-Cutout):
  `pageSize = rows × 8` wird **aus dem tatsächlich gemessenen Platz** berechnet,
  `pages(cat) = ceil(48 / pageSize)`, und die Anzeige zeigt **immer** die berechnete
  Zahl. **Verboten:** eine fest verdrahtete Seitenzahl („2") oder eine Anzeige, die
  nicht zur realisierten Seitengröße passt (Ist-Fehler 4: 32 statt 48, Anzeige
  stimmte nur zufällig). Mit 4×8 = 32 sind es **2 Seiten je Kategorie**, die zweite
  Seite ist mit **16** Glyphen teilgefüllt — das ist korrekt und keine Lücke.

## E10.4 — Zustandsregeln (Schließen/Öffnen/Sprachwechsel)

1. **Beim Schließen gemerkt:** die **Kategorie** bleibt erhalten; die **Seite** wird
   auf **1** zurückgesetzt. Begründung: Wer das Panel erneut öffnet, sucht in aller
   Regel ein *anderes* Zeichen; eine stehengebliebene halbgefüllte Seite 2 (16 von 32
   Plätzen) ist die schlechtere Startposition. Kein Lernverhalten (KONZEPT).
2. **Sprachwechsel bei offenem Panel** (Code ruft bereits `hideEmojiPanel()`): Panel
   schließt, **Kategorie bleibt**, Seite → 1, das neue Layout wird geladen; der
   Editorfokus bleibt. Kein Zeichen wird eingefügt.
3. **Tipp auf 🌐 bei offenem Panel** (durch E10.1 wieder sichtbar): identisch zu (2) —
   erst schließen, dann Sprachwechsel; **niemals** Panel offen über einem
   Sprachwechsel stehen lassen (Grid-Geometrie und Zählung hängen am Layout).
4. **Diktat aktiv (Zustand ≠ IDLE):** Panel bleibt gesperrt (E9.4), Statuszeile
   `Emoji während Diktat nicht möglich`; ein Öffnungsversuch schließt nichts.
5. **Einfügen:** unverändert über `onTextInput → commitText(text,1)`; das Panel bleibt
   nach dem Einfügen **offen** (mehrere Emoji nacheinander), Cursor steht dahinter.

## E10.5 — Vier prüfbare JVM-Aussagen

| # | Test | Aussage |
|---|---|---|
| T1 | `EmojiPanelCloseTest` | Das Schließen-Signal liefert **genau eine** Aktion (`panelClosed`) und ändert **weder** Text im Editor **noch** die Ebene der Tastatur außer der Wiederherstellung von `savedLayer`; `currentInputConnection` bleibt gesetzt (kein `finishInput`) |
| T2 | `EmojiPagingTest` | Für jede Kategorie gilt `pages = ceil(48 / pageSize)`, und das **Aneinanderhängen** aller Seiten ergibt **exakt** die Asset-Liste (keine Verluste, keine Duplikate, letzte Seite teilgefüllt erlaubt); die angezeigte Zahl entspricht `pages` |
| T3 | `EmojiCategoryLabelTest` | Jeder `labelKey` im Asset existiert als String-Ressource, und der angezeigte Text entspricht dem Ressourcenwert (DE-Fixture: die 8 Namen aus E10.2); ein fehlender Key lässt den Test **rot** werden |
| T4 | `EmojiPanelStateTest` | Nach `open → (Kategorie 3, Seite 2) → close → open` ist die Kategorie 3, die Seite 1; nach Sprachwechsel bei offenem Panel ist das Panel geschlossen, die Kategorie erhalten und das Layout = Zielsprache |

**Abnahme am Gerät (manuell):** Panel öffnen → ein Emoji einfügen → **😊** tippen →
Tastatur ist sofort wieder in der vorherigen Ebene, das eingefügte Zeichen steht im
Feld, der Cursor blinkt im selben Feld; die Blätterzeile ist vollständig sichtbar
(nicht von der Gesten-Pille überdeckt) und zeigt `Gesichter 1/8 · 1/2`.

## ANNAHME-Zeilen

- `ANNAHME: Die Kategorie bleibt beim Schließen erhalten, die Seite nicht — warum:`
  `Wiedereinstieg sucht meist ein anderes Zeichen; halbgefüllte Seite 2 als Start wäre`
  `irreführend.`
- `ANNAHME: Kein Tabs-Streifen im Grid — warum: er kostet eine Glyphen-Zeile (32 → 24`
  `pro Seite, 3 Seiten je Kategorie) und damit genau den Platz, den E9.4 als knapp`
  `markiert; die Blätterzeile trägt Name und Fortschritt ohne Zusatzzeile.`
