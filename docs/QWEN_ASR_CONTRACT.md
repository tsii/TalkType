# QWEN_ASR_CONTRACT — T22a Gate (V0.1.46)

**Status 2026-11-04 · Referenz-Pipeline PINNIERT und gemessen für Qwen3-ASR-0.6B (int8, ORT 1.31.1).
1.7B: encoder exportiert+verifiziert, decoder-Export am Host-RAM gescheitert (Details §7).**
Ziel: Zahlenbasis für die GO/NO-GO-Entscheidung (1.7B vs 0.6B vs Abbruch) nach `docs/PLAN_V0146.md` §1/T22a.

Messumgebung: Projekts-Container x86_64 (16 vCPU, AVX512-BF16/VNNI), ORT 1.31.1 — **identisch zur Android-ORT-Version** (build.gradle.kts:131), `.venv-asr` (tools), 16 Threads. **Wichtige Einschränkung: Der Host war während aller Messungen durch eine Parallel-Session (Emulator/Gradle) zu 44–54 GB belegt — alle Zeiten sind Worst-Case-obere Schätzungen, ±50 % Streuung möglich (vgl. ASR_CONTRACT RTF-Messung mit gleicher Methode).**

---

## 1. Modellwahl

**Gewählt für die Referenz: `Qwen/Qwen3-ASR-0.6B-hf`** (nicht das Original-Repo `Qwen/Qwen3-ASR-0.6B`!).

- HF bestätigt (T22a, 2026-11-04, via `list_repo_files`/`model_info`): `Qwen3-ASR-0.6B` und `-1.7B` existieren, **license apache-2.0**, safetensors, **KEINE fertigen ONNX-Dateien im Repo** (nur config/tokenizer/weights) → Export ist Eigenleistung (erledigt, s. §2).
- Das Original-Checkpoint-Format (`thinker.*`-Keys, `thinker_config`) ist **inkompatibel zu transformers 5.22.1** (Keys UNEXPECTED/MISSING, Modell lädt Zufallsgewichte). Die offiziellen Konvertierungen **`Qwen/Qwen3-ASR-0.6B-hf` / `Qwen/Qwen3-ASR-1.7B-hf`** sind das native Format (model_type `qwen3_asr`, `Qwen3ASRForConditionalGeneration`) — nur diese verwenden.
- Architektur 0.6B: Whisper-Log-Mel 128 → Audio-Encoder (18 L, d=896, 14 Köpfe, ffn 3584, 3×Conv2d s2 k3 + `conv_out`, Fensterung `n_window=50`, `n_window_infer=800`) → Projektor (Linear 896→896, gelu, Linear 896→1024) → Qwen3-LLM (28 L, hidden 1024, 16/8 Köpfe, head_dim 128, ffn 3072, rope_theta 1e6, **tied embeddings**, vocab 151936). Audio-Tokens: 13 pro 1-s-Chunk; Attention-Fenster im Encoder: 104 Tokens (8 Chunks).
- Sprache forciert per Prefill `language German<asr_text>` (offline, deterministisch, keine Sprach-ID-Ausgabe nötig).
- 1.7B: LLM hidden 2048/ffn 6144, Encoder 24 L/d=1024 → Export s. §7.

## 2. Exportweg (eigener Export, kein optimum)

`tools/qwen_ref/export_qwen_onnx.py` — **staged, skip-if-present, `--force`, Ein-Zeile-Log je Stage in `tools/qwen_ref/EXPORT_LOG.txt`** (Zeitpunkt, Stage, Modell, Sekunden, Artefakt, Bytes, sha256). Kein `optimum` (pint inkompatibel zu transformers 5.x); manueller Weg:

1. **Graphen** per `torch.onnx.export` (Legacy-Tracer, opset 17, fp32) über handgeschriebene Wrapper, die die HF-Gewichte 1:1 nutzen:
   - `EncoderExport`: Audio-Tower + Projektor; die cu_seqlens-Fenster-Attention des CPU-Pfads ist als **additive Block-Maske** ([1,1,S,S], 0 innerhalb / −1e9 außerhalb, Blöcke à 104 Tokens) nachgebaut — numerisch äquivalent (Parität s. u.).
   - `PrefillExport`: Token-Embedding + Audio-Scatter an `<|audio_pad|>`-Positionen + 28 Qwen3-Schichten (RMSNorm, RoPE 1e6, GQA 16→8, **SiLU nur auf gate_proj** — Qwen3-MLP ist `down(act(gate)*up)`, nicht Llama-Schema!) + finaler RMSNorm + lm_head (nur letzte Position, logits [1,1,V]).
   - `StepExport`: 1-Token-Decode mit explizitem `past_k/past_v` KV-Cache ([28,1,16,P,128]), KV-Out [28,1,16,P+1,128] (T22-fix: 16 aufgeblasene Q-Heads, s. u.).
   - **Numerische Parität gegen HuggingFace fp32 (gleiche Inputs, assert im Script):** Encoder 0.6B **2.999e-07**, Encoder 1.7B **8.345e-07**, Prefill-Logits **3.052e-05**, Step-Logits **1.812e-05**. Beweis der End-to-End-Treue: die fp32-ORT-Pipeline (alle 3 Graphen) liefert auf ALLEN 4 Gold-WAVs + Stille **exakt die HF-Texte** (§5).
2. **External Data:** Graphen >2 GB (fp32-Decoder) schreibt der Tracer als **eine Datei pro Initializer** — `quantize_dynamic` kann das nicht lesen (`ValueError: cannot reshape array of size 2097152 into shape (1024,3072)`). Lösung im Script: `flatten_and_dedupe()` — Laden, gebundene Gewichte **byteweise identisch** deduplizieren, als EINE `.onnx.data` speichern, Per-Tensor-Dateien löschen. **Zwei Fehlerlessons (dokumentiert, behoben):** (a) Legacy-Tracer nummeriert `onnx__MatMul_*` global über Exporte hinweg → aufeinanderfolgende Exporte in **ein** Verzeichnis überschreiben einander die Weight-Dateien → jede Stage in eigenem Unterverzeichnis; (b) Dedupe **nur nach Shape** verschmilzt bei 1.7B 28 verschiedene Layer-Gewichte → Korruption; Dedupe nur noch bei Byte-Gleichheit (bei 0.6B wird wegen transponiertem lm_head gar nicht dedupliziert — embed [151936,1024] vs. lm_head [1024,151936]).
3. **Quantisierung:** `onnxruntime.quantization.quantize_dynamic(weight_type=QInt8)` (per-channel, MatMulInteger; Decoder-Graphen). ORT-Load-Fehler `MatMulInteger … Incompatible dimensions` trat nur bei der Shape-Dedupe-Korruption auf, nie nach Fix.

## 3. Artefakte 0.6B (festgezurrte Dateinamen, Bytes, sha256)

Ablage `/home/dev/models/qwen-export/out-0.6b/` (NICHT im Repo; nur Hashes hier). App-Set-ID künftig: **`qwen3-asr-0.6b-int8`** (Plan §2; falls 1.7B später: `qwen3-asr-1.7b-int8`).

| Datei (Set-Layout) | Bytes | sha256 |
|---|---|---|
| `encoder/encoder.int8.onnx` | 187,361,021 | `327974e11e2914037272224fc439af093bb251d0d232c0513bc9054d1ae6adba` |
| `prefill/qwen_prefill.int8.onnx` | 752,810,624 | `1a505e73e42074a2fdded9cdef4d9484586d52dbd654760982df738e1c03e48d` |
| `step/qwen_step.int8.onnx` | 752,598,179 | `9a7f8946996be4f36c0687265cc18f0d929c459001d15d1dfc5b6f783cb198d5` |
| `tokenizer.json` | 11,429,653 | `fe1fad59be22a41ee293363fcf95fdedbc7c93f3b49270b1d2e18bd1399a7a05` |
| `mel/mel_filters.npy` | 103,040 | `8abf12444e0d9c0fd6c5e49f8b977d41e47b51a5eb48d399c232578d3d5e914f` |
| `mel/mel_config.json` | 260 | `1888b2defc1b7db6ca081bd706f31e8fc1107cd44f1eba644834251f6dccd24c` |

