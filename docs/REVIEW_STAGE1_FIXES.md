# Review Stufe 1 — FIX-DURCHLAUF (VK-01 … VK-07)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Geprüft:** Fixes zu VK-01…VK-07 (plus nebenbei VK-12/VK-13), eingebracht von GLM 5.3.
**Nicht angefasst/geprüft (parallel in Arbeit):** `app/src/main/java/de/voicekeyboard/asr/`,
`app/src/test/java/de/voicekeyboard/asr/` — diese Pfade wurden **nicht** verändert.

---

## 0. Verifikation (Build & Tests)

`ANDROID_HOME=/opt/android-sdk ./gradlew --offline test` → **BUILD SUCCESSFUL**, 19 Tests grün:

| Testklasse | Tests | Fehler |
|---|---|---|
| `de.voicekeyboard.keyboard.KeyboardModelTest` | 6 | 0 |
| `de.voicekeyboard.layouts.CaseMapperTest` | 4 | 0 |
| `de.voicekeyboard.layouts.LayoutCompletenessTest` | 1 | 0 |
| `de.voicekeyboard.layouts.LayoutParserTest` | 8 | 0 |
| **Summe** | **19** | **0** |

**Wichtiger Kontext (nicht GLM anzulasten):** Gegen Ende des Durchlaufs ist der *parallele*
ASR-Agent mitten in der Arbeit; seine (hier nicht relevanten) Dateien
`app/src/test/java/de/voicekeyboard/asr/TdtGreedyTest.kt` standen zeitweise auf einem
nicht-kompilierenden Stand (`No parameter with name 'frames' found` → `framesIn`). Die obigen
19 Tests betreffen ausschließlich die Layout-/Keyboard-Ebene (nicht `asr`). Für die isolierte
Mutationsprobe wurde der `asr`-Testquellbaum temporär per Init-Script `-I` von der
Kompilierung ausgeschlossen (Dateien selbst unangetastet, Script danach entfernt).

---

## 1. Status je Finding

Legende: BEHOBEN / TEILWEISE / OFFEN.

