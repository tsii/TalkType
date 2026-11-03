# PLAN V0133 — dictation reliability, digit overlay, backspace

**Planner status:** 2026-11-02 · **Role:** planning, read-only on code; no git
operations
**Target version:** `0.1.33` / **versionCode 34**
**Base:** debug **546/0**, release **482/0**
**Groundwork:** device findings 18:56–19:04 (`/tmp/vk_phone_sess*.log`,
`/tmp/vk_phone_window.log`) and the verified code paths.
**Table of contents:** §0 findings/guardrails · §1 slice A (A1–A4) · §2 slice
B (B1–B3) · §3 slice C (measurement plan/acceptance) · §4 test
targets/risks/release.

---

## 0. Findings and binding guardrails

### 0.1 Verified starting point

| Finding | Evidence | Consequence |
|---|---|---|
| The cold start sets three watchdog clocks **before** `engine.load()`; the first empty 50-ms poll after 4.42 s of load time immediately triggers `ERR_STALL`. | `DictationCoordinator.kt:258–267,300,319–330,444,494–495`; `AudioRecordSource.kt:155–158`; `/tmp/vk_phone_sess.log:2–4` | A1: set the time bases only after a successful source start. Real source deadness remains a system error. |
| Case 2 shows **ASR continuing despite a visible text stop**: at 8/10/12 s the token counts grow 55/70/85; decodes take only 442/537/627 ms. | `/tmp/vk_phone_window.log:3270,3616,3942` | No blanket CPU/TDT/pacing therapy. A2 must handle the write path W1/W3d. |
| Case 3 proves, without `VK-ASR` lines, only frozen `out`, not "no decode". | `/tmp/vk_phone_sess2.log:3–7` | A4 delivers the missing decode/write evidence in release. |
| W1 and W3d discard writes without a session error. The finale uses the same blockable `writeHypothesis()` path. | `DictationController.kt:368–390,485–530`; `DeltaTyper.kt:74–90,137–148` | W3d must not block permanently; the finale needs its own ownership-secured total revision. |
| `seg_final` before `start_ok` arises from an old `sessLastSeg=1` and a new session counter 0, not from a delayed mini finale. | `VoiceKeyboardService.kt:196,1394–1397` | A3: log only a real counter increase and reset the session baseline. |
| The export currently has **53 fields = header + 53 lines = 54 lines**. | `DebugInfoFormatter.kt:175–193`; `DebugInfoFormatterTest.kt:55–62` | A4 appends fields only; test the exact new line count. |
| The real r0 geometry yields `top=-168`; visibility depends on drawing outside the `KeyboardView`. | `LongPressMenu.kt:82–139`; `KeyboardView.kt:548–558` | B1 chooses the small, local fix inside the IME window, no window extension. |
| Backspace executes only once on UP; a selection is not handled separately. | `KeyboardView.kt:939–997`; `VoiceKeyboardService.kt:1497–1504` | B2: repeat lifecycle + selection branch. |

### 0.2 Non-negotiable

1. **User contract §6.3c:** hold means continuous recording until UP/CANCEL,
   a defined system event, or the upper limit. Transient decode, editor,
   pacing, or hypothesis latency produces a **diagnosis/hint, never a
   watchdog abort**. Real >3-s source deadness (`ERR_STALL`) stays as a
   system event.
2. **Rule 9:** logs/export contain only numbers, durations, lengths, and
   closed codes. No transcripts, hypothesis texts, audio data, paths, URLs,
   package names, or free-form exception texts. `:` in values is sanitized to
   `=`.
3. **Ownership safety:** no revision without a fresh suffix/length proof.
   Foreign text is never deleted. `SAME` produces no sink access.
4. **TDD per task:** run 1 = the new/changed test verifiably red; run 2 = the
   fix and green debug/release tests. No production code before the red
   test.
5. **Budget per task:** at most **4 files** and **2 test runs**. An emulator
   screenshot/OCR is the acceptance of that same second run, not an
   additional code task.
6. **No speculative B3 change:** measure with A4 first; change F43/gate
   behavior only with an evidenced root.

### 0.3 Order

`A1 ∥ A2a ∥ B1 ∥ B2` → `A2b` → `A4a` → `A4b` → device measurement C → `B3
only with an evidenced root`. `A3` is independent and may run in parallel
with A2.

---

## 1. Slice A — dictation reliability

### A1 — cold start: bind watchdog baselines to the real capture start

- **Files (2):**
  1. `app/src/main/java/de/voicekeyboard/ime/DictationCoordinator.kt`
  2. `app/src/test/java/de/voicekeyboard/ime/DictationCoordinatorTest.kt`
- **Red test expectation (2 tests):**
  1. Fake `engine.load()` advances the injected clock by **4,500 ms**;
     `src.start()` returns successfully, the first PCM buffer arrives after
     **100 ms**. Expectation: `isActive=true`, `lastError=null`, no
     `audio_error:stall`, no `audio_error:silence`. Before the fix:
     `ERR_STALL` after the first 50-ms timeout.
  2. After a successful start the source delivers no blocks for **3,000+ ms**.
     Expectation: exactly one `audio_error:stall`, `onAborted` exactly once,
     controller IDLE. The existing stall contract stays red-green secured.
