# Design: offline speech keyboard for GrapheneOS

## 0. Binding nature and product scope

This document specifies a sideloadable Android IME for Android 16 / API 36.
It is a complete on-screen keyboard and additionally offers held
push-to-talk with Parakeet TDT 0.6B v3, ONNX Runtime Android, and CPU EP only.
After the one-time model download, dictation is fully possible offline.
No cloud, no AccessibilityService, no dependence on Google Play Services.

**Source precedence:** `TECH_FACTS.md` is the verified environment/artifact basis;
`KONZEPT.md` provides proven desktop mechanisms but no Android performance
promise. All time, window, and memory budgets below are design starting values
and must be validated on a real GrapheneOS target device; they are not
measurements. The product overview describes known architecture approaches, not
a version comparison tested here. Release-dependent capabilities are explicitly
marked as such.

Important clarifications relative to the desktop concept:
- The active IME writes into third-party target apps via its InputConnection.
- UI events remain responsive only if native inference never runs in the UI.
- TDT generates tokens autoregressively in the prediction net; greedy is not
  parallel across all tokens. Encoder computation and duration jumps speed it up.
- Desktop Whisper options such as `temperature`, `beam_size`, or
  `condition_on_previous_text` are not presupposed ONNX parameters.
- CPU EP is set; NNAPI/GPU are neither acceleration promises nor fallbacks.
- Releasing proves no unchanged cursor on Android. Final correction needs the
  same ownership checks as any other change.

### Concept fidelity

KONZEPT.md is the binding experience base. The table names adoptions and
deliberate changes; Android security boundaries do not justify silently
switching the proven streaming default.

| Mechanism | KONZEPT.md requirement | our decision | Rationale | Risk |
|---|---|---|---|---|
| Push-to-talk | idempotent, hold/start, release/finalize | adopted; plus STARTING/STOPPING | Android routing/service start asynchronously | queue a late UP |
| Determinism | temperature=0 | argmax without sampling adopted | ONNX TDT has no presupposed temperature parameter | test native reproducibility |
| LocalAgreement | two confirmations, full word boundary | adopted | proven protection against hallucination/flicker | first output delayed |
| Committed prefix | untouchable live | adopted | no stable text flicker | early errors remain until the finale |
| Revision | max_revision_chars=60; otherwise only append | 60 Unicode code points, grapheme boundaries; on uncertainty stop rather than blindly append | the Android cursor can be moved externally; avoid duplicate text | fewer automatic corrections |
| Final transcription | fully correcting, committed_len=0 | full whole-buffer final decode; own span fully revisable with proven ownership | UP proves no cursor position on Android | on ownership loss, explicit takeover |
| Final revision limit | force_revision for full correction | no 60-character limit for the safe final own span | do not artificially cap the final full correction | large changes only with safe ownership |
| Token budget | 12*s+64, min 256, max 4096 | adopted, the entire input including context | prevents context-induced truncation | limit: fail visibly/retry |
| First partial | min_audio_s at least 1, better 2.5–3 | **2.0 s** — measured (ASR_CONTRACT §4.4): 1.0 s hallucinates ("Oh yes."), from 2.0 s 0 failures in 23 samples; 3.0 s brings no measurable benefit. Still decode short finals | first output ~1 s earlier than 3 s, without documented hallucination risk | short finals prone to hallucination |
| VAD | discard only short remnants under 2 s on False | no VAD gate in version 1; discard nothing because of VAD | leaving out the optional filter prevents speech loss | more inference on silence |
| Stop | join → drain queue → newest remainder | semantically adopted; unblock read, join, queue, last read, EOS | AudioRecord.stop yields no remainder buffer | race/sequence tests required |
| Recording limit | 300 s | adopted | 9.6 MB raw PCM is acceptable | tensor/inference demand larger |
| Streaming | whole_buffer; windowed failed | whole-buffer binding default; overlap only a disabled experiment | no Android measurement justifies the failed default | high CPU/RAM demand remains a release risk |
| Normalization | no post-hoc text normalization | adopted; ASR output unchanged | preserve capitalization/punctuation | Unicode comparison must be exact |
| Tests | fakes without I/O; pause WAV against single decode | adopted; additionally editor/routing tests | desktop regression plus Android data integrity | fakes prove no model quality |
| Output path | desktop key emulation; Android hints partly accessibility | exclusively the active IME InputConnection | TECH_FACTS corrects the desktop transfer | the host editor can be unreliable |
| Acceleration | NNAPI/GPU as an opportunity, desktop 8 threads | CPU EP; measure thread count | product requirement and missing operator guarantees | real-time capability unproven |
| Auto-space | append_space=true | no blanket trailing space | the IME writes between existing app characters | behavior deviates from the desktop |
| Accessibility | no toggle | optional explicitly activated start/stop mode only as an operating aid | holding is not always possible with screen readers/motor impairment | label the mode clearly; default remains hold |

## 1. State of the art and consequences

### 1.1 Android keyboards