| ID | Sev | Status | Codebeleg |
|---|---|---|---|
| **VK-01** ä ö ü ß als eigene `base`-Taste | P0 | **BEHOBEN** | `app/src/main/assets/layouts/de-de.json` Row 1 `{"id":"ue",…,"value":"ü"}` (Z.20), Row 2 `{"id":"oe",…,"value":"ö"}` + `{"id":"ae",…,"value":"ä"}` (Z.30/31), Row 3 `{"id":"eszett",…,"value":"ß"}` (Z.39). Emulator-Gegenprobe (Text-Commit): Taps auf diese Koordinaten ergeben „qüäöß1" im EditText. |
| **VK-02** Ziffern-/Symbolebene verdrahten | P0 | **TEILWEISE** | `KeyboardView.kt:246` `KeyActions.LAYER_SYMBOLS -> switchLayer(if (symbolShift) "symbolsShift" else "symbols", fromSymbols = true)` + `:247` `LAYER_BASE -> switchLayer("base", …)`. funktioniert: base→symbols (`?123`) und symbols→base (`ABC`). **NICHT** funktioniert: symbols→symbolsShift (neues Finding, s. u.). |
| **VK-03** Backspace graphem-/codepointsicher | P1 | **BEHOBEN** | `VoiceKeyboardService.kt:50-62`: `getTextBeforeCursor(32,0)`, leer/null → `sendKeyEvent(KEYCODE_DEL)` (up/down), sonst `lastGraphemeLength(before)` + `deleteSurroundingText(units,0)`. `lastGraphemeLength` (Z.90-101) nutzt `BreakIterator.getCharacterInstance()` mit Codepoint-Fallback (`codePointBefore`/`charCount`). Emulator: Backspace löscht genau 1 Zeichen (kein Doppel-Löschen). |
| **VK-04** Verbotene Label-Artefakte `=\<`/`⌇` | P1 | **BEHOBEN** | `de-de.json` symbols-shift-Taste jetzt `{"id":"shift","label":"⇧",…,"value":"layer-symbols"}` (Z.145); `question` in symbolsShift jetzt `"¿"` (war `⌇`). `en-us.json` analog (shift-`⇧`, kein `=\<`/`⌇`). Kein Label `\` `<` `>` `&` mehr vorhanden. |
| **VK-05** Accessibility-Integration | P1 | **BEHOBEN** | `KeyboardView.kt:279-373`: `getAccessibilityNodeProvider()` liefert virtuelle Knoten je Taste mit `contentDescription`, `setBoundsInScreen/Parent`, `ACTION_CLICK`/`ACTION_ACCESSIBILITY_FOCUS`; `performAction(ACTION_CLICK)` ruft `handleKey(key,…)` — derselbe Pfad wie Touch. `sendAccessibilityEvent(TYPE_WINDOW_CONTENT_CHANGED)` nach Ebenen-/Shift-Wechsel. Kleinere Lücken s. neue Findings. |
| **VK-06** `LayoutCompletenessTest` fehlte | P1 | **BEHOBEN** | Datei existiert jetzt: `app/src/test/java/de/voicekeyboard/layouts/LayoutCompletenessTest.kt`. Mutationsprobe belegt ROT-Verhalten (s. §3). Coverage-Lücken s. neue Findings. |
| **VK-07** `ß→ẞ` in `shift` | P1 | **BEHOBEN** | `de-de.json` shift: `{"id":"eszett","label":"ẞ",…,"value":"ẞ"}` (Z.131). Test `checkShiftPairs` verlangt das Paar `"ß" to "ẞ"` (Z.43) und erzwingt es als eigene shift-Taste. |
| VK-12 (`shiftActive` toter Zustand) — nebenbei | P2 | BEHOBEN | `KeyboardView.kt` hat kein `shiftActive` mehr; Zustand läuft sauber über `shiftTemporary`/`capsLock`/`symbolShift` (`:55-68`, `:236-254`). Ein Shift-Tap bei CapsLock deaktiviert CapsLock korrekt. |
| VK-13 (`sourceKey` O(n²) im `onDraw`) — nebenbei | P2 | BEHOBEN | `KeyboardView.kt:63` `sourceKeys = layer.rows.flatten().associateBy { it.id }` einmal in `rebuildModel`; `displayLabel`/`textFor` greifen per `sourceKeys[key.id]` zu (`:141`, `:178`). |

---

## 2. Neue Findings aus diesem Durchlauf

### **NEW-01 — P1 — `symbolsShift`-Ebene ist unerreichbar (State-Machine broken)**

**Ort:** `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt:246,262-264` in Kombination mit
`app/src/main/assets/layouts/de-de.json:145` (symbolebene `shift`-Taste `value:"layer-symbols"`).

**Befund:** Die geforderte Folge `base→symbols→symbolsShift→symbols→base` ist an 2 von 4
Übergängen kaputt:
- `symbols→symbolsShift` funktioniert **nicht**: `LAYER_SYMBOLS` ruft
  `switchLayer(if (symbolShift) "symbolsShift" else "symbols")`. Beim Eintritt in `symbols` ist
  `symbolShift=false` (wird in `switchLayer` erst bei Ziel `symbolsShift` auf `true` gesetzt,
  Z.262-264) → ein Tipp auf die Symbolebenen-Shift-Taste (`value:"layer-symbols"`) mappt erneut
  auf `"symbols"` (Selbst-No-Op). Es gibt **keinen** Pfad, der `symbolShift=true` setzt, ohne
  vorher in `symbolsShift` zu sein → Zirkelschluss, `symbolsShift` ist nie erreichbar.
- `symbolsShift→symbols` fehlt ebenfalls: die symbolsShift-Shift-Taste ist `value:"layer-base"`
  (`de-de.json:197`) und springt direkt nach `base`.
- Der Parameter `fromSymbols` in `switchLayer` (Z.257) ist **unbenutzt** (toter Parameter,
  stützt das „Stub"-Signal).

**Evidenz (reproduziert):** Im Emulator bei aktiver Symbolebene auf die Shift-Taste getippt und
auf Position „1" getippt → Feld wurde `…ß11` statt `…ß¹` (es bleibt `1`, nicht `¹` aus
symbolsShift). D. h. das Umschalten auf die Shift-Gegenstücke (Klammern, Währungen, `¹²³`
aus LAYOUT_REQUIREMENTS §2.1/§1 Regel 9) schlägt still fehl.

**Minimaler Fix:** `LAYER_SYMBOLS` als echten Toggle implementieren: ist man bereits in `symbols`
oder `symbolsShift`, zwischen den beiden togglen (`symbols ↔ symbolsShift`); ist man in einer
Buchstabenebene, nach `symbols`. `fromSymbols` verwenden oder entfernen und den Toggle in einem
Test absichern (Zustandsmatrix).

### **NEW-02 — P2 — `LayoutCompletenessTest` deckt die de-DE-Matrix-Spalte B nicht ab**

**Ort:** `app/src/test/java/de/voicekeyboard/layouts/LayoutCompletenessTest.kt:43`.

**Befund:** `MatrixRow("de-de", listOf("ä","ö","ü","ß"), shiftPairs=…)` hat `requiredReachable`
leer. LAYOUT_REQUIREMENTS §2.2 verlangt für de-DE zusätzlich `é è ê á à â ç` **erreichbar**
(Stufe B). Der Test prüft nur die A-Menge (eigene Tasten) und `ß→ẞ`; die B-Menge für die **einzige
aktuell vorhandene** nicht-ASCII-Sprache ist ungeprüft (dieselbe Lücke würde `en-us`-Punktzeichen
`' – — … £ $ °` betreffen, die nur teilweise/unscharf als `requiredReachable` erfasst sind).
Außerdem prüft der Test nicht §1 Regel 9 (Ziffern 0–9 je einmal auf `symbols`) und nicht §2.1
global/§2.2 Satzzeichen (dafür wäre ohnehin ein eigener `PunctuationCoverageTest` nötig, der noch
fehlt); tr-TR-Matrixzeile (Z.51) lässt das geforderte eigene `i` aus (`A = ç ğ ı i ö ş ü`).