- **Fix outline:** after a successful `src.start()` and immediately before
  `captureThread.start()`, take **one** value
  `captureStartedAtMs=nowMs()` and set `lastBlockAtMs`, `startRefMs`,
  `lastLoudAtMs` from it together. The start duration diagnosis may still
  measure `engine.load()`; only the watchdog baselines move. No threshold
  change.
- **Acceptance:** cold start 4.5 s + 100 ms start latency stays RECORDING;
  `cap_stall_ms<3,000` before the first block. Real source deadness fires
  at the earliest after >3,000 ms exactly once. No new abort on transient
  latency.
- **Budget:** 2 files, 2 runs, **+2 tests**.

### A2a — `DeltaTyper`: typed result return, W3d recovery, total revision

- **Files (3):**
  1. `app/src/main/java/de/voicekeyboard/editor/DeltaTyper.kt`
  2. `app/src/test/java/de/voicekeyboard/editor/DeltaTyperTest.kt`
  3. `app/src/test/java/de/voicekeyboard/commit/CommitE8Test.kt`
- **Red test expectation (4 tests):**
  1. `reviseOutcome()` returns closed results `SAME`, `W1`, `W3d`, `DELTA`;
     `SAME` for an identical target; the previous null ambiguity is
     forbidden in the new production path.
  2. W3d case with **75 codepoints**, `committedLength=70`: the first
     blocked attempt reports `W3d`; the next hypothesis revises its own span
     atomically with ownership proven again and reports `DELTA`. No
     permanent standstill.
  3. `reviseFinal(target)` after a W3d block revises the **entire own span**
     in exactly one sink step and leaves a foreign prefix untouched.
  4. **100** identical hypotheses after an initial delta deliver 100×
     `SAME`; sink calls and written characters stay exactly at the level
     after the initial delta.
- **Fix outline:** a new result object with `kind` and an optional
  `TextDelta`; `reviseOutcome()` becomes the only new production path. A W3d
  state is visible once and is resolved on the next ownership-secured call
  by an atomic total revision of the **own** span. `reviseFinal()` checks
  ownership first too and does not bypass the foreign text boundary. The
  existing tests are migrated to the typed result; no compatibility null in
  the controller.
- **Acceptance:** a W3d burst >60 characters blocks at most one hypothesis;
  the next hypothesis writes. The finale after W3d is complete and without a
  duplicate. SAME flood: **0 additional sink calls**. W1 stays
  write-protected.
- **Budget:** 3 files, 2 runs, **+4 tests**.

### A2b — controller: W1 re-baseline, finale, counters, and decode diagnosis

- **Files (4):**
  1. `app/src/main/java/de/voicekeyboard/dictation/DictationController.kt`
  2. `app/src/main/java/de/voicekeyboard/ime/DictationCoordinator.kt`
  3. `app/src/main/java/de/voicekeyboard/editor/OwnershipTracker.kt`
  4. `app/src/test/java/de/voicekeyboard/ime/SchreibstoppTest.kt` (new)
- **Red test expectation (4 tests):**
  1. A controller session with a W3d burst stays RECORDING; the next
     hypothesis writes; `write_w3d=1`, afterwards `write_delta` grows.
  2. The finale after W3d commits the complete final target state; no old
     partial hypothesis remains standing.
  3. W1 with an `actualBeforeCursor` suffix proof and
     `fresherExpected=sink.ownText()` may re-baseline and then write.
     Foreign/diverging editor content ⇒ `W1`, no sink access, `lost` stays
     set.
  4. E1c coordinate: fresh sink text takes precedence per the prefix rule
     over an older tracker expectation. Only after a successful suffix proof
     does `lost=false`; the `ownEditFresh` special case alone does **not**
     count as a suffix proof.
- **Fix outline:**
  - `writeHypothesis()` uses `reviseOutcome()` and counts
    `SAME/W1/W3d/DELTA` plus `lastWriteAudioS`; `lastDecodeMs`, `hypLen`,
    `currentLen`, `committedLen`, `writeReason` become readable as
    numbers/codes.
  - The W1 re-baseline uses exactly the E1c coordinates: length
    `max(currentText.length,sink.ownText().length)`,
    `actual=sink.textBeforeCursor(len)`,
    `fresherExpected=sink.ownText()`, then
    `tracker.rebaseline(actual,fresherExpected)`. Only after its positive
    suffix proof may the same hypothesis be retried once.
  - `OwnershipTracker.rebaseline()` clears `lost` exclusively in the
    successful `stillOurs` branch. No clearing on `actual==null`, foreign
    text, or a merely fresh edit.
  - The controller's `stop()` uses `reviseFinal()`. The coordinator passes an
    immutable decode window diagnosis object with an optional callback to
    the outside; no UI/Android types in the object.
- **Acceptance:** all four tests green; the session does not end through
  W1/W3d itself. Foreign text stays byte-for-byte unchanged. The counters
  are monotonic per session and zeroed at the start;
  `last_write_audio_s=-1` before the first DELTA.
