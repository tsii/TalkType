# ASR-Vertrag: gemessen, nicht angenommen

Erzeugt am 2026-10-27 in dieser Umgebung mit `onnxruntime 1.31.1` (CPU-EP,
8 Threads) gegen die unten gehashten Dateien. **Diese Datei ist die Wahrheit für
die Android-Implementierung.** Werte daraus dürfen nicht durch geratene
Tensornamen oder Achsenreihenfolgen ersetzt werden.

## 1. Gepinnte Artefakte (SHA-256, gemessen)

```
eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70  decoder_joint-model.int8.onnx   18.202.004 B
6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09  encoder-model.int8.onnx        652.183.999 B
a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f  nemo128.onnx                       139.764 B
d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d  vocab.txt                           93.939 B
```

Quelle: `https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/<datei>`
Diese Hashes gehören in das signierte Modell-Manifest der App.

`config.json` des Exports: `{"model_type":"nemo-conformer-tdt","features_size":128,"subsampling_factor":8}`

## 2. Tensorvertrag (per `InferenceSession.get_inputs()/get_outputs()` ausgelesen)

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
| IN | `encoder_outputs` | float32 | `[B, 1024, 1]` (genau **ein** Frame pro Aufruf) |
| IN | `targets` | int32 | `[B, 1]` (letztes Label; Start = `<blk>`) |
| IN | `target_length` | int32 | `[B]` |
| IN | `input_states_1` | float32 | `[2, B, 640]` (LSTM h) |
| IN | `input_states_2` | float32 | `[2, B, 640]` (LSTM c) |
| OUT | `outputs` | float32 | `[B, 1, 1, 8198]` |
| OUT | `prednet_lengths` | int32 | `[B]` |
| OUT | `output_states_1` | float32 | `[2, B, 640]` |
| OUT | `output_states_2` | float32 | `[2, B, 640]` |

**Logit-Aufteilung (verifiziert):** `8198 = 8193 Vokabeln + 5 Durationsklassen`.
`[0..8192]` = Token-Logits, `[8193..8197]` = Duration-Logits für {0,1,2,3,4}
Encoderframes. Kein Softmax nötig (Argmax).

**Vokabular:** `vocab.txt` Zeilenformat `<token> <id>`, 8193 Einträge (0..8192),
`<blk> = 8192`. Detokenisieren: Token-Strings konkatenieren, `\u2581` → Leerzeichen,
trimmen. `<unk>`=0, `<|nospeech|>`=1, `<pad>`=2, `<|endoftext|>`=3,
`<|startoftranscript|>`=4 sind zu filtern.

**Subsampling:** 8 → 1 Encoderframe = 80 ms. `frames = encoded_lengths[0]`.

## 3. Verifizierter Greedy-Algorithmus (Referenz: `tools/reference_decode.py`)

```
state1 = state2 = zeros([2,1,640]); last_label = 8192 (blank); t = 0
while t < frames:
    logits, _, state1, state2 = decoder_joint(enc_out[:,:,t:t+1], [[last_label]], [1], state1, state2)
    v = logits[0,0,0]; token = argmax(v[0:8193]); dur = argmax(v[8193:8198])
    if token != 8192 and token < 8192:
        emit(token); last_label = token; t += dur        # dur == 0 erlaubt mehrere Labels/Frame
    else:
        t += max(dur, 1)                                  # blank: immer Fortschritt
```

Wichtig: Die **States werden nur bei Non-Blank fortgeschrieben** (so wie hier
implementiert: `dec.run` liefert neue States, sie werden für den nächsten Aufruf
übernommen; bei Blank wird `last_label` NICHT geändert). Der Encoder wird pro
Fenster **einmal** über alle Frames gerechnet; der Decoder läuft frame-weise.

## 4. Gemessene Referenzergebnisse (Golden Data)

Audio: `testdata/*.wav`, 16 kHz mono PCM16, per `espeak-ng -v de` synthetisiert
(roboterhaft, aber für Pipeline-Beweise ausreichend).

| Datei | Dauer | Referenztext (Python-Referenzdecoder) |
|---|---|---|
| `de_smoke.wav` | 6,40 s | `So, jetzt bin ich gespannt, ob die Diktive Funktion auf dem Telefon funktioniert.` |
| `de_pause.wav` | 9,90 s | `Das ist der erste Teil des Diktats. Und einer Pause kommt der zweite Teil.` |
| `de_part1.wav` | 2,80 s | `Das ist der ersteil des Dikt Arts.` |
| `de_part2.wav` | 3,10 s | `Und nach einer Pause kommt der zweite Teil.` |

**Wichtige Beobachtung (bestätigt KONZEPT.md 3.4):** Dasselbe Material als
2,8-s-Einzelfenster (`de_part1.wav`) dekodiert **schlechter** als im
Gesamtkontext (`de_pause.wav`). Kurze Fenster degradieren die Qualität messbar.
Das ist das empirische Argument für `min_audio_s = 3.0` und den
Whole-buffer-Default.

**Konsequenz für Tests:** Ein Android-Golden-Test darf nicht Zeichengleichheit mit
`de_pause.wav` erwarten, wenn er nur einen Ausschnitt füttert. Verglichen wird
immer: ganzes WAV → obiger Referenztext; und die Pipeline mit Pause muss
mindestens so viele Zeichen liefern wie die Summe der beiden Teilfenster
(KONZEPT.md §7, „256 vs 137 Zeichen"-Test).

## 5. Messwerte x86 (nur als Plausibilitätsanker, **nicht** ARM-Erwartung)

| Audio | mel | encoder | decoder | RTF gesamt |
|---|---|---|---|---|
| 6,40 s | 0,06 s | 0,08 s | <0,01 s | 0,022 |
| 9,90 s | 0,01 s | 0,13 s | <0,01 s | 0,014 |

Auf ARM64-Smartphone realistisch 5–20× langsamer (RTF 0,1–0,4). Diese Zahl ist
eine **Schätzung**, keine Messung; die App muss Latenz auf dem Gerät anzeigen.