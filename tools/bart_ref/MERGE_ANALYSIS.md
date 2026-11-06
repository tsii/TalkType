# T24a MERGE_ANALYSIS — U2-Graph-Merge: duplizierte Initialisierer in den BART/T5-Rechtschreibgraphen (de/en)

**Status 2026-11-05 · Analyse + Fusion durchgeführt, Paritäts-GATE bestanden
(100 % Wort-Identität, Logits bit-identisch). Größenziel ERREICHT:
en 179,3 MB ≤ 220 MB · de 248,5 MB ≤ 270 MB (int8 gesamt).**

Datengrundlage: `tools/bart_ref/merge_analysis.py` →
`reports/bart_merge_analysis_{de,en}.json` (jeder Initialisierer je Graph mit
Name, Dims, dtype, Bytes, **sha256 der Rohdaten**, konsumierenden ONNX-Knoten).

Messumgebung: ORT 1.31.1, onnx 1.23.2, Python 3.11, `.venv-asr` (identisch zum
U2-Gate). **Die ungefusionerten Artefakte sind unangetastet** (alle int8-sha256
aus `EXPORT_LOG.txt` stimmten nach der Fusion unverändert, verifiziert).

---

## 1. Befund: was ist wo dupliziert?

Die dynamische int8-Quantisierung (`quantize_dynamic`, QInt8) quantisiert
**Gather (Embedding-Lookup) und MatMul (inkl. lm_head)**; die Tabellen liegen
also in ALLEN int8-Graphen als quantisierte Initialisierer mit identischen
Bytes. Duplikat-Erkennung über **Inhalt (sha256)**, nicht Namen (der
Legacy-Tracer nummeriert `onnx::MatMul_*` je Graph unterschiedlich).

### en (BART-base-Finetune, V=50265, lm_head an embed gebunden)

| Tensor (Rolle) | Graph(en) | Größe | dtype | Lesende ORT-Knoten | sha256 (12) |
|---|---|---|---|---|---|
| `embed.weight_quantized` [50265,768] (embed_tokens) | encoder + decoder_init + decoder_step | 38,6 MB | uint8 | `Gather` | `3a76f86fe45e…` (alle 3 identisch) |
| `onnx::MatMul_1350/1160_quantized` [768,50265] (lm_head, tied) | decoder_init + decoder_step | 38,6 MB | int8 | `MatMulInteger` | `ce1df3f4479b…` (beide identisch) |
| `pos_weight_quantized` [1026,768] (gelernte Positionen) | decoder_init + decoder_step | 0,79 MB | uint8 | `Gather` | `a1825a569457…` |
| 12 Layer-Gewichte (self/cross/FFN) je Decoder-Layer | decoder_init + decoder_step | 2×56,6 MB | int8 | `MatMulInteger` | je Layer paarweise identisch |
| 12 Layer-Gewichte je Encoder-Layer | nur encoder | 42,5 MB | int8 | `MatMulInteger` | (kein Duplikat) |
| `embed.weight_scale/_zero_point` (Skalar) | alle 3 | 4–8 B | f32/u8 | `DequantizeLinear` | identisch |

**137 Duplikat-Gruppen ≥ 1 KiB, davon 2 große Tabellen** (embed 3×, lm_head 2×)
= 77,2 MB Tabellen + 89,3 MB Layer-Gewichte doppelt → **166,5 MB redundante
Kopien** in den 345,7 MB.

### de (FLAN-T5-base-Finetune, V=32100, lm_head UNgebunden)

| Tensor (Rolle) | Graph(en) | Größe | dtype | Lesende ORT-Knoten | sha256 (12) |
|---|---|---|---|---|---|
| `embed.weight_quantized` [32100,768] (embed_tokens) | encoder + decoder_init + decoder_step | 24,65 MB | uint8 | `Gather` | `602e1164e283…` (alle 3 identisch) |
| `onnx::MatMul_2848/2433_quantized` [768,32100] (lm_head, eigener Satz Gewichte) | decoder_init + decoder_step | 24,65 MB | int8 | `MatMulInteger` | `595e804944a8…` (beide identisch) |
| `rel_bias…` + 36 Layer-Gewichte je Decoder-Block | decoder_init + decoder_step | 2×99,1 MB | int8 | `MatMulInteger`/`Gather` | paarweise identisch |
| 24 Layer-Gewichte je Encoder-Block | nur encoder | 85,2 MB | int8 | `MatMulInteger` | (kein Duplikat) |

