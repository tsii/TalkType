# Review Stufe 1 — Voice-Keyboard (Android-IME)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Geprüfter Code-Stand:** Stufe 1 (M0/M1-Umfang: Gerüst + bedienbare Tastatur, kein Diktat).
**Nicht geprüft (parallel in Arbeit eines anderen Agenten):** `app/src/main/java/de/voicekeyboard/asr/`,
`app/src/test/java/de/voicekeyboard/asr/`.

**Verifikation:** `ANDROID_HOME=/opt/android-sdk ./gradlew --offline assembleDebug test` → **BUILD SUCCESSFUL**
(68 Tasks, 6 executed). `adb -s <device>` zeigt IME aktiv (`de.voicekeyboard/.ime.VoiceKeyboardService`, `mInputShown=true`),
Host-Editor enthält `qwe` (3 Tasten via `InputConnection` — M0-Kriterium erfüllt).

**Nicht prüfbar / Einschränkung:** Der Screenshot `/tmp/vk-stage1.png` wurde aufgenommen, konnte vom Review-Modell
aber **nicht visuell ausgewertet** werden (kein Bild-Support). Die Layout-Befunde sind stattdessen **direkt aus
`de-de.json`** belegt (stärkere Evidenz als ein Screenshot). `uiautomator dump` zeigt: die IME-Tasten erscheinen
**nicht** im Accessibility-Baum (nur Host-App-Views, vgl. Befund VK-05).

---

## Pflichtfragen (Kurzantworten)

