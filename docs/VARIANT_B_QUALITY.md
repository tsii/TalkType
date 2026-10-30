# Variante B (Arm LiteRT, dynamisch int8) — Qualitätsmessung Deutsch

**Stand:** 2026-10-28 · **Rolle:** Messung (keine Code-/Build-Änderung)
**Schwelle (aus `docs/E12_VARIANT_B_DESIGN.md` §94):** Wort-Identität **≥ 95 %**,
Zeichen-Edit-Distanz **CED ≤ 2 %**, **kein fremdsprachiger Output** (harte Null-Toleranz).

## Aufbau (reproduzierbar, lokal)

| Element | Wert |
|---|---|
| B-Referenz | `tools/arm_litert_reference_decode.py` (unverändert; **nur** `AUDIO`-Pfad je Lauf ersetzt, Kopie unter `/tmp/b_ref_<datei>.py`) |
| Modelle | `testdata/litert/*.tflite` + `token_vocab.bin` |
| Runtime | `.venv-asr` `ai-edge-litert 2.3.0` (Signature-Runner; `tflite_runtime` bewusst **nicht**) |
| Audio | `testdata/*.wav` (16 kHz int16) → für B nach float32 konvertiert (`/tmp/wav32/`, sonst verweigert der Reader `tag==3`) |
| A-Referenz | `tools/reference_decode.py` (ONNX Runtime 1.31.1, `.models/`) — auf demselben Material |
| Rohdaten | `testdata/b_quality/*.b.txt` (B), `*.a.json` (A), `metrics.json` |

**Ablauf je Datei:** eine B-Dekodierung (ganzes Audio, ein bzw. mehrere Fenster), eine
A-Dekodierung (ganzes Audio), Vergleich gegen den §4.1-Zieltext.

## Ergebnis