**147 Duplikat-Gruppen ≥ 1 KiB** = 49,3 MB Tabellen + 123,9 MB Layer-Gewichte
doppelt → **173,2 MB redundante Kopien** in den 421,7 MB.

Wichtig: Die Duplikate sind **byteweise identisch** (gleicher sha256), weil
(a) alle Graphen aus demselben safetensors-Checkpoint exportiert und
(b) `quantize_dynamic` deterministisch ist. Das ist die Voraussetzung dafür,
dass die Fusion verhaltensneutral sein KANN (und muss).

## 2. Bewertung der angeboteten Fusions-Wege

### Weg (a) geteilter-Referenz-Export mit `input_embeds`-Zwischenpfaden — ABGELEHNT

Idee: Initialisierer aus prefill/step entfernen, Embedding nur im
Encoder-Graph, Decoder erhalten `input_embeds`. Abgelehnt, weil:

1. **Reicht rechnerisch nicht (de)**: Entfernt man nur embed + lm_head aus
   beiden Decoder-Graphen (de 2×49,3 MB), blieben ~347 MB > 270-MB-Ziel. Das
   Ziel ist ohne Dedup der Decoder-Layer-Gewichte zwischen init/step
   (weitere ~99 MB) unerreichbar — die Tabellen sind bei T5 nur ~49 MB des
   Problems.
2. **lm_head kann nicht „außen“ bleiben**: Der Decoder müsste Hidden-States
   [1,1,768] ausgeben und der Host den 768×V-MatMul je Token rechnen —
   Änderung des App-Vertrags (§3/§4) + neue Host-Pfad-Risiken, ohne
   Qualitätsgewinn.
3. **Embedding außerhalb der Decoder-Graphen** hieße: Host macht den
   Gather-Lookup je Decode-Schritt selbst — der Host bräuchte die Tabelle
   wieder als eigenes Artefakt (denselben Dedup-Effekt wie Weg (b), aber mit
   geändertem Tensor-Vertrag und neuem Runner-Code statt bewiesenem Pfad).
4. Neu-Export mit geänderten Wrappern riskiert Abweichungen von den
   U2-bewiesenen Graphen; Weg (b) braucht gar keinen Export.

### Weg (b) ONNX-Graph-Rewrite — GEWÄHLT

`tools/bart_ref/merge_shared_initializers.py`: Reiner Datei-Level-Rewrite der
existierenden int8-Graphen. Jeder Initialisierer ≥ 1 KiB, dessen Rohbytes
(sha256) in ≥ 2 der 3 Graphen vorkommen, wird EINMAL in einen
sprach-lokalen Blob `shared/shared_weights.bin` gepackt und in jedem Graphen
durch eine **ExternalData-Referenz** (location/offset/length) ersetzt.
Initialisierer < 1 KiB bleiben inline (Overhead der Referenz > Nutzen).
Knoten, Graph-Ein-/Ausgaben, value_info werden **nicht angefasst**.

Warum das sicher ist:

- Die Gewichte sind per Konstruktion **byteweise identisch** zur unfusionierten
  Referenz (im Skript per `np.array_equal` über ALLE Initialisierer verifiziert,
  plus onnx.checker + Struktur-Fingerprint Nodes/IO).
- ExternalData ist für ORT beim Laden transparent — identische Tensoren,
  identischer Graph ⇒ identische Ausgaben. Kein Neu-Quantisieren, kein
  Neu-Tracen, kein Qualitätsrisiko.
- Duplikat-Schlüssel ist der **Inhalt**, nicht der Name (Tracer-Nummerierung
  `onnx::MatMul_*` ist je Graph verschieden).

Bewusst NICHT dedupliziert: die 3 fp32-Graphen (Ziel ist int8-Gesamtgröße)
und ONNX-`Constant`-Attribute (Unsqueeze-Achsen etc., nur kB-Bereich).

## 3. Ergebnis: Größen vorher/nachher (int8 gesamt je Sprache)