| Keyboard | Layouts and multilingual support | Dictation / offline classification | Consequence |
|---|---|---|---|
| Gboard | broad language/layout offering, cross-language suggestions, special characters and emoji | voice input and advanced on-device functions depend on device, language, and Google components; no transferable GrapheneOS building block | adopt the good mic/language-switch ergonomics, not its service architecture |
| Microsoft SwiftKey | multilingual prediction/autocorrect, many layouts, personalization | voice input depends on available language services and product state; no guaranteed standalone offline ASR for our goal | do not couple layout language and recognition; do not rebuild prediction infrastructure |
| HeliBoard | AOSP/LatinIME-like complete keyboard, multiple languages, dictionaries and customizations | offline keyboard is not the same as offline dictation; voice input may require an external provider | the ordinary keyboard must be fully usable without a loaded ASR model |
| FlorisBoard | modular, customizable IME approach with language/layout data; feature set release-dependent | no solid evidence of an integrated Parakeet pipeline from our sources | separate layout data and editor actions cleanly from the ASR core |
| FUTO Keyboard / Voice Input | keyboard and a local voice-input approach, multilingual support model-/release-dependent | an example of locally shipped or downloaded speech models; not to be equated with our model/runtime contract | treat model setup, download status, and offline trust as a distinct UX |
| AnySoftKeyboard | extensible language/layout packs, dictionaries and classic IME features | voice input possibly via an installed provider; no presupposed own offline ASR stack | data-driven extensions instead of hard-coded language branches |

Classification: "offline" can only refer to the key/dictionary function. An
existing mic icon proves neither local recognition nor independence from system
services. Product details are to be checked against concrete releases before a
later comparison. Starting points are official product documentation plus the
projects `github.com/Helium314/HeliBoard`, `github.com/florisboard/florisboard`,
`github.com/AnySoftKeyboard/AnySoftKeyboard`, and the FUTO product
documentation. These sources are research pointers, not additionally verified
measurement data here.

### 1.2 On-device ASR

| Approach | Technical status / limit | Decision |
|---|---|---|
| whisper.cpp | native local Whisper inference, quantization and Android integration; live output often via repeated audio windows rather than true stateful streaming | reference standard for offline UX and WAV tests, not a second backend path |
| ONNX Runtime Android | AAR, explicit tensor/session management, CPU EP; the model graph decides which optimizations are available | the chosen portable runtime path; three sessions for mel, encoder, and decoder/joint |
| NNAPI | hardware/driver/operator dependent; on newer Android versions no future-proof universal path | do not use; no automatic provider selection, no mixed CPU/NNAPI operation |
| Parakeet ONNX ports | exporting encoder, prediction/joint, and preprocessing enables native local execution; the export alone is not yet streaming ASR | use the export documented in TECH_FACTS; check the decoding contract first |
| Native streaming transducer | with a suitably trained model, encoder state/lookahead instead of full recomputation | our encoder is not streaming-capable; no invented cache inputs or lossless chunk equivalence |

Concrete port: `istupakov/parakeet-tdt-0.6b-v3-onnx`; artifacts exclusively as
in TECH_FACTS. Other ports/frameworks are references, not further dependencies.
Language automation comes from the multilingual model, not from a claimed ONNX
parameter `language=auto`. Switching the keyboard layout does not change ASR
automatically. All 15 desired languages must be tested separately despite the
multilingual model choice.

## 2. Architecture and lifecycles

### 2.1 Modules and responsibilities

| Module | Responsibility / boundary |
|---|---|
| `setup` | enable/select the IME, explain and request microphone permission, install the model, test the audio source |
| `keyboard-ui` | own view, touch/pointer state, accessibility, layouts, emoji, shift and editor actions |
| `layout-core` | JSON validation, layer resolution, dead keys, language-specific case mapping |
| `dictation-controller` | state machine, session/editor generation, start/stop/abort and deadlines |
| `capture-service` | foreground service for the microphone, AudioRecord, routing, and the audio timeline |
| `audio-core` | PCM blocks, resampling if needed, sequenced storage and window planning |
| `asr-core` | mel/encoder/decoder sessions, tensor contract, deterministic TDT greedy |
| `commit-core` | time normalization, overlap matching, LocalAgreement, commit horizon |
| `editor-core` | DeltaTyper, InputConnection ownership check, UTF-16/grapheme boundaries |
| `model-store` | download/resume, checksums, atomic installation, licenses and compatibility |

Pure core modules know neither Android views nor concrete ORT sessions.
Interfaces are our own abstractions, not claimed Android APIs:
`AudioSource → PcmBlock`, `AsrEngine(window) → Hypothesis`,
`EditorSink.apply(EditPlan, editorGeneration) → Result`.
Hypotheses contain token IDs, text, global audio positions, a run ID, and a
session ID.

### 2.2 Service topology and permissions

- The InputMethodService and a **bound plus started** microphone foreground
  service run in the same app process. No separate inference process, no audio
  over Binder.
- The IME owns the InputConnection; the capture service never receives it.
- The FGS lives only for recording/brief orderly drain. After the microphone is
  released, the microphone FGS ends; final inference remains a process-bound
  job of the visible IME.
- ORT models are shared process-wide, not tied to the recording. Unload when
  all users release it under memory pressure; no permanently running
  keep-warm FGS.
- Manifest: `RECORD_AUDIO`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, service type `microphone`, and `INTERNET`
  for the download. Declare the IME service with system-protected binding via
  `BIND_INPUT_METHOD`.
- Explain/request `POST_NOTIFICATIONS` from API 33 in the setup. Refusal does
  not fundamentally prevent an FGS, but it does not replace the required
  notification.
- The runtime grant for the microphone happens in a visible setup activity,
  not via a dialog from the keyboard while holding.
- Start the mic FGS only from a deliberate user event with the IME visible; no
  boot start. Android start rules and while-in-use permission are separate
  checks. The system-bound visible IME is a relevant special case; its actual
  permissibility on API 36/GrapheneOS is tested as an implementation gate.
