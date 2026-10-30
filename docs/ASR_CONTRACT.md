# ASR-Vertrag: gemessen, nicht angenommen

Erzeugt am 2026-10-27 in dieser Umgebung mit `onnxruntime 1.31.1` (CPU-EP,
8 Threads) gegen die unten gehashten Dateien. **Diese Datei ist die Wahrheit für
die Android-Implementierung.** Werte daraus dürfen nicht durch geratene
Tensornamen oder Achsenreihenfolgen ersetzt werden.

> **Revision 3 (2026-10-27, nach Rev 2):** §4.4 neu — empirische Messung von
> `min_audio_s` mit dem *korrigierten* Decoder (Kurzfenster 1,0–6,0 s, Fenster
> mitten im Material, Stille-/Rauschproben). Ergebnis: **`min_audio_s = 2,0 s`**;
> 1,0 s ist widerlegt, 3,0 s ist nicht belegt. §4.3 (Punkt 4, Schlussabsatz),
> §6 (Emissionsrate) und §7 (Punkt 8) sind entsprechend nachgezogen.
>
> **Revision 2 (Review 2026-10-27):** Der Referenzdecoder hatte zwei Fehler
> (PredNet-State wurde auch bei Blank fortgeschrieben; `MAX_SYMBOLS_PER_FRAME`
> war als globales statt Frame-Limit implementiert) und filterte Sondertokens
> nicht. Dadurch waren drei von vier Golden-Texten falsch und die Aussage
> „kurze Fenster degradieren" war ein Artefakt des Fehlers. §3, §4, §5 und §6
> sind korrigiert; das Abweichungsprotokoll steht in §4.3.
>
> Referenzimplementierung des Exports (maßgeblich für Semantik):
> `istupakov/onnx-asr`, `src/onnx_asr/asr.py` → `_AsrWithTransducerDecoding._decoding`
> und `src/onnx_asr/models/nemo.py` → `NemoConformerTdt._decode`.

## 1. Gepinnte Artefakte (SHA-256, gemessen)

```
eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70  decoder_joint-model.int8.onnx   18.202.004 B
6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09  encoder-model.int8.onnx        652.183.999 B
a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f  nemo128.onnx                       139.764 B
d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d  vocab.txt                           93.939 B
```

Quelle: `https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/<datei>`
(HF-Revision des Repos: `8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce`, geprüft am 2026-10-27).
Diese Hashes gehören in das signierte Modell-Manifest der App.

`config.json` des Exports (vollständig, 97 B):

```json
{"model_type":"nemo-conformer-tdt","features_size":128,"subsampling_factor":8}
```

**Konsequenz:** `max_tokens_per_step` und `max_sequence_length` fehlen in der
Config. Die Referenzimplementierung nutzt dann Defaults: `max_tokens_per_step = 10`
(`_AsrWithTransducerDecoding` / `NemoConformerRnnt._max_tokens_per_step`) — die
Android-Implementierung MUSS diesen Wert als Konstante führen und darf ihn nicht
aus der Config erwarten. Die App-seitige Config-Prüfung darf also nicht auf
diesen Schlüsseln bestehen.

Sprachabdeckung des Modells (Model-Card `nvidia/parakeet-tdt-0.6b-v3`, HF-Revision
`541d1f99c6b0c3cd0b11a95167540bb8edefd82b`, 25 Sprachen): `en es fr de bg hr cs da
nl et fi el hu it lv lt mt pl pt ro sk sl sv ru uk`. **Kein Türkisch, kein
Norwegisch** — siehe `docs/LAYOUT_REQUIREMENTS.md` §5 (Layout ≠ ASR-Sprachsupport).

## 2. Tensorvertrag (per `InferenceSession.get_inputs()/get_outputs()` ausgelesen)

Achsen mit Namen sind dynamisch; die in Klammern genannten Werte sind die
tatsächlich genutzten/beobachteten Ausprägungen.

### nemo128.onnx — Mel-Frontend (NeMo, 128 bins)
| Richtung | Name | Typ | Shape |
|---|---|---|---|
| IN | `waveforms` | float32 | `[B, N]` (16 kHz Rohsamples, [-1,1]) |
| IN | `waveforms_lens` | int64 | `[B]` |
| OUT | `features` | float32 | `[B, 128, T]` |
| OUT | `features_lens` | int64 | `[B]` |

### encoder-model.int8.onnx — Conformer-Encoder
| Richtung | Name | Typ | Shape |
|---|---|---|---|
| IN | `audio_signal` | float32 | `[B, 128, T]` |
| IN | `length` | int64 | `[B]` |
| OUT | `outputs` | float32 | `[B, 1024, T/8]` |
| OUT | `encoded_lengths` | int64 | `[B]` |

