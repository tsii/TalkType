# ASR contract: measured, not assumed

Generated on 2026-10-27 in this environment with `onnxruntime 1.31.1` (CPU EP,
8 threads) against the files hashed below. **This file is the truth for the
Android implementation.** Values from it must not be replaced with guessed
tensor names or axis orders.

> **Revision 3 (2026-10-27, after Rev 2):** §4.4 new — empirical measurement of
> `min_audio_s` with the *corrected* decoder (short windows 1.0–6.0 s, windows
> starting mid-material, silence/noise samples). Result: **`min_audio_s = 2.0 s`**;
> 1.0 s is refuted, 3.0 s is not supported. §4.3 (point 4, final paragraph),
> §6 (emission rate) and §7 (point 8) have been updated accordingly.
>
> **Revision 2 (review 2026-10-27):** The reference decoder had two errors
> (the PredNet state was also advanced on blank; `MAX_SYMBOLS_PER_FRAME`
> was implemented as a global instead of a per-frame limit) and did not filter
> special tokens. As a result, three of four golden texts were wrong and the
> claim "short windows degrade" was an artifact of the error. §3, §4, §5 and §6
> are corrected; the deviation log is in §4.3.
>
> Reference implementation of the export (authoritative for semantics):
> `istupakov/onnx-asr`, `src/onnx_asr/asr.py` → `_AsrWithTransducerDecoding._decoding`
> and `src/onnx_asr/models/nemo.py` → `NemoConformerTdt._decode`.

## 1. Pinned artifacts (SHA-256, measured)

```
eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70  decoder_joint-model.int8.onnx   18.202.004 B
6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09  encoder-model.int8.onnx        652.183.999 B
a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f  nemo128.onnx                       139.764 B
d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d  vocab.txt                           93.939 B
```

Source: `https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/<file>`
(HF revision of the repo: `8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce`, checked on 2026-10-27).
These hashes belong in the app's signed model manifest.

`config.json` of the export (complete, 97 B):

```json
{"model_type":"nemo-conformer-tdt","features_size":128,"subsampling_factor":8}
```

**Consequence:** `max_tokens_per_step` and `max_sequence_length` are missing from
the config. The reference implementation then uses defaults:
`max_tokens_per_step = 10`
(`_AsrWithTransducerDecoding` / `NemoConformerRnnt._max_tokens_per_step`) — the
Android implementation MUST carry this value as a constant and must not expect it
from the config. The app-side config check must therefore not insist on these
keys.

Language coverage of the model (model card `nvidia/parakeet-tdt-0.6b-v3`, HF
revision `541d1f99c6b0c3cd0b11a95167540bb8edefd82b`, 25 languages): `en es fr de bg
hr cs da nl et fi el hu it lv lt mt pl pt ro sk sl sv ru uk`. **No Turkish, no
Norwegian** — see `docs/LAYOUT_REQUIREMENTS.md` §5 (layout ≠ ASR language support).

## 2. Tensor contract (read out via `InferenceSession.get_inputs()/get_outputs()`)

Named axes are dynamic; the values given in parentheses are the
configurations actually used/observed.

### nemo128.onnx — mel frontend (NeMo, 128 bins)
| Direction | Name | Type | Shape |
|---|---|---|---|
| IN | `waveforms` | float32 | `[B, N]` (16 kHz raw samples, [-1,1]) |
| IN | `waveforms_lens` | int64 | `[B]` |
| OUT | `features` | float32 | `[B, 128, T]` |
| OUT | `features_lens` | int64 | `[B]` |

### encoder-model.int8.onnx — Conformer encoder
| Direction | Name | Type | Shape |
|---|---|---|---|
| IN | `audio_signal` | float32 | `[B, 128, T]` |
| IN | `length` | int64 | `[B]` |
| OUT | `outputs` | float32 | `[B, 1024, T/8]` |
| OUT | `encoded_lengths` | int64 | `[B]` |