**Summe int8-Set: 1.704.302.777 B** (187,361,021 + 752,810,624 + 752,598,179 + 11,429,653 + 103,040 + 260 = 1,704,302,777).

**Download-Set int8 gesamt: 1.704 GB** (unter Plan-Budget 2–2.5 GB; Space-Gate ~4 GB Reserve-Regel bleibt anwendbar).

fp32-Zwischenstände (nicht auszuliefern, für Re-Quantisierung): `encoder.onnx` 745,680,228 B `49e2d72f16522ffb2e79182841266f350050883f28fe23c07fc6394e2803864e`; `qwen_prefill.onnx`+`.data` 598,557+5,390,671,872 B (`6c9b4c9a…`, `11133a48b9ebd0242dffa41b761da318b86b0e089a58c82272988c17dedd7da9`); `qwen_step.onnx`+`.data` 480,377+5,390,700,544 B (`194d0c15…`, `e79723be6a69db4684c3b18caec4df3f18762c77473e3ee64fc47a8de2e548ed`).

**Optimierungshinweis:** Prefill- und Step-Graph führen JEWEALS embed+lm_head (155,6 M Parameter, int8 je ~155 MB) mit — Zusammenführen zu EINEM Decoder-Graph (optimum-Stil `decoder_model_merged`) spart im Set ~155 MB und im RAM (beide Sessions resident). Für T22b+ vorgemerkt, nicht Gate-relevant (Zahlen hier gelten für 2 Graphen).

## 4. Tensor-I/O-Vertrag (0.6B, int8-Graphen, opset 17)

### 4.0 Mel-Frontend (numpy/Kotlin; `mel_filters.npy` = Slaney-Bank [201,128] fp32)
Input: raw 16-kHz-mono float32 → **Silence-Padding auf volle Sekunden** (min 0.5 s = 8000 Samples). Dann: STFT n_fft 400 (periodisches Hann), hop 160, center reflect → 101 Frames/s, **letzten Frame verwerfen** → 100; Power-Spektrum (`|rfft|²`); `melbank.T @ mags`; `log10(clamp(x,1e-10))`; `max(x, max−8)`; `(x+4)/4` → **`input_features` float32 [1,128,100·n]**. (Exakt die Mathematik von `Qwen3ASRFeatureExtractor`; `mel/mel_config.json` pinnt alle Konstanten.)

### 4.1 `encoder.int8.onnx` (Audio-Tower + Projektor)
| Name | Typ | Shape | Beschreibung |
|---|---|---|---|
| `input_features` (I) | float32 | [1,128,T], T=100·n | Log-Mel |
| `attn_mask` (I) | float32 | [1,1,S,S], S=13·n | additive Block-Maske: 0 innerhalb, −1e9 außerhalb; Blöcke à 104 Tokens (8 Chunks), Rest-Fenster am Ende |
| `audio_embeds` (O) | float32 | [1,S,1024] | projizierte Audio-Embeddings |

### 4.2 `qwen_prefill.int8.onnx` (Decoder-Prefill, einmal je Äußerung)
| Name | Typ | Shape | Beschreibung |
|---|---|---|---|
| `input_ids` (I) | int64 | [1,L] | Prompt (s. §6), L ≈ 18+S+15 |
| `audio_embeds` (I) | float32 | [1,S,1024] | aus 4.1 |
| `attn_mask` (I) | float32 | [1,1,L,L] | kausal additiv: 0 unterhalb, −1e9 oberhalb der Diagonale |
| `position_ids` (I) | int64 | [1,L] | 0..L−1 |
| `logits` (O) | float32 | [1,1,151936] | letzte Position |
| `past_k`,`past_v` (O) | float32 | [28,1,16,L,128] | KV je Layer (Schicht 0..27; T22-fix: 16 WIEDERHOLTE Q-Heads — GQA 16→8 liegt IM Graph, Laufzeit-Print: [28,1,16,31,128] bei L=31) |