- If the start is refused (e.g. SecurityException/
  ForegroundServiceStartNotAllowedException), the keyboard remains available;
  hint to open the setup, no unsafe retry. If necessary there: use a visible
  activity for explicit activation, do not record secretly.
- The service publishes its notification immediately within the prescribed
  start window, before models are loaded or audio is initialized.
- No blanket battery-optimization exemption; explain it only as an option after
  a documented device problem. Neither accessibility nor general package access
  is required.

### 2.3 Threading, data flow, and backpressure

1. Main/UI: touch events, the controller actor, and all InputConnection
   operations. Only short events; no audio read, no tensor setup, no joining on
   native code.
2. A dedicated capture thread with blocking AudioRecord.read. It writes
   monotone blocks with start sample, sample count, and sequence number into a
   bounded queue.
3. An audio consumer copies into the session-related PCM storage and plans
   snapshots.
4. A dedicated serial ASR worker processes mel → encoder → TDT decoder.
   Kotlin coroutines coordinate; ORT may use CPU threads internally.
5. Immutable results go to the controller; it discards stale
   session/window/editor generations and has commit/delta applied on the main
   thread.

Data flow: microphone → PCM16 → normalized float PCM → `nemo128.onnx`
→ encoder → prediction/joint greedy → time/text matching → LocalAgreement
→ DeltaTyper edit plan → `deleteSurroundingText` / `commitText` on the current
connection.

Only one ASR run at a time; pending intermediate runs are collapsed onto the
newest snapshot. A final job replaces pending partials, not the running native
call. No unbounded coroutine/audio/tensor queue. Measure the CPU thread count
on the device (initial candidates 2 and 4); do not adopt desktop-8; account
for CPU heterogeneity, UI responsiveness, and thermal throttling. On capture
queue overflow, end the recording with an error instead of silently losing
samples. A 300-s limit needs roughly 9.6 MB of raw PCM; budget float/mel/tensor
copies additionally. Do not continuously duplicate the entire dictation as
float.

### 2.4 State machine

Model state separately: ABSENT → DOWNLOADING → VERIFYING → READY; error →
ABSENT/BROKEN. Dictation state: IDLE → STARTING → RECORDING → STOPPING →
FINALIZING → IDLE. STARTING/RECORDING/STOPPING/FINALIZING can transition to
IDLE via CANCELLING. An error leads via orderly cleanup to IDLE with a visible
error cause.

- DOWN is idempotent, generates a session ID, and remembers exactly one
  active pointer.
- UP during STARTING queues the stop immediately: no delayed unwanted
  recording.
- UP in RECORDING sets the stop request without waiting for running inference.
- Pointer loss, ACTION_CANCEL, swipe abort, and notification abort use the
  same cancel function. Double UP/cancel has no effect.
- Further mic starts during FINALIZING are rejected; the status stays visible.
  Ordinary keys remain usable but invalidate the ASR editing authorization
  first.
- 300 s from the first sample: automatic stop/finalize with a clear hint.
- Editor change, IME hiding, and process/service loss stop the capture; no
  post-hoc output into another editor, no automatic resumption.

## 3. Audio recording and ordering

AudioRecord preferably gets 16 kHz mono PCM16; size the buffer at least using
`getMinBufferSize()`, chunk starting value 20–40 ms. Check the hardware/route
rate: if 16 kHz cannot be delivered reliably, record at a supported rate and
normalize to 16 kHz with a tested stateful resampler. The model gets float PCM
with suitable scaling, not raw PCM16 byte values.

Routing is part of the recording, not of mic touch detection:
- Default source the built-in microphone; available input devices selectable
  when needed.
- Support wired/USB headsets and the Bluetooth communication microphone.
- Bluetooth A2DP output is not a microphone input. For BT communication
  routes use the AudioManager communication device selection
  (`setCommunicationDevice`); observe availability and the actual routing, do
  not merely assume a successful selection.
- The AudioRecord device preference (`setPreferredDevice`) is a preference, not
  a guarantee. Whether the audio source and MODE_IN_COMMUNICATION are needed
  is to be tested on target devices; set them only during our own session and
  reset our own audio mode afterwards.
- Confirm the communication route before recording; STARTING shows "connecting
  microphone". After the first sample, a visible source display; if another
  source is active, inform the user.
- Request `BLUETOOTH_CONNECT` only for protected Bluetooth operations actually
  used; no unnecessary scan/location permission. Refusal keeps the internal mic
  usable.
- Route change, headset removal, another recorder/call, or a persistently
  silent recording: end the session in a controlled way, show an error, do not
  silently switch to another mic.

Stop protocol: set the stop marker → end/unblock AudioRecord.read in a
controlled way → let the capture thread drain → collect all older queue
blocks by sequence → possibly the last successful read block → end-of-stream
marker → final inference. AudioRecord has no generally usable `stop()` return
buffer like the desktop wrapper. The concrete unblock/stop sequence must pass
race tests; no new reads after EOS. PCM is processed only once per sample.
Release the microphone device and route immediately afterwards.

## 4. Model installation and ONNX contract

### 4.1 Binding artifacts

From `istupakov/parakeet-tdt-0.6b-v3-onnx`:
`encoder-model.int8.onnx` (652.2 MB), `decoder_joint-model.int8.onnx`
(18.2 MB), `nemo128.onnx` (~0.1 MB), `vocab.txt` (~0.1 MB), together about
671 MB. Location: `filesDir/models/parakeet-tdt-0.6b-v3-int8/`.
No additional model URL or ORT version number is assumed here. The
build/release configuration pins a tested ORT version and a model revision.

