# German model survey (offline, Android/GrapheneOS) — is a different model worth it?

**Status:** 2026-10-28 · **Role:** comparison/verdict (no code/build changes)
**Basis:** orchestrator measurements (A = 670,619,706 B, 100% word identity on
`testdata/*.wav` against `ASR_CONTRACT.md` §4.1, live dictation on the Pixel green;
B = Arm/LiteRT fast, but 50.2% / language-unstable → see `VARIANT_B_QUALITY.md`).
**No network in this assignment** — all third-party numbers are **[ASSUMPTION]**
(documentation knowledge from memory, not verifiable here) and marked as such.
Decisions do **not** hinge on these numbers, but on architecture and our gates.

## 0. The yardstick that filters everything

1. **Our golden set is exact** (§4.1, 4 WAVs) and on device + desktop **100%**.
   Any switch must at least maintain that — otherwise it is a regression.
2. **Streaming every 0.3–1 s** with `commit/` + `DeltaTyper` (E8: 3 hypotheses,
   2 words unprotected). A model that can only do 30-s windows breaks the architecture.
3. **Offline, one signed sideload APK**, no Google services.

## 1. Candidates in comparison

| # | Candidate | Size (installed) | German quality | Streaming 0.3–1 s? | Android route | Verdict |
|---|---|---|---|---|---|---|
| **A** | **`istupakov/parakeet-tdt-0.6b-v3-onnx` int8 (today)** | **671 MB** | **100% on our golden set** | **yes** (TDT, frame-synchronous) | ONNX Runtime AAR, runs | **reference** |
| A2 | same as **fp32** (`encoder-model.onnx` + `.data`) | **2.44 GB** (+41.8 MB encoder) | ≈ int8, `ASSUMPTION:` ΔWER < 0.5 percentage point | yes | ORT, unchanged | **rejected**: 4× download, no provable quality gain (our golden set is already exact) |
| A3 | same as **fp16** | ~1.2 GB | ≈ int8 | yes | ORT-CPU fp16 only partially native (casts) → `ASSUMPTION:` no speedup guaranteed | **measurement candidate** (see §4b) |
| W1 | `openai/whisper-tiny` | 39 M ≈ 40 MB (int8 ~40 MB) | Common Voice de **~30–40% WER** `ASSUMPTION:` | **no** (30-s window) | whisper.cpp (NDK) | **unusable** (quality) |
| W2 | `whisper-base` | 74 M ≈ 80–140 MB | CV-de **~18–25%** `ASSUMPTION:` | no | whisper.cpp | unusable (quality + architecture) |
| W3 | `whisper-small` (also `hustxuejie/whisper-small-de`) | 244 M ≈ 250 MB (int8) | CV-de **~10–15%** `ASSUMPTION:` | no | whisper.cpp / CTranslate2 (Python) | **architecture problem** + below our gate |
| W4 | `whisper-large-v3` / `-turbo` | 1.55 B ≈ 1.6 GB int8 (turbo ~0.8 GB) | CV-de **~4–6%** (turbo ~5–7%) `ASSUMPTION:` | no | whisper.cpp | best German quality, but 30-s window + 1.6 GB |
| W5 | `mobiuslabsgmbh/faster-whisper-large-v3`, `Systran/faster-whisper-*`, `primeline/whisper-large-v3-german`, Distil-Whisper-de | 0.8–1.6 GB | like W4 (±1 percentage point) `ASSUMPTION:` | no | **CTranslate2 has no official Android AAR** → NDK do-it-yourself `ASSUMPTION:` | **rejected** (runtime risk + architecture) |

**Architecture core (the most important result of this table):** Whisper is
**not** a streaming model. It processes 30-s windows and, for "a hypothesis every
0.3–1 s", must either keep re-running over 30 s (compute load ×10–30) or buffer
with overlapping windows (latency ≥ 2–5 s, edge duplicates). That is exactly what
B failed on, and Whisper has the same problem in a stronger form. Additionally,
Whisper is known for **hallucinations on silence** — that is the error class we
laboriously ruled out with §4.3/E5.

## 2. German WER numbers: what is comparable to our situation?

| Source | What it measures | Comparable for us? |
|---|---|---|
| Common Voice de (WER) | read sentences, many speakers, **good microphone** | only roughly: our 4 WAVs are **synthetic (espeak-ng)** and robotic |
| VoxForge de | volunteers, strongly varying quality | no (domain mismatch) |
| DWD/radio news | broadcast speakers, full text | no (no short dictations) |
| Our §4.1 goldens | **exactly our material** (4 sentences, 12–27 tokens) | **yes — that is the only robust yardstick** |

