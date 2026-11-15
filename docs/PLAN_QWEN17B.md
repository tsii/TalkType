# PLAN: Qwen3-ASR **1.7B** auf dem Telefon — Machbarkeit + TDD-Plan

Stand 2026-11-15 · Basis: `versionCode 52`, ORT Android 1.31.1 (`app/build.gradle.kts:131`).
Status: **Analyse, kein Produktcode.** Kennzeichnung: **[M]** = gemessen/aus Dateien gelesen,
**[S]** = Schätzung (Rechenweg steht dabei), **[E]** = externe Angabe aus Modellwissen. In dieser Sitzung
gab es **keinen Netzzugriff** (`curl` ohne Antwort), deshalb sind [E]-Angaben vor der Umsetzung gegen die
genannte Quelle zu prüfen.

---

## A) Messungen und Schätzungen (getrennt)

### A.1 Was gemessen ist / was auf der Platte liegt [M]

| Fakt | Wert | Beleg |
|---|---|---|
| Parameter 0.6B (safetensors-Header gezählt) | 0,782 G: enc 2D 173,4 M · enc-conv 11,0 M · embed 155,6 M · LLM 2D 442,1 M | `Qwen3-ASR-0.6B-hf/*.safetensors` |
| Parameter 1.7B | 2,038 G: enc 2D 302,0 M · enc-conv 12,0 M · embed 311,2 M · LLM 2D 1412,4 M | `Qwen3-ASR-1.7B-hf/*.safetensors` |
| Architektur 1.7B | LLM 28 L, hidden 2048, ffn 6144, 16 Q-/8 KV-Köpfe, head_dim 128, tied embeddings; Encoder 24 L, d 1024, **output_dim 2048**, Downsample-Conv 480 Kanäle (wie 0.6B) | `config.json` |
| 0.6B-Set (int8, gefused) | 6 Dateien, 1,704 GB: encoder 187 MB · prefill 753 MB · step 753 MB · tokenizer 11,4 MB · mel 2 Dateien | `ModelManifest.QWEN` |
| **0.6B dupliziert die Gewichte**: prefill UND step tragen je LLM + embed + lm_head (int8) | 442 + 156 + 156 ≈ 753 MB je Graph | Rechnung passt byte-genau zur Dateigröße |
| 1.7B-Encoder int8 | 318.894.668 B, sha `9034587e…eaddb`, Parität fp32 8,3e-7 | `QWEN_ASR_CONTRACT.md §7`, `EXPORT_LOG.txt` |
| 1.7B-Prefill fp32 | `out-1.7b/prefill/`: Stub + 255 externe Tensoren, **alle 255 Referenzen vorhanden** (heute geprüft) → wohl ladbar, aber **nicht verifiziert, nicht quantisiert** | onnx-Check in dieser Sitzung |
| 1.7B-Prefill int8 in `prefill.corrupt-dedupe/` | 1,001 GB — **KAPUTT** (Dedupe nur nach Shape hat 28 Layer zusammengelegt) | Contract §2.2b/§7 |
| 1.7B-Step | **nicht exportiert** | Contract §7 |
| Export-Bedarf | ~17–18 GB freier RAM in einem Prozess; Host hat heute 9 GB frei (`free -g`) → **weiterhin blockiert** | Contract §7, `free` |
| 0.6B Host (x86, 16 Thr.) | RTF 0,13–0,35; Peak-RSS **2,50 GB** bei 1,70 GB Dateien (Faktor 1,47) | Contract §9 |
| 0.6B Emulator-Referenz | 10 s: enc 3,31 / pre 0,44 / dec 1,26 = 5,06 s; 35 s (3 Segmente): enc 13,8 / pre 1,7 / dec 12,0 = 40,4 s → **RTF 1,16** | PLAN_V0147 §20 |
| KV-Cache (Export mit 16 wiederholten Köpfen) | 28·16·P·128·4 B = 229.376·P B; 15-s-Deckel P_max ≈ 341 → 78 MB je Generation, 2 Generationen ≈ 156 MB | Contract §6 (T22-oom-fix 2) |
| Laufzeit-Einstellungen | CPU-EP, 4 intra / 1 inter Threads, `disable_prepacking=true`, MemPattern aus, Segmentdeckel 15 s | `QwenGraphs.kt:93-104`, `QwenAsrEngine.kt:196`, `VoiceKeyboardService.kt:1405` |
| **Fest verdrahtet** | `PROJECTOR_DIM = 1024` (1.7B braucht **2048**), `LOGITS 151936` | `QwenGraphs.kt:85-86` |
| Pixel-Messung des 0.6B | **fehlt bis heute** (Bedingung C3 aus PLAN_V0147 §20b ist offen) | PLAN_V0147 §20b |

