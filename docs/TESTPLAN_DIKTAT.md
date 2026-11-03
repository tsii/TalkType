# Dictation test plan – binding Sol acceptance per version

**Status: v0.1.23; ten criteria K01–K10.**

## 1. Purpose, scope, and rules

Regression: capture ends, the display stays "Diktat", UP briefly shows
processing, afterwards "Bereit", but neither text nor a persistent error.
This sequence must never persist silently.
Sol checks dictation on every acceptance going forward; green
keyboard/layout tests do not replace it.
The basis is the standard engine A, warm-loaded, a constant configuration,
and a defined audio source.
Check the cold start additionally; measure start/loading time separately,
not hidden inside warm latencies.
Other engines get their own results and may not adopt A's successes.
All limits below are target values, not measurements newly produced here.
No audio content, hypotheses, or transcripts in this plan/protocol/diagnosis
export.
Only controlled test signals or approved offline fixtures; target texts stay
local in the test.
No raw logcat, target app name, content screenshot, or audio export in the
acceptance protocol.
Crop screenshots to status/controls only; on the editor side record only
counters/verdicts.

## 2. Measurement contract and status truth

Time measurement monotonic; a fake clock for the JVM, real time on the
device. Unambiguously attribute session/engine.
Reset the start values on every session; do not measure before/after values
from different runs.
`status_shown` is a diagnostic code set by the IME, not an independent UI
proof.
Therefore always compare the controller/event trace **and the actually
visible TextView**.
The expected code is a state mapping, not raw string equality of IDLE and
ready:
- RECORDING with capture active → `dictat`; FINALIZING → `finalizing`.
- IDLE after a successful completion → `ready`; visible/sticky error →
  `error`.
- An error takes precedence over IDLE; STARTING must be recognizable as
  starting/loading, not as an active recording.
- Silence with PCM blocks still arriving is **not a capture stall**.
The UI must follow every transition at the latest **500 ms** after the
controller/error event.
An error stays visible for at least **5 s after UP without a new user
action** (R-2).
A mere time/RTF counter does not count as an explanatory status change when
text is missing.
Native inference must block neither UP nor the capture watchdog or the UI
error display.

## 3. Ten acceptance criteria

| ID | Test case | Measurable target / error condition |
|---|---|---|
| K01 | **30-s continuous dictation**, a continuous controlled signal | Capture active over 30 s, `cap_blocks` rising; on success at least **8 visible text increments**. No text gap >**3000 ms** without an explanatory delay/error display within a further **500 ms**; a merely running timer does not suffice. Recording status and delay must not contradict each other. |
| K02 | **2-s short dictation**, then UP | Within **3000 ms after UP** at least one successful visible text input **or** a persistent, understandable error; if finalizing is visibly going to take longer, show processing in time and deliver success/failure at the latest **5000 ms after UP**. Never an empty completion with just `ready`; also test timeout/empty hypothesis. |
| K03 | **Status truth in every transition** | Start, recording, UP, finalization, error, abort, and a new start per §2; **0** stale displays longer than 500 ms. After the session ends never `dictat` again; after an error no pure `ready` without a hint. A last snapshot does not suffice. |
| K04 | **No audio blocks >3000 ms** | At 3000 ms no premature stall; after the threshold is exceeded a watchdog, a visible error, and the capture ended at the latest at **3500 ms since the last positive block**. Exactly one abort; a valid stall code in `abort_reason`. Test null reads **and** a permanently blocking read; UP is not needed for that. |
| K05 | **POST_NOTIFICATIONS missing, API ≥33** | Product policy: a visible justification/setup hint within **500 ms** before capture; no covert recording start, **0** positive capture blocks. After granting, a new conscious start possible; denial does not block normal keyboard input. |
| K06 | **UP during STARTING / wantedStop** | Play through a delayed start and UP before/during/after the return: after a successful start exactly **1 stop**, **0 additional aborts**, at most **1 final request**; no later zombie recording. CANCEL separately: exactly **1 abort**, **0 finals**; start errors separately without an invented success stop. |
| K07 | **Focus/app/IME switch during dictation** | Defined safe completion: **abort without a late final output**, the mic released at the latest **500 ms** after the switch, `abort_reason` set in a controlled way, e.g. `editor_change`/`ime_destroy`. Preserve existing text, **0** edits in the new/lost editor; restart only consciously. |
| K08 | **15-min soak run memory/decode** | At least **900 s of active recording in total**, unchanged parameters: because of the KONZEPT limit **3×300 s**, no covert 900-s single dictation. Every decode including the final **<1000 ms**; no growing backpressure, no silent session end, no OOM. After the same settling time the last idle PSS ≤ the first warm idle PSS + **max(20 MiB,10 %)**; a comparable last session p95 at most **10 %** above the first. |

### Addition v0.1.23 – diagnostic access and signal loss

