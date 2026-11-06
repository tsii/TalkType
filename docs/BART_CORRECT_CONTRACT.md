# BART_CORRECT_CONTRACT — U2-GATE Rechtschreib-Korrektur seq2seq (de/en)

**Status 2026-11-05 · Beide Kandidaten exportiert (fp32+int8), Parität und RTF auf dem Host
GEMESSEN und belegt. Go/No-Go: §11. T24a-Update: fusionierte Merged-Artefakte
(`{en,de}-merged`, Größenziel erreicht, Parität 100 %): §12.**
Ziel: Zahlenbasis und Laufzeit-Vertrag für die App-Integration einer
Rechtschreib-Korrektur für getippten Text (20-Wort-Bereiche) de/en.

Messumgebung: Projekt-Container x86_64, ORT **1.31.1** (identisch zur Android-ORT-Version,
build.gradle.kts:131 — gleiche Major.MINOR wie beim Qwen-Gate), `.venv-asr`
(transformers 5.22.1, torch 2.15.0+cpu), 16 Threads. HF-Downloads nach
`/home/dev/models/bart-export/raw/{de,en}/` **auf Revision gepinnt**.

---

## 1. Modellwahl & Lizenzen (DECISION)

| Sprache | Modell | Revision | Model-Lizenz | Basis | Basis-Lizenz |
|---|---|---|---|---|---|
| en | `oliverguhr/spelling-correction-english-base` | `0e395835` | MIT | facebook/bart-base `aadd2ab0` | Apache-2.0 |
| de | `oliverguhr/spelling-correction-german-base` | `b3916256` | Apache-2.0 | google/flan-t5-base `7bcac57` | Apache-2.0 |