The setup shows total download, free storage, a Wi-Fi recommendation, progress,
and abort. HTTP resume must check range/ETag behavior; if the object changed,
start over. First download `.part`, verify SHA-256 of all files, run a tensor
smoke test, activate the complete version atomically. No mixing of old and new
graphs. Checksums and the revision identifier must come from a trustworthy,
released manifest signed with the APK; TECH_FACTS does not yet contain hashes.
Without this release groundwork, no production download with a claimed
integrity check. Include temporary files and any old version in the storage
requirement. Abort/network errors do not damage the active version; the
ordinary keyboard remains usable. Check the license/redistribution terms of
model, vocabulary, and runtime before release.

### 4.2 Tensor contract as a mandatory implementation gate

TECH_FACTS confirms the files but no tensor names, axes, or the blank ID.
Before implementing the decoder, produce a small reference protocol against
the pinned export:
- Read out ONNX inputs/outputs, data types, dynamic axes, and length tensors.
- Check the mel layout, padding, normalization, and encoder subsampling
  including frame time.
- Check the prediction input (last symbol/SOS), recurrent states, and their
  initialization.
- Identify the joint outputs: separate token/duration logits or a combined
  vector with an explicitly verified split. Do not blindly discard batch/
  singleton axes.
- Check the vocabulary, blank ID, special tokens, SentencePiece detokenization,
  and the actually trained duration values and their order.
- Compare a reference decode of the same WAV in the original export
  environment against Android.

The adapter normalizes to `tokenScores[V]`, `durationScores[D]`,
`nextPredictorState`. With a combined output, the split happens only per the
verified contract, not by guessed numbers. Mismatched dimensions/NaN/missing
vocabulary → model error. A classic RNNT joint output without a duration head
does not suffice for this TDT design.

## 5. TDT greedy, windows, and commit

### 5.1 Decoder rules

Per window, run the encoder once over all mel frames; respect the valid encoder
length. Prediction state and last label are re-initialized per independently
decoded window. No carried-forward decoder state across doubly included
overlap.

For encoder position t and the current prediction state:
1. Evaluate prediction/joint with the encoder frame, the last label, and the
   state.
2. Choose the token argmax and duration argmax deterministically; ties by a
   fixed index rule. Logits need no softmax for argmax. Adopt the exact head
   interpretation and any exported masking from the reference decoder.
3. Non-blank: emit the label, update the predictor according to the accepted
   label; map the time point t to a global sample time.
4. Blank: emit no label, do not carry forward the semantic label/prediction
   state. With a fused graph, returned states are adopted only per its
   verified call protocol; blank must not produce a new spoken label.
5. Increase t by the chosen **duration in encoder frames**, not by its class
   index. Non-blank with duration 0 allows multiple labels on the same frame.
6. Blank with duration 0 must not create an infinite loop: reproduce the
   reference decoder's behavior (e.g. masking or a minimum advance of 1)
   exactly. Until clarified, this is a blocking contract gate, not a freely
   invented model rule.
7. Stop when t leaves the valid encoder length. Do not add separate
   Whisper-EOS or temperature rules; correctly filter exported special tokens.

Additional independent safety limits: initially at most 10 non-advancing
emissions per frame and a global step limit proportional to frames plus the
token budget. These starting values are to be validated. Reaching them marks
the run as incomplete/erroneous; never treat a silently truncated hypothesis as
final. TDT frame times are model alignments, not guaranteed word boundaries;
edge tokens therefore remain uncommitted within the safety context.

### 5.2 Budget and realistic latency

Starting rule from KONZEPT: `B(s) = max(256, min(4096, ceil(12*s)+64))` tokens.
s is the **entire** window length passed to the encoder, including overlap.
Limit blank steps separately; they consume no text token budget. On hitting the
budget, retry once in a controlled way with a larger budget; after that a
visible error instead of silent text truncation. Window shrinking belongs only
to the chunk experiment. The 4096 limit remains the default even for a 300-s
whole buffer; never report saturation as complete success. The larger retry is
an explicit exception, not a silent change to the default formula.

Starting parameters: first partial hypothesis after 3 s of audio; re-plan every
1 s of new audio. LocalAgreement needs two different audio states; the same
snapshot does not count twice. Target budget after warm-up: mel/encoder
together ≤650 ms, decode ≤250 ms, matching/edit ≤50 ms per planned 1-s update,
the rest as reserve. That is a **measurement target**, not the claim that 0.6B
fits in 950 ms on every CPU. First stable text at the earliest about 4–5 s
after start, later under load; UP→final target ≤2 s for short dictations and a
warm model; for long whole-buffer dictations there is no solid final latency
promise without a device measurement. Show cold loading separately. Measure
RTF, p50/p95, peak RSS, temperature, and battery on real ARM64 hardware; do
not transfer desktop or x86-emulator values. On overload, reduce only the
update frequency, never lose audio. If inference remains slower than inflow,
an honest "processing" display and a release risk.

### 5.3 Whole-buffer default and experimental chunk strategy

The binding default is whole-buffer: every run re-encodes the full audio
[0, now] without encoder-state streaming; likewise the final decode after UP.
Collapse intermediate runs under backpressure; agreement needs two audio
states. Growing compute cost and long final times are open release risks, not a
license to silently switch to the demonstrably problematic windowed approach.