| Criterion | Expectation / measured value | Test type | Role |
|---|---|---|---|
| **K09 (i) status row + 🐞 always visible** | In the base state and during dictation as well as after reopening, **statusText, the mic key, and the debug key** exist, each **VISIBLE**, measured height **>0 dp**, and an actually visible area **>0**; no hiding parent or clipping. The debug key operable; the export delivers `status_shown` from **ready/dictat/finalizing/error**, matching per K03. **0** vanished diagnostic bars as in 0.1.20. | Unit/Robolectric: measure the root after a layout pass, check child/parent visibility/bounds and the export code; manually compare base state/dictation/second opening visibly. | The coder automates the root/export regression in CI; **Sol** checks visibility/operability on the device and logs target/actual. |
| **K10 (j) silence/dropouts visible instead of a silent hang** | **Case silence:** positive blocks without a signal **>3000 ms**, message **„No microphone signal …"** (translated from German), **ERR_SILENT/silence**, session ended. **Case stall:** no positive blocks **>3000 ms**, **ERR_STALL**, a visible error/session end. In each case the end/error at the latest **3500 ms** after the start of the failure, afterwards no `dictat`; the error stable for ≥5 s after UP. Evidence **cap_blocks, loud_s, cap_stall_ms, abort_reason**. | Unit with the existing **FakeWavAudioSource** and a fake clock: distinguish a PCM zero signal from absent/0-read blocks, check 2999/3000/3001 ms and the signal's return; manually both error paths. | The coder automates both independent watchdog cases in CI; **Sol** checks the message, the end, and the 🐞 assignment on the device. |

K10: Positive silent PCM blocks increase `cap_blocks` but not `loud_s`; their
`cap_stall_ms` stays small.
Without blocks, `cap_blocks` stays constant and `cap_stall_ms` rises; that
is not a silence case.
`loud_s` sums loud audio duration, it does not measure the last silence
phase: counter deltas plus a monotonic clock are needed.
Use the existing signal threshold, no new test-only threshold; 2-s short
dictations stay subject to K02.

K01 needs real editor increments; `hyp_count` alone proves no output/commit
acceptance.
K02: A continuously running processing display does not justify unlimited
waiting.
K04: A watchdog only behind a never-returning read does not satisfy the
blocking case.
Without a first block, the deadline counts from the actual capture start,
not from slow model loading.
Also test fake clock start 0 and a never-arriving first block; no sentinel
may mask the stall.
K05 is a deliberate app policy; Android does not make POST_NOTIFICATIONS a
general FGS start requirement.
K06: evidence the events with counters; `mic_rel` holds only the last code,
no event count.
K07 adapts KONZEPT's finalization on normal UP to the lost Android editor
ownership.
K08 starts warm after model initialization; the same memory settling time is
**30 s** each.
Compare K08 peaks and p95 at the same audio/buffer levels, not short against
long buffers.
A running decode ≥1000 ms is already an error, not "0 ms" because of a
missing finished sample.
On interruption/missing sample the verdict is "not proven", not "passed".

## 4. Test types and CI matrix – task for the coder

U = pure JVM with fakes/clock; R = Robolectric for service/TextView/permission
adapters.
I = androidTest with a real editor, fake audio, and a fake/optionally real
engine.
G = a physical GrapheneOS device with a real microphone; no emulator function
assumption.
All new tests first red against the regression, then green; this plan
implements nothing.

| ID | U/R: what must be automated | I: integration | Sol/G and CI |
|---|---|---|---|
| K01 | Fake audio continuous, the engine stalls/ends unexpectedly; edit counters and the status deadline | WAV injection, real InputConnection, timestamps of successful output without content | Sol speaks for 30 s, counts text growth; U/R and fake I in CI, the real model only opt-in cached |
| K02 | 2-s fake, empty/success/exception/never-finished final; deadlines | Real display/editor after UP, no empty silent success | Sol several short runs including a cold start; CI U/R and fake I |
| K03 | State sequences including ERROR→UP→IDLE, late callbacks, a new generation | Reconcile the actual TextView/capture state per event | Sol status video/status crops; CI U/R and I, not just testing setter code |
| K04 | Fake clock 2999/3000/3001 ms; the source delivers 0 resp. blocks; the watchdog independent | Capture abort/error display despite a running fake inference | Sol the real path/privacy/source interruption separately; CI U/R and fake I, monotonic clock |
| K05 | API 32/33/current, granted/denied/permanently denied; no capture factory on denial | Permission and setup changes in the emulator, then a real new DOWN | Sol confirm on the target OS; CI R/I possible without a microphone, don't just mock system policy |
| K06 | Block the start latch, UP/CANCEL/error in all orders; counters/generations | Touch on the mic during artificially slow initialization | Sol release the finger before the recording starts; CI U/R and I |
| K07 | Editor/window hide in RECORDING/FINALIZING/STARTING; the EditSink rejects the old generation | Two host editors/activities, no edit after the switch | Sol real app/screen-off switches and the mic indicator; CI I only with controlled hosts |
| K08 | Long virtual session sequences, resource/queue counters, and correct timing statistics | Opt-in soak with WAV/real engine; record PSS and the total decode max | Sol 3 real 300-s sessions; only a dedicated device/performance CI is a reliable time/thermal proof |
| K09 | Root view with a layout pass: all three elements/parents visible, heights/visible rects >0, valid status code | Optionally check a second opening/active recording against a real view hierarchy | Sol check the visible bar and the operable export; R in normal CI |
| K10 | FakeWavAudioSource: positive silent PCM vs. missing blocks, fake clock, and exactly one error/abort | Optionally inject the same errors via display/controller adapters | Sol distinguish silence/stall including the export; U in CI, the real source stays the device proof |

