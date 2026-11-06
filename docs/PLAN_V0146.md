# PLAN V0.1.46 — Qwen3-ASR as second, switchable dictation model (planner, 2026-11-04)
User mandate (17:3x): "a version with Qwen ASR 1.6b; downloadable from the download
server; models switchable". Baseline: v0.1.45/vc46, main == 6169c30.

## 0. Facts read (repo)
- Runtime: com.microsoft.onnxruntime:onnxruntime-android:1.31.1 (build.gradle.kts:131),
  NO sherpa-onnx anywhere. LiteRT 2.3.2 debugImplementation only (variant B, debug).
- AsrEngine (asr/Hypothesis.kt:51): load(dir) / suspend transcribe(PcmWindow) / close.
  Hypothesis.tokenIds/frameTimesMs are read only inside asr/ (grep) -> Qwen may return empty.
- ParakeetAsrEngine: nemo128 mel -> encoder -> TDT greedy; chunked offline per window.
  Segmenter caps windows at 35 s (VoiceKeyboardService segmentFinalizeMs=35_000).
- Variants: ModelVariant {ONNX_CPU, LITERT_CPU}, prefs vk_layout/model_variant, DEFAULT
  ONNX_CPU, fromId fallback. EngineFactory.create(variant, store) exists but the
  service does NOT use it: buildCoordinator hard-wires ParakeetAsrEngine() (:1165) and
  ModelStore(...).modelDir() = variant A (:1185).
- ModelManifest: per-variant lists, BASE_URL_* = huggingface; ModelDownloader(baseUrl =
  BASE_URL); ModelDownloadService builds ModelStore/Downloader without variant (:169-171).