### A.2 Speicher (Peak) [S]

**Gewichte int8, gleicher Exportweg wie bei 0.6B (prefill und step getrennt, jeweils mit allen Gewichten):**
- Prefill-Graph ≈ LLM 1412 + embed 311 + lm_head 311 = **≈ 2,03 GB**, Step-Graph ebenfalls ≈ 2,03 GB, Encoder 0,32 GB.
- Set ≈ 0,32 + 2·2,03 + 0,011 ≈ **4,4 GB Download**, und ebenso viel **resident**, weil beide Sessions geladen sind.

**Gewichte int8 mit EINEM Decoder-Graph (Prefill = Step mit leerem Past, oder gemeinsame Initializer):**
- 0,32 + 2,03 + 0,011 ≈ **2,36 GB** Set und resident.

**Weitere Posten:**
- KV-Cache: unabhängig von der Modellgröße (28 Layer · 16 Köpfe · 128 sind bei 0.6B und 1.7B gleich) → **≈ 0,16 GB** bei 15-s-Deckel. Zur Einordnung: Mit echtem GQA-KV (8 Köpfe) wären es 0,08 GB.
- Aktivationen: Logits des Prefill [1, L, 151936] fp32 bei L ≈ 18 + 13·15 = 213 → **≈ 0,13 GB**, sofern der Export volle Logits ausgibt. Encoder- und LLM-Zwischenwerte bei hidden 2048 → **≈ 0,1–0,2 GB**.
- ORT-Arena, Fragmentierung, Protobuf, App-Grundlast: beim 0.6B-Host zeigte sich der Faktor RSS/Dateien = 1,47; der Zuschlag liegt also bei ≈ 0,8 GB.

| Variante | Rechnung | **Peak-RSS geschätzt** |
|---|---|---|
| getrennte Graphen (wie 0.6B) | 4,4 GB · 1,25–1,47 | **5,5–6,5 GB** |
| ein Decoder-Graph | 2,36 GB · 1,25–1,47 + 0,16 KV | **3,1–3,6 GB** |
| Ladespitze (transient) | ORT liest jede `.onnx` zuerst als Puffer und kopiert dann die Initializer → +1 Graphgröße während `createSession` | +2,0 GB (lädt sequenziell, also nie mehr als ein Graph zugleich) |

**Android-Grenzen:**
- `android:largeHeap="true"` (`AndroidManifest.xml:35`) wirkt **nur** auf den Dalvik-Heap. Die ORT-Gewichte liegen nativ und unterliegen keinem Heap-Limit. Gefährlich ist nur, was Dalvik zählt, also `allocateDirect`; das ist seit T22-oom-fix 2 beseitigt.
- Die eigentliche Grenze ist der **lmkd** (Low-Memory-Killer, PSI-gesteuert, Auswahl nach `oom_score_adj`). Eine IME ist an die Vordergrund-App gebunden und hat damit hohe Priorität (adj ≈ 100–200 [E]). Ein Prozess mit 5–6,5 GB auf einem Gerät mit 16 GB [E: Google-Spezifikation Pixel 10 Pro XL] fällt aber bei Druck (Kamera, Browser) als **größter Verbraucher** auf. Zusätzlich gibt es ein per-App-Limit für den virtuellen Adressraum bzw. RLIMIT, das unkritisch ist (arm64).
- GrapheneOS verwendet **hardened_malloc** [E]. Große Allokationen laufen dort über eigene mmap-Regionen mit Guard-Pages. Das ist für den RSS unkritisch, kann aber die Ladezeit und die Fragmentierung beeinflussen → **auf dem Zielgerät messen**, nicht im Emulator.
- **Wechselwirkung mit 0.6B/Parakeet:** Regel „nur EIN Engine resident" (`VoiceKeyboardService.kt:1560`, Schließen vor Neuladen). Würden 0.6B (≈ 2,5 GB) und 1.7B (3,1–6,5 GB) gleichzeitig gehalten, käme man auf 5,6–9 GB → **verboten**, und der Plan setzt es auch nicht voraus. Kritisch ist das Umschalten: Die alte Engine muss **vollständig geschlossen sein, bevor** die erste 1.7B-Session erzeugt wird. Ein Test dafür folgt in Scheibe S6.

### A.3 Rechenzeit [S]