Robolectric is feasible for state/UI adapters, not as proof for
AudioRecord, the microphone driver, native ASR speed, real FGS lifetime, or
thermal behavior.
Unlimited fake time proves logic, not 15 real minutes or native memory
freedom.
Normal PR CI: U/R and model-free I, no large downloads and no real microphone
required.
Optional model job: cached, checked artifacts and known fixtures, no network
required per run.

## 5. Role, evidence fields, and limits

**Coder:** creates the U/R/I tests, monotonic event counters, and the
necessary controlled error injections.
**Sol:** performs the G acceptance per version, compares target/actual,
judges each of K01–K10.
**Orchestrator:** provides identical APK/engine/parameters, approvals, and
device access.
Sol marks technical limits as "not proven"; no green verdict from unit test
counts.

| Evidence field | What it is reliable for / additional observation |
|---|---|
| `cap_blocks`, `cap_stall_ms`, `loud_s` | Block inflow vs. technical signal loss: positive silent blocks count, loud audio duration stays constant. Stall time can keep running after an abort: measure the trigger time separately. |
| `mic_rel`, `abort_reason` | Cause/last end event; UP/CANCEL/focus/stall distinguishable, no free-form error texts. Separate test counters are needed for "exactly once". |
| `status_shown`, additionally `ctl_state`, `ctl_failed`, `starting` | Expected status; check the real TextView separately, since the export holds only the last code set. |
| `hyp_count`, additionally `hyp_gap_ms` | Engine progress, not editor commit; text input counters and the time of the last visible change are additionally required. |
| `decode_ms_max`, additionally `decode_ms_p95` | Decode cost of the exported statistics window. Watch resets/ring windows: the test additionally needs a running maximum across all three sessions. |

Record start/end and, on a deviation, a diagnostic snapshot only on a
conscious action.
An info tap/share can lose focus: time/status evidence first, export
afterwards;
a focus abort caused by it must not mask the original error.
Only the last session export suffices neither for state transitions nor for
the 900-s maximum.
PSS is an additional run measurement; none of the export fields above
replaces it.

## 6. Acceptance protocol template per version

Header: version/build identifier, engine, API, device class,
source/route, threads, warm/cold, approvals, date, examiner; no serial
number, target app names, contents, or file paths.
Verdicts: **passed / failed / not proven**; open does not count as green.

| Criterion | Expectation | Measured value | Verdict |
|---|---|---|---|
| K01 30 s | ≥8 increments; no unexplained gap >3 s | __ | __ |
| K02 2 s | Text/error on time; no silent ready | __ | __ |
| K03 status | State mapping ≤500 ms, error stable ≥5 s | __ | __ |
| K04 stall | >3000 ms detected, end/error ≤3500 ms | __ | __ |
| K05 notification | Hint ≤500 ms, 0 capture without approval | __ | __ |
| K06 wantedStop | 1 stop/0 double aborts; CANCEL 1 abort/0 finals | __ | __ |
| K07 focus | Mic free ≤500 ms, reason set, 0 foreign edits | __ | __ |
| K08 soak | 900 s total, decode <1000 ms, memory/p95 within the limit | __ | __ |
| K09 bar/🐞 | statusText/mic/debug VISIBLE, height/area >0, valid status_shown | __ | __ |
| K10 silence/stall | >3 s distinguished, error/end ≤3500 ms, cap_blocks/loud_s/stall time/reason matching | __ | __ |

## 7. Standing task and today's automation limit

Every new dictation/lifecycle/status change repeats K01–K10; error
reconstruction from export #3 supplements these tests but replaces none of
the independent criteria.
All ten logic cases are automatable; complete real K01/K02/K04/K05/K07/K08/
K10 are **today not evidenced by normal CI**: microphone/OS/FGS, real focus,
and native performance/thermal require provided hardware and suitable
measurement instrumentation.
K03/K06 also need real UI/touch evidence for device release; their core
logic is fake-testable.
No case is fundamentally unautomatable: a device I can run these cases too
in the future.
This task delivers only the plan; no tests executed, no
implementation/build/git work.