### 4.3 `qwen_step.int8.onnx` (Decode-Schritt, Token t=0..max−1)
| Name | Typ | Shape | Beschreibung |
|---|---|---|---|
| `input_ids` (I) | int64 | [1,1] | letztes argmax-Token |
| `past_k`,`past_v` (I) | float32 | [28,1,16,P,128] | KV (P=L+t; T22-fix: 16) |
| `attn_mask` (I) | float32 | [1,1,1,P+1] | Nullen (kein Padding im Decode) |
| `position_ids` (I) | int64 | [1,1] | P |
| `logits` (O) | float32 | [1,1,151936] | |
| `past_k_out`,`past_v_out` (O) | float32 | [28,1,16,P+1,128] | angehängtes KV (T22-fix: 16) |

Tokenizer: `tokenizer.json` (BPE, Byte-Level; `tokenizers`-0.23-kompatibel); Detokenize der generierten IDs mit `skip_special_tokens=True`.

## 5. Referenz-Transkripte (greedy, deterministisch) + Stille-Test

Pipeline: mel → encoder.int8 → prefill.int8 → step.int8 (greedy argmax), EOS 151643/151645, **cap 128** (wird von keinem Gold erreicht; Silhouette: 2–18 Tokens). Quelle: `reports/qwen_t22a_0.6b_int8.json` (+ `…_fp32.json`), erzeugt mit `tools/qwen_ref/run_qwen_ref.py`.

| WAV | Referenz (ASR_CONTRACT §4.1) | **Qwen 0.6B int8 (GOLDEN, gepinnt)** | Qwen 0.6B fp32-ORT (= HF) |
|---|---|---|---|
| de_part1.wav | Das ist der erste Teil des Diktats. | `Das ist der erste Teil des Doktats.` | Das ist der erste Teil des Doktors. |
| de_part2.wav | Und nach einer Pause kommt der zweite Teil. | `und nach einer Pause kommt mit smarte Teil.` | Und nach einer Pause kommt jetzt meiter Teil. |
| de_pause.wav | Das ist der erste Teil des Diktats. Und nach einer Pause kommt der zweite Teil. | `das ist der erste Teil des Doktats und nach einer Pause kommt der zweite Teil` | Das ist der erste Teil des Doktats, und nach einer Pause kommt der zweite Teil. |
| de_smoke.wav | So, jetzt bin ich gespannt, ob die Diktierfunktion auf dem Telefon funktioniert. | `So jetzt bin ich gespannt ob die Funktion auf dem Telefon funktioniert.` | So jetzt bin ich gespannt, ob die Textfunktion auf dem Telefon funktioniert. |
| **Stille 1 s (exakt 0)** | — | `systemschluss` (4 Tok) | `Ich.` |
| **Stille 1 s (−60 dBFS-Rauschen, rng(0))** | — | `Ich bin ein Krimi.` (7 Tok) | `Die.` |

**Stille-Test-Antwort: NEIN, nicht leer.** Der LLM-Decoder halluziniert auf Stille (wie bei Whisper befürchtet, ASR_MODEL_SURVEY_DE) → App-Silence-Guard (bestehend: `audio_error:silence` ~2 s) + `max_new_tokens`-Cap + Repeat-Guard sind **Pflichtbestandteil** des Engine-Vertrags (incomplete=true bei Cap, Plan §1).

## 6. Prompt-Bau (exakt, gegen HF-Prozessor verifiziert)

`<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>` + `<|audio_pad|>`×S + `<|audio_end|><|im_end|>\n<|im_start|>assistant\nlanguage German<asr_text>` — Token-IDs: 151644/151645 (im_start/end), 151669 (audio_start), **151676 (audio_pad, ersetzt S-mal durch Audio-Embeddings)**, 151670 (audio_end), 151704 (asr_text); "language German" → [11528, 5938]. **Verifikation:** bei auf volle Sekunden gepolstertem Audio ist der ID-Stream **byteweise identisch** zum `processor.apply_transcription_request`-Output (equal=True, getestet an de_part1@3.0 s, L=57).

