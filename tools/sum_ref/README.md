# sum_ref — on-device summarizer reference pipeline (T25a…)

## T25a — Lizenz-HARD-GATE + fp32-Export-Pipeline (Qwen3-Text, Kandidaten A/B)

- **Lizenz-GATE (A/B/C): BESTANDEN** — alle drei Apache-2.0 oder besser; Texte/Belege
  lokal in [`LICENSES/README_with_pruefung.md`](LICENSES/README_with_pruefung.md)
  (C = HuggingFaceTB/SmolLM2-1.7B-Instruct, nur Prüfung, kein Export — Apache-2.0 per
  Card, aber KEIN LICENSE-File im Repo, Fußnote dokumentiert).
- **Export-Skript:** [`export_qwen_text_onnx.py`](export_qwen_text_onnx.py) — Stufen
  `download → prefill → step` (decode-only, prefill/step mit KV wie die
  Qwen-ASR-Decoder-Graphen, aber **ohne Audio-Zweig**; lm_head erhalten, RoPE wie
  vorhanden), opset 17, skip-if-present, je Stufe sha256 + RSS im
  [`EXPORT_LOG.txt`](EXPORT_LOG.txt) (analog bart_ref/qwen_ref); Aufruf mit
  `/usr/bin/time -v` (Output angehängt). **RAM-Guard:** pro Export-Stufe
  MemAvailable-Check (B ≥ 8 GB, A fp32 ≥ 24 GB), bei Untergrenze NO-GO-Meldung
  (exit 3) statt OOM-Crash. Quantisieren/Fusion = **T25b**, hier nicht durchgeführt.
- **B (Qwen/Qwen3-0.6B) — Pipeline-Beweis KOMPLETT:** fp32-Prefill+Step unter
  `/home/dev/models/sum-export/b-0.6b/{raw,prefill,step}/` (je ~3,01 GB Stage-Ordner,
  Gewichte noch per-Initialiszer-External-Data → T25b-Flatten).
  **Parität vs. HF-fp32-Greedy: prefill rel 1.368e-06, step rel 1.682e-06,
  Greedy-Loop (8 Schritte) Token-identisch, Logit-rel-Max 2.530e-06 — GATE ≤1e-4 PASS**
  (Asserts im Log, Zeilen `stage=prefill_fp32` / `stage=step_fp32`; nur Token-IDs im
  Log, Regel 9). RSS-Peak: prefill 5,3 GB / step 11,9 GB.
- **A (Qwen/Qwen3-1.7B) — Go/No-Go nach fp32 = NO-GO auf DIESEM Host:** Roh-Gewichte
  komplett geladen+sha-gepinnt (4,08 GB, `/home/dev/models/sum-export/a-1.7b/raw/`),
  aber 24-GB-RAM-Untergrenze nie erreicht (Host-Spitze 16 GB, >45 GB von anderen
  Host-Mietenden belegt). Guard hat Start sauber verweigert (kein OOM 137, kein
  Retry-Loop) — identische Risiko-Klasse wie 1.7B-ASR-Decoder-OOM
  (`docs/QWEN_ASR_CONTRACT.md` §7), hier vor dem Crash gestoppt.
  **Wiederaufnahme:** auf Host mit ≥24 GB frei einfach
  `.venv-asr/bin/python tools/sum_ref/export_qwen_text_onnx.py --candidate a --stage prefill`
  (Download vorhanden).
- **Report:** [`reports/sum_t25a.json`](../../reports/sum_t25a.json)
  (Größen, shas, Paritäten, Lizenz-Befund je Kandidat).

Beispielaufruf (B, komplett):

```
/usr/bin/time -v .venv-asr/bin/python tools/sum_ref/export_qwen_text_onnx.py \
  --candidate b --stage all 2>> tools/sum_ref/EXPORT_LOG.txt
```

## T25b — int8 + Fusion (B) + Prüfer + Goldens — **Qualitäts-STOPP dokumentiert**

- **Goldens:** [`testdata/sum_goldens/`](../../testdata/sum_goldens/README.md) — 20
  eigen verfasste Monologe (12 de/8 en, 459–565 Wörter, fiktive Anker) + enzyklopädische
  Referenzen (14–20 %) + `traps.tsv` (12 synthetische Fälle).
- **Prüfer:** [`check_summary.py`](check_summary.py) (offline, deterministisch):
  Schema 3–6 Sätze + Spracherkennung, Erfindungs-Check (Ziffern-Tokens,
  Großbuchstaben-Namen mit Compound-Toleranz), too_long>60 % gelb.
  Selbsttest: **20/20 PASS, 6/6 Traps FAIL korrekt, 6/6 PASS korrekt.**
- **Pipeline [`quantize_qwen_text.py`](quantize_qwen_text.py):** flatten (byte-verifiziert) →
  quantize_dynamic QInt8 opset17 → ExternalData-Fusion nach U2-Muster — **Größen-GATE
  PASS: fused 753,6 MB ≤ 800 MB** (Asserts: Bit-Identität, Node-Fingerprint, onnx.checker);
  lm_head int8 (fp32-Kopf bringt messbar nichts). D2-Determinismus PASS (sha d61ab06c…).
- **int8-Greedy-Parität vs. fp32: 0,0234 — GATE FAIL** (Lehrer-Zwang 0,648;
  Attention-fp32-Variante 0,883/0,227 und 2 GB/Graph). Ursache: dynamische
  Aktivierungs-Quantisierung der Attention, nicht Gewichte/Fusion/Flatten (alle
  bit-identisch entlastet). **STOPP gemäß Task-Regel — Meldung statt Pfusch.**
- **Messung (int8, threads=4):** median 2,85 s / 32 Tokens, peak RSS 3,3 GB.
- **Verträge/Reports:** [`docs/SUM_MODEL_CONTRACT.md`](../../docs/SUM_MODEL_CONTRACT.md)
  (KEIN GO; Wiederaufnahme-Bedingungen),
  [`reports/sum_t25b.json`](../../reports/sum_t25b.json), Stage-Zeilen in
  [`EXPORT_LOG.txt`](EXPORT_LOG.txt).

## T25c-B-WO (2026-11-06) — weight-only-Versuch, ENDGÜLTIG FAIL

`quantize_qwen_text.py --wo` (per_channel=True; IntegerOps ist ORT-Default und
quantisiert Aktivierungen immer mit — wahres weight-only mit diesem Werkzeug nicht
darstellbar). Ergebnis: Größen-GATE PASS (755,8 MB), Fusion-Asserts + D2 PASS,
**Parität free-run 0,0664 < 0,95 → Slip akzeptiert, keine dritte Runde.**
Reports: `reports/sum_t25c_b_wo.json`, Contract §10a, Artefakte
`/home/dev/models/sum-export/b-0.6b/int8wo[-fused]/` (unangetastet, nur Beweismittel).
