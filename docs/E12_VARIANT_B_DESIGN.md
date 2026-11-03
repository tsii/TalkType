# E12 – Variant B: latency, switch, and delivery limit

## 1. Decision and binding facts

**Recommendation: (a), honest later start; B exclusively as an explicitly enabled
preview option.** A stays the proven standard engine: ONNX Runtime, dynamic int8,
existing golden test. B is `Arm/parakeet-tdt-0-6b-v3-int8-litert`, LiteRT,
683,298,069 bytes, cached streaming. The handed-over measurements count as facts;
no new runtime measurement/implementation here. Four B models: featurizer, cached
encoder, decoder_step (LSTM), joint_step. The featurizer demands a fixed 121,600
samples at 16 kHz = 7.6 s per raw_wav input. Masks, normalization values,
pre_encode_feature_cache, and previous_sample belong to the contract. Encoder/
decoder states are session-related; cache_last_channel alone needs roughly 18.6 MB.
That is not a total RAM budget: graphs, tensors, copies, and runtime add to it.

## 2. Latency promise and short recordings

For the first unbuffered block, about 7.6 s of audio is collected; inference adds
on top. **No promise "text guaranteed after 8 s" and no unevidenced "more stable
as a trade-off".** Status while holding: **"B (LiteRT) is collecting audio – first
output no earlier than after about 8 s."** After block handover: **"B (LiteRT) is
processing locally …"** The time is recording time, not necessarily pure speech
time; pauses count toward samples.
**ASSUMPTION:** the first full valid input block needs no further unmeasured
waiting time.

(b) is rejected for live hypotheses: the existing mask inputs do not yet evidence
correct short windows, cache progress, or a first output after one second. Buffer
compute load is not the same as genuinely low encoder latency.
(c) is rejected: no automatic long/short detection and no covert A/B switch. The
user picks one engine for the whole dictation; a permanent limit would blur its
identity.

Releasing ends the microphone immediately, independent of the fill level of the
B block. Status then: **"B (LiteRT) is finalizing your recording …"** Short
recordings and the final partial block must not be lost: final processing with
masks/zero padding is its own mandatory contract/quality test. As long as correct
masks, padding, and cache/length progress are not evidenced, B is **not
activatable**; no delivery that silently discards short recordings. No live short
feed as a disguised fallback and no long silent post-recording after UP.
Subsequent hypotheses depend on the verified valid hop and device performance; the
A cadence of 0.3–1 s is not adopted for B unchecked.

## 3. UI switch and model states

Location: existing setup/settings, section **"Speech recognition"**, two options:
- **"A – Proven (ONNX)"**; help text: "Use the proven local recognition."
- **"B – Preview version (LiteRT)"**; help text: "Expect a first output no earlier
  than after about 8 s."
No CPU/NPU advertising: LiteRT alone proves no accelerator in use.

A ready and IDLE: switch immediately, without confirmation. B ready: on first
activation, explicit consent **"B is experimental. Do you want to use B for your
next dictations?"** Actions **"Use B"** / **"Stay on A"**. A later switch
ready/IDLE immediate. During recording, stop/finalization, or a running engine
switch, the switch is locked: **"First finish your dictation."** Normal keyboard
input remains possible. A switch affects only the next dictation and does not
covertly end an active recording.

B missing: **"B requires an additional download: about 683 MB. A remains
available."** Explicit action **"Download B"**, with network grant and
cancellation; no request merely by tapping/marking the option. Consent names the
experimental status, download, and storage requirement; check free space including
staging/old inventory. Progress shows received/total bytes; afterwards hash/
integrity check of all four pinned model parts. No partial inventory counts as an
executable B installation. After the download, A stays active until the user
chooses **"Use B"**; no automatic switch.

B partial: **"B incomplete – resume the download."** B not activatable.
B damaged: **"B model corrupted – download B again."** B not activatable.
B complete but incompatible: **"B cannot be loaded. Use A."** A previously chosen,
now corrupted B installation is displayed as **not ready**, not reported as a
running A. Returning to A requires a deliberate user action. There is no
automatic engine fallback and no automatic repair download.

On AsrNotReadyException: end/release capture, invalidate the output of the
affected session; status **"B is not ready. Check the model or choose A."** The
message persists after release; not replaced by "ready". A corresponding A error
names A. Keep already inserted text, no late results/ghost writing, no silent
IDLE. No exception/stack texts for the user and no audio/text in the error log.
Switch/session generations discard old load/inference results; only one engine
per session. Do not pass B caches between dictations or engines; close old
resources in an orderly way, no unnecessarily parallel resident A/B weights.

## 4. One trustworthy display: actual engine in the diagnostics row

The existing **diagnostics row** receives the actually running engine as its
first field:
**"Diagnosis: computing=B/LiteRT | first=…s RTF=… T=…"**
For A correspondingly **"Diagnosis: computing=A/ONNX | first=…s RTF=… T=…"**.
The source is the actually started backend call including session generation, not
the selection preference. Before the first backend call **"computing=none"**, when
not ready **"computing=none (B not ready)"**. After the run ends
**"last=B/LiteRT"** instead of continuing to pretend "computing=B". Measured
values come exclusively from the same session/engine; reset previous values.
`first` measures from the first captured sample to the first actually applied
text, so including B collection time; if text is missing, "–", not 0. RTF and T
keep their existing documented meaning, no new invented unit/temperature
interpretation.

## 5. Quality limit and release

A keeps §4.1 golden unchanged; B may not inherit its success. For B, **word
identity ≥95 % and character edit distance ≤2 %** are a minimum gate, not a
guaranteed equivalence: word identity as 1−WER with Levenshtein alignment,
character distance as CER; no position-wise token counting. Both limits per
fixture, not hidden by averaging bad cases with good ones; version the reference
texts. Do not remove capitalization/punctuation afterwards to make the limit look
nicer. "No foreign-language output" means no invented translation/foreign language
passage; expected technical terms and code-switching from the reference text
remain allowed. Additionally mandatory: no lost/duplicated words at cache/block
boundaries, short recording, partial block finalization, pause, long recording,
and stop without post-recording. Foreign text protection, capture guards, and
15-language layout function remain mandatory independently of that. If not
reached: B locked as **"preview version not released"**, A still available; no
covert loosening of the metrics and no switching to A under the name B.

**Delivery: preview version, OFF by default, only with explicit consent.** That is
the target decision **after** passing the contract/quality/security gate; the
described model inventory alone does not yet justify an activatable B engine. A
stays the default and return path; an honest later start is not a functional
defect — lost short dictations or an unprovable engine identity would be release
blockers.

**OPEN:** B mask/hop contract, tail tests, actual device latencies, and B quality
evidence.
**VERIFICATION:** pure design decision on handed-over facts; no builds/adb/code
work.