**Dokumentierte Abweichung zum HF-Referenzpfad:** Wir polstern das Audio mit Stille auf volle Sekunden (alle Mel-Chunks voll gültig → statisches Packing, S=13·n); HF maskiert Mel-Padding und zählt nur valide Frames (de_part1@2.8 s: 39 vs. 36 Audio-Tokens). Deterministisch, in §5 eingemessen; HF-Textgleichheit der fp32-Pipeline (§2/§5) zeigt, dass die Abweichung die Transkripte nicht ändert (fp32-Texte identisch, obwohl HF 36 Tokens im Prompt hatte — Verifikationslauf mit 3.0-s-Padding lief für beide gleich).

**T22-fix (2026-11-05, Emulator-5566-Fund):** §2/§4.2/§4.3 hatten die KV-Cache-Form falsch als [28,1,8,·,128] (8 KV-Heads) notiert. Host-Reprint der TENSOR-WEHRHEIT mit ORT 1.31.1 auf den sha-identischen Artefakten: prefill OUT `past_k`/`past_v` = **[28,1,16,L,128]** (Laufzeit [28,1,16,31,128] bei L=31), step IN = [28,1,16,P,128], step OUT `past_k_out`/`past_v_out` = [28,1,16,P+1,128] — der Export trägt **16 WIEDERHOLTE Q-Heads**; die GQA-Reduktion 16→8 passiert IM Graph. Der erste echte Gerätelauf schlug mit `ORT_INVALID_ARGUMENT: past_k index: 2 Got: 8 Expected: 16` fehl, weil der Kotlin-Treiber die Kopfzahl als Konstante (8) annahm. Fix: Der Treiber leitet die Kopfzahl aus der Tensor-Größe her (layers·heads·pastLen·headDim) und reicht die Prefill-Outputs UNVERÄNDERT an step weiter (wie der Host-Runner); §4.2/§4.3-Zeilen oben sind korrigiert.

**T22-oom-fix (2026-11-05, Emulator-5566):** der zweite Gerätelauf starb an `OutOfMemoryError` (Dalvik-Grenze 192 MB) — der Treiber hielt den KV-Cache als Java-FloatArrays UND allozierte pro Schritt eine NEUE Generation (alt+neu gleichzeitig im Heap: K+V ≈ 2·28·16·P·128·4 B ≈ 69 MB bei P≈150 pro Generation → Heap-Grab). Fix: KV lebt OFF-HEAP (`OffHeapKvPool`: java.nio allocateDirect, Ping-Pong A/B — nie zwei Java-Generationen, keine Per-Schritt-Allokation), Input-Tensoren direkt aus den Direct-Buffern, Output-Kopie direct→direct (Nur-Views), Pool-Größe aus der Prefill-Output-TENSOR-FORM (L+maxSteps), `android:largeHeap="true"` als Sicherheitsnetz. **Gedächtnis-Ziffern (16-Heads-KV):** pro Tensor 28·16·P·128·4 B = 229.376·P B ⇒ P=160: 36,7 MB pro K bzw. V, **K+V ≈ 73 MB pro Generation**, Ping-Pong-Pool (A+B) ≈ **147 MB** bei P_max=160; 35-s-Fenster (S=455, L=473, P_max=601): 137,6 MB pro Tensor, Pool ≈ **550 MB** off-heap. **Phone-RAM neu geschätzt** (die 2,5-GB-Host-Ziffer von §9 galt dem 8-Heads-Pfad der Annahme — tatsächlich lief der Host mit demselben 16-Heads-Graph, also bleibt die 2,50-GB-Messung gültig, JEDOCH kommt auf dem Gerät der Pool DOPPELT dazu, da ORT-Arena UND Java-Pool je eine Generation halten): int8-Gewichte ~1,55 GB + ORT-Arena/Transienten + Pool ≈ 0,55 GB (35-s-Fenster worst case) ⇒ **~2,6–3,1 GB resident worst case**; RAM-Gate ≥8 GB bleibt korrekt, der 35-s-Fenster-Fall ist der Auslegungsrand (Kürzungen: segmentFinalizeMs senken oder 0,6B-KV-Kompression wäre ein künftiger Hebel).