| | en vorher | en nachher | de vorher | de nachher |
|---|---|---|---|---|
| encoder | 82,2 MB | 43,6 MB | 109,9 MB | 85,2 MB |
| decoder_init | 135,3 MB | 7,5 MB | 163,0 MB | 14,5 MB |
| decoder_step | 128,2 MB | 0,32 MB | 148,8 MB | 0,33 MB |
| shared/shared_weights.bin | — | 127,9 MB | — | 148,5 MB |
| **Summe int8** | **345,7 MB** | **179,3 MB** | **421,7 MB** | **248,5 MB** |
| **Ziel (T24a)** | ≤ 220 MB | ✅ **−48 %** | ≤ 270 MB | ✅ **−41 %** |

Artefakte: `/home/dev/models/bart-export/{en,de}-merged/` (je 3 Graphen +
`shared/` + `tokenizer/`-Kopie), sha256 je Datei in `EXPORT_LOG.txt`
(stage=merge, mit Tool-Versionen). sha256 der 4 Einzeldateien:

- en: encoder `bba119c4…36cf54` · decoder_init `d9860263…9f96629` ·
  decoder_step `2d1a70e4…01dc8b2` · shared_weights.bin `0a8a2920…a39c347`
- de: encoder `05507b81…b25dbb89` · decoder_init `97872f56…8d6ccda2` ·
  decoder_step `275a7853…91fd1a2b` · shared_weights.bin `68296cd9…1da430cd`

## 4. Paritäts-GATE nach Fusion (20 U2-Goldens je Sprache, Seed/Korpus unverändert)

`tools/bart_ref/verify_merge_gate.py` → `reports/bart_u2_merge_{en,de}.json`
(dieselben synthetischen Sätze aus `testdata/bart_goldens/{en,de}.tsv` wie im
U2-GATE, greedy, `max_new_tokens=64`, ORT 1.31.1 CPU):

| Kriterium | en | de | Ziel | Status |
|---|---|---|---|---|
| Wort-Identität fusioniert vs UNfusioniert int8 | **1.000** (20/20 Sätze, Token-Id-Sequenzen identisch) | **1.000** (20/20) | 100 % | ✅ |
| ORT-Logitpfad-Parität fusioniert vs unfusioniert (rel.) | enc/init/step **0.0** | enc/init/step **0.0** | ≤ 1e-4 | ✅ |
| RTF (20-Wort-Bereich, fusionierte Pipeline, Median) | 0,134 s (**RTF 0,019**) | 0,261 s (**RTF 0,037**) | ≤ 0,1 | ✅ |

Die U2-Referenzwerte (HF-vs-int8-Wortparität, fp32-ORT-vs-HF-Logitpfad ≤
1,4e-6) bleiben unverändert gültig, da die fusionierten Graphen dieselben
Bytes berechnen — das Gate bestätigt es empirisch auf allen 40 Sätzen.

## 5. Restrisiken / nächste Hebel

- **RAM**: ExternalData spart Platte, NICHT RAM — jede ORT-Session lädt ihre
  Kopien (~420–510 MB ONNX-RAM, §9 im Vertrag). Nächster Hebel für RAM/Platte:
  Decoder-Graphen init/step zu EINEM Graph fusionieren (init = Spezialfall
  P=0) — spart zusätzlich die doppelte Decoder-Struktur (RAM: eine Session
  weniger) und wäre der T24b-Hebel; Verhalten wieder per Gate zu beweisen.
- **ORT-Versionen**: ExternalData (location/offset/length) ist ein
  langstabiles ONNX-Feature (seit opset/IR 8-freigabe, ORT seit ~1.x
  unterstützt); getestet mit ORT 1.31.1 == Android-Version. Beim
  ORT-Upgrade der App die Merge-Gate-Skripte einmal laufen lassen.
- **Packaging**: Die 3 Graphen brauchen `shared/shared_weights.bin` im
  gleichen Verzeichnis-Baum (relative Referenz `shared/…`); wer einzelne
  Graphen verschiebt, bricht die Referenz. Relative Pfeile nach außen
  („..“) werden vom ONNX-Checker abgelehnt — Layout bewusst flach gewählt.
- **FP32**: fp32-Graphen sind weiterhin 3-fach redundant (1377/1683 MB) —
  für die App irrelevant (int8-only), aber bei künftigen fp32-Verwendungen
  denselben Rewrite anwendbar.