- **Budget:** 4 files, 2 runs, **+4 tests**.

### A3 — eliminate the `seg_final` log artifact

- **Files (2):**
  1. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
  2. `app/src/test/java/de/voicekeyboard/ime/SegmentSessionLogTest.kt`
     (new)
- **Red test expectation (2 tests):**
  1. Sequence old session `seg=1` → mic DOWN → new session `seg=0`: **no**
     `seg_final`; afterwards `0→1`: exactly one entry.
  2. `1→1` produces no entry; a second session repeats the same sequence
     without the old artifact.
- **Fix outline:** `sessLastSeg=-1` on mic DOWN; the pure decision logs only
  when `previous>=0 && current>previous`. No change to the segmenter or its
  counters.
- **Acceptance:** the `/tmp` repro sequence never contains `seg_final`
  before `start_ok`; real mini finales stay visible exactly once.
- **Budget:** 2 files, 2 runs, **+2 tests**.

### A4a — diagnosis: session/last-window fields into the release export

- **Files (4):**
  1. `app/src/main/java/de/voicekeyboard/debug/DebugInfoCollector.kt`
  2. `app/src/main/java/de/voicekeyboard/debug/DebugInfoFormatter.kt`
  3.
     `app/src/test/java/de/voicekeyboard/debug/DebugInfoCollectorTest.kt`
  4.
     `app/src/test/java/de/voicekeyboard/debug/DebugInfoFormatterTest.kt`
- **Red test expectation (4 tests):**
  1. The collector delivers the A2b values; removing a collector assignment
     turns the test red.
  2. The export contains exactly the ten new keys: `last_decode_ms`,
     `hyp_len`, `current_len`, `committed_len`, `write_reason`,
     `write_same`, `write_w1`, `write_w3d`, `write_delta`,
     `last_write_audio_s`.
  3. Missing values become `-`; no write yields
     `last_write_audio_s=-1`; values with `:` are sanitized to `=`.
  4. The existing **53 KEYS stay unchanged in order**, the ten new ones are
     appended: **63 fields, header + 63 = 64 lines**, deterministic for an
     empty and a full snapshot.
- **Fix outline:** only pass through the A2b diagnosis object; no new text
  sources. The existing fields `audio_s`, `output_chars`, `decode_pace_ms`,
  `decode_skips`, `hyp_gap_ms`, `decode_ms_max/p95`, `buffer_s`,
  `owner_lost_at_s`, `sel_unexpected`, `restart_inputs`, `rebases` stay
  unchanged. The export is in the main source set and therefore
  release-effective.
- **Acceptance:** export = header + 63 field lines = **64 lines** stable;
  sanitizer and rule 9 tests green; the release export shows real
  numbers/codes instead of `-` after at least one decode.
- **Budget:** 4 files, 2 runs, **+4 tests**.

### A4b — compact `VK-ASR` window line in release

- **Files (2):**
  1. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
  2. `app/src/test/java/de/voicekeyboard/ime/DecodeWindowLogTest.kt` (new)
- **Red test expectation (2 tests):**
  1. A pure formatting function produces exactly one line with
     `event=window` and all **17 metrics**: `audio_s,out,decode_ms,pace_ms,
     skips,hyp_len,current_len,committed_len,write,owner_lost_at_s,
     sel_unexpected,restart_inputs,rebases,hyp_gap_ms,decode_ms_max,
     decode_ms_p95,buffer_s`.
  2. Rule 9 test: no text/audio fields, no paths/URLs/package names, only
     closed write codes; no debug guard. `out` comes synchronously from the
     existing `outputCounter`.
- **Fix outline:** wire the A2b callback in the service; after every decode
  window output exactly one compact `VK-ASR` line. The existing engine stage
  line may stay; the new `event=window` line is the unambiguous
  decode→write chain. No endless loop and no logging between windows.
- **Acceptance:** exactly one `event=window` line per decode, also in
  release; at 30/60/90 s the sequence covers every window and shows
  immediately whether decode, pacing, W1, W3d, or SAME is responsible for an
  output gap. No abort through this diagnosis.
- **Budget:** 2 files, 2 runs, **+2 tests**.

---

## 2. Slice B — user UI

### B1 — the row-0 digit menu fully inside the IME window again

- **Files (2):**
  1. `app/src/main/java/de/voicekeyboard/keyboard/LongPressMenu.kt`
  2. `app/src/test/java/de/voicekeyboard/keyboard/LongPressMenuTest.kt`
- **Red test expectation (3 tests):**
  1. Real geometry `keyTop=0,rowH=150,menuH=147,gap=21,pad=10,cell=126,
     spaceAbove=126`: expected `menuTopPx=171` (the fallback **below** the
     key), not `-168`; `top>=0` and `top+147<=318`.
  2. The same decision controls `stillArmed/menuBelowKey`: cell 0 stays
     reachable by shifting; `spaceAbovePx` may no longer rescue visibility
     through root clipping.
  3. The middle row/footer and the dot menu keep exactly their previous
     coordinates/action cells.