### decoder_joint-model.int8.onnx — Prediction-Netz + Joint (TDT)
| Richtung | Name | Typ | Shape |
|---|---|---|---|
| IN | `encoder_outputs` | float32 | `[B, 1024, T]` — **Nutzung: T = 1** (ein Frame/Schritt) |
| IN | `targets` | int32 | `[B, U]` — **Nutzung: U = 1** (letztes Label; Start = `<blk>`) |
| IN | `target_length` | int32 | `[B]` (Wert: Anzahl Targets = 1) |
| IN | `input_states_1` | float32 | `[2, B, 640]` (LSTM h) |
| IN | `input_states_2` | float32 | `[2, B, 640]` (LSTM c) |
| OUT | `outputs` | float32 | `[B, T, U, 8198]` → bei Nutzung `[B, 1, 1, 8198]` |
| OUT | `prednet_lengths` | int32 | `[B]` (unbenutzt) |
| OUT | `output_states_1` | float32 | `[2, B, 640]` |
| OUT | `output_states_2` | float32 | `[2, B, 640]` |

Achsenreihenfolge von `outputs` **gemessen** (nicht geraten): T=1,U=1 →
`(1,1,1,8198)`; T=2 → `(1,2,1,8198)`; T=1 mit zwei Targets → `(1,1,2,8198)`;
T=5 → `(1,5,1,8198)`. Also **`[B, T, U, 8198]`** — Index `logits[0, 0, 0]` ist
bei T=U=1 korrekt, bei gepufferten Frames darf nicht `[0, 0, t]` indiziert
werden. Die Achse ist dynamisch, `T > 1` läuft ohne Fehler; der Predictor-Output
`g` ist dabei für alle T identisch (gleiches Label/gleicher State), deshalb ist
T>1 nur für reines Blank-Skip-Batching brauchbar, nicht für Label-Iteration.

**Logit-Aufteilung (verifiziert):** `8198 = 8193 Vokabeln + 5 Durationsklassen`.
`[0..8192]` = Token-Logits, `[8193..8197]` = Duration-Logits für {0,1,2,3,4}
Encoderframes. Kein Softmax nötig (Argmax).

**Subsampling:** Faktor 8 aus `config.json`; 1 Encoderframe = 8 × 10 ms = **80 ms**.
Gemessene Zuordnung `frames = ceil(mel_T / 8)`: 640→80, 990→124, 280→35, 311→39.
`frames` kommt aus `encoded_lengths[0]`; die Android-Implementierung muss den Wert
zusätzlich gegen `outputs.shape[2]` clampen (bei int8/TensorRT-Exporten kann
`encoded_lengths` die Tensorbreite überschreiten — onnx-asr macht genau das).

### 2.4 Vokabular und Sondertokens

`vocab.txt`: 8193 Zeilen `<token> <id>`, IDs 0..8192 lückenlos und aufsteigend,
**kein** Token enthält ein Leerzeichen, `<blk> = 8192` ist die letzte Zeile.
`▁` (U+2581) steht als eigenes Token im Vokabular und markiert den Wortanfang.

Sondertokens (265 Stück, Muster `<...>`):

| IDs | Inhalt |
|---|---|
| 0..233 | `<unk>`, `<pad>`, `<|nospeech|>`, `<|endoftext|>`, `<|startoftranscript|>`, `<|pnc|>`, `<|noitn|>`, `<|timestamp|>`, `<|diarize|>`, `<|emo:…|>`, **`<|aa|>`…`<|zu|>` (ISO-Sprachprompts, u. a. `<|de|>`=78)**, `<|0|>`…`<|10|>`, `<|spk0|>`…`<|spk15|>` |
| 234..243 | **`"0"`…`"9"` — regulares Vokabular, NICHT filtern** |
| 244..273 | `<|spltoken0|>`…`<|spltoken29|>` |
| 8192 | `<blk>` (Blank, nie emittieren) |

**Filterregel (verbindlich):** Token verwerfen, wenn der Token-String auf `<`
beginnt und auf `>` endet, plus ID 8192. **Nicht** über ID-Bereiche filtern —
eine Regel wie „IDs < 274 verwerfen" würde die Ziffern 0–9 zerstören (empirisch
geprüft mit `tools/reference_decode.py` auf einem Ziffern-Testaudio: `4711` und
`2026` bleiben nur mit der Muster-Regel erhalten).

**Detokenisierung (`_AsrWithDecoding.DECODE_SPACE_PATTERN`):**
Token-Strings konkatenieren, `▁` → Leerzeichen, dann
`re.sub(r"\A\s|\s\B|(\s)\b", …)` anwenden (führendes Leerzeichen, Leerzeichen vor
Satzzeichen/nach Wortende entfernen), zum Schluss trimmen. Für alle vier
Golden-WAVs ist das Ergebnis identisch mit „konkatenieren, `▁`→Leerzeichen,
`strip()`"; die Regex ist trotzdem die Referenz, weil sie z. B. `" ,"` zu `","`
normalisiert.

## 3. Verifizierter Greedy-Algorithmus (Referenz: `tools/reference_decode.py`)

```
state1 = state2 = zeros([2,1,640]); last_label = 8192 (<blk>); t = 0; emitted = 0
while t < frames:
    logits, prednet_lengths, new1, new2 =
        decoder_joint(enc_out[:, :, t:t+1], [[last_label]], [1], state1, state2)
    v = logits[0, 0, 0]                       # [8198]
    token = argmax(v[0:8193]); dur = argmax(v[8193:8198])
    if token != 8192:                          # Nicht-Blank
        state1, state2 = new1, new2            # (a) NUR hier State übernehmen
        emit(token); last_label = token; emitted += 1
    if dur > 0:
        t += dur; emitted = 0                  # (b) gilt AUCH bei Blank
    elif token == 8192 or emitted >= 10:       # (c) Blank bzw. Symbolbudget
        t += 1; emitted = 0
```