The following chunk strategy is a **disabled experiment**, not a default.
Activation only after documented Android resource necessity, passed
pause/quality tests, and an updated deviation decision in concept fidelity.
Starting values: window W=16 s, hop H=8 s, overlap O=8 s; right commit distance
R=2 s. These values are hypotheses, not optimal parameters derived from the
model.

- At the start, [0, now] grows up to 16 s; afterwards fixed epochs [8,24],
  [16,32] etc. In the new epoch, the end again grows up to the window boundary.
- Window progress follows only recorded samples and an inference watermark,
  never `committed_s`, word count, or VAD output.
- At the transition, old and new epoch contain 8 s of identical audio.
  Discard the new epoch initially without left context? **No:** decode it
  completely and map it in the overlap using global token times plus token/
  word sequences.
- Two-part handover: project the old last hypothesis and the new first
  hypothesis onto a shared audio range; use only an unambiguously matched
  sequence as the anchor. Never output tokens in the left context a second time
  wholesale.
- An existing agreement cache remains for the shared audio range; only history
  outside the overlap is dropped. A window switch alone generates neither a new
  commit nor a second agreement hit.
- A commit candidate is the shared complete word prefix of two hypotheses,
  temporally at most up to `window end − R`, consistent with already committed
  text. The commit horizon bounds eligibility but does not force a commit under
  uncertainty.
- Long pauses let the audio/window watermark advance. They may leave the text
  commit unchanged; later speech must still be processed.
- No stable handover before the overlap drains: keep the affected text
  uncommitted and remember the affected audio span for repair. On release,
  re-decode this span in larger reference windows and match it unambiguously.
- If the matching remains ambiguous or the repair exceeds the device budget:
  no unsecured deletion, no blind gluing — stop automatic editing, leave the
  retained text standing, show the remaining result for explicit takeover in
  the IME. This case is a quality defect, not a normal success path.

Bounded repair runs and a sequential watermark prevent the commit/pause/
agreement deadlocks observed on the desktop. Whether window decoding reaches
sufficient quality remains a high risk; release only after the tests in
section 10.

### 5.4 LocalAgreement and finalization

First detokenize token pieces correctly per the model contract. No post-hoc
normalization or regex capitalization repair. Compare ASR text exactly, align
prefix boundaries on graphemes; perform no NFC, case, or punctuation
normalization of the transcript. Determine word boundaries language-sensitively;
never commit a partial word. Two compatible hypotheses stabilize only the
common prefix behind the already protected prefix. Contradiction with the
protected prefix → do not force a commit. Partials may appear in the target app
as a bounded revisable tail.

After EOS, the default runs a deterministic final decode of the entire audio;
only in the disabled chunk experiment would the last epoch and repair spans
matter. Finalization sets the commit protection for its own session to zero
and may correct all of its own text, **only** with a still-verified ownership
span. The live limit of 60 characters does not limit this safe final full
correction. Otherwise retain the protected text and explicitly offer the
result. LocalAgreement is stability, not a guarantee of recognition
correctness.

## 6. Safe text output: DeltaTyper for Android

The InputConnection is a short-lived editor access, not a transactional
document editor. Ownership protocol per session: editor generation, start
selection, expected cursor position, inserted span, expected suffix, pending
own selection callbacks. No assumption that Java string lengths correspond to
characters or code points.

- Non-empty selection at mic start: insert the first ASR text **behind** the
  selection if the cursor mapping is confirmed; do not automatically replace
  the user's originally highlighted text. If unsupported, reject/explain
  dictation there.
- Before every revision, evaluate `onUpdateSelection` and a bounded
  `getTextBeforeCursor` comparison. Recognize our own expected callbacks
  separately. Null, a truncated answer, an unexpected selection, or an editor
  change means "ownership not proven".
- `beginBatchEdit`/`endBatchEdit` group the display; they are not an atomic CAS
  operation. Check again right before deleting; if uncertain, do not write.
- Delta = the longest common prefix of the old and new tail after the protected
  commit; fall back to a grapheme boundary, then delete only our own suffix and
  add the new one.
- `deleteSurroundingText(beforeLength, 0)` counts UTF-16 code units. Align
  deletion limits with grapheme clusters; do not split surrogate pairs,
  accents, or emoji ZWJ.
- `commitText(delta, 1)` keeps the cursor behind the new text; afterwards check
  the expectation state. Evaluate return values. If the second step fails after
  a successful deletion, do not blindly repeat: re-read the state, show an
  error, change no third-party data.
- Revision limit: at most 60 retractable Unicode code points live; observe
  grapheme boundaries additionally. The limit concerns the deletion scope, not
  the tail length. Exceeding it discards the edit plan instead of simply
  appending twice as on the desktop. Append-only only with a cursor again
  proven directly behind our own text.
- Manual typing, emoji, a shift-independent editor action, cursor movement, or
  an app switch ends ASR ownership before the action. Never write late results
  across it.
- No `setText`, no clipboard as an injection path, no AccessibilityService. No
  composing span for ASR; predictable behavior even with simple editors.
- On abort, remove only the still-revisable tail if provable; already stable
  text remains. A complete "undo dictation" is its own confirmed action and
  possible only with an intact ownership span.

PIN/password fields: mic disabled, no context reading, no text logs; numeric
and phone editor types get suitable layouts. The target app may transform
contents itself or implement an insufficient InputConnection: then switch off
revision, and if the position is unclear, stop appends too. Do not risk
third-party text for live UX.