**Skalierungsfaktoren 0.6B → 1.7B je Phase** (aus den gezählten Parametern, nicht pauschal):
- Decode (pro Token speicherbandbreiten-bestimmt): gelesene Bytes (LLM 2D + lm_head) 598 M → 1723 M = **×2,88**.
- Prefill (rechenbestimmt, gleiches L): LLM 2D ×3,19, mit lm_head ×2,88 → **×2,9–3,2**.
- Encoder: Transformer-Anteil ×1,74. Die Downsample-Convolutions (480 Kanäle, fp32, `quantize_dynamic` quantisiert keine Conv) sind **in beiden Modellen gleich**. Im Emulator ist der Encoder mit ~14 GOPS gegenüber ~400 GOPS beim Prefill auffallend langsam, was auf conv-dominiert hindeutet → **×1,2–1,74**.

**Hochrechnung aus der Emulator-Referenz** (35 s, 0.6B RTF 1,16):
- enc 13,8·(1,2…1,74) = 16,6…24,0 s; pre 1,7·(2,9…3,2) = 4,9…5,4 s; dec 12,0·(2,88…3,4) = 34,6…40,8 s
- Summe 56–70 s auf 35 s → **RTF 1,6–2,0** (gegenüber 1,16). Mit dem pauschalen Faktor 2,8–3,4 aus dem Auftrag ergäbe sich RTF 3,2–3,9. **In der Referenzumgebung liegt das 1.7B damit klar über RTF 1.**

**Roofline für den Pixel (Obergrenze, optimistisch)** [S, Hardwaredaten E]:
- Tensor G5: 1×X4, 5×A725, 2×A520, LPDDR5X [E]. Nutzbare Bandbreite für einen CPU-Cluster ≈ 15–25 GB/s [S].
- Decode: 1,72 GB pro Token → 70–115 ms → **9–14 tok/s maximal**, mit ORT-MatMulInteger realistisch 5–9 tok/s. Diktat erzeugt ≈ 2,5 tok/s Audio (86 tok / 35 s [M]) → Decode-Anteil RTF ≈ **0,28–0,5**.
- Prefill: 213 Token · 2·1,72 G ≈ 0,73 TOP pro 15-s-Segment. 4 Threads (X4 + 3×A725) mit i8mm/sdot über MLAS, effektiv 0,5–0,9 TOPS → 0,8–1,5 s → RTF-Anteil **0,05–0,1**.
- Encoder: ≈ 8 GFLOP pro Audiosekunde (Transformer) plus Conv fp32 → RTF-Anteil **0,1–0,3**.
- **Summe Roofline: RTF ≈ 0,45–0,9.** Erfahrungsgemäß liegt ORT auf ARM bei dynamischer int8-Quantisierung 1,5–2× hinter der Roofline → **realistisch RTF ≈ 0,7–1,8.**