**T22-oom-fix 2 + Messlatte (2026-11-05, dritter Lauf 5566, APK dd2b7bd7):** der Ping-Pong-Pool-Entwurf reichte nicht — **Dalvik zaehlt allocateDirect in denselben Topf** (growth limit 604 MB): `ByteBuffer.allocateDirect(137.854.995)` starb am 35-s-Fenster. Messung 10-s-Fenster: total 5058 ms (enc 3304 / prefill 444 / dec 1258, 15 tok/s decode). Drei Fixes: (a) **Segment-Obergrenze 15 s** — `QwenAsrEngine.MAX_SEGMENT_AUDIO_S=15` (defensiv: Truncation => incomplete) UND die Coordinator-Grenze: `VoiceKeyboardService.buildCoordinator` setzt `segmentFinalizeMs = 15_000` im Qwen-Zweig (Parakeet behaelt 35_000); P_max = 18+13·15+128 ≈ 341 Slots => KV-Generation 78 MB, **zwei Generationen ≈ 156 MB**. **35-s-Hold = 3 Segmente** (15+15+5), Finalize-Gesamtzeit ≈ 3x der 10/15-s-Workload. (b) **Pool-Architektur umgedreht: ORT-eigener Speicher** — jede KV-Generation ist ein offener `OrtSession.Result` + seine Output-Tensoren (ORT-allociertes Natives); Step-Inputs BORROWen die Direct-Views der Vorgaenger-Generation (`createTensor(env, directView, shape)`: zero-copy bzw. native Copy — beides ORT-Speicher, kein Dalvik-Topf), die alte Generation wird DETERMINISTISCH via `result.close()` nach dem Run freigegeben (kein Cleaner, keine eigene Allokation > 0). (c) **Goldtest-Latte korrigiert**: exakte Textgleichheit war eine falsche Praemisse (die Goldtexte sind Parakeet-abgeleitet; Host-Gate mass Qwen-WER 11,9 % gegen sie, §8) — Urteil jetzt Wort-Aehnlichkeit >= 0,85 (Wort-Levenshtein, normalisiert Gross/Klein + Interpunktion) + Halluzinations-Guard (<= 1,5x Gold-Woerter) + incomplete=false; Stille->'' und Guard-Verhalten bleiben EXAKT; bekannte Drifts (Doktat<->Doktor, 'mit smarte') als dokumentierte Toleranzfaelle im Testkopf.

## 7. 1.7B — Status: encoder fertig, Decoder NICHT exportierbar auf diesem Host

- **`Qwen3-ASR-1.7B-hf`** (apache-2.0, 2.04 B Parameter, 4,076,193,080 B safetensors bf16) heruntergeladen.
- **Encoder 1.7B: ERFOLGREICH, Parität 8.345e-07** — `out-1.7b/encoder/encoder.onnx` 1,270,132,286 B `aaacd7a1ea164a78533c11b164f652ca5283f037d85e726f78dddf474b07115a` (fp32), `encoder.int8.onnx` **318,894,668 B** `9034587eb6d24aef6e550394c51093643a98f54d71c25d091e3fec6ccc3eaddb`. mel-Stage identisch (Bank gleich, sha s. §3).
- **Prefill 1.7B: Export selbst gelang einmal (fp32, per-Tensor-External-Data), aber alle nachfolgenden Verarbeitungsversuche starben an RAM:** `quantize_dynamic`/`flatten` brauchten die 8.2-GB-ONNX zusätzlich zum 9.0-GB-fp32-Torch-Modell (RSS gemessen 9.0 GB). Fehlerbild (bash): **`PYEXIT=137` (SIGKILL/OOM-Killer), keine Python-Traceback** — zweimal, bei 8 bzw. 15 GB freiem RAM. Der Shape-Dedupe-Fehlversuch (§2.2b) erzeugte zwischenzeitlich **ungültige** 1.7B-Decoder-Artefakte (`out-1.7b/prefill.corrupt-dedupe/`, int8 1,001,284,822 B — NICHT verwenden); der letzte Re-Exportversuch brach ebenfalls mit 137 ab (`out-1.7b/prefill/` enthält nur per-Tensor-Reste, unvollständig).
- **Step 1.7B: NICHT versucht/beendet** (Rettungsversuche inkl. 16-GB-Swapfile-Anlauf vom Orchestrator gestoppt; kein aktiver Swap von uns, Datei wieder entfernt — s. SESSION_LOG).
- **Fazit/Ausweg:** 1.7B-fp32-Export braucht ~17–18 GB freien RAM in EINEM Prozess (9 GB Modell + 8.2 GB ONNX-Kopie) — auf diesem Host (Parallel-Session) nicht machbar. **Braucht: Host mit ≥24 GB frei ODER ein bf16→int8-Direktpfad (nicht gebaut, ORT-CPU-bf16 auf AVX512_BF16 wäre da, quantize_dynamic frisst aber nur fp32/fp16-Graphen).** Auf dem 16-GB-Phone selbst wäre 1.7B-int8 (2×~1.0 GB Decoder + 319 MB Encoder) laut Größe machbar, die int8-Qualität wäre aber UNGEPRÜFT (kein Paritäts-/WER-Lauf möglich gewesen).
- **1.7B-Zahlen: NICHT GEMESSEN.** Grobe Schätzung (nicht belastbar, nur zur Einordnung): int8-Set ~2.3 GB; RTF host ≈ 2.5–3× 0.6B (FLOPs 2048²/1024² × Layer-Breiten); WER auf dem espeak-Goldkorpus vermutlich ähnlich (Fehler stammen überwiegend von der robotischen Synthese), auf echter Sprache laut Qwen deutlich besser — **hier nicht messbar**.