- **Fix outline:** r0 chooses the upper path only if the **complete menu and
  cell area lies within the KeyboardView** (`above>=0`); otherwise the F38
  position below the key. No IME window resize, no change to
  `clipChildren`. The same pure function continues to control geometry and
  the arming zone.
- **Acceptance:** emulator 1080×2404, long-press Q: the digit menu visible,
  the cell "1" proven by OCR with exactly one commit; the dot menu
  unchanged. Screenshot before/after; all visible menu rectangles satisfy
  `0<=top` and `top+menuH<=keyboardHeight`.
- **Budget:** 2 files, 2 runs, **+3 tests**; screenshot+OCR in run 2.

### B2 — backspace repeat and complete selection deletion

- **Files (4):**
  1. `app/src/main/java/de/voicekeyboard/keyboard/BackspaceRepeat.kt`
     (new, pure time logic)
  2. `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt`
  3. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
  4. `app/src/test/java/de/voicekeyboard/ime/BackspaceTest.kt` (new)
- **Red test expectation (5 tests):**
  1. Fake schedule: initial delete at **0 ms**, repeats at
     **450,520,590,660,730,800,870,940 ms**; on UP at 1,000 ms exactly **9
     deletes**, none afterwards.
  2. No repeat before 450 ms; UP/CANCEL/detach stops deterministically; a
     short tap still produces exactly one delete.
  3. Non-empty selection: exactly one `commitText("",1)`, **0**
     `deleteSurroundingText`, **0** DEL key events; the complete selection
     is replaced.
  4. Empty/null selection: the existing grapheme cluster path stays; no
     double delete from DOWN+UP.
  5. Repeat after a selection has disappeared keeps deleting via the normal
     grapheme cluster/fallback path.
- **Fix outline:** backspace DOWN executes exactly one action and arms the
  timer; UP/CANCEL ends without a second action. Start delay **450 ms**,
  repeat period **70 ms** (target range 60–80 ms). The service checks
  `getSelectedText(0)` first and atomically replaces a non-empty selection
  with empty text; otherwise the existing path. Repeat always uses the same
  service branch.
- **Acceptance:** fake time exactly 9 deletes/1,000 ms and 0 after UP;
  device: even deletion, release stops immediately; the selection disappears
  completely. No repeat timer after an editor switch/detach.
- **Budget:** 4 files, 2 runs, **+5 tests**.

### B3 — toggle mode: measure the cause first, then fix minimally

- **Precondition:** A4a+A4b and the C runs are available. Symptom: toggle
  aborts and alternately shows "Kein Text erkannt". F43 with a 2.5-s pause
  is only a candidate, not an evidenced root.
- **Measurement without a code change:** three toggle cycles with continuous
  speech and three with a defined 3-s pause. Check: `start_src=toggle`,
  `gate_at_stop=1`, `fgs_stopped_by=autostop` vs. `stop`, `abort_reason`,
  `stop_cause`, `audio_s`, `loud_s`, `hyp_gap_ms`, `output_chars`,
  `last_write_audio_s`, `write_reason`, `owner_lost_at_s`.
- **Files (0 for the measurement; at most 4 for an evidenced fix):**
  1. `app/src/main/java/de/voicekeyboard/ime/DictationCoordinator.kt`
  2. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
  3. `app/src/test/java/de/voicekeyboard/ime/DictationAutoStopTest.kt`
  4. `app/src/test/java/de/voicekeyboard/ime/MicToggleTest.kt`
- **Red test expectation (only after proof, 2 tests):** the test models the
  measured toggle sequence exactly and is red before the fix; the
  cross-checks hold and the normal toggle pause stop stay green.
- **Fix outline:** change only the evidenced gate/stop/status root. No
  blanket extension of the 2.5-s threshold, no hold gate intervention, no
  suppressing the "Kein Text" hint without a proven empty output.
- **Acceptance:** three toggle cycles with continuous speech without a
  premature stop or alternating empty message; on a real 2.5-s pause still
  exactly one controlled finale. `stop_cause` and `fgs_stopped_by` explain
  every run; no text contents in the log.
- **Budget:** measurement 0 files; fix 4 files, 2 runs, **+2 tests only if
  justified**.

---

## 3. Slice C — device measurement plan and PASS criteria

### 3.1 Runs

1. **Cold start once** after a natural IME process cold start (no
   `force-stop`, no log clearing): observe the model load, speak 10 s.
   Expectation A1: no stall 50 ms after `start_ok`.
2. **Warm hold 30/60/90 s**, at 1 word/s each, finger continuously down. 60 s
   additionally in the problematic Compose editor and in a plain editor as a
   control.
3. **B3 toggle** only after evaluating these holds as defined in §2.

In parallel for every hold (`SECS` = 30/60/90):

```bash
S=<phone-host>:<adb-port>
SECS=60
adb -s $S logcat -v time -s VK-SESS:D VK-ASR:I VK-IME:W VK-Capture:I '*:S'

PID=$(adb -s $S shell pidof -s de.voicekeyboard)
adb -s $S shell "top -H -b -d 1 -n $((SECS+5)) -p $PID"
adb -s $S shell "dumpsys cpuinfo | grep -i -B2 -A2 voicekeyboard"
adb -s $S shell dumpsys thermalservice
# optional, read-only, if simpleperf is available/permitted:
adb -s $S shell "simpleperf stat -p $PID --duration 15"
```