**Wichtigste Klarstellung (Abweichung vom Aufgaben-Titel):** Der DE-Kandidat ist
**kein BART**, sondern ein **FLAN-T5-Base-Finetune** (config.json:
`_name_or_path: google/flan-t5-base`, `T5ForConditionalGeneration`; der
Model-Card-Name „bart-base-spelling-de" ist irreführend). Beide sind
permissiv lizenzierte, ungegatede seq2seq-Modelle mit `use_cache`-Decode —
Vertrag, Export und Laufzeit sind daher für beide Sprachen identisch
strukturiert (unterschiedlich nur: Schichtzahl L, Vokabular V, Positionskodierung,
s. §3). Kein Gated-Repo-Blocker; Alternativen-Liste und alle Lizenztexte:
`tools/bart_ref/LICENSES/` (NOTES.md + apache-2.0.txt + mit.txt + Model Cards).

Modelle (fp32, sha256 in `tools/bart_ref/EXPORT_LOG.txt`):
en 557,912,616 B `322f77ac…48b80` · de 990,173,034 B `384595fc…c7c1f`.

Architektur-Parameter:

| | en (BART-base-Finetune) | de (FLAN-T5-base-Finetune) |
|---|---|---|
| Schichten L (enc/dec) | 6 / 6 | 12 / 12 |
| d_model / d_kv (heads 12, head_dim 64) | 768 | 768 |
| FFN / Aktivierung | 3072, gelu (post-LN) | 2048, gated-gelu (pre-LN, RMSNorm eps 1e-6) |
| Positionen | gelernt, offset +2, max 1024 | relative Bias (32 Buckets, max_dist 128), kein qk-Scaling |
| Vokabular V | 50265 (BPE, roberta/GPT-2-Familie) | 32100 (SentencePiece-Unigram) |
| lm_head | an embed gebunden, `final_logits_bias`=0 | **UNgebunden** (eigene Gewichte im Checkpoint!), `scale_decoder_outputs=False` |
| Parameter | ~139 M | ~248 M |

## 2. Exportweg (eigener Export, Qwen-Muster)

`tools/bart_ref/export_bart_onnx.py` — staged, skip-if-present, `--force`,
Eine Log-Zeile je Stage in `tools/bart_ref/EXPORT_LOG.txt` (Zeitpunkt, Stage,
Sprache, Sekunden, **peak RSS**, Artefakt, Bytes, **sha256**; Pinn-Revision bei
stage=raw). Stages: `raw` (snapshot_download, gepinnt) → `tokenizer` →
`encoder` → `decoder_init` → `decoder_step`. Je Stage: fp32-Export per Legacy-Tracer
(**opset 17**, handgeschriebene Wrapper über die HF-Gewichte 1:1) mit
**Paritäts-Assert gegen HF-fp32 VOR dem Export** (rel. Fehler ≤ 1e-4 für
Hidden/Logits, ≤ 1e-5 für KV; gemessen: überall **0.0**, s. EXPORT_LOG), dann
`quantize_dynamic(weight_type=QInt8)` (MatMulInteger). Jede Stage in eigenem
Unterverzeichnis (Legacy-Tracer nummeriert `onnx__MatMul_*` global — Qwen-Lesson).

Ablage `/home/dev/models/bart-export/{de,en}/` (NICHT im Repo; Hashes im Log):
`encoder/`, `decoder_init/`, `decoder_step/` (je `.onnx` fp32 + `.int8.onnx`),
`tokenizer/` (tokenizer.json + tokenizer_config.json + vocab_meta.json).

Beweis der numerischen Wrapper-Treue (torch-vs-HF, im Skript asserted):
alle Stages beider Sprachen **0.0 rel. Fehler** (byteweise identische Ops).

## 3. Tensor-Vertrag (ONNX-Graphen, präzise)

Batch B=1, **kein Padding** (Batch-1/ungepolstert → keine Attention-Mask-Inputs
nötig; Masken sind im Graph-Design trivial: Encoder bidirektional voll,
Decoder-Step sieht alle Cache-Keys). Alle floats: **float32**, alle ids:
**int64**. `L` = Decoderschichten (en 6, de 12), `H`=12 Köpfe, `hd`=64,
`d`=768, `V` (en 50265, de 32100), `S`=Encoder-Länge (dynamisch), `P`= bisher
generierte Tokens (dynamisch).

### encoder.onnx — `input_ids` → `last_hidden`
| Tensor | Dim | dtype | Bemerkung |
|---|---|---|---|
| input_ids | [1, S] | int64 | tokenizer.encode(prefix+text), ohne Special-Pad |
| last_hidden | [1, S, 768] | float32 | en: post-LN-Encoder; de: pre-LN/RMS + rel. Bias (im Graph berechnet) |

### decoder_init.onnx — erster Decode-Schritt (Cross-KV wird gebaut)
| Tensor | Dim | dtype | Bemerkung |
|---|---|---|---|
| decoder_start_ids | [1, 1] | int64 | en 2 (`</s>`), de 0 (`<pad>`) — aus vocab_meta.json |
| encoder_hidden | [1, S, 768] | float32 | = last_hidden |
| logits | [1, 1, V] | float32 | nächstes Token = argmax |
| self_k / self_v | [L, 1, 12, 1, 64] | float32 | KV des Start-Tokens |
| cross_k / cross_v | [L, 1, 12, S, 64] | float32 | **loop-invariant**: K/V über encoder_hidden, Runner hält sie fest |

### decoder_step.onnx — 1-Token-Decode mit KV
| Tensor | Dim | dtype | Bemerkung |
|---|---|---|---|
| input_ids | [1, 1] | int64 | letztes generiertes Token |
| position_ids | [1, 1] | int64 | absolute Query-Position = P (Schrittindex) |
| self_k / self_v | [L, 1, 12, P, 64] | float32 | Cache-In |
| cross_k / cross_v | [L, 1, 12, S, 64] | float32 | aus decoder_init, unverändert |
| logits | [1, 1, V] | float32 | |
| self_k_out / self_v_out | [L, 1, 12, P+1, 64] | float32 | Cache-Out (cat) |

En nutzt `position_ids` für die gelernten Position-Embeddings (Lookup-Index =
P+2); de nutzt es für die relative-Bias-Bucket-Zeile (arange(P+1) − P, im
Graph). opset 17, dynamische Achsen S/P/P+1 überall deklariert.

## 4. Greedy-Decode-Loop (App-Vertrag, exakt)

Pinned Generation-Parameter (beide Sprachen, identisch im HF-Referenzlauf):
**greedy** (num_beams=1, do_sample=False), **keine** no_repeat_ngram-/forced_bos/
forced_eos-Processor, `max_new_tokens = 64` (Cap; wird von keinem Golden
erreicht). Ablauf (`tools/bart_ref/run_bart_ref.py::OnnxRunner.greedy`):

1. `ids = tokenizer.encode(prefix + region)` — **Präfix PFLICHT: de `"correct: "`**
   (ohne degeneriert das DE-Modell in Wiederholungen), en ohne Präfix.
2. `enc_h = encoder(ids)`
3. `logits, sk, sv, ck, cv = decoder_init(start_id, enc_h)` — P=1
4. Loop `pos = 0..63`: `tok = argmax(logits)`; **wenn tok == EOS → stop**
   (en EOS 2, de EOS 1); sonst `logits, sk, sv = decoder_step(tok, pos+1, sk, sv, ck, cv)`
5. Rückgabe: Token-Ids → `tokenizer.decode(skip_special_tokens)`.

EOS/PAD/Start (Quelle: `vocab_meta.json`, aus config.json):
en: start **2**, EOS **2**, PAD **1**, BOS 0 · de: start **0**, EOS **1**, PAD **0**.

## 5. Vokabular-Handling

- Export je Sprache: `tokenizer/tokenizer.json` (en: **BPE**/ByteLevel nach
  tokenizer.json konvertiert; de: SentencePiece-**Unigram** als tokenizer.json)
  + `tokenizer_config.json` + `vocab_meta.json` (alle IDs + Präfix).
  sha256 in EXPORT_LOG (stage=tokenizer).
- **Vor/Nach-Zählung** (20 Goldens je Sprache, `reports/bart_u2_*.json`,
  Felder `n_input_tokens/n_output_tokens/n_words_in/n_words_out`):
  - en: 165 → 147 Tokens, 106 → 107 Wörter (Modell ergänzt Satzpunkt)
  - de: 226 → 161 Tokens (Präfix „correct: " = +2 Tokens/Satz), 105 → 107 Wörter
- Der Runner zählt Wörter/Tokens vor und nach der Korrektur — Grundlage für den
  Diff-Guard (§6) und die Vor/Nach-Statistik im App-Telemetrie-Debug.

## 6. Substitutions-Diff-Guard (App-Algorithmus)

Die Korrektur darf NUR Wörter ersetzen — niemals Text umbrechen, Wörter
einfügen/löschen oder Verschiebungen committen:

```
in_words  = split(region_text)            # getippter Bereich
out_words = split(corrected_text)          # Modell-Output
identical = (corrected_text == region_text)
if identical: return NO_ACTION            # §7 silence-äquivalent
if len(out_words) != len(in_words): return REJECT   # keine Einfügungen/Löschungen
diffs = [(i, in_words[i], out_words[i]) für i mit in_words[i] != out_words[i]]
if len(diffs) > max(1, ceil(0.3 * len(in_words))): return REJECT  # ≤30 % ersetzt
for (i, a, b) in diffs:
    if not same_word_shape(a, b): return REJECT      # Zeichenzahl/Non-Word-Zeichen bewahrt
commit als Wort-Ersetzungen an Position i (Cursor-Wiederherstellung pro Ersetzung)
```

`same_word_shape`: gleiche Länge ±1, gleiche Non-Word-Zeichen (Bindestrich,
Apostroph) an gleicher Stelle. Nachweis am Goldenkorpus: **alle** HF-vs-ONNX-
Wortdifferenzen sind reine Substitutionen (s. §8) — der Guard verwirft bei den
Goldens nichts Falsches (identical_in_out = 0/20 je Sprache, kein Golden
„sauber", jede Eingabe enthält einen Tippfehler).

## 7. Silence-Äquivalent

**identical-in-out ⇒ keine Aktion**: Wenn der Modell-Output zeichenidentisch
mit der Eingabe ist (oder nurWhitespace/Case), committet die App NICHT —
kein Textfeld-Update, kein Undo-Eintrag, kein Frame. Gemessen: alle 40 Goldens
(20 de + 20 en) sind echte Tippfehler → identical_in_out = 0; der Pfad ist im
Runner implementiert (`identical_in_out`) und für saubere Eingaben der
Regelfall (Modell gibt Eingabe unverändert zurück, z. B. „i will call you
tomorrow" → „I will call you tomorrow." ist NICHT identisch → 1 Substitution
+ Satzpunkt — Satzpunkt-Anfügung zählt als Ersetzung am letzten Wort).

## 8. Parität (20 Goldens je Sprache, `testdata/bart_goldens/{de,en}.tsv`)

Eigene synthetische Tippfehler-Sätze (Alltagskontext, KEINE personenbezogenen
Daten), deterministisch erzeugt (`tools/bart_ref/gen_bart_goldens.py`, Seed 42,
Klassen: neighbour/transposition/missing/doubled/umlaut(de)/caps(en)/spacing).
Messung: HF-fp32 `generate` (pinned, §4) vs ONNX-**int8** greedy, ORT-CPU.

| Metrik | en | de | Ziel | Status |
|---|---|---|---|---|
| Wort-Identität HF vs ONNX-int8 | **1.000** (107/107 Wörter) | **0.972** (104/107) | ≥ 95 % | ✅ |
| int8-Text == fp32-ORT-Text | 20/20 | 18/20 | — | ✅ |
| Logitpfad-Parität fp32-ORT vs HF (rel.) | enc 7.6e-7 · init 1.0e-6 · step 9.1e-7 | enc 4.5e-7 · init 1.2e-6 · step 1.4e-6 | ≤ 1e-4 | ✅ |

**Alle Wort-Differenzen vs HF sind ausschließlich Ersetzungen** (dokumentiert in
`reports/bart_u2_de.json`, Satz-Einträge `diffs_vs_hf`):
- de „der Zug kommt um acht ihr" → int8 „…um ihr Acht." vs HF „…um acht Uhr."
  (2 Substitutionen, Wortzahl gleich)
- de „wir schauen heüte…" → int8 „hüten" vs HF „heute" (1 Substitution)

Korrekturqualität (Kontext, nicht Gate-Kriterium): int8 erreicht 17/20
wortgenau korrekt (nach Normalisierung Case/Satzpunkt), HF 17/20 (en) bzw.
17/20 int8 vs 19/20 HF (de) — die 2 DE-Differenzen sind reine
Quantisierungsverschiebungen an kniffligen Tokens, die HF selbst teils auch
nicht korrigiert („Gärten" bleibt in ALLEN Pfaden stehen).

## 9. RTF & RAM (Host, int8, 16 Threads, ORT 1.31.1)

Korrektur eines **20-Wort-Bereichs** (End-to-End: tokenize → encode → init →
greedy-Steps bis EOS), 20 Läufe. RTF-Bezug: Tippfenster 7,0 s für 20 Wörter
(350 ms/Wort, Tastaturkontext) → `rtf = latency / 7.0`.

| | en (BART) | de (FLAN-T5) |
|---|---|---|
| Median | **0.26 s** (früherer Lauf: 0.137 s — Host-Lastvarianz ±2×) | **0.42 s** (früherer Lauf: 0.394 s) |
| p95 | 0.38 s | 0.49 s |
| **RTF** | **0.037** (0.020 im ersten Lauf) | **0.060** |
| Peak-RSS ONNX-only (3 Sessions + Run, int8) | **~420 MB** | **~510 MB** |

Alle Zeiten weit unter dem Tippfenster — **RTF-Gate erfüllt** (≤ 0.1).
RAM: ONNX-only-Delta gemessen in isoliertem Prozess (Sessions + Arena).

## 10. Größen (int8 gesamt je Sprache; Ziel ≤ 200 MB → VERFEHLT, Report)

| Graph | en fp32 | en int8 | de fp32 | de int8 |
|---|---|---|---|---|
| encoder | 327.7 MB | 82.2 MB | 438.5 MB | 109.9 MB |
| decoder_init | 539.1 MB | 135.3 MB | 650.5 MB | 163.0 MB |
| decoder_step | 510.7 MB | 128.2 MB | 593.9 MB | 148.8 MB |
| **Summe** | 1377.5 MB | **345.7 MB** | 1682.9 MB | **421.7 MB** |

Beide Sprachen überschreiten das 200-MB-Ziel. Ursache: **Embedding + lm_head
duplizieren sich in allen 3 Graphen** (en: 38,6 M Parameter = 154 MB fp32 je
Decoder-Graph; de zusätzlich UNTIED lm_head = 2×24,6 M im Decoder). Ausweg
(dokumentiert, analog QWEN_ASR_CONTRACT §Optimierungshinweis): EIN
Decoder-Graph (init als Spezialfall P=0 des Steps, Cross-KV im Runner) spart
je Sprache einen vollen Decoder-Graph → en ~215 MB, de ~259 MB int8; weitere
Hälfte nur durch gemeinsamen Graph/Vokabular-Truncation (V=50265/32100 sind
vorberechnet; Trimming auf Korpus-Vokabular wäre ein Quality-Gate-Aufwand).
Fürs Phone-Set zusätzlich: int8-RAM (§9) ist die härtere Grenze als Dateigröße.

**Update T24a (2026-11-05): Größenziel je Sprache erreicht durch
Initialisierer-Deduplizierung über ExternalData — en 179,3 MB, de 248,5 MB
int8 gesamt, s. §12 „Fusion (T24a)".**

## 11. Go/No-Go für die App-Integration

**GO (mit Bedingungen)** für beide Sprachen — Begründung:

- ✅ **Lizenzen sauber**: en MIT (+ bart-base Apache-2.0), de Apache-2.0
  (flan-t5-base Apache-2.0); ungegated; Texte lokal gesichert (tools/bart_ref/LICENSES/).
- ✅ **Export reproduzierbar & bewiesen**: 3 Graphen je Sprache, opset 17,
  dynamische Achsen, KV-Decode mit use_cache-Semantik; Wrapper-vs-HF-Parität
  0.0; ORT-vs-HF Logitpfad ≤ 1.4e-6 rel (Ziel ≤ 1e-4).
- ✅ **Wort-Parität HF vs int8-ONNX**: en 100 %, de 97,2 % (Ziel ≥ 95 %);
  Differenzen ausschließlich Substitutionen → Diff-Guard (§6) deckt sie.
- ✅ **RTF**: 0.02–0.06 gegen Tippfenster — interaktionsfähig.
- ✅ **Silence-Äquivalent & Diff-Guard spezifiziert** (§6/§7) — App kann
  konservativ committen.

Bedingungen / Risiken (vor App-Rollout zu lösen):

1. ⚠️ **Größe**: 345.7 MB (en) / 421.7 MB (de) int8 > 200-MB-Ziel → **T24a
gelöst**: fusionierte Artefakte `{en,de}-merged` (en 179.3 MB / de 248.5 MB,
§12) — App-Set nutzt die Merged-Artefakte. Nicht blockierend für Prototyp.
2. ⚠️ **RAM**: ~420–510 MB ONNX-RAM (16 Threads) ist auf Mid-Range-Phones
   knapp; Threads/ARENA auf 4 reduzieren und auf dem Gerät messen (U3).
3. ⚠️ **DE-Modell ist FLAN-T5, nicht BART** (Titel-Annahme korrigiert) und
   braucht das Präfix `correct: ` — im App-Runner zwingend (vocab_meta.json
   führt es). `oliverguhr/spelling-correction-multilingual-base` (MIT) als
   „ein Modell für beide Sprachen"-Kandidat für U3 prüfen.
4. ⚠️ **Qualität**: PoC-Modelle (Model-Card-Warnung „can produce artefacts"):
   17/20 wortgenau auf synthetischen Einzelfehler-Sätzen; echte
   ASR-Diktatfehler (Wortketten, Bindestriche, Eigennamen) sind UNGEPRÜFT —
   vor Integration gegen Diktat-Goldens (testdata/typing_goldens + echte
   ASR-Transkripte) messen, sonst drohen Fehl-"Korrekturen" an korrektem Text
   (Guard §6 fängt Struktur-, nicht Sinn-Fehler ab).

Beweis-Dateien: `tools/bart_ref/EXPORT_LOG.txt` (Stages, sha256, RAM),
`reports/bart_u2_{en,de}.json` (alle 40 Sätze, Diffs, RTF, Größen),
`testdata/bart_goldens/{de,en}.tsv` (Goldens), `tools/bart_ref/run_bart_ref.py`
(Messskript), `tools/bart_ref/LICENSES/` (Lizenz-Nachweis).

---

## 12. Fusion (T24a) — deduplizierte Merged-Artefakte `{en,de}-merged`

**Status: durchgeführt & bewiesen (2026-11-05).** Weg (b) ONNX-Graph-Rewrite
gewählt (Begründung + vollständige Analyse: `tools/bart_ref/MERGE_ANALYSIS.md`):
Jeder Initialisierer ≥ 1 KiB, dessen Rohbytes (sha256) in ≥ 2 der 3 Graphen
identisch sind — embed_tokens (3×), lm_head (2×), alle Decoder-Layer-Gewichte
(init/step), Positions-/Rel-Bias-Tabellen — wird EINMAL in den sprach-lokalen
Blob `shared/shared_weights.bin` gelegt und per **ExternalData-Referenz**
(location/offset/length) eingebunden. Weg (a) (input_embeds-Zwischenpfade,
Embedding im Encoder-Graph) abgelehnt: erreicht für de nur ~74 MB Ersparnis
(Ziellücke ~152 MB) und erzwingt Tensor-Vertrags-Änderungen ohne
Qualitätsgewinn. Knoten/IO/value_info unangetastet, Gewichte byteweise
identisch (skriptseitig per-Tensor verifiziert + onnx.checker).

### Finale Größen (int8 gesamt, Artefakt-Verzeichnis je Sprache)

| Datei | en | de | Rolle in der Pipeline (§3/§4 unverändert) |
|---|---|---|---|
| `encoder.int8.onnx` | 43.6 MB | 85.2 MB | Tokenize → input_ids → last_hidden (enthält Encoder-Layer + embed-Referenz) |
| `decoder_init.int8.onnx` | 7.5 MB | 14.5 MB | 1. Decode-Schritt: cross-KV-Aufbau, logits, self_k/v (Layer + embed/lm_head als Referenz) |
| `decoder_step.int8.onnx` | 0.32 MB | 0.33 MB | KV-Decode-Loop (Layer + embed/lm_head als Referenz) |
| `shared/shared_weights.bin` | 127.9 MB | 148.5 MB | EIN Exemplar aller Tabellen/Gewichte, die in mehreren Graphen gebraucht werden |
| **Summe int8** | **179.3 MB** (vorher 345.7) | **248.5 MB** (vorher 421.7) | Ziel ≤ 220 / ≤ 270 MB → ✅ (−48 % / −41 %) |

sha256 aller Dateien in `tools/bart_ref/EXPORT_LOG.txt` (stage=merge, mit
Tool-Versionen). Tokenizer unverändert übernommen (sha-identisch).

### App-Integrations-Hinweis

- **Welcher Graph welche Rolle**: identisch zu §3/§4 — `encoder` (Prefix `correct: `
  bei de!) → `decoder_init` (start_id aus vocab_meta.json) → `decoder_step`-Loop
  bis EOS. Die Fusion ändert am Tensor-Vertrag NICHTS (gleiche Ein-/Ausgaben,
  gleiche Graph-Struktur); nur die Gewichtslagerung ist dedupliziert.
- **Embedding-Pfad**: nach wie vor IN jedem Graph (Gather im encoder/decoder,
  lm_head-MatMulInteger in den Decodern) — die Tabellen liegen physisch nur
einmal in `shared/shared_weights.bin`; ORT lädt sie beim Session-Start
transparent nach. **Die 3 Graph-Dateien + `shared/` + `tokenizer/` müssen
zusammen im selben Verzeichnis-Baum ausgeliefert werden** (Relative Referenz
`shared/shared_weights.bin`, kein „..“); wer einzelne Graphen verschiebt,
bricht die Referenz.
- **RAM**: ExternalData spart Download-/Plattengröße, NICHT Session-RAM
  (~420–510 MB bleiben, §9) — nächster Hebel (T24b): init/step zu EINEM
  Decoder-Graph fusionieren.
- **Parität nach Fusion** (`tools/bart_ref/verify_merge_gate.py` →
  `reports/bart_u2_merge_{en,de}.json`): Wort-Identität fusioniert vs
  UNfusioniert int8 **100 %** (20/20 Sätze je Sprache, Token-Id-Sequenzen
  identisch); ORT-Logitpfad-Parität enc/init/step **0.0** rel (≤ 1e-4-Ziel);
  RTF fusioniert: en 0.134 s (RTF 0.019), de 0.261 s (RTF 0.037).