**Minimaler Fix:** `requiredReachable = listOf("é","è","ê","á","à","â","ç")` für de-de ergänzen;
`i` in die tr-A-Zeile; Regel 9 (Ziffern) und Satzzeichen-Abdeckung ergänzen bzw. `PunctuationCoverageTest`
nachliefern.

### **NEW-03 — P2 — Graphem-Backspace (VK-03) hat keinen Unit-Test**

**Ort:** `VoiceKeyboardService.kt:90-101` (`lastGraphemeLength` ist `internal`, aber ungetestet).

**Befund:** Kein `VoiceKeyboardServiceTest`/`LastGraphemeTest` vorhanden (`find app/src/test` →
nur Layout-/Keyboard-Tests). Die Kerndefektlücke von VK-03 (Surrogatpaare/Emoji nicht zerlegen)
ist damit nicht regressiongeschützt; `BreakIterator.getCharacterInstance()` nutzt die
Default-Locale (unbestimmt).

**Minimaler Fix:** Test ergänzen: `lastGraphemeLength("…😀")==2`, `("e\u0301")==2`,
`("abc")==1`, `("")==0`, Familien-Emoji (ZWJ) — plus robuster Locale-Super (`BreakIterator.getCharacterInstance(Locale)`).

### **NEW-04 — P2 — `performClick()` ist ein No-Op; Klickpfad inkonsistent belegt**

**Ort:** `KeyboardView.kt:187` (`performClick()` in `ACTION_DOWN`).

**Befund:** Es ist kein `OnClickListener` gesetzt; die eigentliche Aktivierung passiert in
`ACTION_UP` über `handleKey` (`:218-223`). `performClick()` ohne Listener liefert `false` und
bewirkt nichts (wird zudem auf `DOWN` statt `UP` gerufen). Der a11y-Pfad (`performAction` →
`handleKey`) ist zwar bewusst deckungsgleich mit Touch, aber `performClick()` selbst ist toter
Code/Rauschen und `onPopulateAccessibilityEvent(TYPE_VIEW_CLICKED)` (`:375-379`) hängt an einem
Klick-Event, das mangels Listener nicht zuverlässig ausgelöst wird.

**Minimaler Fix:** Aktivierung auf den Standard-Klickpfad legen (Kommit in `performClick`/
`OnClickListener`, onTouchEvent ruft `performClick()` bei `ACTION_UP`), damit Touch, a11y-Click
und das TalkBack-Klick-Event denselben einzigen Pfad teilen.

### **NEW-05 — P2 — Accessibility-NodeProvider-Lücken + CapsLock-Status nach Ebenen-Roundtrip**

**Ort:** `KeyboardView.kt:315` (`findFocus` → `null`), `:285-296` (virtuelle IDs = Listenindex),
`switchLayer`/`applyLayerState` (`:257-278`).