**Export only after UP:** the share dialog covers the IME and would
influence the session. No `logcat -c`, no force stop, no system properties
as a release precondition.

### 3.2 Export checklist per run

- Identity/end: `app_version=0.1.33 (34)`, `audio_s`, `start_ms`,
  `abort_reason`, `stop_cause`, `mic_rel`, `fgs_stopped_by`, `last_error`,
  `ctl_failed`.
- Audio: `cap_blocks`, `cap_stall_ms`, `loud_s`, `peak_dbfs`, `dropped`,
  `gate_at_stop`.
- Decode: `hyp_count`, `hyp_gap_ms`, `last_decode_ms`, `decode_ms_max`,
  `decode_ms_p95`, `decode_pace_ms`, `decode_skips`, `buffer_s`,
  `hyp_last_buffer_s`, `segment_finalizations`.
- Write: `output_chars`, `hyp_len`, `current_len`, `committed_len`,
  `write_reason`, `write_same`, `write_w1`, `write_w3d`, `write_delta`,
  `last_write_audio_s`.
- Editor: `owner_lost_at_s`, `sel_unexpected`, `restart_inputs`, `rebases`,
  `status_res`.

### 3.3 PASS per warm hold (D = 30/60/90)

| Criterion | Expected value/code |
|---|---|
| Recording until real UP | `audio_s = D ± 1.5 s`; `stop_cause=stop:holdup`; `abort_reason=final`; `last_error=-`; `ctl_failed=0`; no `audio_error:*` |
| Decode flow | `hyp_count >= 5/10/15` for D=30/60/90; every gap between `event=window` lines **<=7,000 ms**; `decode_pace_ms<=6,000` |
| Decode cost/window | `decode_ms_max<=2,000`; `hyp_last_buffer_s<=38.0`; `buffer_s = audio_s ± 0.2 s`; segmenter only the 35-s/pause rule |
| No visible write stop | `last_write_audio_s >= D−7.0`; `output_chars >= 30/60/90`; after W3d a `DELTA` follows at the latest in the next window; no unresolved final `W1/W3d` |
| Finale | Output grows or stays equal only with `SAME`; a final W3d block excluded; no duplicate, no foreign text intervention |
| Editor/CPU | No unexpected `owner_lost_at_s>=0`; `sel_unexpected=0` in the control. `top`, `cpuinfo`, thermal serve explanation only: if a window >7 s is missing, a decode/thread finding must exist; no blanket CPU limit. |
| Diagnosis | Every decode line contains all 17 metrics; the export has header + 63 field lines and no contents/paths/URLs/package names. |

A run **does not pass** if the audio keeps running but an output gap >7 s
occurs without subsequent recovery, if the finale is blocked again by W3d,
or if a transient latency leads to a session abort.

### 3.4 C-emu — emulator pre-check BEFORE the phone measurement phase

**Purpose/order:** E1 → E2 → E3 → E4 → E5. **E1 first**, because only the
**FIRST app session after an emulator cold start** delivers real sound
(analogous to the I5 rule). Do not spend this first-session budget on test
microphones, toggles, or smoke preliminaries. FakeWav bypasses this law for
engine/editor runs but does **not** replace `AudioRecordSource` + FGS + the
real AudioRecord chain.

**Total budget: 50 min + 5 min reserve = <60 min:** E1 10 min · E2 5 min · E3
8 min · E4 8 min · E5 4 min · shared boot/install/buffer 15 min. No Gradle
build parallel to the emulator; the APKs and the model are finished before
the start.

#### Shared preparation and export judge

```bash
cd <workdir>
export PATH="$PATH:/opt/android-sdk/platform-tools"
export XDG_RUNTIME_DIR="/run/user/$(id -u)"
unset DISPLAY                         # explicitly NO DISPLAY
ADB="adb -s <device>"            # never 5558/another port
PKG=de.voicekeyboard

# Silence the Google troublemakers before any IME handling; no pm clear!
for p in com.google.android.apps.messaging com.google.android.gms \
         com.google.android.calendar com.google.android.googlequicksearchbox; do
  $ADB shell pm disable-user --user 0 "$p"
done

focus_ime() {                         # plain/debug editor only, no restart:20
  for k in 1 2 3; do
    $ADB shell "dumpsys input_method | grep -m1 mInputShown" | grep -q true && break
    $ADB shell input tap 540 300; sleep 0.6
    $ADB shell input text "x"; sleep 1.2
  done
  $ADB shell "dumpsys input_method | grep -m1 mInputShown" | grep -q true || return 1
}

export_clear() {                      # debug: file hook + old state removed
  $ADB shell setprop debug.vk.export_file 1
  $ADB exec-out run-as "$PKG" rm -f files/debug_export.txt
}

export_wait() {                       # $1 = host target; judge = BYTES>100
  local out="$1" bytes=0
  for i in $(seq 1 50); do
    bytes=$($ADB exec-out run-as "$PKG" stat -c %s files/debug_export.txt 2>/dev/null || echo 0)
    [ "${bytes:-0}" -gt 100 ] && break
    sleep 0.2
  done
  [ "${bytes:-0}" -gt 100 ] || { echo "E: Export <=100 bytes"; return 1; }
  $ADB exec-out run-as "$PKG" cat files/debug_export.txt > "$out"
  [ "$(wc -c < "$out")" -gt 100 ]
}
```