1. **ä ö ü ß auf `base`?** **NEIN.** Alle vier nur als `alternates` (`ä`/`ü` auf `u`, `ö` auf `o`, `ß` auf `s`),
   keine eigene Taste. Verstoß gegen LAYOUT_REQUIREMENTS („eigene Tasten in `base`: ä ö ü ß") und DESIGN §7.3.
   `=\<` (symbolebene, shift-Taste) und `⌇` (symbolsShift) sind genau die verbotenen Artefakte aus
   LAYOUT_REQUIREMENTS Grundregel 5. Mic ist **doppelt** (Statuszeile-Button + `mic`-Taste je Ebene).
2. **Backspace nicht codepoint-/graphemsicher.** `getTextBeforeCursor(2,0)` + `substring(len-1)` zerlegt
   Surrogatpaare (löscht nur das Low-Surrogat). `performEditorAction` verwendet `IME_MASK_ACTION`, prüft aber
   **nicht** `IME_FLAG_NO_ENTER_ACTION`/Mehrzeiligkeit. `commitText(text, 1)` (`newCursorPosition=1`) ist korrekt.
3. **Shift/CapsLock:** Doppeltipp-Caps und einmaliges Shift funktionieren; `shiftActive` ist jedoch **toter
   Zustand** (wird nie `true`). Kritisch: **`layer-symbols`/`layer-base` sind gar nicht verdrahtet** — die
   Ziffern-/Symbolebene ist unerreichbar, „Caps betrifft Buchstaben, nicht Ziffern" kann nicht einmal eintreten.
4. **KeyboardView:** `onMeasure`/`onSizeChanged` rebuilden das Modell (Rotation ok). `onDraw` allokiert pro Key
   eine Liste (`rows.flatten()` in `sourceKey`, O(n²)); keine Paint-Allokation pro Frame. `ACTION_CANCEL` behandelt
   (kein Fehl-Key). **Barrierefreiheit fehlt vollständig** (nur Canvas, keine ContentDescription/NodeInfo).
5. **LayoutParser** validiert Allowlist, eindeutige IDs, Zeilensummen, `width>0`, `schemaVersion==1`. Lücken:
   `requireString` prüft nur Existenz, nicht Typ; Root-`additionalProperties` nicht geprüft; `alternates`-Limit (max 8)
   und „nur Zeichen" nicht geprüft; `deadKeys` nicht als `required` durchgesetzt. Fehlerisolierung je Asset und
   Fallback DE→EN sind vorhanden.
6. **CaseMapper** tr `i→İ`/`ı→I` und `I→ı`/`İ→i` korrekt; nicht-tr über `uppercase(locale)` (mehrzeichenfähig,
   `ß→SS`). Die `ß→ẞ`-Anforderung wird vom **Layout** nicht erfüllt (shift-Ebene enthält kein `ß`/`ẞ`).
7. **Manifest** minimal und korrekt (`exported=true` + `BIND_INPUT_METHOD` am Service ist Pflicht).
   `RECORD_AUDIO`/`FOREGROUND_SERVICE*`/`INTERNET`/`foregroundServiceType` fehlen — **Hinweis für spätere Stufen** (FGS).
8. **Tests decken nicht die Risiken ab.** Es fehlt insbesondere der verbindlich geforderte
   `LayoutCompletenessTest` (LAYOUT_REQUIREMENTS Abnahmekriterium 1), dessen Fehlen genau den ä/ö/ü/ß-Fehler
   durchgelassen hat. Details in VK-09/VK-10.

---

## Findings

Legende: P0 = Merge blockiert · P1 = vor Release beheben · P2 = Report.

| ID | Sev | Datei:Zeile | Befund | Evidenz (Codezitat / Befehl) | Konkreter Fix |
|---|---|---|---|---|---|
| VK-01 | **P0** | `app/src/main/assets/layouts/de-de.json:21,23,27,28` | Pflichtzeichen ä ö ü ß fehlen als eigene `base`-Taste; nur `alternates`. Verstößt gegen DESIGN §7.3 und LAYOUT_REQUIREMENTS (Tabelle: „eigene Tasten in `base`: ä ö ü ß", Grundregel 1). | `{"id":"u",…,"alternates":["ü",…]}`, `{"id":"o",…,"alternates":["ö",…]}`, `{"id":"a",…,"alternates":["ä",…]}`, `{"id":"s",…,"alternates":["ß",…]}` | DE-QWERTZ umbauen: ä ö ü ß als eigene (dezidierte) Tasten, z. B. `p ü`-Spalte und ß neben `0`; alternates nur als Zusatz. |
| VK-02 | **P0** | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:44-71` + `keyboard/KeyboardView.kt:205-223` | Ziffern-/Symbolebene unerreichbar: `layer-symbols`/`layer-base` werden weder in `KeyboardView.handleAction` noch in `VoiceKeyboardService.onAction` behandelt → `?123`/`ABC` ohne Wirkung. | `handleAction`: `when(action){SHIFT->… else->listener?.onAction(action)}`; `onAction` kennt nur `BACKSPACE/SPACE/ENTER/CURSOR_L/R`. | Layer-Wechsel in `KeyboardView` implementieren (analog Shift) oder/und `onAction` auf `LAYER_SYMBOLS`/`LAYER_BASE` erweitern und `activeLayerName` setzen + `rebuildModel`. |
| VK-03 | P1 | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:47-54` | Backspace zerlegt Surrogatpaare/Emoji: `getTextBeforeCursor(2,0)` + `substring(before.length-1)` liefert nur das Low-Surrogat → `deleteSurroundingText(1,0)` hinterlässt korrupten Text (High-Surrogat). Verstößt gegen DESIGN §6. | `val last = before.substring(before.length-1); val utf16 = last.length; ic.deleteSurroundingText(utf16,0)` (bei `…😀` wird nur `\uDE00` gelöscht). | Vollständigen letzten Codepoint/Graphem bestimmen (High-Surrogat erkennen → 2 Einheiten; ggf. `Character.charCount`/Graphemgrenze) und erst dann `deleteSurroundingText(n,0)`. |
| VK-04 | P1 | `app/src/main/assets/layouts/de-de.json:134,185` (und `en-us.json:134`) | Verbotene Label-Artefakte `=\<` (Symbolebene-Shift-Taste) und `⌇` (symbolsShift `question`). Verstoß gegen LAYOUT_REQUIREMENTS Grundregel 5 („Verboten sind insbesondere … `=\<` und `⌇`"). | `{"id":"shift","label":"=\\<","value":"layer-symbols"}`; `{"id":"question","label":"⌇",…}` | Labels durch echte Zeichen ersetzen (Shift-Taste z. B. `ABC`/`⇧`, `question` z. B. `?`); `\`/`<`/`>`/`&` aus Labels verbannen. |
| VK-05 | P1 | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt` (gesamte View) | Keine Accessibility-Integration: Tasten nur per `Canvas`; kein `contentDescription`, kein `onPopulateAccessibilityEvent`, keine Beschreibung. `performClick()` ohne `setOnClickListener`. TalkBack kann einzelne Tasten nicht ankündigen. Verstößt gegen DESIGN §8 (Barrierefreiheit). | `uiautomator dump` zeigt ausschließlich Host-App-Nodes, keine IME-Tasten; im Code kein `contentDescription`/`AccessibilityNodeProvider`. | `onInitializeAccessibilityNodeInfo` pro Taste implementieren (Label + Action), `contentDescription` auf Tasten, `setOnClickListener` für `performClick`. |
| VK-06 | P1 | `app/src/test/java/de/voicekeyboard/layouts/` (Datei fehlt) | Der verbindlich geforderte `LayoutCompletenessTest` (LAYOUT_REQUIREMENTS „Abnahmekriterien 1+2") existiert **nicht**; deshalb rutschte der ä/ö/ü/ß-Fehler (VK-01) und das Artefakt (VK-04) unentdeckt durch. | `find app/src/test -name '*Completeness*'` → leer; `LayoutParserTest` prüft nur Schema-Struktur, nicht Zeichenabdeckung. | `LayoutCompletenessTest` ergänzen: je Asset Pflichtzeichen aus LAYOUT_REQUIREMENTS-Matrix auf `base` **als eigene Taste**, ß→ẞ in `shift`, Artefakt-Verbot (`\ < > &`, `=\<`, `⌇`), alternates ≤8 & nur Zeichen. |
| VK-07 | P1 | `app/src/main/assets/layouts/de-de.json:shift-Ebene (id „s", Zeile ~78)` | `ß → ẞ` (Großform) fehlt vollständig in `shift`. LAYOUT_REQUIREMENTS Sonderfall Deutsch: „ß → ẞ muss in shift vorhanden sein, auch wenn Unicode-Default SS wäre". shift-`s` hat nur `["Ś","Š"]`. | shift: `{"id":"s",…,"value":"S","alternates":["Ś","Š"]}` (kein `ẞ`). | In shift-Ebene ẞ ergänzen (eigene Taste oder `alternates` in Großform) und im Completeness-Test abdecken. |
| VK-08 | P2 | `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:62-70` | `performEditorAction` wählt Aktion ohne `IME_FLAG_NO_ENTER_ACTION`- und Mehrzeilig-Prüfung; ein mehrzeiliges Feld mit gesetzter `ImeAction` würde `performEditorAction(GO/…)` statt Zeilenumbruch auslösen. | `val action = info?.imeOptions?.and(IME_MASK_ACTION)` → bei `!= NONE` immer `performEditorAction(action)`. | Vor `performEditorAction` prüfen: `(inputType & IME_FLAG_NO_ENTER_ACTION)!=0` oder `TYPE_TEXT_FLAG_MULTI_LINE` → dann `commitText("\n",1)`. |
| VK-09 | P2 | `app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt:36` vs. `de-de.json:55` | Mic-Taste doppelt: deaktivierter `micButton` in der Statuszeile **und** `mic`-Action-Taste in jeder Layout-Ebene. Beide in Stufe 1 wirkungslos, aber redundant/verwirrend. | `micButton = Button(context).apply { text="🎤"; isEnabled=false }` + jede Ebene `{"id":"mic",…,"value":"mic"}`. | Einen Ort definieren (DESIGN §8: „getrennte große Touchfläche"); Statuszeilen-Platzhalter entfernen oder als reine Statusanzeige ohne Button bedienen. |
| VK-10 | P2 | `app/src/main/java/de/voicekeyboard/layouts/LayoutParser.kt:169-172` (`requireString`) + `parse` | „Strikte" Validierung ist nur teilweise strikt: `requireString` prüft nur `has(field)`, nicht den Typ — `getString` konvertiert Integer/Boolean still zu String (Schema `type:"string"` verletzt). Root-`additionalProperties:false` wird nicht geprüft. | `private fun requireString(obj,f,path){ if(!obj.has(f)) throw … }` → `{"id":5}` wird zu `"5"` akzeptiert. | Typ prüfen (`opt(field) is String`) und Root-Felder gegen Allowlist (`schemaVersion,id,language,layers,deadKeys`) via `rejectUnknown`. |
| VK-11 | P2 | `app/src/main/java/de/voicekeyboard/layouts/LayoutParser.kt` (`parseKey`, `parseDeadKeys`) | `alternates` wird weder auf max 8 noch auf „nur Zeichen, keine Aktionsnamen" limitiert; `deadKeys` ist im Schema `required`, wird aber optional akzeptiert. | `val alternates = if(obj.has("alternates"))…map{getString(it)}` (ungeprüft). | Limit 8 und Zeichenprüfung ergänzen; `deadKeys` als Pflichtfeld behandeln. |
| VK-12 | P2 | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:42,96,208-210` | `shiftActive` ist toter Zustand (initialisiert `false`, nur auf `false` gesetzt, nie `true`); Zustandslogik verlässt sich ausschließlich auf `shiftTemporary`/`capsLock`. Erschwert CapsLock-Interaktion (ein Shift-Tap schaltet CapsLock **nicht** aus). | `var shiftActive=false`; in `handleAction` nur `shiftActive=false`. | Zustandsmaschine säubern: entweder `shiftActive` für die Anzeige nutzen oder entfernen; Verhalten „einmal Shift bei CapsLock deaktiviert CapsLock" festlegen + testen. |
| VK-13 | P2 | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:120,215` | `sourceKey()` ruft pro Key `rows.flatten().firstOrNull` in `displayLabel`/`textFor` auf (je `onDraw`-Frame, O(n²) + Listen-Allokation). | `private fun sourceKey(id)=layout?.layers?.get(…)?.rows?.flatten()?.firstOrNull{it.id==id}` | ID→Key/Index-Map einmal beim `rebuildModel` vorhalten und in `onDraw`/Treffererkennung wiederverwenden. |
| VK-14 | P2 | `app/src/main/res/xml/method.xml:4-8` | Nur `de_DE`-Subtype deklariert; das vorhandene `en-us`-Layout hat keinen IME-Subtype (System-Integration/Sprachmetadaten). | Nur `<subtype … imeSubtypeLocale="de_DE" …/>`. | `en_US`-Subtype ergänzen (DESIGN §7.3 aktivierte Layouts per Systemsubtypes). |
| VK-15 | P2 | `app/src/main/AndroidManifest.xml` (gesamtes Manifest) | Für das Endprodukt fehlen Permissions `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `INTERNET` und `android:foregroundServiceType="microphone"` am späteren FGS. **Hinweis** (DESIGN §2.2) — für Stufe 1 korrekt weggelassen, da weder FGS noch Mikrofonpfad existieren. | Manifest enthält nur `<application>` + Activity + IME-Service. | In der FGS-Stufe (M2/M6) nachtragen; `foregroundServiceType` erst mit dem Mic-Service deklarieren. |

---

## Verdikt

**BLOCK für Merge (Stufe 1 ohne die beiden P0 nicht freigeben).**

Der M0-Kern (installierbare IME, 3 Tasten über `InputConnection`, Build + JVM-Tests grün) ist erfüllt und solide.
Blockierend sind **VK-01** (verbindliche DE-Pflichtzeichen ä ö ü ß fehlen als eigene Tasten, verstößt gegen
LAYOUT_REQUIREMENTS) und **VK-02** (Ziffern-/Symbolebene ist gar nicht erreichbar — Bedienumfang DESIGN §7.1/§7.3).
Beides sind direkte Vertragsverstöße gegen die verbindlichen Docs, keine Geschmacksfragen. Zusätzlich sind die P1
(VK-03 Datenintegrität bei Backspace, VK-04 Artefakte, VK-05 Barrierefreiheit, VK-06 fehlender Completeness-Test,
VK-07 ß/ẞ) vor Release zu beheben.

---

## Die 5 wichtigsten Maßnahmen (Reihenfolge)

1. **VK-01** — DE-Layout fixen: ä ö ü ß als eigene `base`-Tasten, `ß→ẞ` in `shift` ergänzen (VK-07 inklusive). Direkter Pflichtzeichen-Verstoß.
2. **VK-02** — Layer-Wechsel `layer-symbols`/`layer-base` verdrahten; sonst sind Ziffern und beide Symbolebenen (DESIGN §7.1) nutzlos.
3. **VK-06** — `LayoutCompletenessTest` nach LAYOUT_REQUIREMENTS einführen (Pflichtzeichen ja/nein, Artefakt-Verbot `=\<`/`⌇`, alternates≤8, ß→ẞ); dieser Test hätte VK-01/VK-04/VK-07 automatisch abgefangen.
4. **VK-03** — Backspace codepoint-/graphemsicher machen (Surrogatpaare/Emoji nicht zerlegen). Textintegrität ist DESIGN §6-Pflicht.
5. **VK-04 + VK-05** — Artefakt-Labels entfernen und Barrierefreiheit (ContentDescription/AccessibilityNodeInfo) ergänzen, um DESIGN §8 zu erfüllen.