Die drei Stellen, an denen eine naive Implementierung messbar falsche Texte
liefert (alle drei in dieser Umgebung gegengeprüft):

**(a) PredNet-States nur bei Non-Blank fortschreiben.** Bei Blank wird derselbe
State erneut gefüttert (das Prediction-Netz wird mit demselben Blank-Label
erneut ausgewertet — der LSTM-State darf dabei nicht weiterlaufen). Gegenprobe
mit „State auch bei Blank übernehmen": `de_pause` verliert das Wort „nach"
(`… Und einer Pause kommt …`), `de_part1` zerfällt in `Das ist der ersteil des
Dikt Arts.` Die alte Fassung dieses Dokuments behauptete in einem Satz das
Gegenteil („States werden bei jedem Schritt übernommen") und beschrieb im
selben Satz die richtige Regel. Die Referenz (`asr.py`, `prev_state = state`
nur im `token != blank`-Zweig) ist eindeutig; jetzt auch im Code so umgesetzt.

**(b) `t += max(dur, 1)` bei Blank ist korrekt — und `t += 1` ist es nicht.**
Anders als beim klassischen RNNT sind die Duration-Logits dieses Exports auch
für Blank-Schritte bedeutungstragend (Silence-Skip). Gegenprobe mit `t += 1` bei
Blank auf `de_pause`: 84 statt 40 Schritte liefert `… des Diktats Und einer
Pause …` (Satzzeichen weg) — messbar schlechter. `max(dur, 1)` verfälscht das
Alignment also nicht, sondern verhindert nur den Stillstand bei `dur == 0`
(in den Golden-WAVs kam bei Blank nie `dur == 0` vor; `dur == 0` tritt bei
Nicht-Blank auf und hält dann korrekt auf demselben Frame). Beweis für die
Alignment-Bedeutung: `de_pause` springt in den Token-Zeitstempeln von 2,08 s auf
6,16 s — die 4-s-Pause wird über Durations übersprungen (§4.2).

**(c) `MAX_SYMBOLS_PER_FRAME` ist ein Pro-Frame-Limit, kein globales.**
Nach 10 Emissionen innerhalb *eines* Encoderframes muss `t += 1` erzwungen
werden (verhindert Endlosschleifen bei `dur == 0` + wiederholtem Nicht-Blank).
Ein globales Limit („10 × frames Tokens") ist wirkungslos, weil damit bis zu
`10 × frames` Symbole in einem einzigen Frame entstehen können.

Zusatzregeln: Argmax ohne Softmax; `frames = min(encoded_lengths[0],
outputs.shape[2])`; der Encoder wird pro Fenster **einmal** über alle Frames
gerechnet, der Decoder frame-weise. `prednet_lengths` wird nicht benötigt.

## 4. Golden Data (nach Korrektur neu gemessen)

Audio: `testdata/*.wav`, 16 kHz mono PCM16, per `espeak-ng -v de` synthetisiert
(roboterhaft, aber für Pipeline-Beweise ausreichend).

### 4.1 Referenztexte + Token-IDs

| Datei | Dauer | frames/mel | steps | Tokens | Referenztext |
|---|---|---|---|---|---|
| `de_smoke.wav` | 6,40 s | 80 / 640 | 32 | 27 | `So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.` |
| `de_pause.wav` | 9,90 s | 124 / 990 | 40 | 25 | `Das ist der erste Teil des Diktats. Und nach einer Pause kommt der zweite Teil.` |
| `de_part1.wav` | 2,80 s | 35 / 280 | 21 | 12 | `Das ist der erste Teil des Diktats.` |
| `de_part2.wav` | 3,10 s | 39 / 311 | 15 | 13 | `Und nach einer Pause kommt der zweite Teil.` |

Token-IDs (für Android-Tests: erst Token-IDs vergleichen, dann Text — so sind
Detok-Fehler von Decode-Fehlern unterscheidbar):

```
de_smoke : [1960, 7877, 7036, 4189, 1076, 317, 2893, 1607, 7868, 7877, 601, 615,
            360, 6085, 7918, 7995, 1297, 1240, 1593, 1044, 357, 567, 4949, 5027,
            1240, 5102, 7883]
de_pause : [3211, 929, 623, 468, 974, 1769, 305, 594, 360, 2995, 1780, 7883, 2413,
            3189, 4379, 3000, 1163, 758, 6338, 623, 6099, 582, 1769, 305, 7883]
de_part1 : [3211, 929, 623, 468, 974, 1769, 305, 594, 360, 2995, 1780, 7883]
de_part2 : [2413, 3189, 4379, 3000, 1163, 758, 6338, 623, 6099, 582, 1769, 305, 7883]
```

Sprechtext war jeweils `Das ist der erste Teil des Diktats.` / `Und nach einer
Pause kommt der zweite Teil.` / `So, jetzt bin ich gespannt, ob die
Diktierfunktion auf dem Telefon funktioniert.` — d. h. `de_smoke` enthält einen
**echten Modellfehler** (`Dikti-Funktion` statt `Diktierfunktion`, inklusive
eingefügtem Bindestrich-Token 7918). Der Golden-Test muss den Modell-Output
festhalten, nicht den Wunschtext; `de_smoke` taugt also nur als
Regressions-, nicht als Qualitätsgate.

### 4.2 Alignment-Beweis (Token-Zeitstempel, 1 Frame = 80 ms)

```
de_pause : 0.00 0.24 0.48 0.64 0.88 1.04 1.20 1.44 1.68 1.84 2.08 | 6.16 6.48 6.96 …
de_part1 : 0.00 0.24 0.48 0.64 0.80 1.04 1.20 1.44 1.68 1.84 2.00 2.48
```

Der Sprung 2,08 s → 6,16 s in `de_pause` ist die übersprungene Pause; er beweist,
dass Blank-Durations in diesem Export Alignment tragen (§3b).

### 4.3 Abweichungsprotokoll (Rev 2)

| Datei | Dokumentiert bis Rev 1 | Korrekt (Rev 2) | Ursache |
|---|---|---|---|
| `de_smoke.wav` | `… ob die Diktive Funktion auf dem Telefon funktioniert.` | `… ob die Dikti-Funktion auf dem Telefon funktioniert.` | State-Update bei Blank (§3a) |
| `de_pause.wav` | `… Und einer Pause kommt der zweite Teil.` | `… Und nach einer Pause kommt der zweite Teil.` | State-Update bei Blank (§3a) |
| `de_part1.wav` | `Das ist der ersteil des Dikt Arts.` | `Das ist der erste Teil des Diktats.` | State-Update bei Blank (§3a) |
| `de_part2.wav` | unverändert | unverändert | — |

**Folge für die Teststrategie:** Die alte Behauptung „dasselbe Material als
2,8-s-Fenster dekodiert schlechter als im Gesamtkontext" ist **widerlegt** — sie
war ein Artefakt des State-Fehlers. Mit referenzkonformem Decode liefert
`de_part1.wav` (2,8 s) exakt den Satz, den auch `de_pause.wav` im Kontext liefert.
Damit ist `de_part1`/`de_pause` **kein** empirisches Argument mehr für
`min_audio_s = 3.0` oder für den Whole-buffer-Default. Die **neue** Messung (§4.4,
Kurzfenster 1,0–6,0 s) ersetzt dieses Argument: sie stützt `min_audio_s = 2,0 s`
und entzieht den 3,0 s die Grundlage. Der Whole-buffer-Default bleibt richtig,
aber aus Deadlock-Freiheit und Commit-Logik (KONZEPT.md §5.1), **nicht** aus
Fensterqualität. `docs/KONZEPT.md` (3.4 / §4-Konfiguration / §5.1) ist an dieser
Stelle zu präzisieren — bewusst nicht in diesem Review geändert, siehe §4.4.6.

**Testkriterium, das so NICHT funktioniert:** „Pipeline mit Pause muss mindestens
so viele Zeichen liefern wie die Summe der beiden Teilfenster." Für dieses
Material ist das eine Tautologie: 35 Zeichen (`de_part1`) + 43 Zeichen
(`de_part2`) = 78, und der Gesamttext ist **exakt** `part1 + " " + part2`
(= 79 Zeichen, Zeichen für Zeichen nachgeprüft). Das Kriterium ist durch die
Konstruktion der Testdateien immer erfüllt und kann keinen Pipeline-Fehler
aufdecken; bei anderen Schnitten ist die Summe der Teiltranskripte ein
**beliebig** schlechtes Untermaß (Teilfenster duplizieren Satzzeichen und
zerfallen an Wortgrenzen). Ersatz-Kriterien:

1. `de_pause` → **exakt** der Referenztext aus §4.1 (bzw. die Token-IDs).
2. `de_smoke` → exakt der Referenztext (inkl. des Modellfehlers).
3. Bei Pause-Pipeline: Ergebnis enthält beide Ankerwörter (`Diktats`, `zweite`)
   und ist nicht leer; Abweichungen nur als Qualitätsregression melden.
4. Kurzes Audio darf **nicht** als „Text vorhanden" gelten: gemessen liefert
   0,3 s digitale Stille `Yeah.` (zeichengenau reproduziert, §4.4.4), 0,5 s
   Weißrauschen mit −30 dBFS ebenfalls `Yeah.`, 1,5 s mit −50 dBFS `No.`.
   Ab 2,0 s lieferten **alle** Stille-/Rauschproben ein leeres Ergebnis (9 Proben,
   0 Halluzinationen). Der Guard (`min_audio_s`) schützt also vor erfundenen
   Wörtern, nicht nur vor Leerlauf — aber nur im Bereich **unter 2 s**; darüber
   ist nicht die Länge das Problem, sondern ein langer Stille-Schwanz (§4.4.5 F5).

### 4.4 Empirische `min_audio_s`-Messung (korrigierter Decoder)

**Warum neu gemessen:** Die Begründung für `min_audio_s = 3.0` war der Vergleich
`de_part1` (2,8 s) gegen `de_pause` — und der beruhte auf dem State-Fehler aus
§3a (§4.3). Nach der Korrektur ist dieses Argument weg; die Schwelle muss also
aus Kurzfenstern neu bestimmt werden, statt sie aus der alten Aussage zu erben.

**Aufbau (reproduzierbar, deterministisch):**

```bash
.venv-asr/bin/python tools/make_cut_windows.py        # testdata/cut/*.wav + manifest.json
.venv-asr/bin/python tools/measure_min_audio.py --markdown
```

- Fenster: Schnitte aus `testdata/de_smoke.wav` (6,40 s) und `testdata/de_pause.wav`
  (9,90 s; 0–2,3 s Sprache, 2,4–6,7 s **digitale Stille**, 6,8–9,5 s Sprache) in
  den Längen 1,0 / 1,5 / 2,0 / 2,5 / 3,0 / 4,0 / 6,0 s, PCM16, exakte Samplezahl.
- Zusätzlich Fenster, die **mitten im Material** beginnen (`de_smoke` ab 2,0 s,
  `de_pause` ab 6,0 s) — der realistische Streaming-Fall, in dem der Puffer nicht
  am Satzanfang anfängt.
- Stille-/Rauschproben 0,3–4,0 s (digitale Stille sowie Weißrauschen mit −50 und
  −30 dBFS RMS, fester Seed) als Halluzinationstest.
- Decoder: `tools/reference_decode.py` **Rev 2** (referenzkonform, §3), Modelle
  aus `.models/`. Argmax ist deterministisch, Wiederholung liefert dieselben IDs.
- Rohdaten: `testdata/cut/min_audio_measurements.json` (Texte, Token-IDs,
  Kennzahlen je Fenster), Fixture-Liste: `testdata/cut/manifest.json`.

Kennzahlen: **Präfix** = Anzahl führender Token-IDs, die exakt mit dem
Gesamtkontext übereinstimmen; **Ähnl.** = 1 − Levenshtein/max(len) gegen den
Gesamttext (bei Teilfenstern naturgemäß < 1); **im Text** = Anteil der
Fensterzeichen, die als zusammenhängender Block im Gesamttext vorkommen (das
Maß für „kein Müll" bei Fenstern mitten im Material).

#### 4.4.1 Gesamtkontext (Referenz, unverändert zu §4.1)

| Datei | Dauer | Tokens | Text |
|---|---|---|---|
| `de_smoke.wav` | 6,40 s | 27 | `So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.` |
| `de_pause.wav` | 9,90 s | 25 | `Das ist der erste Teil des Diktats. Und nach einer Pause kommt der zweite Teil.` |

#### 4.4.2 Kopffenster (Schnitt ab 0 s)

| Fenster | Dauer | Tokens | tok/s | Präfix | exakt | Ähnl. | im Text | Text |
|---|---|---|---|---|---|---|---|---|
| `de_smoke_1.0s` | 1,0 s | 4 | 4,0 | 0/27 | nein | 0,06 | 0,71 | `Oh yes.` |
| `de_smoke_1.5s` | 1,5 s | 8 | 5,3 | 5/27 | nein | 0,23 | 0,90 | `So, jetzt bin ich...` |
| `de_smoke_2.0s` | 2,0 s | 10 | 5,0 | 9/27 | nein | 0,34 | 1,00 | `So, jetzt bin ich gespannt.` |
| `de_smoke_2.5s` | 2,5 s | 12 | 4,8 | 12/27 | **ja** | 0,43 | 1,00 | `So, jetzt bin ich gespannt, ob die` |
| `de_smoke_3.0s` | 3,0 s | 18 | 6,0 | 13/27 | nein | 0,56 | 0,92 | `So, jetzt bin ich gespannt, ob die Diktative...` |
| `de_smoke_4.0s` | 4,0 s | 19 | 4,8 | 19/27 | **ja** | 0,67 | 1,00 | `So, jetzt bin ich gespannt, ob die Dikti-Funktion auf` |
| `de_smoke_6.0s` | 6,0 s | 27 | 4,5 | 27/27 | **ja** | 1,00 | 1,00 | Gesamttext (identisch) |
| `de_pause_1.0s` | 1,0 s | **0** | 0,0 | 0/25 | nein | 0,00 | — (leer) | *(leer)* |
| `de_pause_1.5s` | 1,5 s | 8 | 5,3 | 7/25 | nein | 0,29 | 1,00 | `Das ist der erste Teil.` |
| `de_pause_2.0s` | 2,0 s | 12 | 6,0 | 12/25 | **ja** | 0,44 | 1,00 | `Das ist der erste Teil des Diktats.` |
| `de_pause_2.5s` | 2,5 s | 12 | 4,8 | 12/25 | **ja** | 0,44 | 1,00 | `Das ist der erste Teil des Diktats.` |
| `de_pause_3.0s` | 3,0 s | 12 | 4,0 | 12/25 | **ja** | 0,44 | 1,00 | `Das ist der erste Teil des Diktats.` |
| `de_pause_4.0s` | 4,0 s | 12 | 3,0 | 12/25 | **ja** | 0,44 | 1,00 | `Das ist der erste Teil des Diktats.` |
| `de_pause_6.0s` | 6,0 s | 8 | 1,3 | 0/25 | nein | 0,17 | 0,74 | `Assist the Ailvesti` |

#### 4.4.3 Fenster mitten im Material

| Fenster | Dauer | Tokens | tok/s | Ähnl. | im Text | Text |
|---|---|---|---|---|---|---|
| `de_smoke_t2.0_1.0s` | 1,0 s | 9 | 9,0 | 0,17 | 0,65 | `Up the dictators.` |
| `de_smoke_t2.0_2.0s` | 2,0 s | 9 | 4,5 | 0,27 | 0,82 | `Ob die Dikt die Funktion auf` |
| `de_smoke_t2.0_3.0s` | 3,0 s | 13 | 4,3 | 0,43 | 0,94 | `Ob die Diktfunktion auf dem Telefon.` |
| `de_pause_t6.0_1.0s` | 1,0 s | 4 | 4,0 | 0,11 | 0,82 | `Understand.` |
| `de_pause_t6.0_2.0s` | 2,0 s | 6 | 3,0 | 0,27 | **1,00** | `Und nach einer Pause.` |
| `de_pause_t6.0_3.0s` | 3,0 s | 13 | 4,3 | 0,42 | 0,87 | `Und nach einer Pause kommt Witz weiter.` |

#### 4.4.4 Stille und Rauschen (Halluzinationstest, 21 Proben)

| Probe | 0,3 s | 0,5 s | 1,0 s | 1,5 s | 2,0 s | 3,0 s | 4,0 s |
|---|---|---|---|---|---|---|---|
| digitale Stille | `Yeah.` ❌ | leer | leer | leer | leer | leer | leer |
| Rauschen −50 dBFS | leer | leer | leer | `No.` ❌ | leer | leer | leer |
| Rauschen −30 dBFS | leer | `Yeah.` ❌ | leer | leer | leer | leer | leer |

Alle Proben ab 2,0 s (9 Stück) liefern **0 Tokens**; Halluzinationen treten
ausschließlich bei ≤ 1,5 s auf. Die 0,3-s-Stille-Halluzination `Yeah.` aus §4.3
Punkt 4 ist damit zeichengenau reproduziert.

#### 4.4.5 Befunde

- **F1 — 1,0 s reicht nicht.** Alle vier 1,0-s-Fenster scheitern: `Oh yes.`
  (Ähnl. 0,06), `Up the dictators.`, `Understand.` (drei fremdsprachige
  Halluzinationen) und bei `de_pause` ein **leeres** Ergebnis, obwohl die Datei
  in dieser Sekunde Sprache enthält (Totalausfall, kein „kein Text vorhanden").
- **F2 — 1,5 s ist grenzwertig.** `de_pause` liefert 7 Tokens exakt richtig,
  bricht aber vor „des Diktats" ab; `de_smoke` endet mit dem Ellipsen-Artefakt
  `So, jetzt bin ich...`; die Rauschprobe mit −50 dBFS halluziniert `No.`.
- **F3 — 2,0 s trägt.** `de_pause` liefert mit 12 Tokens ein exaktes Präfix des
  Gesamttextes (vollständiger Satz), `de_smoke` einen sauberen Satzschluss
  `So, jetzt bin ich gespannt.` — die ersten 9 Tokens stimmen exakt, erst das
  10. weicht ab (`.` statt `,`, weil der Satz im Fenster endet). Auch die Fenster
  mitten im Material sind bei 2,0 s deutsch und wortweise korrekt
  (`Und nach einer Pause.` — im Text 1,00). **Keine Halluzination.**
- **F4 — länger ist nicht besser.** `de_smoke` bei 3,0 s schneidet mitten im
  Wort und liefert `… ob die Diktative...` (18 Tokens, 6,0 tok/s, Ähnl. 0,56) —
  schlechter als 2,5 s (12 Tokens, exaktes Präfix). `de_pause` liefert bei 2,0 /
  2,5 / 3,0 / 4,0 s **identisch** dieselben 12 Tokens. Oberhalb 2,0 s gibt es
  also keinen messbaren Qualitätsgewinn, der 3,0 s rechtfertigen würde.
- **F5 — Stille ist harmlos, ein Stille-Schwanz nicht.** Digitale Stille bis
  4,0 s und Rauschen bis 4,0 s erzeugen ab 2,0 s keinen Text. Aber ein 6,0-s-
  Fenster, das 2,3 s Sprache + 3,7 s Stille enthält (`de_pause_6.0s`), driftet
  nach `Assist the Ailvesti` (Ähnl. 0,17). `min_audio_s` zählt **Wanduhr-Audio,
  nicht Sprechanteil** — der Guard schützt gegen *kurze* Puffer, nicht gegen
  *stillelastige*.
- **F6 — Ratenkorrektur für §6.** Kurzfenster erreichen 6–10 Tokens/s
  (`de_smoke_3.0s` 6,0; `de_smoke_t2.0_1.0s` 9,0; `silence_0.3s` 10,0), nicht
  nur die 5,3 aus §6. Sprachfenster ≥ 2,0 s liegen bei 3,0–6,0 Tokens/s; der
  Ausreißer nach unten ist `de_pause_6.0s` mit 1,3 Tokens/s — dort bestehen zwei
  Drittel des Fensters aus Stille.

#### 4.4.6 Entscheidung

**`min_audio_s = 2,0 s`** (statt 3,0 s). Begründung aus den Messwerten:

1. **1,0 s ist widerlegt** (F1: 3 Halluzinationen, 1 Totalausfall in 4 Fenstern).
2. **1,5 s ist die letzte beobachtete Halluzinationslänge** (F2, F5) — 2,0 s
   lässt 0,5 s Marge und ist der kleinste Wert ohne Fehlfall (F3: 23 Proben
   ≥ 2,0 s, davon 14 mit Sprache, **0 Halluzinationen**).
3. **3,0 s bringt keinen messbaren Vorteil** (F4) und kostet 1,0 s Latenz bis
   zur ersten Ausgabe — bei `asr_interval_s = 1,0` erscheint der erste Partial
   mit 2,0 s Guard nach ~2 s statt nach ~3 s.
4. Wer mehr Marge will, nimmt **2,5 s** (messgleich zu 3,0 s, 0,5 s früher).
   **Harte Untergrenze bleibt 2,0 s** — darunter ist die Halluzination belegt.

Konsequenzen für die Pipeline (verbindlich):

- Der Guard gilt **nur** für die automatische Re-Transkription während der
  Aufnahme. Der **finale** Decode bei `UP` transkribiert **jede** Länge —
  ein 1,2-s-Diktat darf nicht verloren gehen (Datenverlust wiegt schwerer als
  das Halluzinationsrisiko; das Ergebnis ist dann ggf. zu verwerfen, nicht zu
  unterdrücken).
- `min_audio_s` ist **kein** Ersatz für eine Stille-Prüfung (F5). Empfehlung:
  bei leerem Ergebnis und einem Puffer, dessen letzte ≥ 2 s unter dem
  RMS-Grundrauschen liegen, **nicht** committen (die First-Partial-Freigabe
  bleibt aus).
- Ein First-Partial bei 2,0 s kann mitten im Wort enden (F3: `Ob die Dikt die
  Funktion auf`). Das ist unkritisch, solange die Commit-Logik den Text nicht
  einfriert, bevor LocalAgreement zwei Bestätigungen hat (KONZEPT.md 2.6).
- Tests: `min_audio_s`-Grenzfall als Golden-Fixture — `de_smoke_1.0s.wav`
  (muss **verworfen/geblockt** werden) und `de_pause_2.0s.wav` (muss
  transkribiert werden) liegen dafür fertig in `testdata/cut/`.

**Relativierte Aussagen in `docs/KONZEPT.md` (dort bewusst nicht geändert):**

| Stelle | Aussage dort | Relativiert durch | Konsequenz |
|---|---|---|---|
| 3.4 | Parakeet halluziniert bei kurzen Chunks; `min_audio_s` **≥ 1,0 s**, besser 2,5–3,0 s | F1: 1,0 s ist messbar unbrauchbar; F3: 2,0 s genügt | Untergrenze auf 2,0 s anheben; „besser 2,5–3,0" ist als Obergrenze richtig, als Notwendigkeit nicht |
| §4 (Konfiguration) | `min_audio_s = 3.0` | F4: kein Vorteil gegenüber 2,0 s | Wert auf 2,0 setzen (oder 2,5 als Kompromiss) |
| §5.1 | `windowed`-Streaming gescheitert (137–139 statt 256 Zeichen) | Die Deadlock-Gründe bleiben gültig; die *Fensterqualität* als Begründung nicht — 2,0-s-Fenster liefern exakte Präfixe | `whole_buffer` bleibt Default, aber begründet über Deadlock-Freiheit und Commit-Logik, nicht über kurze Fenster |
| 8 (Checkliste) | „Guards: `min_audio_s` ≥ 1 s" | F1/F3 | auf 2,0 s präzisieren |

## 5. Messwerte x86 (Plausibilitätsanker, **nicht** ARM-Erwartung)

| Audio | mel | encoder | decoder | RTF gesamt |
|---|---|---|---|---|
| 6,40 s | 0,004 s | 0,090 s | 0,009 s | 0,016 |
| 9,90 s | 0,006 s | 0,124 s | 0,004 s | 0,014 |
| 2,80 s | 0,002 s | 0,060 s | 0,003 s | 0,023 |
| 3,10 s | 0,002 s | 0,070 s | 0,013 s | 0,027 |
| 48,9 s (6 Sätze, wiederholt) | — | — | — | 0,024 |

(8 Threads, wechselnde Containerlast → ±50 % Streuung; der RTF ist praktisch
längenunabhängig. Der Encoder dominiert, der Decoder ist mit ≤ 40 Schritten
vernachlässigbar.)

**Speicher (gemessen, maxRSS im selben Prozess):** nach Laden von `nemo128` 44 MB,
**nach Laden des Encoders 848 MB**, mit Decoder 863 MB, während einer 9,9-s-
Inferenz **926 MB Peak**. Das ist die harte Randbedingung für die Gerätewahl:
~0,9 GB RSS nur für das Modell (int8!), unabhängig vom Java-Heap
(`android:largeHeap` hilft nicht, es ist nativer Speicher). Auf ARM64 ist mit
ähnlichem Gewichts-Footprint zu rechnen; die App muss das Vorher/Nachher messen
und bei OOM sauber degradieren (KONZEPT.md 3.2 / DESIGN.md §9).

Auf ARM64-Smartphone realistisch 5–20× langsamer (RTF 0,1–0,4). Diese Zahl ist
eine **Schätzung**, keine Messung; die App muss Latenz auf dem Gerät anzeigen.

## 6. Token-Budget: Dimensionierung gegen 8193 Vokabeln

Formel (aus KONZEPT.md 3.1 / DESIGN.md §5.2, unverändert übernommen):

```
B(s) = max(256, min(4096, ceil(12 * s) + 64))
```

Gemessene Emissionraten (dieselbe Pipeline, korrigierter Decoder):

| Material | Tokens/s |
|---|---|
| de_pause (9,9 s) | 2,5 |
| de_smoke (6,4 s) | 4,2 |
| de_part1 (2,8 s) | 4,3 |
| Ziffern-/Kontonummern-Diktat (7,0 s) | **5,3** (Ziffern sind Einzeltokens, IDs 234–243) |
| buchstabiertes Alphabet (7,7 s) | 1,6 |
| 48,9 s wiederholtes Diktat | 3,2 |
| Kurzfenster 2,0–4,0 s (§4.4.2/§4.4.3) | 3,0–6,0 |
| Kurzfenster 1,0 s (§4.4.3) | 9,0 |

Bewertung:

- **12 Tokens/s ist ausreichend dimensioniert** — aber nicht mit „2,3× Reserve":
  das Maximum über 2 s liegt bei **6,0 Tokens/s** (`de_smoke_3.0s`, §4.4.2), in
  Fenstern unter 2 s bei **9,0–10,0 Tokens/s** (wenige Tokens auf kurzer Zeit →
  Rauschen der Rate, kein Sprachphänomen). Für die Formel ist das unkritisch,
  weil `max(256, …)` bis s ≈ 16 s dominiert (ein 10-s-Fenster mit 10 Tokens/s
  emittiert 100 Tokens gegen 256 Budget); im linearen Teil (s > 16 s) liegen alle
  Messungen bei 2,5–5,3 Tokens/s, dort sind die 12/s mindestens Faktor 2,3
  entfernt. Die Rate ist sprach-/sprechratenbegrenzt, nicht vokabelbegrenzt.
- **`min 256` ist unkritisch** (entspricht ~49 s bei 5,3 Tokens/s).
- **`max 4096` ist die eigentliche Grenze:** 4096/12 ≈ 341 s. Bei einer
  Aufnahme-Obergrenze von 300 s (KONZEPT.md 3.2) liegt das Budget bei 3664 und
  damit unter dem Deckel — die 4096 sind also gerade ausreichend, aber **nicht
  mehr**, wenn die Aufnahmedauer jemals erhöht wird. Bei Budgettreffer muss der
  Lauf als „gesättigt" markiert werden (DESIGN.md §5.2), sonst ist die Kappung
  unsichtbar.
- **Das Budget ist kein Runaway-Schutz.** Theoretisch sind 10 Symbole/Frame ×
  12,5 Frames/s = 125 Tokens/s möglich; die Absicherung gegen Endlosschleifen
  ist ausschließlich das Pro-Frame-Limit aus §3c plus Frame-Fortschritt. Wer das
  Budget als Schleifengrenze missbraucht, bekommt bei fehlerhaftem Decoder
  entweder 4096 Tokens Müll oder — schlimmer — eine Schleife, die nie terminiert.
- Budget gilt für die **Gesamtlänge des übergebenen Audios** inklusive
  Links-Kontext (KONZEPT.md 3.1), nicht für den Delta-Anteil.

## 7. Was die Android-Implementierung daraus zwingend ableiten muss

1. `frames = min(encoded_lengths[0], outputs.shape[2])`, `1 Frame = 80 ms`.
2. State nur bei Nicht-Blank übernehmen; bei Blank denselben State erneut nutzen.
3. `t += dur` (auch bei Blank), bei `dur == 0` → `t += 1` nur bei Blank oder
   erschöpftem Pro-Frame-Budget (10).
4. Sondertokens per Muster `<...>` + ID 8192 filtern, Ziffern-IDs 234–243 behalten.
5. `▁` → Leerzeichen, dann Leerraum-Regeln aus §2.4, trimmen.
6. Erst Token-IDs gegen §4.1 prüfen, dann den Text — Fehlersuche sonst blind
   gegenüber Detok-Bugs.
7. RTF/Latenz und RSS auf dem Gerät messen und anzeigen; die x86-Werte aus §5
   sind keine Release-Grundlage.
8. `min_audio_s = 2,0 s` (§4.4.6) nur für automatische Partials anwenden — der
   finale Decode bei `UP` läuft für **jede** Audiolänge, auch unter 2 s. Der
   Guard zählt Wanduhr-Audio, nicht Sprechanteil; ein langer Stille-Schwanz ist
   damit nicht abgedeckt (§4.4.5 F5).