| Datei | Wörter (B) | Wort-Identität B | CED B | Sprache B | Auffälligkeit |
|---|---|---|---|---|---|
| `de_smoke.wav` | 8 | **66,7 %** | 21,5 % | deutsch | **Anfang fehlt**: `gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.` (die ersten 6 Wörter „So, jetzt bin ich" fehlen) |
| `de_part1.wav` | 9 | **0,0 %** | 68,8 % | **ENGLISCH** | `I'm not sure if you're still tile mystic cards.` — kompletter Fehlschlag |
| `de_part2.wav` | 8 | **87,5 %** | 4,5 % | deutsch | nur Groß/Klein: `und nach einer Pause kommt der zweite Teil.` (inhaltlich korrekt) |
| `de_pause.wav` | 11 | **46,7 %** | 38,0 % | deutsch | Anfang fehlt („Das") **und** zweiter Satz falsch: `Und da feiert die Dreh.` statt `Und nach einer Pause kommt der zweite Teil.` |
| **Mittel** | — | **50,2 %** | **33,2 %** | 1 von 4 englisch | — |

**Variante A (gleiches Material, gleiche Maschine):** **100 % Wort-Identität, 0 % CED** auf
allen vier Dateien (exakt §4.1) — deckungsgleich mit den Gerätewerten
(`s1_realEngine_realWav` grün).

## Urteil gegen die Schwelle

| Kriterium | Soll | Ist (B) | Bewertung |
|---|---|---|---|
| Wort-Identität (Mittel) | ≥ 95 % | **50,2 %** | **nicht bestanden** (Faktor 2 daneben) |
| CED (Mittel) | ≤ 2 % | **33,2 %** | **nicht bestanden** (Faktor 16) |
| Fremdsprachiger Output | 0 Fälle | **1 von 4** (`de_part1` komplett englisch) | **nicht bestanden** (harte Regel) |

**→ Nicht bestanden. B in dieser Form nicht ausliefern** — auch nicht „nur für Englisch",
denn die App ist ein deutsches Diktat-Werkzeug mit 15 Layouts, und ein stiller
Sprachwechsel im Text ist genau die Fehlerklasse, die wir mit E5/`ASR_CONTRACT` §4.3
ausgeschlossen haben.

## Was davon Artefakt sein könnte (und was nicht)

- **Systematischer Anfang-Verlust (3 von 4 Dateien):** die ersten Wörter fehlen
  (`de_smoke` −6 Wörter, `de_pause` −1 Wort). Das riecht nach **Priming/Cache-Semantik**
  des Referenz-Skripts (erste Fenster/Decoder-Warmup), nicht zwangsläufig nach
  Modellschwäche — `ANNAHME:`. Gegenprobe wäre ein Offset-Test (Audio mit 0,5 s Stille
  voranstellen) — **nicht** Teil dieser Messung.
- **`de_part1` englisch:** dabei fehlt der Anfang *nicht* (9 Wörter ausgegeben), der Text
  ist inhaltlich frei erfunden. Das ist **kein** Artefakt-Effekt, sondern ein echter
  Sprachwechsel der Pipeline — die Arm-Kette wurde nur auf **LibriSpeech (Englisch)**
  evaluiert (Werbezeile des Modellkarten-Textes).
- **`de_pause` Satz 2 falsch:** nach der 4-s-Pause bricht der Kontext
  („Und da feiert die Dreh."). Plausibel derselbe Cache-/Fenster-Effekt; unabhängig
  davon ist das Ergebnis unbrauchbar.

## Konsequenz für die Auslieferung

1. **B bleibt draußen** (v0.1.x und v0.2), solange die drei Gates nicht erreicht sind.
2. Ein **zweiter Messlauf mit Offset-Variante** (Stille-Prefix, damit der Anfang nicht
   verloren geht) ist billig (10 min) und würde klären, ob die 50,2 % durch den
   Priming-Effekt allein zustande kommen. `ANNAHME:` selbst dann bliebe `de_part1`
   englisch → Gate bleibt gerissen.
3. **A ist und bleibt die Referenz** (100 % / 0 % CED, Geräte- und Desktop-identisch).
4. Die **Engine-Umschalt-Infrastruktur** (S0–S2 aus `MODEL_VARIANT_SWITCH_MEMO.md`) ist
   davon unabhängig sinnvoll, aber **ohne** ausgelieferte B-Variante nur Gerüst.

## ANNAHME-Zeilen

- `ANNAHME: Der Anfang-Verlust ist ein Priming-/Cache-Artefakt des Referenzskripts —`
  `warum: er tritt bei 3 von 4 Dateien gleichartig auf, und de_part2 (3,1 s) ist trotz`
  `gleicher Länge fast fehlerfrei. Nicht verifiziert (Offset-Test fehlt).`
- `ANNAHME: RTF-Werte aus dem Skript (0,014–0,039 auf x86) sind kein ARM-Maßstab —`
  `nur die Textqualität ist hier die Aussage.`

---

## Anfangsverlust-Test (R4, 2026-10-28) — Priming-Hypothese geprüft

**Hypothese:** Der systematische Anfangsverlust kommt vom **Fenster-/Cache-Priming**
(erstes Fenster ab Sample 0, leerer `pre_encode_feature_cache`, `previous_sample = 0`) —
nicht vom Modell.

**Aufbau:** dasselbe Skript, nur `AUDIO` ersetzt; Audio in `/tmp/wav32/` als float32.
Varianten je Datei: **(A)** unverändert, **(B)** 0,4 s Stille vorangestellt
(6400 Nullsamples), **(C)** erste 0,4 s abgeschnitten. Rohdaten:
`testdata/b_quality/*_pre.b.txt`, `*_cut.b.txt`, `prefix_test.json`.

| Datei | Variante | Wörter | Wort-Identität | CED | Text |
|---|---|---|---|---|---|
| `de_smoke` | A (R3) | 8 | 66,7 % | 21,5 % | `gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.` |
| `de_smoke` | **B +0,4 s Stille** | 8 | **66,7 %** | **21,5 %** | **identisch zu A** |
| `de_smoke` | **C −0,4 s** | 8 | **66,7 %** | **21,5 %** | **identisch zu A** |
| `de_part1` | A (R3) | 9 | 0,0 % | 68,8 % | `I'm not sure if you're still tile mystic cards.` (englisch) |
| `de_part1` | **B +0,4 s Stille** | 2 | 28,6 % | 62,9 % | `des Diktats.` (**deutsch**, aber falscher Inhalt) |
| `de_part1` | **C −0,4 s** | 4 | 0,0 % | 68,6 % | `and the five years.` (**englisch**) |

### Befund 1 — Anfangsverlust ist **nicht** Priming

`de_smoke` liefert in **allen drei** Varianten **denselben** Text (66,7 % / 21,5 %).
Weder 0,4 s Stille davor noch 0,4 s weniger ändern ein Zeichen. Die fehlenden Wörter
„So, jetzt bin ich" sind damit **modell-/pipelineimmanent** und nicht durch
Sample-0-Priming erklärbar. Hypothese **widerlegt**.

### Befund 2 — `de_part1` kippt mit 0,4 s Stille die Sprache

Ohne Prefix und mit −0,4 s: **englisch** (`I'm not sure…`, `and the five years.`).
Mit +0,4 s Stille: **deutsch**, aber inhaltlich falsch (`des Diktats.`, 2 von 7 Wörtern).
Das ist **schlimmer** als ein stabiler Fehlschlag: die Pipeline ist bei kurzem,
satzartigem Deutsch **sprachinstabil** — 400 ms führende Stille entscheiden über
Deutsch vs. Englisch. Ein solcher Pfad ist für ein Diktat-Werkzeug nicht
auslieferbar (stiller Sprachwechsel im Text = genau die Fehlerklasse aus E5/§4.3).

### Konsequenz

- Die **Priming-Erklärung ist tot**; der Anfangsverlust bleibt als echter B-Mangel.
- Die mittleren Gates bleiben gerissen (50,2 % / 33,2 %), und die Sprachinstabilität
  ist ein **zusätzlicher, unabhängiger** Ausschlussgrund.
- **B wird nicht ausgeliefert.** Ein weiterer Debug-Zyklus an der Arm-Referenzkette
  wäre nur dann sinnvoll, wenn Arm/Google deutsche Evaluationsdaten für diese Variante
  liefern — nicht als App-Integrationsarbeit.