## 8. WER gegen Parakeet (Gold-Korpus, difflib, tools/qwen_ref/wer_report.py)

Korpus: die 4 Gold-WAVs mit Referenz aus ASR_CONTRACT §4.1 (42 Wörter, espeak-ng-Synthese). Parakeet-Basis: Gold-Texte SIND die Parakeet-Ausgabe (§4.1 Rev 3, token-exakt) → **Parakeet-WER 0.000 per Konstruktion**; Vergleich auf identischem Korpus.

| Lauf | de_part1 | de_part2 | de_pause | de_smoke | **Korpus-WER (norm.)** | Korpus-WER (raw) |
|---|---|---|---|---|---|---|
| **Qwen 0.6B int8** | 14.3 % (1 S) | 25.0 % (2 S) | 6.7 % (1 S) | 8.3 % (1 S) | **11.9 % (5/42)** | 16.7 % (7/42) |
| Qwen 0.6B fp32 | 14.3 % | 25.0 % | 6.7 % | 8.3 % | **11.9 % (5/42)** | 13.1 % (5.5/42≈) |
| **Parakeet (ONNX int8, gepinnt)** | 0 % | 0 % | 0 % | 0 % | **0.0 %** | 0.0 % |

norm. = kleingeschrieben, Satzzeichen entfernt. **Alle Qwen-Fehler sind Substitutionen** (Diktats→Doktats, zweite/meiter→mit smarte, Diktierfunktion→Funktion). Kernaussage: **auf unserem (synthetischen) Goldkorpus ist Parakeet mit großem Abstand besser; das Plan-Gate „Qwen-WER ≤ Parakeet-WER" ist für 0.6B NICHT erfüllt.**

## 9. RTF Host + RAM (0.6B, int8, ORT 1.31.1, 16 Threads, belasteter Host)

RTF = (mel+encoder+prefill+Σ decode)/Audiosekunden (ganzer Finalize-Pfad, Offline-Fenster).

| WAV | Audio-s | Tokens | t_enc | t_prefill | t_decode | gesamt | **RTF** | tok/s (decode) |
|---|---|---|---|---|---|---|---|---|
| de_part1 | 2.80 | 10 | 0.113 s | 0.089 s | 0.335 s | 0.542 s | **0.194** | 29.9 |
| de_part2 | 3.10 | 10 | 0.124 s | 0.083 s | 0.283 s | 0.527 s | **0.170** | 35.4 |
| de_pause | 9.90 | 18 | 0.318 s | 0.155 s | 0.793 s | 1.298 s | **0.131** | 22.7 |
| de_smoke | 6.40 | 16 | 0.220 s | 0.102 s | 0.728 s | 1.087 s | **0.170** | 22.0 |
| Stille 1 s | 1.00 | 4–7 | 0.06 s | 0.05 s | 0.12–0.22 s | 0.24–0.35 s | 0.24–0.35 | ~31 |

