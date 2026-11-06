# SUM_MODEL_CONTRACT — On-Device-Zusammenfasser (T25a/T25b), Stand 2026-11-05

**STATUS (final, 2026-11-06): KEIN GO — endgültig.** T25c-B-WO Nachschuss
(per-channel IntegerOps) failte dasselbe Paritäts-GATE mit 0,0664 (§10a);
Harz-Regel greift: **Slip akzeptiert, keine dritte Runde, kein Kandidat C, kein
App-Work.** Begründung 2026-11-05: A (Qwen3-1.7B): fp32-Export auf diesem Host nicht
möglich (RAM-GATE), Wiederaufnahme-Bedingung offen. B (Qwen3-0.6B): fp32-Pipeline
+ int8-Größen-GATE bewiesen, aber **int8-Greedy-Parität 2,3 % ≪ 95 % →
Qualitäts-GATE FAIL** (§8). Plan §3-Entscheidungskette: A+B fail ⇒ **Feature slippt
auf 0.1.5x, KEIN App-Work (T25d…k nicht beauftragbar)**. Dieses Dokument ist der
dokumentierte Zwischenstand ("STOPP statt Pfusch").

## 1. Kandidaten & Lizenzen (HARD GATE — BESTANDEN)

| Kand. | HF-ID @ Revision | Lizenz | Befund |
|---|---|---|---|
| A primär | `Qwen/Qwen3-1.7B` @ `70d244cc86cc` | **Apache-2.0** (LICENSE-Datei, kanonischer Volltext, "Copyright 2024 Alibaba Cloud", keine Clauses) | BESTANDEN |
| B Fallback | `Qwen/Qwen3-0.6B` @ `c1899de289a0` | **Apache-2.0** (LICENSE byte-identisch zu A) | BESTANDEN |
| C nur Prüfung | `HuggingFaceTB/SmolLM2-1.7B-Instruct` @ `31b70e2e869a` | **Apache-2.0** per cardData+README; **kein LICENSE-File im Repo** (NOTICE-Pflicht bei Wahl) | BESTANDEN m. Fußnote |

Belege: `tools/sum_ref/LICENSES/` (Texte, README_with_pruefung.md, SHA256SUMS.txt).

## 2. Exportweg (bewiesen an B, fp32 = T25a GO)

Stufen-Skript `tools/sum_ref/export_qwen_text_onnx.py` (download→prefill→step,
opset17, skip-if-present, RAM-Guard exit 3), analog `tools/qwen_ref`;
Quantisierung/Fusion `tools/sum_ref/quantize_qwen_text.py`
(flatten→quantize→fuse→parity→measure), Fusion exakt nach U2-Muster
(`tools/bart_ref/merge_shared_initializers.py`: Content-Sha, ExternalData,
byte-identity + node-fingerprint + onnx.checker Asserts).
Artefakte: `/home/dev/models/sum-export/b-0.6b/`. EXPORT-Zeilen:
`tools/sum_ref/EXPORT_LOG.txt`.

## 3. Tensor-Vertrag (decode-only, kein Audio-Zweig)

- `qwen_text_prefill.onnx`: `input_ids [1,L]`, `attn_mask [1,1,L,L]` additiv,
  `position_ids [1,L]` → `logits [1,1,V]`, `past_k/past_v [28,1,8,L,128]`
- `qwen_text_step.onnx`: `input_ids [1,1]`, `past_k/past_v [28,1,8,P,128]`,
  `attn_mask [1,1,1,P+1]`, `position_ids [1,1]` → `logits [1,1,V]`, neue pasts
- V = 151936 (Qwen3-Vokabler, `tokenizer.json` 11,4 MB im raw-Ordner); Greedy
  argmax, Stop EOS/„\n\n", cap 256 (Plan §3) — App-pflichtig, hier nicht gebaut.

## 4. fp32-Parität (T25a, B) — PASS

Wrapper-ONNX vs. HF-fp32: prefill rel **1.368e-06**, step rel **1.682e-06**,
Greedy-Loop 8 Schritte token-identisch, Logit-rel-Max 2.530e-06 (Gate ≤1e-4).
Log-Zeilen `stage=prefill_fp32` / `stage=step_fp32`.

## 5. Artefakt-Größen & shas (B)

| Artefakt | Bytes | sha256 (lang) |
|---|---|---|
| flatten/prefill/qwen_text_prefill.onnx | 597 319 | fd7aa710…a46ba2cb |
| flatten/step/qwen_text_step.onnx | 481 652 | 37574247…199647ea |
| int8/prefill/qwen_text_prefill.int8.onnx | 752 807 032 | d727302b…a8508ba1 |
| int8/step/qwen_text_step.int8.onnx | 752 598 179 | 1f9b5aa8…ce5f399f |
| int8-fused/qwen_text_prefill.int8.onnx | 1 025 404 | adda6186…190e12c7 |
| int8-fused/qwen_text_step.int8.onnx | 816 551 | 25d16714…86eecb181 |
| int8-fused/shared/shared_weights.bin | 751 800 320 | 3225df9a…3ad94ff4 |