**Beschleuniger** (Details: `docs/GPU_ACCELERATION_ANALYSIS.md`, `NPU_LITERT_FEASIBILITY.md`):
- XNNPACK-EP: deckt QDQ/QLinear und fp32 ab, aber **nicht** `MatMulInteger`/`DynamicQuantizeMatMul` aus `quantize_dynamic` [E: ORT-Doku „XNNPACK EP supported ops"] → bringt praktisch nichts, die MLAS-ARM64-int8-Kerne sind ohnehin aktiv.
- NNAPI: seit Android 15 **deprecated** [E: developer.android.com/ndk/guides/neuralnetworks], keine dynamischen Shapes, NPU-Zugang auf Tensor privat → **nein**.
- GPU: kein produktiver Android-GPU-EP im Standard-AAR, QNN nur für Snapdragon → **nein**.
- Threads: 4 → 6 (alle großen Kerne) bringt höchstens ×1,2–1,4 [S]. Das Decode ist bandbreiten-gebunden, und es drohen Thermik und mehr Ruckeln der UI → nur als Messvariante prüfen.
- `disable_prepacking=true` **muss bleiben**, sonst kämen vorgepackte Gewichtskopien hinzu (≈ +2 GB). Das kostet Geschwindigkeit im GEMM [S].

**Antwort auf Frage 2:** RTF < 1 auf dem Pixel ist **möglich, aber nicht belegbar und nicht wahrscheinlich robust** (Bandbreite 0,7–1,8). Selbst das 0.6B ist auf dem Gerät noch nicht gemessen. Ehrlich ist daher:
- **kein Default**, kein Ersatz für Parakeet;
- Streaming ist ausgeschlossen: Bei der Decode-Rate würde jede Teilhypothese ein neues Prefill erfordern, und die CPU ist bereits ausgelastet.
- Einzig **opt-in als „Diktat-Set: langsam, genau"** mit 15-s-Segmenten (Finalize-Wartezeit ≈ RTF · Segmentlänge, also 7–27 s für 15 s Sprache). Das ist nur bei kurzen Äußerungen erträglich.
- Die Variante „**zweite Meinung**" (das letzte Segment nach dem Diktat mit 1.7B neu erkennen) wäre fachlich die ehrlichste Nutzung. Sie verlangt aber Audio-Retention und zwei Engine-Wechsel (Ladezeit 2–6 s für 2,4 GB von UFS [S]) unter der Ein-Engine-Regel → **V2, nicht jetzt**.

---

## B) Entscheidung

**Jetzt anbieten: NEIN.** Vorgehen: Bau als *inertes* Set (Manifest-URL leer, Karte gesperrt). Die Freischaltung erfolgt nur über das Mess-Gate (§B.2) und nur als **opt-in „experimentell, langsam"-Diktat-Set**.

Begründung:
1. **Kein valides Artefakt.** Prefill-int8 ist korrupt, Step fehlt, der Export ist auf diesem Host RAM-blockiert (9 GB frei, nötig sind ≈ 18 GB).
2. **Mit dem heutigen Exportweg** wären es 4,4 GB Download und 5,5–6,5 GB Peak in einer IME → lmkd-Risiko mitten im Diktat, unvereinbar mit „Diktat darf nie abbrechen". **Ein gemeinsamer Decoder-Graph ist Vorbedingung** (→ 2,4 GB / 3,1–3,6 GB).
3. **RTF:** Die Referenz liegt bei 1,6–2,0 (bzw. bis 3,9), der Pixel-Schätzbereich bei 0,7–1,8. Belegt ist nichts, das 0.6B ist auf dem Gerät noch nicht gemessen (C3 offen). Also gilt die Reihenfolge: **erst 0.6B auf dem Pixel messen**, dann daraus den Gerätefaktor für 1.7B ableiten.
4. **Qualität:** Der Gewinn durch 1.7B in int8 ist unbelegt. Das 0.6B verliert auf dem Goldkorpus gegen Parakeet (WER 11,9 % gegenüber 0 %, Contract §8).

### B.1 Betriebsart nach bestandenem Gate
- Zusätzliches ASR-Set `qwen3-asr-1.7b-int8` als **Opt-in** mit **Segmentdeckel 15 s** (bei p90-RTF > 0,8 nur 10 s). Parakeet bleibt Default und empfohlen.
- Gate-Kette auf dem Gerät: Gesamt-RAM ≥ 12 GiB, freier Speicher, `availMem` vor dem Laden. Schlägt die Prüfung fehl → **Fallback auf Parakeet mit sichtbarem Hinweis**, niemals Abbruch.

### B.2 Freischalt-Messvorschrift (Pixel 10 Pro XL, GrapheneOS, Android 16) — ALLE Punkte müssen bestehen

**Aufbau:**
- diag-Release-Build (arm64), Akku ≥ 50 %, nicht am Ladekabel, Flugmodus, Starttemperatur < 35 °C (`dumpsys battery`).
- Vorher 10 min Leerlauf, Bildschirm an.
- Korpus: 4 Gold-WAVs + **≥ 20 echte Sprachaufnahmen** (5 / 10 / 15 / 35 s, DE + EN) + 10× 5 s Stille.
- Jede Messung 3×, Auswertung Median und p90.

| # | Kriterium | Messweg | Schwelle |
|---|---|---|---|
| G1 | RTF je Segment | Debug-Felder `rtf`, `enc_ms/prefill_ms/decode_ms` | **Median ≤ 0,8, p90 ≤ 1,0** |
| G2 | Finalize-Latenz bei 10 s Sprache | Stopp → Text im Feld (Debug `finalize_ms`) | ≤ 8 s p90 |
| G3 | Peak-RSS | `/proc/<pid>/status` VmHWM nach dem Lauf + `dumpsys meminfo de.voicekeyboard` (TOTAL PSS) | VmHWM ≤ **4,0 GB**, PSS ≤ 3,8 GB |
| G4 | Ladezeit kalt | `engine_load_ms` | ≤ 10 s p90, UI-Thread nie blockiert |
| G5 | Kein ANR | `dumpsys dropbox --print data_app_anr`, `logcat` „ANR in de.voicekeyboard" | **0** über alle Läufe |
| G6 | Kein Kill | `logcat -b events` `am_kill`/`lmk_kill`, `logcat -s lowmemorykiller` mit PID | **0**, auch im Stresslauf G8 |
| G7 | Dauerlauf thermisch | 3 min kontinuierliches Diktat (12 Segmente) | RTF-Drift < 15 %, kein Throttling-Abbruch |
| G8 | Diktat bricht nie ab | Chrome (10 Tabs) + Kamera im Hintergrund, 10 Diktate | 10/10 liefern Text oder einen sauberen Parakeet-Fallback mit Hinweis |
| G9 | Wechsel | 10× Parakeet ↔ 1.7B, davon 5 während laufendem Diktat | Mitten im Diktat → `Deferred`; nach dem Entladen fällt VmRSS < 0,5 GB über die Grundlast; 0 Crashes |
| G10 | Stille | 10× 5 s Stille | 10× „" (Halluzinations-Guard) |
| G11 | Qualität | WER auf echter Sprache | ≤ WER 0.6B **und** nicht schlechter als Parakeet + 2 pp — sonst lohnt sich der Aufwand nicht |

Jeder Fehlschlag → Karte bleibt gesperrt (`BASE_URL_QWEN_17B = ""`). Das Protokoll kommt nach `docs/PERF_QWEN17B.md`.

---

## C) TDD-Scheiben (je: erst roter Test, dann Code; Akzeptanzkriterium)

| # | Scheibe | Inhalt | Akzeptanz (Tests grün) |
|---|---|---|---|
| **S0** | Export (Tooling, kein App-Code) | `tools/qwen_ref/export_qwen_onnx.py` um **gemeinsamen Decoder** erweitern (ein Graph, Past-Länge 0 für Prefill, oder Step-Graph mit L>1). Lauf auf einem Host mit ≥ 24 GB frei. int8 per Byte-Dedupe | Parität int8 ↔ HF fp32: Wortähnlichkeit ≥ 0,95 auf Gold + echter Sprache; Logit-Parität fp32 ≤ 1e-4; Set ≤ 2,5 GB; sha256 + Bytes in `QWEN_ASR_CONTRACT.md §7` und `MODEL_SHA256.txt` |
| **S1** | `ModelVariant.QWEN3_ASR_17B("qwen3-asr-1.7b-int8", …)` | neuer Eintrag, `Kind.ASR`; Release-Sichtbarkeit aus | `fromId("qwen3-asr-1.7b-int8") == QWEN3_ASR_17B`; DEFAULT bleibt `ONNX_CPU`; `textFromId` gibt null; alle exhaustiven `when` kompilieren; bestehende Variant-Tests unverändert grün |
| **S2** | Manifest `ModelManifest.QWEN_17B` + `BASE_URL_QWEN_17B = ""` | gepinnte Dateien (encoder, decoder, tokenizer, mel ×2) | jede sha256 = 64 Hex, size > 0, flache Namen; Summe = Wert aus S0; `forVariant`/`baseUrlFor` mappen korrekt; **bei leerer URL `SpaceNeed.Unsupported`** (inert) |
| **S3** | RAM-/Speicher-Gate | `QWEN17_MIN_TOTAL_RAM_BYTES = 12 GiB`; Platzbedarf = Σ Manifest + `SET_RESERVE_BYTES`; Laufzeit-Gate `availMem ≥ geschätzter Peak + 1 GB` vor `load()` | `canOfferSet`: 8-GB-Gerät → false, 16-GB-Gerät → true, wenig Platz → false; Laufzeit-Gate rot → `EngineFactory` liefert Parakeet + Fehlercode `ram_gate` (kein Abbruch) |
| **S4** | Setup-Karte mit ehrlicher Warnung | eigene Karte, gesperrt bis URL gesetzt; Text (EN/DE) z. B. „Very slow, experimental. ~2.4 GB download, needs ≥12 GB RAM. Not measured on your phone; dictation may take longer than speaking." Delete sichtbar, sobald Dateien READY sind (wie C2-Fix 0.6B) | `SetupModelChoiceTest`-Stil: gesperrt/RAM-gesperrt/READY-Zustände; Warntext vorhanden; 1.7B ist nie vorausgewählt |
| **S5** | Engine-Anbindung | `QwenGraphs` parametrisieren: `PROJECTOR_DIM` aus dem Session-Input-Shape (`audio_embeds[..,2048]`) statt Konstante 1024; Dateinamen pro Set; Ein- oder Zweigraph-Modus; `EngineFactory`-Zweig `QWEN3_ASR_17B` | JVM: Fake-Graphs mit dim 2048 → korrekte Tensorgrößen; dim-Mismatch → sauberer `model:`-Fehler statt Crash; androidTest `Qwen17GoldenTest` (sideloaded, debug): 4 Gold ≥ 0,85, Stille == "" |
| **S6** | Ein-Engine-Regel + Wechsel | `ModelSwitchPolicy` deckt die neue Variante ab; Service schließt die alte Engine **vor** `load()`; `segmentFinalizeMs` = 15 000 für 1.7B | `ModelSwitchPolicyTest`: laufendes Diktat → `Deferred`; `ModelSwitchServiceWiringTest`: Reihenfolge close→load nachgewiesen (Fake zählt gleichzeitig offene Engines ≤ 1) |
| **S7** | Debug-Felder | `model_set_id`, `enc_ms`, `prefill_ms`, `decode_ms`, `tok_s`, `finalize_ms`, `vm_hwm_kb`, `avail_mem_at_load_mb`, `ram_gate` (+ vorhandene `rtf`, `engine_load_ms`) | `DebugInfoFormatter`-Tests: Felder vorhanden, nur Zahlen/Codes (Regel 9), Eintrag in `DEBUG_INFO_FIELDS.md` |
| **S8** | Geräte-Gate + Freischaltung | Messung nach §B.2, danach EIN Commit, der `BASE_URL_QWEN_17B` setzt (Rücknahme = ein Commit) | `docs/PERF_QWEN17B.md` mit G1–G11 alle grün; Nutzer-GO |

---

## D) Risiken

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| Export weiter RAM-blockiert | S0 kommt nicht zustande → alles andere bleibt inert | Host mit ≥ 24 GB frei oder bf16→int8-Direktpfad; S1–S7 sind ohne Artefakt testbar (Fakes) |
| Gemeinsamer Decoder klappt nicht (ORT und Past-Länge 0) | 4,4 GB / bis 6,5 GB Peak | Alternative: externe, geteilte Initializer (`SessionOptions.addExternalInitializers`, [E]); sonst **kein Release** |
| lmkd tötet die IME mitten im Diktat | Verstoß gegen die Kernregel | G6/G8 + Laufzeit-Gate S3 + Segmentdeckel; bei Druck Fallback statt Laden |
| RTF > 1 auf dem Pixel | unbrauchbar lange Wartezeit | G1 blockiert die Freischaltung; vorher 0.6B messen (C3) |
| Qualität int8-1.7B nicht besser | Aufwand ohne Nutzen | G11 + S0-Parität; Abbruch ist legitim |
| Hartkodierte 0.6B-Annahmen (`PROJECTOR_DIM`, 16-Köpfe-KV) | Absturz oder falsche Tensoren | S5: Shapes aus Session-Metadaten |
| Thermik / Akku | RTF-Drift, Nutzerärger | G7; ggf. 4 Threads beibehalten |
| hardened_malloc (GrapheneOS) | Ladezeit, Fragmentierung | nur auf dem Zielgerät messen (G3/G4) |
| Speicherplatz 2,4 GB + 1,5 GB Reserve | Download scheitert | vorhandenes Space-Gate (S3) |
| Nutzer hält es für den Default | Frust | Karte nie vorausgewählt, Warntext S4 |

---

## E) Reihenfolge und Aufwand [S]

0. **Vorbedingung:** 0.6B-Stichprobe auf dem Pixel (C3) — 0,5 PT. Ergibt den Gerätefaktor. Liegt das 0.6B auf dem Gerät bei RTF > 0,5, ist 1.7B (×2–3) aussichtslos → **Abbruch hier**.
1. S0 Export gemeinsamer Decoder + Parität — 1,5–2 PT (plus Zugang zu einem großen Host)
2. S1 Variant — 0,25 PT · S2 Manifest — 0,25 PT · S3 Gates — 0,5 PT
3. S5 Engine-Parametrisierung — 1–1,5 PT · S6 Wechsel-/Ein-Engine-Tests — 0,5 PT · S7 Debug — 0,5 PT
4. S4 Setup-Karte — 0,5 PT
5. S8 Geräte-Gate + Protokoll — 1 PT, danach Nutzer-GO

**Summe ≈ 6–7,5 PT.** S1–S7 laufen parallel zu S0 und bleiben inert. Kritischer Pfad: C3-Messung → S0 → S8.