## 7. Complete keyboard and layout model

### 7.1 Feature scope and the autocorrect decision

**No autocorrect and no learning dictionaries in version 1.** That keeps all
15 languages fully typable without dictionary downloads, and ASR revisions do
not collide with a second text correction. No silent replacements, no
auto-period rule. Manual shift, one-shot shift until the next letter,
double-tap caps lock, backspace with repeat, space, enter or editor action,
digits, and two symbol layers. An optional auto-shift stays out initially;
the state of shift/caps lock is always visible. Caps lock affects letters, not
the digit/punctuation layer. Test the Turkish i/ı and multi-character case
mapping explicitly, not just per-key Unicode uppercase. Cursor left/right and
space swiping, long delete repeat, the layout switcher, an emoji panel with
categories/search from local data and favorites; no sticker/GIF search.
Commit emoji as complete sequences; check display/font coverage on Android.
Editor actions based on EditorInfo instead of a blanket line break; mind
multiline fields.

### 7.2 JSON schema: normative contract excerpt

The complete schema is versioned as an asset at implementation time. This
short excerpt shows the structure; the invariants below are also binding.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["schemaVersion", "id", "language", "layers", "deadKeys"],
  "properties": {
    "schemaVersion": {"const": 1},
    "id": {"type": "string", "pattern": "^[a-z0-9-]+$"},
    "language": {
      "type": "object",
      "required": ["tag", "label", "caseLocale", "direction"],
      "properties": {
        "tag": {"type": "string"}, "label": {"type": "string"},
        "caseLocale": {"type": "string"}, "direction": {"enum": ["ltr", "rtl"]}
      },
      "additionalProperties": false
    },
    "layers": {
      "type": "object",
      "required": ["base", "shift", "symbols", "symbolsShift"],
      "additionalProperties": {"$ref": "#/$defs/layer"}
    },
    "deadKeys": {"type": "object"}
  },
  "$defs": {
    "layer": {
      "type": "object", "required": ["rows"],
      "properties": {
        "rows": {"type": "array", "minItems": 1,
          "items": {"type": "array", "minItems": 1,
            "items": {"$ref": "#/$defs/key"}}}
      }, "additionalProperties": false
    },
    "key": {
      "type": "object", "required": ["id", "label", "width", "kind", "value"],
      "properties": {
        "id": {"type": "string"}, "label": {"type": "string"},
        "width": {"type": "number", "exclusiveMinimum": 0},
        "kind": {"enum": ["text", "action", "dead"]},
        "value": {"type": "string"},
        "caps": {"type": "string"},
        "alternates": {"type": "array", "items": {"type": "string"}}
      }, "additionalProperties": false
    }
  },
  "additionalProperties": false
}
```

Further schema/semantics rules: `tag`/`caseLocale` are valid BCP-47
identifiers; `rows` describes all visible keys including function keys, widths
are relative units. Letter keys have an explicit shift counterpart and `caps`
if needed; no implicit QWERTY completion. IDs are unique per layer; the same
physical key keeps its ID across layers. Row sums are validated. `action.value`
comes from an allowlist (shift, caps, layer-symbols, layer-base, backspace,
space, enter, language, emoji, mic, cursor-left, cursor-right); no executable
code. `dead.value` references an entry in `deadKeys` with `spacing`, an
optional `combining`, and an explicit `compose` table from base sequence to
output sequence. Dead key + space outputs spacing; an unknown sequence outputs
spacing plus the original; backspace first deletes the pending dead key, a
layout change discards it visibly. NFC only within a new composition, not
applied over existing app text.

### 7.3 Fifteen complete launch layouts

Each asset contains base/shift, digits, symbols, local punctuation, accent
alternates, and all function keys; no language is represented only by its flag
label. The following variants are deliberate product decisions, not claims of
a single nationally binding mobile standard. Other variants later as their own
IDs.

| Language / identifier | Primary layout | Completeness / special data |
|---|---|---|
| DE / de-DE | QWERTZ | ä ö ü ß, uppercase forms, accent alternates |
| EN / en-US | QWERTY | US character mapping, apostrophe and English punctuation |
| FR / fr-FR | AZERTY | é è ê ë à â ç ù û ü œ æ, digits explicitly on the symbol layer |
| ES / es-ES | QWERTY | ñ, acute/vowel variants, ü, ¿ ¡ |
| IT / it-IT | QWERTY | à è é ì ò ó ù and shift forms |
| PT / pt-PT | QWERTY | ç ã õ á à â é ê í ó ô ú and uppercase forms |
| NL / nl-NL | QWERTY | ë ï é è ê ö ü, local quotation marks |
| PL / pl-PL | QWERTY | ą ć ę ł ń ó ś ź ż each directly/long-press reachable |
| TR / tr-TR | Turkish Q | ğ ü ş ı ö ç, i→İ and ı→I explicit |
| RU / ru-RU | ЙЦУКЕН | all Cyrillic letters including ё/Ё |
| CS / cs-CZ | QWERTZ | ě š č ř ž ý á í é ú ů ď ť ň and uppercase forms |
| SV / sv-SE | QWERTY | å ä ö, accent/quotation alternates |
| DA / da-DK | QWERTY | æ ø å and uppercase forms |
| FI / fi-FI | QWERTY | ä ö å, š ž as alternates |
| EL / el-GR | Greek | complete alphabet, final ς/Σ, tonos and dialytika including ΐ/ΰ |

Switch activated layouts via the language menu and long-press on space; store
the favorites order locally. Android IME subtypes serve system integration and
language metadata; imported JSONs additionally work in the internal switcher,
without rebuilding the manifest. Import via the system file picker, a copy into
private storage; no full storage permission. Check version, size, row count,
string lengths, references, and the action allowlist. Reject an unknown schema
major version, isolate a broken layout, keep the built-in DE/EN as a fallback.
No scripts, network URLs, fonts, or plugins in the layout format.

## 8. UX and privacy

- The mic is permanently discoverable, with a separate large touch area and
  label for screen readers. STARTING shows loading/source selection; RECORDING
  shows time, source, a clear recording indicator, and "release to finish". No
  recording without an indication.
- Recording starts only after a valid permission/route; haptics for the actual
  start, release, and abort optional. The UI does not confuse "finger down"
  with "audio active".
- Swiping left out of the mic zone (starting value 64 dp) marks "cancel";
  crossing confirms the abort; returning before UP may clear the mark. Pointer
  capture/loss is handled; no recording remains stuck after ACTION_CANCEL.
- FINALIZING shows "processing locally" and abort, no misleading recording
  icon.
- Screen-reader/motor alternative: an explicitly activated accessible
  start/stop mode with the same time limits; the default remains hold only.
- Without the model: the mic opens the setup instead of hiding a download in
  the background. Without permission: explanation + open the setup; refusal
  blocks only voice input. No microphone/route error: source selection/try
  again, no empty success message.
- Under 3 s of audio: after UP still make a final attempt; do not discard
  short speech via a blanket VAD; hint if the input is not clearly
  recognizable.
- No optional VAD in version 1 as a hard barrier. A simple level indicator is
  not speech recognition and must never alone delete real speech.
- No forced space after every dictation. Spacing only at the proven own edge
  and per fixed punctuation rules; never normalize existing text.
- Privacy page: "audio and text stay on this device; network only for the
  model download". No analytics, crash text, cloud fallback, or account
  requirement.
- PCM/hypotheses only in RAM, released reference-free after the session; no
  promise of secure physical RAM erasure in the JVM/native runtime. No audio
  dumps in product operation.
- Debug WAV/transcripts only after explicit separate consent, a clear
  deletion deadline, private storage, and deliberate export; never capture
  password fields.
- After the download, the user can revoke GrapheneOS network access; the
  offline function, including app restart, must then work unchanged. The
  Android INTERNET manifest right is not a runtime-toggleable download switch;
  an in-app network-off switch additionally prevents all new download requests.

## 9. Failure modes and countermeasures

| Failure | Countermeasure / safe reaction |
|---|---|
| UP during blocking inference | UI controller and capture independent; immediate stop marker |
| Double DOWN/UP, orphaned pointer | idempotency, active pointer ID, cancel and time limit |
| Audio timeline twisted / duplicated trailing bytes | sequence numbers, EOS, drain before the finale, monotone sample check |
| Queue overflow / read error / process loss | abort explicitly, close resources, no silent continuation |
| Short chunk hallucinates | first partial from 3 s, two agreements, do not blanket-discard a short final |
| Long pause blocks the window | audio watermark independent of commit/VAD; pause regression test |
| Agreement cache deleted on every shift | preserve overlap history, count by audio state instead of commit generation |
| Duplicate words / sentence-part loss at window boundaries | time/sequence anchor, conservative commit distance, repair or explicit takeover |
| Fixed token limit truncates text | budget with full window length, saturation detectable, retry/error |
| TDT hangs at duration 0 | reference rule plus step/emission limit; never silently truncate the run |
| Blank/duration misinterpreted | pinned tensor contract and reference decoder comparison as a gate |
| Shift/caps lock falsifies ASR | commit text directly; keyboard modifiers apply only to manual typing |
| Cursor moved / user types | invalidate editor generation/ownership, no further automatic revision |
| Emoji/diacritics half-deleted | grapheme boundaries plus UTF-16 length conversion |
| InputConnection null, app changes text | no write attempts without ownership; partial failure without blind retry |
| Final correction deletes stable/third-party text | revise the entire own span only with proven ownership; otherwise offer explicitly |
| Download aborted / disk full / wrong hash | temporary file, resume check, active version unchanged, space check |
| Graph does not fit / ORT error / native OOM | upfront smoke test, close sessions, keyboard stays usable; no retry storm |
| Microphone permission/privacy switch revoked | stop capture, understandable hint, no circumventing the system switch |
| FGS start forbidden / notification refused | handle the start error explicitly, visible setup; the notification duty remains |
| BT routing takes long / headset disconnected / call | STARTING with timeout, check the actual route, controlled stop |
| Screen off / IME hidden / editor changed | end recording immediately, discard third-party editor updates |
| CPU thermally throttled / battery/RAM pressure | collapse partials, update less often, trim memory; no false real-time promise |
| JSON/dead-key/case errors | import validation, reference tests per language, built-in fallback |
| Empty/silent audio | no dummy text, an empty result allowed; do not discard a long remainder recording just because of VAD |

## 10. Test strategy and release gates

### 10.1 Unit tests without Android, microphone, or the large model

- FakeAudioSource delivers PCM with sequences, pauses, late reads, and errors;
  a virtual clock controls DOWN/UP/timeouts, no real-time sleeping.
- FakeAsrEngine delivers hypotheses by window/audio state, including
  contradicting prefixes, time drift, overlap duplicates, errors, and budget
  overrun.
- FakeEditorSink models text, selection, UTF-16 lengths, external changes, and
  individual failing calls; check ownership and "no third-party deletion" as
  invariants.
- LocalAgreement: first agreement, word end, punctuation, Unicode, stable
  prefixes, short and 300-s recordings, window switch without history loss.
- TDT via a fake joint: separate/combined heads, blank, duration index ≠
  value, duration 0, state only on correct label transitions, invalid
  lengths/NaN.
- Layout schema and all 15 assets: complete layers, letter coverage,
  shift/caps, TR-i, EL diacritics, dead-key fallback, emoji graphemes.
- Scheduler properties: the watermark advances during silence, at most one
  run, obsolete results ineffective, shutdown delivers samples exactly once in
  order.

### 10.2 Instrumented/emulator tests on API 36

Headless AVD with the SDK/emulator paths documented in TECH_FACTS; activate
the test IME. Test host app with single-/multi-line EditText, password,
number, selection, Unicode, editor actions, and a deliberately restricted/
transforming InputConnection. UI tests type on all layers, switch layouts,
hold the mic, swipe abort, and move the cursor during fake inference. adb text
input alone does not prove the IME. Inject fake audio/ASR/download backends
only in the test/debug build; the production APK has no externally activatable
WAV/text injection interface. Test the foreground service, permissions,
notification refusal, activity/IME lifecycle, process death, and restart. The
emulator cannot prove real BT/thermal behavior.

ORT integration without 671 MB: tiny self-generated ONNX test graphs check
tensor lifetime, data types, and error paths. They do not prove Parakeet
quality. A fake download server delivers small files, ranges, ETag changes,
connection drops, wrong checksums, and storage scarcity. CI stays model-free/
offline by default.

### 10.3 WAV injection and the real model path

A separate opt-in test job uses once locally cached, hash-verified original
artifacts; no automatic large download per test run. Inject WAV files directly
into the audio source: PCM16/16 kHz mono plus rate/resampler cases with a
known reference. A fixture with a 4-s pause reproduces the desktop window bug;
compare a reference decode over the whole short WAV against composed windows,
not just the character count. Further cases: 0.3/1/3 s, 16-s window
boundaries, 300 s, long silence, number words, code switching, all 15
languages, repetitions, and as-similar-as-possible words in the overlap.
Report WER/CER, omitted/duplicated words, and final/live latencies separately.
Document fixture licenses and consent; do not collect user audio in CI.

### 10.4 Release gates on a real GrapheneOS device

- Install the APK, activate the IME, complete the model setup, revoke network,
  restart the app/phone: keyboard and ASR remain usable offline.
- Demonstrate internal, wired/USB, and Bluetooth microphones with a real
  route; document hardware combinations; do not derive "Bluetooth supported"
  from the emulator alone.
- The visible IME may legally start the mic FGS; release, privacy switch, call,
  screen-off, and app switch free the microphone, no secret post-recording.
- No third-party text is deleted in cursor/lifecycle stress tests; zero
  tolerance.
- Pause/overlap fixtures lose/duplicate no reference sentence part; ambiguous
  transitions trigger the safe error path, not a silent success.
- Measure peak RSS, model load time, warm p95 latencies, a 5-min thermal
  test, and battery. If the device does not meet the live targets, keep the
  risk open instead of inventing values.
- The release contains real signed model hashes, verified tensor contracts,
  and licenses.

## 11. Non-goals and open risks

Non-goals: cloud ASR, additional models/backends, NNAPI/GPU, always-on/wake
word, background audio recording without a visible session, hardware PTT/
media-button support, beam search, translation, speaker separation, learning
autocorrect, swipe typing, clipboard synchronization, GIFs, plugins, and a
complex theme editor. The Bluetooth microphone is mandatory; remote control via
its hardware key is not. No production-side permanently stored audio recorder
and no automatic recovery of an interrupted dictation after process loss.

| Risk | Severity | Resolution / release criterion |
|---|---|---|
| CPU/RAM insufficient for 0.6B and live partials | high | ARM64 profiler, peak RSS/5-min test; do not silently swap the mandatory model |
| Whole buffer over 300 s overloads CPU/RAM | high | device measurement; do not change the default without a documented release |
| Experimental chunk quality deviates from whole-buffer | high | keep disabled until the pause/edge-case corpus and the repair path pass |
| ONNX TDT contract not yet documented | blocking | graph/reference-decode protocol before building the decoder |
| Trustworthy hashes/revision/license missing | blocking for release | released manifest and license check |
| Mic FGS start from a visible IME on API 36 | high | early instrumented/GrapheneOS spike, visible setup fallback |
| Target app delivers an unreliable InputConnection | high | conservative ownership check, stop automatic output on uncertainty |
| Bluetooth routes vary widely | high | test real devices/headsets, time limit and source display |
| 15 layouts linguistically incomplete | medium | native-speaker acceptance plus automated character coverage |
| 671-MB setup fails on network/disk | medium | resume, space check, keyboard independently usable |
| Short incomplete sentences hallucinate | medium to high | minimum context, agreement, explicitly empty/uncertain instead of invented text |

Recommended implementation steps: first tensor/FGS/performance spikes, then the
model-free keyboard and safe editor logic, then the WAV pipeline/LocalAgreement,
finally real capture/routing and device acceptance. Do not hide a risky chunk
heuristic behind a pretty live animation; data integrity takes precedence over
latency.