**Größen-GATE (≤800 MB): PASS** — fused set 753 642 275 B (−751,8 MB Dedup).
lm_head bleibt int8: fp32-Kopf gemessen ohne Qualitätsgewinn (§8), int8 spart 467 MB.

## 6. Prüfer & Goldens

- Goldens `testdata/sum_goldens/sum_goldens.jsonl`: 20 eigen verfasste Monologe
  (12 de / 8 en, 459–565 Wörter, fiktive Anker) + enzyklopädische Referenzen
  (13,7–20,4 % Länge) + `entities`; `traps.tsv` 12 synthetische Fälle.
- Prüfer `tools/sum_ref/check_summary.py` (offline, deterministisch):
  Schema (3–6 Sätze, Sprache), Erfindungs-Check (Ziffern-Tokens normalisiert;
  Großbuchstaben-Namen mit Compound-/Prefix-Toleranz), too_long >60 % gelb.
- Selbsttest: **20/20 Goldens PASS, 6/6 Trap-FAILs korrekt, 6/6 PASS korrekt.**
- Regel 9: Modell-/Referenztexte nur in reports/*.json, nie in Logs.

## 7. Wiederaufnahme A (fp32 NO-GO auf diesem Host)

Bedarf ≥24 GB MemAvailable (Host-Spitze 16 GB, >45 GB Fremdbelegung). Guard hat
zweimal sauber verweigert (exit 3, kein OOM-137). Raw-Gewichte 4,08 GB sha-gepinnt
unter `/home/dev/models/sum-export/a-1.7b/raw/`; Wiederaufnahme = identischer
Aufruf auf ≥24-GB-Host. Erwartung: int8-Parität zeigt dasselbe Aktivierungs-Problem (§8).

## 8. int8-Parität B — **GATE FAIL** (Messwerte)

Gate: Greedy-Übereinstimmung int8-vs-fp32 ≥95 % (8 Texte × 32 Tokens) oder nur
erlaubte Ersetzungen. Gemessen **0.0234** frei laufend; Proben:

| Konfiguration | Lehrer-Zwang/Schritt | frei laufend |
|---|---|---|
| full int8 per-tensor | 0.648 | 0.0234 |
| full int8 per-channel | — (Logit-rel 0.378, corr 0.946) | — |
| lm_head fp32 | — (Logit-rel 0.383 → Kopf unschuldig) | — |
| Attention fp32 + MLP/head int8 | 0.883 | 0.227 (und 2,01 GB/Graph → Größen-GATE bräche ebenfalls) |

Flatten und Fusion sind durch Bit-Identitäts-Proben entlastet.
**Grund:** dynamische per-tensor-**Aktivierungs**Quantisierung der
Attention-Stapel (QLinearMatMul), nicht die Gewichte. ASR-int8 (0.6B) war
gerettet durch extrem gespitzte ASR-Logits; Textgenerierung hat flachere
Verteilungen → jede Stufe kippt Tokens.

## 9. Messung & Determinismus (int8-fused, Host, threads=4)

4 Goldtexte × 32 Tokens: median **2,85 s** (~11 tok/s), peak RSS 3,3 GB;
D2-Determinismus: Doppel-Lauf-Logit-Blobs byte-identisch
(sha `d61ab06c…c81f04c5e`). (`stage=measure_int8_host`.)

## 10. Go/No-Go

- **B: NO-GO** (Qualität GATE FAIL §8) — Größen-/Determinismus-/Pipeline-GATES grün.
- **A: NO-GO** (Host-RAM; Qualität unabhängig davon erwartet gleichartig, §7).
- App-Kette T25d…k bleibt gesperrt; Rückkehr nur mit (a) Host ≥24 GB für A fp32
  und (b) einem kalibrierten weight-only int8-Verfahren (GPTQ/AWQ-Stil — eigenes
  Scoping, nicht Teil von T25b) oder einer Plan-Änderung des Users.

### 10a. T25c-B-WO Nachschuss (2026-11-06) — **ENDGÜLTIG FAIL, Slip akzeptiert**

Ein kalibrierungsfreier Versuch exakt nach Plan (`quantize_dynamic` QInt8 +
`per_channel=True`, Modus IntegerOps) auf denselben flatten-fp32 Artefakten
(bit-Identität vorher neu assertiert): Größen-GATE **PASS** (755,8 MB ≤ 800 MB,
+2,2 MB Delta = per-Channel-Scales), Fusion-Asserts grün, **D2-Determinismus
PASS**, Messung median 2,11 s/32 Tok (threads=4). **Parität free-run 0,0664 <
0,95** (T25b per-tensor: 0,0234). Mechanismus-Befund: `quantize_dynamic` hat
IntegerOps bereits als Fix-Default und quantisiert Aktivierungen stets
uint8-dynamisch mit — wahres weight-only ist mit dem vorgeschriebenen Werkzeug
nicht darstellbar; per-Channel bestätigt die Diagnose „Aktivierungsrauschen".
**Harz-Regel: keine dritte Runde, kein Kandidat C, kein App-Work — das Feature
slippt endgültig (Slip-Doku durch den Planer).** Zahlen:
`reports/sum_t25c_b_wo.json`, Log `stage=t25c_b_wo_decision`.