**Runner contract for E2:** `tools/emu_c2_run.sh` has the `mInputShown`
asserts and uses mic **`(880,1635)`**; never `x=166`. The murder cell
`restart:20` is triggered via the watcher trigger `input text`. The
FakeWav/engine hooks take effect only after the runner's `force-stop`. No
parallel build.

#### E1 — A1 cold start with the only real audio session

- **Purpose:** test A1 against a model load >4 s and the real
  `AudioRecord`/FGS chain; the most important run first.
- **Preparation:** no app session beforehand.
  `emulator_audio_up.sh` handles the cold boot, offscreen instead of
  headless, PulseAudio/null sink, and the end-to-end sound check. Only
  after that install the APK and open the IME.
- **Exact command sequence:**

```bash
tools/emulator_audio_up.sh            # XDG_RUNTIME_DIR is set; no DISPLAY
$ADB wait-for-device
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell ime set "$PKG/.ime.VoiceKeyboardService"
$ADB shell am start -n "$PKG/.debug.DebugInputActivity"; sleep 3
$ADB shell input tap 540 300; focus_ime
export_clear
$ADB logcat -c
$ADB logcat -v time -s VK-SESS:D VK-ASR:I VK-IME:W '*:S' > /tmp/v0133_e1.log & LOGPID=$!
$ADB shell input -d 0 motionevent DOWN 880 1635   # first and only app audio session
paplay testdata/c2/k120.wav & PLAYPID=$!
sleep 35                                           # >30 s hold
$ADB shell input -d 0 motionevent UP 880 1635
kill "$PLAYPID" 2>/dev/null || true; sleep 10; kill "$LOGPID" 2>/dev/null || true
$ADB shell input tap 1017 1635; sleep 2; $ADB shell input keyevent 4
export_wait /tmp/v0133_e1_export.txt
```

- **PASS:** `start_ms>4000` (otherwise the cold load was not hit),
  `audio_s>=30`, `cap_stall_ms<3000`, **no** `audio_error:stall`,
  `hyp_count>=2`, `write_delta>0`, `output_chars>0`, `last_error=-`; after
  UP `stop_cause=stop:holdup`. No second mic attempt on the same boot.
- **Not proven:** the Pixel/GrapheneOS audio HAL, a real
  microphone/routing, thermal throttling, and phone FGS policy. The 30-s run
  does not replace the phone holds 30/60/90 s.

#### E2 — A2 write stop against the `restart:20` murder cell

- **Purpose:** W1/W3d recovery and the export counters under repeated editor
  restarts with a real engine and deterministic FakeWav.
- **Preparation:** `k120.wav` = **121.248 s / golden_chars=896**. E1 is
  finished; FakeWav needs no second real-sound budget.
- **Exact command sequence:**

```bash
export_clear
# Runner: mInputShown asserts, mic 880/1635, watcher trigger input text, restart:20.
tools/emu_c2_run.sh restart:20 real testdata/c2/k120.wav v0133_e2
export_wait /tmp/v0133_e2_export.txt
$ADB logcat -d -s VK-SESS:D > /tmp/v0133_e2_sess.log
```

- **PASS:** the acceptance-4 pattern **130 s / 1,660 characters** is the
  reference; hard gates for k120: `audio_s>=118`, `editor_len>=896` (1,660
  is not a binding exact target), `restart_inputs>=1`,
  `rebases>=restart_inputs`, `write_delta>0`, `last_write_audio_s>=114`,
  `stop_cause=stop:holdup`, `abort_reason=final`, `last_error=-`, **no
  abort**. `write_w3d` is documented numerically; with `write_w3d>0` a
  subsequent `write_reason=DELTA` must be proven. The export contains all A4
  counters and >100 bytes.
- **Not proven:** the real AudioRecord chain, the real phone Compose
  implementation, and no statement about every third-party editor. FakeWav
  proves coordinator/engine/editor, not the microphone.

#### E3 — A3/B1/B2 UI and log pre-acceptance

- **Purpose:** check the A3 sequence; r0 menu visible and OCR-proven;
  backspace repeat and selection deletion pre-verified without a phone.
- **Preparation:** the debug APK and DebugInputActivity stay active. The UI
  helpers deliver key coordinates or lengths only (no text contents):

