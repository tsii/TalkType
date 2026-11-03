# Plan: real microphone path in the emulator · layout fixes · debug info
sharing

**Status:** 2026-10-28 · **Role:** plan (no code/build change) · order =
priority.
Section 1 is the heart: the app has **never** proven via `AudioRecord` that it
records.

## 1. Emulator with a real microphone path

### 1.1 Decision: PulseAudio user daemon + null sink/remap source, emulator
**without** `-no-audio`
`pulseaudio --start` (user mode, no system daemon), `module-null-sink` as a
virtual target, `module-remap-source` from the **monitor** as the recording
source, restart the emulator with `QEMU_AUDIO_DRV=pa` (still `-no-window`).
**Rejected:** `-allow-host-audio` (no semantics of its own in 37.2.12), QEMU
`-audiodev` directly (collides with the emulator's own `-audiodev` arguments),
GUI microphone (not present headless). The null-sink/remap route is the only
one where **every** program feeds deterministically (`paplay`) → reproducible,
CI-suitable.

### 1.2 Commands
```bash
export XDG_RUNTIME_DIR=/tmp/pa-rt; mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
pulseaudio --start --exit-idle-time=-1 --log-target=file:/tmp/pa.log; pactl info
pactl load-module module-null-sink sink_name=vkmic sink_properties=device.description=VKMic
pactl load-module module-remap-source master=vkmic.monitor source_name=vkmic_src \
      source_properties=device.description=VKMicSrc
pactl set-default-source vkmic_src
adb -s <device> emu kill                       # ONLY 5560, 5558 is foreign
QEMU_AUDIO_DRV=pa "$ANDROID_HOME/emulator/emulator" -avd vk-api36 -port 5560 \
      -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect &
adb -s <device> wait-for-device
adb -s <device> shell dumpsys media.audio_flinger | grep -i -m5 "input\|source"   # input present?
paplay -d vkmic testdata/de_smoke.wav               # feed a known signal
```

### 1.3 Proofs (test cases, instrumented, app first `install -r` + `ime
enable/set`)
| # | Test name | Assertion |
|---|---|---|
| M1 | `mic_capture_stateInitialized` | `AudioRecord.state == STATE_INITIALIZED` **after** `start()` |
| M2 | `mic_readYieldsSamplesWithRms` | `read()` **> 0** frames, **RMS ≥ 200** (int16 ≈ −44 dBFS), **peak ≥ 1000** |
| M3 | `mic_realRecordingMatchesGolden` | microphone run of `de_smoke` against §4.1: **word identity ≥ 95 %**, **CED ≤ 2 %**, no English output (tolerance as in R3/R4 — the guest resamples) |
| M4 | `mic_levelListenerEmits` | `levelListener` delivers **≥ 3** values during RECORDING, level-correlated |
| M5 | `mic_stopHasNoRestSamples` | after `stop()`/EOS **no** further `read()` (counter in the fake `AudioRecord`) |
| M6 | `mic_permissionDeniedMapsToClassC` | without `RECORD_AUDIO`: class (c) "microphone permission missing", no crash, typing stays possible |

### 1.4 Code wiring as TDD tasks (≤ 4 files per task)
| # | Test FIRST | File | Expectation |
|---|---|---|---|
| T1 | `LevelMeterTest` (JVM) | `audio/LevelMeter.kt` (new) | peak/RMS from a `ShortArray`; empty array = 0; clipping detected |
| T2 | `mic_levelListenerEmits` | `audio/AudioRecordSource.kt` | `levelListener` called with peak/RMS on every read |
| T3 | `MicLevelWiringTest` | `ime/VoiceKeyboardService.kt` (**line 362** `levelListener = null` → real assignment) | the level reaches the status row; the `null` setting is dropped |
| T4 | `AudioRecordFactoryTest` | `audio/AudioRecordSource.kt` | `AudioRecord` via an injectable factory (testable without a real mic) |
| T5 | `DebugInfoTest` (JVM) | `debug/DebugInfo.kt` (new, §3) | peak/RMS/RTF fields fillable, no content |

### 1.5 Pre-checks (each ≤ 2 min)
1. `emulator -help-audio | head -20` — does 37.2.12 accept `pa`? (otherwise
   `-audio auto` + drop the env)
2. `pactl load-module module-null-sink …` → module ID > 0 (otherwise
   rights/daemon)
3. After the restart: `dumpsys media.audio_flinger | grep -i input` not empty
4. `parec -d vkmic.monitor --raw | wc -c` > 0
5. `uptime` load < 2.0 (otherwise everything shifts)

### 1.6 Teardown (mandatory for all other tests)
```bash
adb -s <device> emu kill
pactl unload-module <remap-id>; pactl unload-module <null-sink-id>; pulseaudio --kill
"$ANDROID_HOME/emulator/emulator" -avd vk-api36 -port 5560 -no-window -no-snapshot \
      -no-audio -gpu swiftshader_indirect &
adb -s <device> wait-for-device
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device> shell ime enable de.voicekeyboard/.ime.VoiceKeyboardService
adb -s <device> shell ime set    de.voicekeyboard/.ime.VoiceKeyboardService
# NOT: pm clear / clearPackageData — the model stock (671 MB) stays
```

### 1.7 Risks → pre-check
headless without an audio backend (C1+C3, fallback `-audio auto`) · PulseAudio
needs `XDG_RUNTIME_DIR` (C2) · guest resampling falsifies the golden (M3 with
tolerance, not exact) · `connectedDebugAndroidTest` uninstalls (after every run
§1.6) · confusing 5558 (**always** `-s <device>`).

## 2. Keyboard layout

### 2.1 Diagnosis (`ASSUMPTION:` until the measurement test)
`KeyboardView.onMeasure` sets height = `max(240dp, model.heightPx)` — a
**content** height with no relation to the available space; `ImeRootView` is a
vertical `LinearLayout` (status + keyboard + emoji block `height=0`/weight 1)
→ if the sum is larger than the IME window, it gets cut off **at the bottom**
(last row, space). The rebuild guard compares `lastMeasuredHeight !=
measuredHeight`, i.e. the view's **own** last measurement with its own → on
the **second** opening "equal", the model stays on the geometry of the first
window. **Fix:** height from `min(model.heightPx, available − status −
footer)`, row height distributed from the space, guard on `(width,
availableHeight)`, `requestLayout` on inset changes.

### 2.2 Moves/sizes (all 15 assets, touch target ≥ 48 dp)
| Change | Geometry | Affected |
|---|---|---|
| **Backspace top right** | row 2, last position, `width 1.5` | §1.4 + assets |
| **Enter into the old backspace position** | row 3, last position, `width 1.5` | ditto |
| **Large mic key bottom right** | bottom row, last position, `width 2.0`, label `🎤` | ditto |
| **Space** | `5.0` → **`3.5`** (row sum stays 11.0) | ditto |
| **🐞 share key top left** | row 1, position 0, `width 1.0`, `🐞`, `kind:"action"`, **new** `value:"share-debug"` (parser allowlist!) | §1.4 action |
| Row height | 44–48 dp (too tall today), status row 24 dp | `ImeRootView` |

**Order:** (1) `KeyboardFitTest` instrumented — all rows fully inside the IME
area, the `VK-HIT` geometry proof stays green; (2) height fix; (3) moves +
script over 15 assets; (4) catch up `LAYOUT_REQUIREMENTS` rules 4a/9; (5)
visual check (open 2×, field change).
**Sol countersign:** geometry table 2.2 + new action `share-debug`.

## 3. Debug information sharing/copying

**Fields (≤ 25 lines, copy-friendly, no content/URLs/paths):** app version +
versionCode · build type · Android API + device brand/model (`ASSUMPTION:`
brand ok, serial number not) · model variant + status (`READY/BROKEN/ABSENT`)
+ file sizes in bytes · last error **code** (never `message`) · first-word
latency (ms) · RTF · threads (`intra_op`) + hypothesis interval +
`min_audio_s` · permissions (yes/no) · free memory (GB) + model size ·
language/layout ID · runtime version (ONNX/LiteRT).
**Format:** `key: value` per line, ASCII, header `# VK-DEBUG v1`, max. 25
lines; **no** logcat, **no** stack traces, **no** audio/text content.
**Without network:** generate text only, then `Intent.ACTION_SEND`
(`text/plain`) or clipboard; fully functional offline.
**TDD:** D1 `DebugInfoFormatterTest` (JVM: 25-line limit, regex assertions
against `/`, `http`, `@`, deterministic order) · D2 `DebugInfoPrivacyTest` (an
existing transcript text does **not** appear) · D3 `ShareDebugIntentTest`
(instrumented: `ACTION_SEND`/`text/plain`, clipboard fallback without a
handler) · D4 `DebugInfoErrorCodeTest` (codes only).
**Sol countersign:** field list (privacy, rule 9).

## ASSUMPTION lines
- `ASSUMPTION: 37.2.12 accepts PulseAudio via QEMU_AUDIO_DRV=pa — pre-check 1`
  `clarifies it in <2 min.`
- `ASSUMPTION: The cut-off = content height without an available-height`
  `reference + self-referential guard; KeyboardFitTest proves/refutes it.`
- `ASSUMPTION: §4.1 + tolerance suffices for M3; no 10 new sentences needed.`