- ModelStore layout root/{active-<variant>.txt, versions/v-<id>, downloads/*.part}.
  BLOCKERS for two sets: (B1) sweepStaleVersions keeps only activeVersion() of variant A
  (ModelStore.kt:289) -> activating set X deletes set Y; (B2) .downloading/.verifying
  markers global; (B3) absentOrPartial() reacts to ANY .part; (B4) manifestSetId()
  hashes the constructor manifest only (variant A in prod) -> invite id per set needed.
- DownloadPolicy.MIN_FREE_BYTES = 2.5 GB fixed (sized for 671 MB).
- Coordinator: engine is a constructor val (engineFactory()), single-flight load,
  resident until shutdown() (A5/W2); service rebuild pattern coordinator=null exists.

## 1. Runtime decision
Decision: own QwenAsrEngine on the EXISTING ORT 1.31.1, chunked offline per segment
window (same contract as Parakeet). No sherpa-onnx: second native runtime (+APK MB,
second JNI surface, version pinning vs. ORT) for a feature whose Qwen3-ASR support is
UNVERIFIED offline. Streaming: not needed — our UX is already window-based.
Candidate: Qwen3-ASR-1.7B ("1.6b" in user wording), fallback Qwen3-ASR-0.6B.
Source: Hugging Face Qwen/Qwen3-ASR-1.7B (Qwen/Qwen3-ASR-0.6B); exact repo ids and
license (expected Apache-2.0) to be CONFIRMED in T22a — not verified by planner.
[orchestrator 2026-11-04 17:4x: CONFIRMED via HF API — `Qwen/Qwen3-ASR-1.7B` exists,
license apache-2.0, safetensors; NO ready-made ONNX flagged -> export is T22a work.]
Architecture (to confirm in T22a): audio encoder (Whisper-like log-mel 128 ->
transformer, ~300M) + Qwen3 LLM decoder (autoregressive, BPE tokenizer ~151k vocab,
chat-template prompt with language tag).
[T22a 2026-11-04 CONFIRMED from model card: qwen3_asr = Whisper-mel 128 -> audio
encoder 18 layers/896 dim -> projection to 1024 -> Qwen3 LLM decoder. No ONNX files
in the HF repo -> own export required.] ORT needs: mel frontend (ONNX like nemo128 or
Kotlin), encoder.onnx, decoder.onnx with KV cache (past_key_values in/out, or
decoder_init + decoder_with_past), tokenizer.json/vocab+merges.
Budget (estimates, MUST be measured): int8 size 1.7B ~2.0-2.5 GB on disk (0.6B ~0.8-1.0 GB);
resident ~2.5-3.5 GB (weights + KV + activations). Pixel 10 Pro XL 16 GB: OK, exactly
ONE engine resident. Decode on ARM CPU: prefill + ~10-30 tok/s (1.7B int8, estimate)
-> 35 s window ~ 5-15 s finalize vs. Parakeet ~1.35 s; latency gate decides 1.7B vs 0.6B.
Concept risk (ASR_MODEL_SURVEY_DE: Whisper rejected for silence hallucinations): LLM
decoder has the same class -> silence/short-window guard + max_new_tokens cap + repeat
guard are part of the engine contract (incomplete=true when capped).

## 2. Model artefacts / server
Set id "qwen3-asr-1.7b-int8" (ModelVariant.QWEN3_ASR id). Files (names fixed in T22a):
mel.onnx (or none if Kotlin frontend), encoder.int8.onnx, decoder.int8.onnx
(+ .onnx_data if >2 GB protobuf limit!), tokenizer files, prompt/config json.
Server: <download-server>/TalkType/models/<set-id>/<storeName> — static bytes only.
Trust: file list + sizes + sha256 PINNED IN APP (ModelManifest.QWEN), NO server-side
manifest.json (an unsigned server manifest would let the server change hashes); baseUrl
per set in code. sha256 produced once by T22a tooling, cross-checked by the
server operator after upload (sha256sum == pinned). manifestSetId per set (B4).
Space gate per set: need = totalBytes(set) + 1.5 GB reserve (parts->staging via rename,
same fs, no double copy) -> Parakeet ~2.2 GB (keep 2.5 GB floor), Qwen ~4 GB.
RAM gate (Qwen only): ActivityManager.MemoryInfo.totalMem >= 8 GB required to offer
the download, else card disabled with reason; < 12 GB shows warning. (Numbers measured
in T22j.) "Delete other model" button: trivial once B1 fixed (deleteSet(variant) when
not active) -> include, one button per non-active READY set.

## 3. Switching
- ModelVariant += QWEN3_ASR("qwen3-asr-1.7b-int8"); release-visible sets = {ONNX_CPU,
  QWEN3_ASR}; LITERT_CPU stays debug-only, hidden in UI. Prefs key unchanged
  (vk_layout/model_variant) -> 0.1.45 users: key absent -> ONNX_CPU (no path change).
- Pure ModelSwitchPolicy(target, statusOf(target), dictationActive) ->
  Rejected(NotReady) | Apply | Deferred. Setup writes pref only on READY.
- Service: OnSharedPreferenceChangeListener: IDLE -> coordinator.shutdown(); null
  (lazy rebuild, preload on next show); active -> pendingSwitch, applied in onStopped.
  Never aborts a running dictation.
- buildCoordinator: variant = activeModelSet(prefs, store) with fallback to ONNX_CPU
  if target not READY (EngineFactory.create(variant, store) becomes the single path;
  Qwen arm added there); modelDir = store.modelDir(variant).
- Residency: one engine at a time; shutdown releases native sessions before the new
  load (no 863 MB + 3 GB overlap). warm_hit/engine_loads per coordinator (unchanged).
- 🐞: active_model_set, engine_set_used (fallback visible), engine_load_ms existing.
- Setup: section "Dictation model": segmented choice Parakeet (~670 MB) | Qwen (~X GB);
  per set: status line, download/cancel, progress, "Delete"; choice enabled only READY.
  Download service gets EXTRA_VARIANT -> store/downloader/baseUrl per set; one download
  at a time (existing single-run service).
- Network invite (T20): one card per manifestSetId -> shows again after Qwen download.

## 4. TDD matrix (red first)
T22c ModelStoreTwoSetsTest (JVM): activate(Q) keeps A version dir (RED today: B1);
  markers per set (Q downloading -> A status READY); .part of Q -> A not DOWNLOADING;
  manifestSetId(A) != (Q), stable; deleteSet(non-active) frees dir, active refuses.
T22b ModelManifestQwenTest: names flat/whitelist, sizes>0, sha 64-hex, baseUrl per set,
  isAllowedName cross-set rejection; ModelVariant.fromId("qwen3-asr-1.7b-int8").
T22d DownloadPolicyPerSetTest: space need per set; RAM gate 7.9/8/12 GB; variant extra
  routes to right baseUrl (fake downloader).
T22e QwenTokenizerTest (JVM): BPE detokenize golden pairs from Python reference,
  special tokens filtered, byte-level unicode (ä ö ü ß €).
T22f QwenGoldenTest (androidTest, assume model present): 4 golden WAVs of
  ASR_CONTRACT §4 -> EXACT text pinned from Python ORT 1.31.1 reference (greedy =
  deterministic); silence 1.0 s -> "" (no hallucination); cap -> incomplete=true.
  Parakeet goldens UNCHANGED (ParakeetGoldenTest untouched, must stay green).
T22g ModelSwitchPolicyTest + EngineFactoryTest: READY-only, Deferred while active,
  applied on stop, fallback to ONNX_CPU when Q broken, default for absent pref;
  Robolectric service seam with fake engines: switch IDLE -> old engine close() called
  before new load(), exactly one load.
T22h Setup Robolectric: section present, Qwen choice disabled until READY, delete button
  only for non-active READY, pref written on select.
Corpus/WER: same corpus files as Parakeet corpus runs (Python reference on host first:
  WER(Qwen) vs WER(Parakeet) on the DE corpus; gate: Qwen WER <= Parakeet WER, else no
  ship of the switch - only the reference numbers decide). Emulator: -no-audio is
  irrelevant for file-based goldens; limiting factor = AVD RAM (check config.ini
  hw.ramSize of vk-api36; < 6 GB -> Qwen goldens PHONE-ONLY, documented split).

## 5. Risks / time
- 2 GB protobuf limit -> external data file; ORT mmap load time (measure engine_load_ms).
- OOM on 8 GB devices (RAM gate), lowmemorykiller killing the IME during load ->
  preload only when Qwen active, never both.
- Latency 5-15 s per 35 s window (estimate) -> may force segmentFinalizeMs lower for
  Qwen (per-set value) or 0.6B fallback; decided by T22a host RTF x phone factor + T22j.
- Hallucination/repeat on silence (LLM decoder) -> guards in T22f.
- APK size: unchanged (same ORT AAR; no new native lib). Model download ~2-2.5 GB.
- Emulator x86_64 swiftshader: CPU ORT runs, but slow + RAM -> correctness goldens
  maybe emu, performance ONLY phone.
- Effort: T22a 1 session (gate!), T22b-d+g-i ~6 coder tasks, T22e/f the bulk (2-3).

## 6. Sequence & files (<=4 files / ~150 lines each)
T22a GATE (no app code): tools/qwen_ref/*.py: obtain/export ONNX int8, run with
  onnxruntime 1.31.1 on host, golden texts for 4 WAVs + corpus WER vs Parakeet, sizes,
  sha256, host RTF, tensor I/O contract -> docs/QWEN_ASR_CONTRACT.md. STOP/GO by user
  (1.7B vs 0.6B vs abort). [user absent -> orchestrator brings the numbers to Opus 5.5;
  HF id + apache-2.0 CONFIRMED 2026-11-04.]
T22b model/ModelVariant.kt, model/ModelManifest.kt (+QWEN, baseUrl per set), test.
T22c model/ModelStore.kt (B1-B4, deleteSet), ModelStoreTwoSetsTest.
T22d model/DownloadPolicy.kt, ModelDownloadService.kt (EXTRA_VARIANT), DownloadController
  wiring, test.
T22e asr/qwen/QwenTokenizer.kt + test.
T22f asr/qwen/QwenAsrEngine.kt (+ frontend), androidTest QwenGoldenTest.
T22g asr/EngineFactory.kt, ime/ModelSwitchPolicy.kt, VoiceKeyboardService.kt
  (buildCoordinator + pref listener + deferred switch), tests.
T22h ime/SetupActivity.kt, setup_activity.xml, strings.xml, Robolectric test.
T22i debug/DebugInfoFormatter.kt + Collector (active_model_set, engine_set_used).
T22j emu duty on emulator-5566 (5564 foreign): switch, deferral, fallback, Parakeet
  regression; phone: Qwen golden + 3x60 s dictation latency/RAM (dumpsys meminfo);
  version 47 / 0.1.46, full tests debug+release, review cortecs, CHANGELOG, dist.
Conflicts: VoiceKeyboardService.kt (T22g only), SetupActivity.kt (T22h only),
ModelStore.kt (T22c only) -> claim_files; Gradle mutex; no ASR host run parallel to emu.

## 7. T22a GATE-ENTSCHEID (Opus 5.5, 2026-11-04, nach docs/QWEN_ASR_CONTRACT.md)
ENTSCHEID: (D) = A mit Telefon-Gate. 0.6B wird experimentelles Zweitmodell
(Set-ID qwen3-asr-0.6b-int8); 1.7B geparkt (Export braucht >=24GB-frei-Host;
spaeter eigenes Set qwen3-asr-1.7b-int8, kein Ersatz). WER 11,9% gilt NICHT als
Qualitaets-Urteil (Gold = Parakeet-Ausgabe per Konstruktion, 42 espeak-Woerter);
Blocker ist Latenz: NEUES GATE vor UI-Freigabe — Debug-APK + adb-push des Sets,
RTF/engine_load_ms/meminfo bei 10/20/35s-Fenstern auf dem ECHTEN Telefon.
GO: 35s-Audio-Finalize <=10s (oder <=4s mit eigenem segmentFinalizeMs~10s fuer
Qwen). NO-GO: Fundament bleibt, UI bietet Qwen nicht an, Bericht an Nutzer.
Engine-Pflicht: 1s Stille -> "" (App-Silence-Guard + max_new_tokens-Cap +
Repeat-Guard; sonst keine Abnahme). Nur EINE Engine resident; Diktat-NIE-abbruch;
Parakeet Standard. UI (EN): "Qwen3-ASR 0.6B (experimental)" / Text siehe
get_result sa-b472203a; Parakeet-Eintrag "Parakeet TDT 0.6B (recommended) · 0.67 GB".
Zusatz (nicht gate-relevant): Common-Voice-de echte Sprache, WER-Vergleich hostseitig.
Telefon-Gate braucht NUTZER-ADB-Freigabe (Port rotiert) — Hinweis bei Rueckkehr:
geliefert wird 0.6B statt bestellter 1.6/1.7B, Begruendung RAM-Exportgrenze.