```bash
ui_key() {                            # $1 = visible label/description
  $ADB shell uiautomator dump /sdcard/vk.xml >/dev/null 2>&1
  $ADB shell cat /sdcard/vk.xml | python3 -c '
import sys,re,xml.etree.ElementTree as E
want=sys.argv[1].lower()
for n in E.fromstring(sys.stdin.read()).iter("node"):
    if (n.get("text","").lower()==want or n.get("content-desc","").lower()==want):
        x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")))
        print((x1+x2)//2,(y1+y2)//2); break
' "$1"
}
editor_len() {
  $ADB shell uiautomator dump /sdcard/edit.xml >/dev/null 2>&1
  $ADB shell cat /sdcard/edit.xml | python3 -c '
import sys,html,re
x=sys.stdin.read(); print(max([len(html.unescape(t)) for t in re.findall(r"text=\"([^\"]*)\",x)]+[0]))'
}

# A3: no seg_final before the first start_ok of the fresh session.
$ADB logcat -d -s VK-SESS:D > /tmp/v0133_a3.log
awk '/start_ok/{seen=1} /seg_final/ && !seen {bad=1} END{exit bad}' /tmp/v0133_a3.log

# B1: hold Q for 700 ms; screenshot + OCR of the digit 1.
read -r QX QY <<< "$(ui_key q)"
$ADB shell input swipe "$QX" "$QY" "$QX" "$QY" 700
$ADB exec-out screencap -p > /tmp/v0133_row0.png
tesseract /tmp/v0133_row0.png stdout -l eng 2>/dev/null | grep -q "1"

# B2a: 30 characters, hold backspace ~2 s; evaluate the length difference only.
$ADB shell input keyevent 280
$ADB shell input text "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234"
read -r BX BY <<< "$(ui_key Löschen)"
BEFORE=$(editor_len)
$ADB shell input -d 0 motionevent DOWN "$BX" "$BY"; sleep 2
$ADB shell input -d 0 motionevent UP "$BX" "$BY"
AFTER=$(editor_len); sleep 0.5; AFTER2=$(editor_len)
[ $((BEFORE-AFTER)) -ge 10 ] && [ "$AFTER" = "$AFTER2" ]
$ADB exec-out screencap -p > /tmp/v0133_backspace.png

# B2b: select all, one backspace => length 0.
$ADB shell input text "ABCDEFGHIJ"; $ADB shell input keyevent 280
$ADB shell input tap "$BX" "$BY"; sleep 0.3
[ "$(editor_len)" = 0 ]
```

- **PASS:** A3 has **0** `seg_final` before `start_ok`; the B1 OCR finds
  "1", the menu rectangle fully in the screenshot/IME (`0<=top`,
  `top+menuH<=keyboardHeight`); B2 deletes **>=10 characters** in 2 s and
  **0 further** after UP; selection+⌫ yields exactly length **0**.
- **Not proven:** the Pixel's finger/system gestures, differing
  densities/OEM clipping, and hardware keyboard key events. OCR does not
  replace the phone's visual check.

#### E4 — B3 toggle: measure before a fix is coded

- **Purpose:** six cause-readable toggle exports with FakeWav: 3×
  continuous, 3× with a >2.5-s pause. No production fix in this run.
- **Preparation:** DebugInputActivity, `debug.vk.fakewav=/data/local/tmp/k.wav`
  as set by the runner; FakeWav starts at the corpus beginning per session.
  The function stops with a second tap only if the current session is still
  RECORDING:

```bash
$ADB push testdata/c2/k120.wav /data/local/tmp/k.wav
$ADB shell setprop debug.vk.fakewav /data/local/tmp/k.wav
$ADB shell setprop debug.vk.engine real
$ADB shell am force-stop "$PKG"
$ADB shell am start -n "$PKG/.debug.DebugInputActivity"; sleep 3
focus_ime
$ADB logcat -c
run_toggle() {                       # $1=index, $2=seconds
  focus_ime
  $ADB shell input -d 0 motionevent DOWN 880 1635; sleep 0.08
  $ADB shell input -d 0 motionevent UP 880 1635     # toggle
  sleep "$2"
  if $ADB logcat -d -s VK-SESS:D | awk '/state=/{s=$0} END{print s}' | grep -q state=RECORDING; then
    $ADB shell input -d 0 motionevent DOWN 880 1635; sleep 0.08
    $ADB shell input -d 0 motionevent UP 880 1635
  fi
  sleep 5; export_clear
  $ADB shell input tap 1017 1635; sleep 2; $ADB shell input keyevent 4
  export_wait "/tmp/v0133_toggle_$1.txt"
}
run_toggle 1 8; run_toggle 2 8; run_toggle 3 8
run_toggle 4 22; run_toggle 5 22; run_toggle 6 22   # contains a 4-s corpus pause
$ADB logcat -d -v time -s VK-SESS:D VK-ASR:I '*:S' > /tmp/v0133_toggle.log
```

- **PASS as a measurement (not as a fix acceptance):** 6 exports >100 bytes;
  each contains `start_src=toggle`, `gate_at_stop`, `stop_cause`,
  `abort_reason`, `fgs_stopped_by`, `audio_s`, `last_write_audio_s`,
  `write_reason`; every session has `event=window` lines. Continuous:
  `audio_s=8±1.5`; pause runs: `audio_s<=22` and the cause unambiguously
  `autostop`/user stop instead of `unknown`. Only then fix the B3 root and
  the red test.
- **Not proven:** everyday toggle on a real microphone, the user's
  finger/IME switching, and phone FGS. **Everyday toggle remains a phone
  obligation.**

#### E5 — A4a export content of the release APK