### decoder_joint-model.int8.onnx — prediction net + joint (TDT)
| Direction | Name | Type | Shape |
|---|---|---|---|
| IN | `encoder_outputs` | float32 | `[B, 1024, T]` — **usage: T = 1** (one frame/step) |
| IN | `targets` | int32 | `[B, U]` — **usage: U = 1** (last label; start = `<blk>`) |
| IN | `target_length` | int32 | `[B]` (value: number of targets = 1) |
| IN | `input_states_1` | float32 | `[2, B, 640]` (LSTM h) |
| IN | `input_states_2` | float32 | `[2, B, 640]` (LSTM c) |
| OUT | `outputs` | float32 | `[B, T, U, 8198]` → with usage `[B, 1, 1, 8198]` |
| OUT | `prednet_lengths` | int32 | `[B]` (unused) |
| OUT | `output_states_1` | float32 | `[2, B, 640]` |
| OUT | `output_states_2` | float32 | `[2, B, 640]` |

Axis order of `outputs` **measured** (not guessed): T=1,U=1 →
`(1,1,1,8198)`; T=2 → `(1,2,1,8198)`; T=1 with two targets → `(1,1,2,8198)`;
T=5 → `(1,5,1,8198)`. So **`[B, T, U, 8198]`** — the index `logits[0, 0, 0]` is
correct at T=U=1; with buffered frames you must not index `[0, 0, t]`. The axis
is dynamic, `T > 1` runs without an error; the predictor output `g` is identical
for all T in that case (same label/same state), which is why T>1 is only usable
for pure blank-skip batching, not for label iteration.

**Logit split (verified):** `8198 = 8193 vocab + 5 duration classes`.
`[0..8192]` = token logits, `[8193..8197]` = duration logits for {0,1,2,3,4}
encoder frames. No softmax needed (argmax).

**Subsampling:** factor 8 from `config.json`; 1 encoder frame = 8 × 10 ms =
**80 ms**. Measured mapping `frames = ceil(mel_T / 8)`: 640→80, 990→124,
280→35, 311→39. `frames` comes from `encoded_lengths[0]`; the Android
implementation must additionally clamp the value against `outputs.shape[2]`
(with int8/TensorRT exports, `encoded_lengths` can exceed the tensor width —
onnx-asr does exactly that).

### 2.4 Vocabulary and special tokens

`vocab.txt`: 8193 lines `<token> <id>`, IDs 0..8192 gapless and ascending,
**no** token contains a space, `<blk> = 8192` is the last line.
`▁` (U+2581) exists as its own token in the vocabulary and marks the word start.

Special tokens (265 of them, pattern `<...>`):

| IDs | Content |
|---|---|
| 0..233 | `<unk>`, `<pad>`, `<|nospeech|>`, `` , `<|startoftranscript|>`, `<|pnc|>`, `<|noitn|>`, `<|timestamp|>`, `<|diarize|>`, `<|emo:…|>`, **`<|aa|>`…`<|zu|>` (ISO language prompts, incl. `<|de|>`=78)**, `<|0|>`…`<|10|>`, `<|spk0|>`…`<|spk15|>` |
| 234..243 | **`"0"`…`"9"` — regular vocabulary, do NOT filter** |
| 244..273 | `<|spltoken0|>`…`<|spltoken29|>` |
| 8192 | `<blk>` (blank, never emit) |

**Filter rule (binding):** discard a token if the token string starts with `<`
and ends with `>`, plus ID 8192. Do **not** filter by ID ranges — a rule like
"discard IDs < 274" would destroy the digits 0–9 (empirically checked with
`tools/reference_decode.py` on a digit test audio: `4711` and `2026` survive only
with the pattern rule).

**Detokenization (`_AsrWithDecoding.DECODE_SPACE_PATTERN`):**
concatenate token strings, `▁` → space, then apply
`re.sub(r"\A\s|\s\B|(\s)\b", …)` (remove leading spaces, spaces before
punctuation/after word end), finally trim. For all four golden WAVs the result
is identical to "concatenate, `▁`→space, `strip()`"; the regex is nevertheless
the reference because it normalizes e.g. `" ,"` to `","`.

## 3. Verified greedy algorithm (reference: `tools/reference_decode.py`)

```
state1 = state2 = zeros([2,1,640]); last_label = 8192 (<blk>); t = 0; emitted = 0
while t < frames:
    logits, prednet_lengths, new1, new2 =
        decoder_joint(enc_out[:, :, t:t+1], [[last_label]], [1], state1, state2)
    v = logits[0, 0, 0]                       # [8198]
    token = argmax(v[0:8193]); dur = argmax(v[8193:8198])
    if token != 8192:                          # non-blank
        state1, state2 = new1, new2            # (a) take over state ONLY here
        emit(token); last_label = token; emitted += 1
    if dur > 0:
        t += dur; emitted = 0                  # (b) also applies on blank
    elif token == 8192 or emitted >= 10:       # (c) blank or symbol budget
        t += 1; emitted = 0
```

