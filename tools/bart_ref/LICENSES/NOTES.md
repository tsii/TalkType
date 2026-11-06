# U2-GATE Lizenz-Nachweis — BART/seq2seq Rechtschreib-Korrektur (de/en)

Alle Quellen am 2026-11-05 geprüft (HF Hub API + Model Cards + config.json),
Snapshots auf Revision gepinnt und lokal gesichert:
`/home/dev/models/bart-export/raw/{de,en}/` (PIN.json je Sprache).

## Gewählte Modelle (DECISION, s. docs/BART_CORRECT_CONTRACT.md §1)

| Sprache | Modell | Revision (SHA) | Model-Lizenz | Basis-Modell | Basis-Lizenz | Gated |
|---|---|---|---|---|---|---|
| en | oliverguhr/spelling-correction-english-base | `0e3958355a09d2816ed2701fdc2f4471d46c320e` | **MIT** (Model Card) | facebook/bart-base @ `aadd2ab0ae0c8268c7c9693540e9904811f36177` | **Apache-2.0** (Hub-Card) | nein |
| de | oliverguhr/spelling-correction-german-base | `b3916256af432f30dbaeb184a1c6b5be78b6f62f` | **Apache-2.0** (Model Card) | google/flan-t5-base @ `7bcac572ce56db69c1ea7c8af255c5d7c9672fc2` | **Apache-2.0** (Hub-Card) | nein |

- Quelle URLs:
  - https://huggingface.co/oliverguhr/spelling-correction-english-base (config `_name_or_path: facebook/bart-base`, arch `BartForConditionalGeneration`)
  - https://huggingface.co/oliverguhr/spelling-correction-german-base (config `_name_or_path: google/flan-t5-base`, arch `T5ForConditionalGeneration`)
  - https://huggingface.co/facebook/bart-base · https://huggingface.co/google/flan-t5-base
- **WICHTIGE Klarstellung (Aufgaben-Titel-Korrektur):** der deutsche Kandidat ist
  **kein BART**, sondern ein **FLAN-T5-Base-Finetune** (Modell-Karten-Name
  "bart-base-spelling-de" ist irreführend; config.json zählt). Beide sind
  seq2seq-Encoder-Decoder-Modelle mit `use_cache`-KV-Decode — der
  Export-/Laufzeit-Vertrag ist identisch strukturiert (Details im Contract).
- Checksummen (fp32-Safetensors, auch in tools/bart_ref/EXPORT_LOG.txt):
  - en `model.safetensors` 557,912,616 B — sha256 `322f77acbba30c2561085c99f3815f99f42fcc48674a6f5d4e785cbc16448b80`
  - de `model.safetensors` 990,173,034 B — sha256 `384595fc275d1162eae07eb41d28b1e88f778475ecff0c4db3801149027c7c1f`
- Tokenizer-Lizenzen: en BPE-vocab abgeleitet von OpenAI GPT-2/roberta-vocab
  (MIT, https://openaipublic.blob.core.windows.net/gpt-2/ — s. mit.txt);
  de SentencePiece/Unigram (Google T5, Apache-2.0, in flan-t5-base enthalten).

## Lizenztexte (lokal gesichert)

- `apache-2.0.txt` — kanonischer Apache-2.0-Text (apache.org)
- `mit.txt` — kanonischer MIT-Text (SPDX)
- `README_*.md` — Model Cards der 4 beteiligten Repos (Lizenzangabe je Kopf)

## Alternativen (permissiv lizenzierte seq2seq-Rechtschreibmodelle, geprüft 2026-11-05)

| Modell | Lizenz | Arch | Anmerkung |
|---|---|---|---|
| oliverguhr/spelling-correction-multilingual-base | MIT | T5 | de+en in EINEM Modell; Kandidat für "ein Modell für beide Sprachen", Qualität auf DE-Goldens noch ungeprüft (U3+-Kandidat) |
| grantslewis/spelling-correction-english-base-* | je Card (offen prüfen) | BART | Finetunes des EN-Modells auf Ortsnamen, en-only |
| Vinctilus/onnx-oliverguhr-spelling-correction-german-base | offen (derivative) | T5-ONNX | Derivat unseres DE-Kandidaten, kein Lizenznachweis gesichtet |
| flozen1981/spelling-correction-german-onnx | offen (derivative) | — | Derivat, keine eigene Basis |
| aiassociates/t5-small-grammar-correction-german | offen prüfen | T5-small | Grammatik (nicht Rechtschreib-)Korrektur, kleiner (T5-small) |

Kein Gated-Repo-Blocker aufgetreten (beide oliverguhr-Kandidaten ungegated);
die Gate-Alternative-Auswahl war daher nicht nötig.