- **Purpose:** prove that the ten new KEYS exist not only in the debug
  export.
- **Preparation:** no parallel build; install the release and show the IME
  in a plain editor. **Release note:**
  `debug.vk.export_file`/`run-as` is deliberately ineffective there; hence
  the judge via 🐞 → share → close → paste → UI dump.
- **Exact command sequence:**

```bash
$ADB install -r app/build/outputs/apk/release/app-release.apk
$ADB shell ime set "$PKG/.ime.VoiceKeyboardService"
$ADB shell am start -a android.settings.SETTINGS; sleep 2
$ADB shell input tap 540 160; sleep 1; $ADB shell input tap 540 300; focus_ime
$ADB shell input tap 1017 1635; sleep 2; $ADB shell input keyevent 4; sleep 1
$ADB shell input tap 540 300; $ADB shell input keyevent 123
$ADB shell "for i in \$(seq 1 240); do input keyevent 67; done"
$ADB shell input keyevent 279; sleep 1
$ADB shell uiautomator dump /sdcard/release_export.xml >/dev/null 2>&1
$ADB shell cat /sdcard/release_export.xml | python3 -c '
import sys,html,re
x=sys.stdin.read()
for m in re.finditer(r"text=\"([^\"]*)\",x):
    t=html.unescape(m.group(1))
    if "audio_s" in t and "cap_blocks" in t: print(t)
' > /tmp/v0133_release_export.txt
for k in last_decode_ms hyp_len current_len committed_len write_reason \
         write_same write_w1 write_w3d write_delta last_write_audio_s; do
  grep -q "^$k:" /tmp/v0133_release_export.txt || exit 1
done
grep -q '^build: release$' /tmp/v0133_release_export.txt
grep -q '^app_version: 0.1.33 (34)$' /tmp/v0133_release_export.txt
python3 -c 'p="/tmp/v0133_release_export.txt"; assert len(open(p).read().splitlines()) == 64'
```

- **PASS:** `build=release`, `app_version=0.1.33 (34)`, all **10 new KEYS**,
  overall **header + 63 field lines = 64 lines** (`splitlines()`
  independent of the final line break); no paths/URLs/package
  names/text contents.
- **Not proven:** real decode values in release; those are proven by
  E2/debug and afterwards the phone runs. E5 checks format and release
  visibility, not the engine.

**Phone obligation despite green C-emu:** the **real-microphone cold start**
on Pixel/GrapheneOS and **everyday toggle** remain obligations unchanged.
Likewise the real warm holds 30/60/90 s per §3.1–3.3. C-emu is a
preliminary trap for regressions and harness errors, not a replacement for
the phone in C.

---

## 4. Test targets, risks, and release

### 4.1 Expected test numbers

| Package | New tests |
|---|---:|
| A1 | +2 |
| A2a + A2b | +8 |
| A3 | +2 |
| A4a + A4b | +6 |
| B1 + B2 | +8 |
| **Sum without B3** | **+26** |
| B3, only with an evidenced root | additionally +2 |

**Target without B3:** debug **572/0**, release **508/0**.
**Target with B3:** debug **574/0**, release **510/0**.
Screenshots/OCR and device holds are additional acceptances, not JUnit
numbers.

### 4.2 Main risks and mitigation

1. **W3d total revision deletes too much:** only after
   `ownershipVerified()`/suffix proof and exclusively the own span; the
   foreign prefix test and the atomic sink step are release blockers.
2. **W1 re-baseline accepts an app/user change:** open `lost` only in the
   `stillOurs` branch; `ownEditFresh`, `actual==null`, or mere length
   equality do not suffice. In doubt the session stays write-protected but
   active.
3. **API migration `DeltaTyper`:** keep A2a and A2b separate; migrate all
   old null expectations first. No silent compatibility path in the
   controller.
4. **Export drift/rule 9:** append new KEYS only; exactly 64 lines, test
   the sanitizer and the collector chain. No target app or text-length
   inferences beyond the contents.
5. **B1 menu covers row 1:** a deliberate, visible fallback instead of an
   invisible negative top; touch arming and the dot menu must stay
   unchanged. No larger IME window as a side effect.
6. **B2 double delete/timer leak:** the DOWN/UP contract and fake time are
   mandatory; CANCEL, detach, and an editor switch stop the same timer.
7. **B3 wrong therapy:** no fix without A4 proof. F43 fundamentally stays
   for toggle; do not change the hold gate as a side effect.
8. **Performance measurement changes behavior:** `top`/`simpleperf` only in
   parallel and read-only; no rigid CPU percentage assumption. What matters
   are decode time and window gaps.

### 4.3 Release gate

1. All tasks individually TDD-green; review P0/P1=0, especially A2 and B2.
2. `app/build.gradle.kts`: **versionCode 34**, `versionName="0.1.33"`;
   expected debug/release test numbers from §4.1.
3. Cold start + warm hold 30/60/90 passed per §3; B1 screenshot/OCR and the
   B2 device acceptance evidenced.
4. Deliver B3 only with a documented measurement finding; otherwise it stays
   documented as an open measurement point and does not block the remaining
   fixes.