The three places where a naive implementation produces measurably wrong texts
(all three counter-checked in this environment):

**(a) Advance PredNet states only on non-blank.** On blank, the same state is
fed again (the prediction net is re-evaluated with the same blank label — the
LSTM state must not keep running). Counter-check with "also take over state on
blank": `de_pause` loses the word "nach" (`… Und einer Pause kommt …`), `de_part1`
disintegrates into `Das ist der ersteil des Dikt Arts.` An earlier version of
this document claimed the opposite in one sentence ("states are taken over at
every step") and described the correct rule in the same sentence. The reference
(`asr.py`, `prev_state = state` only in the `token != blank` branch) is
unambiguous; now also implemented that way in the code.

**(b) `t += max(dur, 1)` on blank is correct — and `t += 1` is not.**
Unlike classical RNNT, the duration logits of this export are also meaningful
for blank steps (silence skip). Counter-check with `t += 1` on blank on
`de_pause`: 84 instead of 40 steps yields `… des Diktats Und einer Pause …`
(punctuation gone) — measurably worse. So `max(dur, 1)` does not distort the
alignment; it only prevents stalling at `dur == 0`
(in the golden WAVs, `dur == 0` never occurred on blank; `dur == 0` occurs on
non-blank and then correctly stays on the same frame). Proof of the alignment
significance: in `de_pause` the token timestamps jump from 2.08 s to 6.16 s —
the 4-s pause is skipped via durations (§4.2).

**(c) `MAX_SYMBOLS_PER_FRAME` is a per-frame limit, not a global one.**
After 10 emissions within *one* encoder frame, `t += 1` must be forced
(prevents infinite loops with `dur == 0` + repeated non-blank). A global limit
("10 × frames tokens") is ineffective because up to `10 × frames` symbols could
still arise in a single frame.

Additional rules: argmax without softmax; `frames = min(encoded_lengths[0],
outputs.shape[2])`; the encoder is computed **once** over all frames per window,
the decoder frame by frame. `prednet_lengths` is not needed.

## 4. Golden data (newly measured after the correction)

Audio: `testdata/*.wav`, 16 kHz mono PCM16, synthesized with `espeak-ng -v de`
( robotic, but sufficient for pipeline proofs).

### 4.1 Reference texts + token IDs

| File | Duration | frames/mel | steps | Tokens | Reference text |
|---|---|---|---|---|---|
| `de_smoke.wav` | 6,40 s | 80 / 640 | 32 | 27 | `So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.` |
| `de_pause.wav` | 9,90 s | 124 / 990 | 40 | 25 | `Das ist der erste Teil des Diktats. Und nach einer Pause kommt der zweite Teil.` |
| `de_part1.wav` | 2,80 s | 35 / 280 | 21 | 12 | `Das ist der erste Teil des Diktats.` |
| `de_part2.wav` | 3,10 s | 39 / 311 | 15 | 13 | `Und nach einer Pause kommt der zweite Teil.` |

Token IDs (for Android tests: compare token IDs first, then text — that way
detok errors are distinguishable from decode errors):

```
de_smoke : [1960, 7877, 7036, 4189, 1076, 317, 2893, 1607, 7868, 7877, 601, 615,
            360, 6085, 7918, 7995, 1297, 1240, 1593, 1044, 357, 567, 4949, 5027,
            1240, 5102, 7883]
de_pause : [3211, 929, 623, 468, 974, 1769, 305, 594, 360, 2995, 1780, 7883, 2413,
            3189, 4379, 3000, 1163, 758, 6338, 623, 6099, 582, 1769, 305, 7883]
de_part1 : [3211, 929, 623, 468, 974, 1769, 305, 594, 360, 2995, 1780, 7883]
de_part2 : [2413, 3189, 4379, 3000, 1163, 758, 6338, 623, 6099, 582, 1769, 305, 7883]
```

The spoken text was in each case `Das ist der erste Teil des Diktats.` / `Und nach
einer Pause kommt der zweite Teil.` / `So, jetzt bin ich gespannt, ob die
Diktierfunktion auf dem Telefon funktioniert.` — i.e. `de_smoke` contains a **real
model error** (`Dikti-Funktion` instead of `Diktierfunktion`, including the inserted
hyphen token 7918). The golden test must pin the model output, not the desired
text; `de_smoke` is therefore only suitable as a regression test, not as a
quality gate.

### 4.2 Alignment proof (token timestamps, 1 frame = 80 ms)

```
de_pause : 0.00 0.24 0.48 0.64 0.88 1.04 1.20 1.44 1.68 1.84 2.08 | 6.16 6.48 6.96 …
de_part1 : 0.00 0.24 0.48 0.64 0.80 1.04 1.20 1.44 1.68 1.84 2.00 2.48
```

The jump 2.08 s → 6.16 s in `de_pause` is the skipped pause; it proves that blank
durations carry alignment in this export (§3b).

### 4.3 Deviation log (Rev 2)

| File | Documented through Rev 1 | Correct (Rev 2) | Cause |
|---|---|---|---|
| `de_smoke.wav` | `… ob die Diktive Funktion auf dem Telefon funktioniert.` | `… ob die Dikti-Funktion auf dem Telefon funktioniert.` | state update on blank (§3a) |
| `de_pause.wav` | `… Und einer Pause kommt der zweite Teil.` | `… Und nach einer Pause kommt der zweite Teil.` | state update on blank (§3a) |
| `de_part1.wav` | `Das ist der ersteil des Dikt Arts.` | `Das ist der erste Teil des Diktats.` | state update on blank (§3a) |
| `de_part2.wav` | unchanged | unchanged | — |

**Consequence for the test strategy:** the old claim "the same material decoded
as a 2.8-s window is worse than in the full context" is **refuted** — it was an
artifact of the state error. With a reference-conformant decode, `de_part1.wav`
(2.8 s) yields exactly the sentence that `de_pause.wav` yields in context.
Thus `de_part1`/`de_pause` are **no longer** an empirical argument for
`min_audio_s = 3.0` or for the whole-buffer default. The **new** measurement
(§4.4, short windows 1.0–6.0 s) replaces this argument: it supports
`min_audio_s = 2.0 s` and removes the basis for 3.0 s. The whole-buffer default
remains correct, but for deadlock freedom and commit logic (KONZEPT.md §5.1),
**not** for window quality. `docs/KONZEPT.md` (3.4 / §4 configuration / §5.1)
needs to be sharpened at this point — deliberately not changed in this review,
see §4.4.6.

**Test criterion that does NOT work this way:** "a pipeline with a pause must
deliver at least as many characters as the sum of the two partial windows." For
this material that is a tautology: 35 characters (`de_part1`) + 43 characters
(`de_part2`) = 78, and the full text is **exactly** `part1 + " " + part2`
(= 79 characters, verified character by character). The criterion is always
fulfilled by the construction of the test files and cannot reveal any pipeline
error; for other cuts the sum of the partial transcriptions is an **arbitrarily**
bad lower measure (partial windows duplicate punctuation and break apart at
word boundaries). Replacement criteria:

1. `de_pause` → **exactly** the reference text from §4.1 (or the token IDs).
2. `de_smoke` → exactly the reference text (incl. the model error).
3. For the pause pipeline: the result contains both anchor words (`Diktats`,
   `zweite`) and is not empty; report deviations only as a quality regression.
4. Short audio must **not** count as "text present": measured, 0.3 s of digital
   silence yields `Yeah.` (reproduced character for character, §4.4.4), 0.5 s of
   white noise at −30 dBFS also `Yeah.`, 1.5 s at −50 dBFS `No.`.
   From 2.0 s onward, **all** silence/noise samples yielded an empty result
   (9 samples, 0 hallucinations). So the guard (`min_audio_s`) protects
   against invented words, not just against idle runs — but only in the range
   **below 2 s**; above that, length is not the problem, but a long silence tail
   (§4.4.5 F5).

### 4.4 Empirical `min_audio_s` measurement (corrected decoder)

**Why re-measured:** the justification for `min_audio_s = 3.0` was the comparison
`de_part1` (2.8 s) against `de_pause` — and that rested on the state error from
§3a (§4.3). After the correction, that argument is gone; the threshold must
therefore be re-determined from short windows instead of being inherited from
the old claim.

**Setup (reproducible, deterministic):**

```bash
.venv-asr/bin/python tools/make_cut_windows.py        # testdata/cut/*.wav + manifest.json
.venv-asr/bin/python tools/measure_min_audio.py --markdown
```

- Windows: cuts from `testdata/de_smoke.wav` (6.40 s) and `testdata/de_pause.wav`
  (9.90 s; 0–2.3 s speech, 2.4–6.7 s **digital silence**, 6.8–9.5 s speech) in
  the lengths 1.0 / 1.5 / 2.0 / 2.5 / 3.0 / 4.0 / 6.0 s, PCM16, exact sample count.
- Additionally windows that start **mid-material** (`de_smoke` from 2.0 s,
  `de_pause` from 6.0 s) — the realistic streaming case where the buffer does
  not begin at the sentence start.
- Silence/noise samples 0.3–4.0 s (digital silence as well as white noise at
  −50 and −30 dBFS RMS, fixed seed) as a hallucination test.
- Decoder: `tools/reference_decode.py` **Rev 2** (reference-conformant, §3),
  models from `.models/`. Argmax is deterministic; repetition yields the same IDs.
- Raw data: `testdata/cut/min_audio_measurements.json` (texts, token IDs,
  metrics per window), fixture list: `testdata/cut/manifest.json`.

Metrics: **prefix** = number of leading token IDs that exactly match the full
context; **sim.** = 1 − Levenshtein/max(len) against the full text (naturally
< 1 for partial windows); **in text** = fraction of window characters that occur
as a contiguous block in the full text (the measure for "no garbage" for windows
starting mid-material).

#### 4.4.1 Full context (reference, unchanged from §4.1)

| File | Duration | Tokens | Text |
|---|---|---|---|
| `de_smoke.wav` | 6,40 s | 27 | `So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.` |
| `de_pause.wav` | 9,90 s | 25 | `Das ist der erste Teil des Diktats. Und nach einer Pause kommt der zweite Teil.` |

#### 4.4.2 Head windows (cut from 0 s)

| Window | Duration | Tokens | tok/s | Prefix | exact | Sim. | in text | Text |
|---|---|---|---|---|---|---|---|---|
| `de_smoke_1.0s` | 1,0 s | 4 | 4,0 | 0/27 | no | 0,06 | 0,71 | `Oh yes.` |
| `de_smoke_1.5s` | 1,5 s | 8 | 5,3 | 5/27 | no | 0,23 | 0,90 | `So, jetzt bin ich...` |
| `de_smoke_2.0s` | 2,0 s | 10 | 5,0 | 9/27 | no | 0,34 | 1,00 | `So, jetzt bin ich gespannt.` |
| `de_smoke_2.5s` | 2,5 s | 12 | 4,8 | 12/27 | **yes** | 0,43 | 1,00 | `So, jetzt bin ich gespannt, ob die` |
| `de_smoke_3.0s` | 3,0 s | 18 | 6,0 | 13/27 | no | 0,56 | 0,92 | `So, jetzt bin ich gespannt, ob die Diktative...` |
| `de_smoke_4.0s` | 4,0 s | 19 | 4,8 | 19/27 | **yes** | 0,67 | 1,00 | `So, jetzt bin ich gespannt, ob die Dikti-Funktion auf` |
| `de_smoke_6.0s` | 6,0 s | 27 | 4,5 | 27/27 | **yes** | 1,00 | 1,00 | full text (identical) |
| `de_pause_1.0s` | 1,0 s | **0** | 0,0 | 0/25 | no | 0,00 | — (empty) | *(empty)* |
| `de_pause_1.5s` | 1,5 s | 8 | 5,3 | 7/25 | no | 0,29 | 1,00 | `Das ist der erste Teil.` |
| `de_pause_2.0s` | 2,0 s | 12 | 6,0 | 12/25 | **yes** | 0,44 | 1,00 | `Das ist der erste Teil des Diktats.` |
| `de_pause_2.5s` | 2,5 s | 12 | 4,8 | 12/25 | **yes** | 0,44 | 1,00 | `Das ist der erste Teil des Diktats.` |
| `de_pause_3.0s` | 3,0 s | 12 | 4,0 | 12/25 | **yes** | 0,44 | 1,00 | `Das ist der erste Teil des Diktats.` |
| `de_pause_4.0s` | 4,0 s | 12 | 3,0 | 12/25 | **yes** | 0,44 | 1,00 | `Das ist der erste Teil des Diktats.` |
| `de_pause_6.0s` | 6,0 s | 8 | 1,3 | 0/25 | no | 0,17 | 0,74 | `Assist the Ailvesti` |

#### 4.4.3 Windows starting mid-material

| Window | Duration | Tokens | tok/s | Sim. | in text | Text |
|---|---|---|---|---|---|---|
| `de_smoke_t2.0_1.0s` | 1,0 s | 9 | 9,0 | 0,17 | 0,65 | `Up the dictators.` |
| `de_smoke_t2.0_2.0s` | 2,0 s | 9 | 4,5 | 0,27 | 0,82 | `Ob die Dikt die Funktion auf` |
| `de_smoke_t2.0_3.0s` | 3,0 s | 13 | 4,3 | 0,43 | 0,94 | `Ob die Diktfunktion auf dem Telefon.` |
| `de_pause_t6.0_1.0s` | 1,0 s | 4 | 4,0 | 0,11 | 0,82 | `Understand.` |
| `de_pause_t6.0_2.0s` | 2,0 s | 6 | 3,0 | 0,27 | **1,00** | `Und nach einer Pause.` |
| `de_pause_t6.0_3.0s` | 3,0 s | 13 | 4,3 | 0,42 | 0,87 | `Und nach einer Pause kommt Witz weiter.` |

#### 4.4.4 Silence and noise (hallucination test, 21 samples)

| Sample | 0,3 s | 0,5 s | 1,0 s | 1,5 s | 2,0 s | 3,0 s | 4,0 s |
|---|---|---|---|---|---|---|---|
| digital silence | `Yeah.` ❌ | empty | empty | empty | empty | empty | empty |
| noise −50 dBFS | empty | empty | empty | `No.` ❌ | empty | empty | empty |
| noise −30 dBFS | empty | `Yeah.` ❌ | empty | empty | empty | empty | empty |

All samples from 2.0 s (9 of them) yield **0 tokens**; hallucinations occur
exclusively at ≤ 1.5 s. The 0.3-s silence hallucination `Yeah.` from §4.3
point 4 is thereby reproduced character for character.

#### 4.4.5 Findings

- **F1 — 1.0 s is not enough.** All four 1.0-s windows fail: `Oh yes.`
  (sim. 0,06), `Up the dictators.`, `Understand.` (three foreign-language
  hallucinations) and, for `de_pause`, an **empty** result although the file
  contains speech in that second (total failure, not "no text present").
- **F2 — 1.5 s is borderline.** `de_pause` yields 7 tokens exactly right but
  breaks off before "des Diktats"; `de_smoke` ends with the ellipsis artifact
  `So, jetzt bin ich...`; the noise sample at −50 dBFS hallucinates `No.`.
- **F3 — 2.0 s holds.** `de_pause` yields an exact prefix of the full text with
  12 tokens (complete sentence), `de_smoke` a clean sentence end
  `So, jetzt bin ich gespannt.` — the first 9 tokens match exactly, only the
  10th deviates (`.` instead of `,` because the sentence ends in the window).
  The windows starting mid-material are also German and correct word by word
  at 2.0 s (`Und nach einer Pause.` — in text 1,00). **No hallucination.**
- **F4 — longer is not better.** `de_smoke` at 3.0 s cuts mid-word and yields
  `… ob die Diktative...` (18 tokens, 6.0 tok/s, sim. 0,56) — worse than 2.5 s
  (12 tokens, exact prefix). `de_pause` yields **identically** the same 12
  tokens at 2.0 / 2.5 / 3.0 / 4.0 s. So above 2.0 s there is no measurable
  quality gain that would justify 3.0 s.
- **F5 — silence is harmless, a silence tail is not.** Digital silence up to
  4.0 s and noise up to 4.0 s produce no text from 2.0 s. But a 6.0-s window
  containing 2.3 s of speech + 3.7 s of silence (`de_pause_6.0s`) drifts to
  `Assist the Ailvesti` (sim. 0,17). `min_audio_s` counts **wall-clock audio,
  not speech share** — the guard protects against *short* buffers, not against
  *silence-heavy* ones.
- **F6 — rate correction for §6.** Short windows reach 6–10 tokens/s
  (`de_smoke_3.0s` 6.0; `de_smoke_t2.0_1.0s` 9.0; `silence_0.3s` 10.0), not
  only the 5.3 from §6. Speech windows ≥ 2.0 s are at 3.0–6.0 tokens/s; the
  downward outlier is `de_pause_6.0s` at 1.3 tokens/s — two thirds of that
  window consist of silence.

#### 4.4.6 Decision

**`min_audio_s = 2.0 s`** (instead of 3.0 s). Justification from the measurements:

1. **1.0 s is refuted** (F1: 3 hallucinations, 1 total failure in 4 windows).
2. **1.5 s is the last observed hallucination length** (F2, F5) — 2.0 s leaves
   0.5 s of margin and is the smallest value without a failure case (F3: 23
   samples ≥ 2.0 s, of which 14 with speech, **0 hallucinations**).
3. **3.0 s brings no measurable benefit** (F4) and costs 1.0 s of latency until
   the first output — with `asr_interval_s = 1.0`, the first partial appears at
   ~2 s instead of ~3 s with a 2.0-s guard.
4. Anyone wanting more margin takes **2.5 s** (measurement-equivalent to 3.0 s,
   0.5 s earlier). **The hard lower bound remains 2.0 s** — below that,
   hallucination is proven.

Consequences for the pipeline (binding):

- The guard applies **only** to automatic re-transcription during the recording.
  The **final** decode at `UP` transcribes **every** length — a 1.2-s dictation
  must not be lost (data loss outweighs the hallucination risk; the result can
  then be discarded, but must not be suppressed).
- `min_audio_s` is **not** a replacement for a silence check (F5).
  Recommendation: with an empty result and a buffer whose last ≥ 2 s are below
  the RMS noise floor, do **not** commit (the first-partial release is withheld).
- A first partial at 2.0 s can end mid-word (F3: `Ob die Dikt die Funktion auf`).
  That is uncritical as long as the commit logic does not freeze the text
  before LocalAgreement has two confirmations (KONZEPT.md 2.6).
- Tests: `min_audio_s` boundary case as a golden fixture — `de_smoke_1.0s.wav`
  (must be **discarded/blocked**) and `de_pause_2.0s.wav` (must be transcribed)
  are ready for this in `testdata/cut/`.

**Qualified statements in `docs/KONZEPT.md` (deliberately not changed there):**

| Place | Statement there | Qualified by | Consequence |
|---|---|---|---|
| 3.4 | Parakeet hallucinates on short chunks; `min_audio_s` **≥ 1.0 s**, better 2.5–3.0 s | F1: 1.0 s is measurably unusable; F3: 2.0 s suffices | raise the lower bound to 2.0 s; "better 2.5–3.0" is correct as an upper bound, not as a necessity |
| §4 (configuration) | `min_audio_s = 3.0` | F4: no advantage over 2.0 s | set the value to 2.0 (or 2.5 as a compromise) |
| §5.1 | `windowed` streaming failed (137–139 instead of 256 characters) | the deadlock reasons remain valid; *window quality* as a justification does not — 2.0-s windows deliver exact prefixes | `whole_buffer` stays the default, but justified by deadlock freedom and commit logic, not by short windows |
| 8 (checklist) | "guards: `min_audio_s` ≥ 1 s" | F1/F3 | sharpen to 2.0 s |

## 5. Measured values x86 (plausibility anchor, **not** an ARM expectation)

| Audio | mel | encoder | decoder | RTF total |
|---|---|---|---|---|
| 6,40 s | 0,004 s | 0,090 s | 0,009 s | 0,016 |
| 9,90 s | 0,006 s | 0,124 s | 0,004 s | 0,014 |
| 2,80 s | 0,002 s | 0,060 s | 0,003 s | 0,023 |
| 3,10 s | 0,002 s | 0,070 s | 0,013 s | 0,027 |
| 48,9 s (6 sentences, repeated) | — | — | — | 0,024 |

(8 threads, varying container load → ±50% spread; the RTF is practically
length-independent. The encoder dominates; with ≤ 40 steps the decoder is
negligible.)

**Memory (measured, maxRSS in the same process):** after loading `nemo128` 44 MB,
**after loading the encoder 848 MB**, with decoder 863 MB, during a 9.9-s inference
**926 MB peak**. That is the hard constraint for device selection:
~0.9 GB RSS just for the model (int8!), independent of the Java heap
(`android:largeHeap` does not help, it is native memory). On ARM64 a similar
weight footprint is to be expected; the app must measure before/after and
degrade cleanly on OOM (KONZEPT.md 3.2 / DESIGN.md §9).

On an ARM64 smartphone, 5–20× slower is realistic (RTF 0.1–0.4). This number is
an **estimate**, not a measurement; the app must display latency on the device.

## 6. Token budget: dimensioning against 8193 vocab

Formula (from KONZEPT.md 3.1 / DESIGN.md §5.2, adopted unchanged):

```
B(s) = max(256, min(4096, ceil(12 * s) + 64))
```

Measured emission rates (same pipeline, corrected decoder):

| Material | Tokens/s |
|---|---|
| de_pause (9.9 s) | 2.5 |
| de_smoke (6.4 s) | 4.2 |
| de_part1 (2.8 s) | 4.3 |
| digit/account-number dictation (7.0 s) | **5.3** (digits are single tokens, IDs 234–243) |
| spelled alphabet (7.7 s) | 1.6 |
| 48.9 s repeated dictation | 3.2 |
| short windows 2.0–4.0 s (§4.4.2/§4.4.3) | 3.0–6.0 |
| short window 1.0 s (§4.4.3) | 9.0 |

Assessment:

- **12 tokens/s is adequately dimensioned** — but not with "2.3× reserve":
  the maximum over 2 s is **6.0 tokens/s** (`de_smoke_3.0s`, §4.4.2), in
  windows under 2 s at **9.0–10.0 tokens/s** (few tokens over a short time →
  rate noise, not a speech phenomenon). For the formula this is uncritical
  because `max(256, …)` dominates up to s ≈ 16 s (a 10-s window at 10 tokens/s
  emits 100 tokens against a 256 budget); in the linear part (s > 16 s), all
  measurements are at 2.5–5.3 tokens/s — there the 12/s are at least a factor
  of 2.3 away. The rate is limited by language/speaking rate, not by vocab.
- **`min 256` is uncritical** (corresponds to ~49 s at 5.3 tokens/s).
- **`max 4096` is the real limit:** 4096/12 ≈ 341 s. With a recording cap of
  300 s (KONZEPT.md 3.2), the budget is at 3664 and thus below the cap — the
  4096 are therefore just sufficient, but **no more**, if the recording duration
  is ever raised. On budget exhaustion, the run must be marked as "saturated"
  (DESIGN.md §5.2), otherwise the capping is invisible.
- **The budget is not a runaway protection.** Theoretically, 10 symbols/frame ×
  12.5 frames/s = 125 tokens/s are possible; the only safeguard against infinite
  loops is the per-frame limit from §3c plus frame advancement. Anyone misusing
  the budget as a loop bound gets either 4096 tokens of garbage with a broken
  decoder or — worse — a loop that never terminates.
- The budget applies to the **total length of the submitted audio** including
  left context (KONZEPT.md 3.1), not to the delta share.

## 7. What the Android implementation must mandatorily derive from this

1. `frames = min(encoded_lengths[0], outputs.shape[2])`, `1 frame = 80 ms`.
2. Take over the state only on non-blank; on blank, reuse the same state.
3. `t += dur` (also on blank); at `dur == 0` → `t += 1` only on blank or an
   exhausted per-frame budget (10).
4. Filter special tokens by the pattern `<...>` + ID 8192; keep digit IDs
   234–243.
5. `▁` → space, then the whitespace rules from §2.4, trim.
6. First check token IDs against §4.1, then the text — otherwise debugging is
   blind to detok bugs.
7. Measure and display RTF/latency and RSS on the device; the x86 values from
   §5 are not a release basis.
8. Apply `min_audio_s = 2.0 s` (§4.4.6) only to automatic partials — the final
   decode at `UP` runs for **every** audio length, including under 2 s. The
   guard counts wall-clock audio, not speech share; a long silence tail is not
   covered by it (§4.4.5 F5).