**Befund:** (a) `findFocus` liefert immer `null` (TalkBack-Fokus-Rückfragen unbeantwortet);
(b) virtuelle View-IDs sind schlichte Indizes in `model.keys` — nach `rebuildModel` (Layer-Wechsel)
zeigen gleiche IDs auf andere Tasten (abgemildert durch `TYPE_WINDOW_CONTENT_CHANGED`, aber nicht
stabil); (c) `switchLayer("base")` setzt `activeLayerName="base"`, ohne `applyLayerState()` zu
rufen → bei aktivem `capsLock` zeigt die Tastatur danach **Kleinbuchstaben**, während die
Shift-Taste weiter `⇪`/hervorgehoben ist (`showShift()`=`capsLock`=true, `textFor` aber
`shiftShowing`=`activeLayerName=="shift"`=false) — der kommentierte „Shift-Zustand einfrieren/
wiederherstellen" wird faktisch nicht wiederhergestellt. (d) `FLAG_CONTENT_SENSITIVE` wird nicht
gesetzt (Design-Entscheidung, hier nur Hinweis).

**Minimaler Fix:** `findFocus` auf dem virtuellen Fokus implementieren (oder Klick-Fokus in
`performAction` zurückgeben); virtuelle ID→Key-Zuordnung über eine stabile Quelle (z. B.
`sourceKeys`-Reihenfolge/Hash statt Liste) oder `notifyViewInvalidated`; nach `switchLayer` auf
Buchstabenebenen `applyLayerState()` ausführen, damit `activeLayerName` den `capsLock`/Shift-Zustand
widerspiegelt.

---

## 3. Mutationsprobe (`LayoutCompletenessTest`)

**Ablauf:** `ü`-eigene Taste aus `de-de.json` entfernt (Value `"ü"` → `"ux"` an Taste `id:"ue"`),
Test isoliert ausgeführt, Datei exakt wiederhergestellt.

| Schritt | Ergebnis | SHA-256 |
|---|---|---|
| Original (Backup) | — | `1e0badeb45118a17b4a1e4f2e1464b4213a348cd5873830267c352676f1e4539` |
| Mutation (`ü`→`ux`) | Test **ROT**: `LayoutCompletenessTest … FAILED` (AssertionError Z.108) | `0a3d7528…` |
| Fehlermeldung | `Asset 'de-de': Pflichtzeichen fehlen als eigene base-Taste: [ü] (alternates zählen NICHT, LAYOUT_REQUIREMENTS Grundregel 1)` | — |
| Wiederhergestellt | Hash identisch zum Backup | `1e0badeb45118a17b4a1e4f2e1464b4213a348cd5873830267c352676f1e4539` ✔ |

**Skip-Logik geprüft:** `LayoutCompletenessTest.asset()` gibt nur bei *fehlendem/lesbarem*
Asset `null` zurück (→ Matrixzeile wird übersprungen); ein **vorhandenes** Asset, das die
Anforderung verletzt, löst `fail()` aus (kein stilles Grün). `assertTrue(checked > 0)` verhindert
„alle übersprungen". Ein vorhandenes aber unparsebares Asset wirft eine `LayoutException` (Test
error → rot), wird also ebenfalls **nicht** still grün.

---

## 4. Emulator-Gegenprobe (`adb -s <device>`, IME aktiv)