`ASSUMPTION:` A public WER comparison says nothing about everyday live dictation
with 4 sentences; at n=4 a difference of 2 percentage points is noise. Therefore
the rule below: **every switch is measured against §4.1 + 20 new German sentences**,
not against third-party WER.

## 3. The actual finding: our problem is **latency/size**, not accuracy

- A is **error-free (100%)** on our material and proven on the device.
- "Bigger" (fp32/Whisper-large) cannot by definition become better than 100% —
  it can only be equally good and cost 2–4× more.
- "Smaller" (Whisper tiny/base/small) drops accuracy **below** our gate and
  additionally brings the 30-s architecture problem. No candidate in this class
  can hold our §4.1 golden (`ASSUMPTION:` evidenced by the public WER ranges).
- What is missing are **numbers from the Pixel**: RTF, first-word latency, RAM
  peak, thermal behavior (G1 §6). As long as those are missing, every model
  switch is speculation.

## 4. Costs

| Variant | Download | at 10 Mbit/s | 25 Mbit/s | 50 Mbit/s | Device storage (in addition to today's 671 MB) |
|---|---|---|---|---|---|
| **A (today)** | 671 MB | 9.0 min | 3.6 min | 1.8 min | 0 (base) |
| **A3 fp16** | ~1.2 GB | 16.0 min | 6.4 min | 3.2 min | +0.5 GB (replacement, if A is dropped) |
| **A2 fp32** | 2.48 GB | 33.1 min | 13.2 min | 6.6 min | +1.8 GB |
| **W3 whisper-small int8** | ~250 MB | 3.3 min | 1.3 min | 0.7 min | −0.4 GB (smaller!) |
| **W4 whisper-large-v3-turbo int8** | ~0.8 GB | 10.7 min | 4.3 min | 2.1 min | +0.13 GB |
| **W4 whisper-large-v3 int8** | ~1.6 GB | 21.3 min | 8.5 min | 4.3 min | +0.9 GB |

**Provable on the x86_64 emulator** (before the Pixel): ONNX models (A, A2, A3)
loading and reproducing goldens — yes; **Whisper routes** only as a whisper.cpp
CLI, not as an app route (no AAR), so **not** provable near-app.

## 5. Recommendation

**(a) Change nothing** — with a single, cheap measurement exception:

- **v0.1.x:** A stays. No model switch. Instead, collect the **four Pixel
  numbers** from G1 §6 (RTF, first-word latency, RAM peak, 3-min thermal). Only
  if RTF > 0.6 does a look at alternatives even become worthwhile.
- **If the number justifies it:** **(b) fp16 Parakeet as a third variant**
  (~1.2 GB) — same architecture, same runtime, half the weight bytes; expected
  gain 1.2–1.6× **only if** the load is bandwidth-limited (`ASSUMPTION:`), risk:
  ORT-CPU-fp16 partially via casts → may be neutral. **Costs only a measurement,
  no architecture change.**
- **(c) Switch to Whisper/Distil/CTranslate2: no.** 30-s architecture,
  hallucination tendency, no Android AAR for CTranslate2, and the quality cannot
  beat our already error-free golden set.
- **(d) Dual model (small live / large on release):** tempting, but it doubles
  download **and** latency complexity and solves no problem we can currently
  measure. **Deferred**, not rejected.

## 6. Which gate to measure first

1. **RTF on the Pixel** (10 s of speech, median of 3 runs) — decides whether
   there is any need for action at all (threshold: > 0.6 = action needed).
2. Only afterwards: fp16 A/B (§4b) with the same four numbers.
3. The quality gate remains **always** §4.1 (exact for A; for every new variant:
   word identity ≥ 95%, CED ≤ 2%, no foreign-language output — R3/R4 yardstick).

## ASSUMPTION lines

- `ASSUMPTION: All WER numbers (Whisper family, CV-de) are memory values without`
  `network verification; they serve only for order of magnitude. Decisions do not hang on them.`
- `ASSUMPTION: ORT-CPU-fp16 does not use continuous FP16 kernels on arm64 (casts) →`
  `net gain unclear; must be measured.`
- `ASSUMPTION: CTranslate2/faster-whisper has no official Android AAR; an NDK`
  `do-it-yourself build is possible, but not estimated.`