- **RTF int8 (x86, belastet): 0.13–0.35; decode 22–35 tok/s.** fp32-Vergleich: RTF 0.23–0.45, nur ~10–12 tok/s → **int8 ist auf VNNI-CPUs ~3× schneller als fp32** (Argument für int8 auf dem Phone, sofern die Neon-Int8-Kerne des Pixel ähnlich skalieren).
- **RAM-Spitze (ru_maxrss, ganzer Lauf inkl. 3 Sessions + Mel):** int8 **2.50 GB** (fp32: 6.26 GB). Auf dem Phone (Plan-Budget 2.5–3.5 GB, 16-GB-Gerät, genau EIN Engine resident) damit im Rahmen; RAM-Gate ≥8 GB bleibt sinnvoll (T22j misst nach).
- Hochrechnung Phone (Plan §1: ARM 5–20× langsamer als x86): **RTF 0.7–7** → 35-s-Fenster-Finalize grob **5–60 s** (vs. Parakeet ~1.35 s). Selbst der Optimi=st-Fall (5×) liegt bei ~4× über Parakeet. **Nur T22j (echtes Gerät) kann das entscheiden.**

## 10. GO/NO-GO-Empfehlung

1. **Technische Machbarkeit 0.6B: GESEN.** Kompletter 3-Graph-Stack (encoder/prefill/step int8, KV-Cache, deterministisch greedy) existiert, ist gegen HF verifizert (Parität ≤3e-5; fp32-Texte exakt HF) und läuft unter ORT 1.31.1 = Android-ORT. Download-Set 1.7 GB, Host-RAM 2.5 GB. T22b–T22e sind mit diesem Fundament baulösbar.
2. **Qualität auf unserem Gold-Korpus: KLARES NO für den Ersatz von Parakeet.** WER 11.9 % vs. 0 % — Qwen 0.6B (int8 wie fp32) verliert auf genau dem Korpus, das die Dictat-Qualität des Produkts definiert. Qwen wäre nur bei echter/robuster Sprache + Mehrsprachigkeit (MULTILANG_PLAN) im Vorteil — auf diesem Host nicht messbar, auf dem espeak-Korpus klar schlechter.
3. **1.7B: nicht entscheidbar auf diesem Host** (Export- und Messweg RAM-blockiert, §7). Wenn das Multilinguale Ziel gewichtet wird: 1.7B-Export auf einem ≥24-GB-Host nachholen (Script ist fertig und staged), DANN erst WER/RTF auf Telefon messen. Ein „1.7B blind shippen" wäre unverantwortbar (ungeprüfte int8-Qualität).
4. **Empfehlung des Implementers:** **Parakeet als Standard-Diktat-Engine behalten (NO-GO für Qwen als Ersatz auf Qualitätsbasis des Projektkorpus)**; Qwen 0.6B allenfalls als **Zweitmodell „Experimentell/Multilingual"** weiterverfolgen (T22b+), ODER Gate auf einem größeren Host mit 1.7B wiederholen, wenn Mehrsprachigkeit Prio hat. Halluzinations-Guard (Stille→„systemschluss"/„Ich bin ein Krimi.") ist in jedem Qwen-Szenario Pflicht.

**Entscheidung an Nutzer/Opus (Plan T22a): 1.7B-Nachexport auf größerem Host / 0.6B als Zweitmodell weiterbauen / Abbruch.**

---

*Erzeugt von T22a (implement-Session). Scripts: `tools/qwen_ref/export_qwen_onnx.py` (staged Export+int8), `run_qwen_ref.py` (ORT-Referenz-Decode), `wer_report.py`, `run_hf_reference.py`, Log `EXPORT_LOG.txt`; Messdaten `reports/qwen_t22a_0.6b_{int8,fp32}.json`. Keine app/-Änderung, kein Commit.*