Build `assembleDebug` (BUILD SUCCESSFUL) installiert (`Success`), `de.voicekeyboard/.ime.VoiceKeyboardService`
als IME gesetzt (`ime set` → „selected for user #0"). `DebugInputActivity` gestartet
(`de.voicekeyboard/.debug.DebugInputActivity`), `dumpsys input_method` → `mInputShown=true`,
`mBoundToMethod=true`.

Belege über **Text-Commits** (`uiautomator dump` auf das EditText; Feld akkumuliert die Eingaben):

| Aktion (Tap auf Tastenposition) | Feldinhalt nachher | Beleg |
|---|---|---|
| `q` (44,1839) | `q` | Taste committet |
| `ü`(1042,1839) `ä`(1042,1991) `ö`(942,1991) `ß`(881,2143) | `qüäöß` | **ä ö ü ß als eigene Tasten vorhanden und funktional** |
| `?123`(58,2295) → `1`(44,1839) | `qüäöß1` | **`?123` aktiviert die Ziffernebene** |
| Symbolebenen-Shift (66,2143) → `1`(44,1839) | `qüäöß11` | **`symbolsShift` schaltet NICHT um** (erwartet `¹`) → NEW-01 |
| Backspace (1057,2143) | `qüäöß1` | genau 1 Zeichen gelöscht (kein Doppel-Löschen) |
| `ABC`(58,2295) → `shift`(66,2143) → `q`(44,1839) | `qüäöß1Q` | base→shift (Großform) funktioniert |

Hinweis: `uiautomator dump` zeigt weiterhin nur die Host-App-Nodes, **nicht** die IME-Tasten —
das IME-Fenster ist `NOT_FOCUSABLE` und wird vom Standard-Dump nicht erfasst (kein Rückschritt
gegenüber VK-05; die Tasten liegen als virtuelle NodeProvider-Knoten vor, aber außerhalb des
App-Fenster-Dumps).

---

## 5. Regressions-Check (Schema/Zeilensummen/IDs/ẞ/Konsistenz)

- **Schema-Validität:** beide vorhandenen Assets (`de-de`, `en-us`) parsen (`LayoutParserTest`
  8 Tests grün; `LayoutCompletenessTest` prüft beide).
- **Zeilensummen:** `LayoutParser.validateSemantics` (Zeilensummen je Index über `shift`/`symbols`/
  `symbolsShift` identisch zu `base`) — grün.
- **Doppelte IDs:** je Ebene geprüft; „shift" in 4 Ebenen ist zulässig (duplikatfrei *innerhalb*
  einer Ebene) — grün.
- **`ẞ`-Taste:** `de-de.json` shift `eszett`=`ẞ` vorhanden; `CaseMapper.upper("ß","de-DE")=="SS"`
  bleibt getrennt korrekt (Label vs. Text-Transformation).
- **base/shift-Tasten-Konsistenz:** beide 46 Keys, identische Struktur/Positionen; `validateSemantics`
  erzwingt Shift-Gegenstück für jede base-TEXT-Taste; `LayoutCompletenessTest.checkShiftPairs`
  prüft Großformen. Keine Regression.
- **Keine Doppel-Löschung/keine Artefakte:** siehe §4 und VK-04.

---

## 6. Verdikt

**OK mit Notes (Merge freigeben) — keine P0-Mehrblocker verbleiben.**

Die beiden P0 der Stufe 1 sind gelöst: VK-01 (ä ö ü ß als eigene Tasten) ist belegt durch JSON +
Emulator-Text-Commit, VK-02 ist zum Kern (Ziffern-/Symbolebene erreichbar) verdrahtet. Alle
P1-Vorgaben (VK-03/04/05/06/07) sind substantiell behoben und belegt.

**Vor Release zwingend beheben (P1):** **NEW-01** — `symbolsShift` ist unerreichbar; die in
LAYOUT_REQUIREMENTS §2.1/§1 Regel 9 geforderten Shift-Gegenstücke (Klammern, Währungen, `¹²³`)
sind dadurch nicht erreichbar. Das ist die verbleibende Hälfte des ursprünglichen VK-02.

---

## Die 5 nächsten Maßnahmen (Reihenfolge)

1. **NEW-01 (P1)** — `LAYER_SYMBOLS` als echten Toggle in `KeyboardView.handleAction`
   implementieren (`symbols ↔ symbolsShift`) und die Zustandsmatrix `base→symbols→symbolsShift→
   symbols→base` als Test festschreiben.
2. **NEW-02 (P2)** — `LayoutCompletenessTest` um die de-DE-B-Menge (`é è ê á à â ç`) und
   §1-Regel-9-/Satzzeichen-Abdeckung (bzw. `PunctuationCoverageTest`) erweitern; tr-Zeile um `i`.
3. **NEW-03 (P2)** — Unit-Test für `lastGraphemeLength` ergänzen (Surrogat/Emoji/ZWJ/leer),
   Locale explizit setzen.
4. **NEW-05 (P2)** — CapsLock/Shift-Zustand nach Ebenen-Roundtrip konsistent halten
   (`applyLayerState` beim Rückschalten) und `AccessibilityNodeProvider` vervollständigen
   (`findFocus`, stabile virtuelle IDs).
5. **NEW-04 (P2)** — `performClick()`/`OnClickListener` als *einzigen* Klickpfad etablieren
   (Touch-`ACTION_UP` und a11y-`ACTION_CLICK` identisch) und den doppelten Mic (VK-09) beim
   Mic-Stufenausbau zusammenführen.