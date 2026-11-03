# A14 — Field catalog for the debug info export

**Status:** 2026-10-28 · **Role:** analysis/plan (no code/build changes)
**Goal:** the user taps the info key → text into clipboard/share dialog → remote
diagnosis **without** logcat. All source line numbers have been **verified by
reading** (as of this commit).

## 0. Upfront: what already exists (do not rebuild)

| Building block | Location | Status |
|---|---|---|
| Level peak (max only, no curve) | `debug/LevelPeak.kt:12` (`note`, `percent`, `reset`) | **done**, pure JVM |
| First-hypothesis latency | `ime/DictationCoordinator.kt:70` `firstHypothesisLatencyMs` | done |
| RTF counter | `DictationCoordinator.kt:74` `processingMs`, `:78` `audioMs`, RTF formula `:96` | done |
| Short diagnostics line | `DictationCoordinator.kt:94` `diagnosticsLine(threads)` | done (**only** `erst/RTF/T/Peak`) |
| Level wiring | `ime/VoiceKeyboardService.kt:363` `levelListener = AudioLevelListener { rms, _ -> coordinator?.noteLevel(rms) }` | **already wired** (P1 plan T3 is done) |
| Error codes | `DictationCoordinator.kt:105/116` `lastError`/`visibleError`; codes `:174` `audioInit:`, `:185` `startFailed`, `:64` `oom`, `:165` `model:`; `ModelError.kt:11-33` | done |
| Model status/variants | `model/ModelStore.kt:136/138/140/143` (`isReady`/`status`, per `ModelVariant`), `:98` `modelDir(variant)` | done |
| Versions | `app/build.gradle.kts:14` `versionCode = 5`, `:15` `versionName = "0.1.4"` — **no** `buildFeatures{buildConfig=true}` ⇒ **BuildConfig is NOT available** | gap → use PackageManager |
| Share key | does not exist (neither status bar nor Q row) | gap |

## 1. Field catalog

| Field | Source (file:line) | Use for remote diagnosis | Concerns |
|---|---|---|---|
| `app_version`, `app_version_code` | `PackageManager.getPackageInfo(packageName,0)` (because BuildConfig is missing; `build.gradle.kts:14-15`) | maps the report to an APK; "which build?" | none (public) |
| `build_type` | `BuildConfig.DEBUG` **missing** ⇒ `ApplicationInfo.FLAG_DEBUGGABLE` | debug vs. release (log/check path) | none |
| `model_status` (`READY/BROKEN/ABSENT/…`) | `ModelStore.status(variant)` `:143` | most frequent cause of "dictation silent" | none |
| `model_variant` (`ONNX_CPU`/…) | `ModelVariant` (enum), `ModelStore.kt:98` | separates A/B reports | none |
| `model_files`, `model_bytes` (sum) | `ModelStore.modelDir(variant).listFiles()` + `length()` | detects partial/missing downloads | numbers only, no names |
| `model_version_short` | `ModelStore.activeVersion(variant)` `:106` (e.g. `v-1730…`) | detect resume/double install | **short name yes, path no** (§2) |
| `last_error` (code) | `DictationCoordinator.visibleError` `:116`; codes `:174/185/64/165` | core of the diagnosis (E5 classes) | **never** `message`/stack trace |
| `rtf` | `processingMs/audioMs` `:74/:78`, formula `:96` | hardware/thread assessment | none |
| `first_hypothesis_s` | `firstHypothesisLatencyMs` `:70` | "text only after 8 s" | none |
| `audio_last_s` | `audioMs` `:78` (or `DictationController` `totalSamples`) | separates "nothing spoken" from "not recognized" | metadata (duration) — integer only |
| `peak_level_pct` | `LevelPeak.percent()` `debug/LevelPeak.kt:32` | "is sound arriving at all?" (mic muted vs. engine) | see §2 (inference) |
| `threads_intra`, `threads_inter` | `AsrThreadConfig` `:19-20` | explains RTF differences | none |
| `chunk_ms`, `hypothesis_min_interval_ms` | `AsrThreadConfig.kt:61/62` | latency/pacing explanation | none |
| `min_audio_s` | `DictationController.MIN_AUDIO_SECONDS_PARTIALS` | "why no text under 2 s?" | none |
| `perm_record_audio`, `perm_post_notifications` | `DictationPermissions.grantedSet()` `:83`, `permanentlyDeniedSet()` `:86` | most frequent cause no. 2 | none |
| `api_level` | `Build.VERSION.SDK_INT` | platform regressions (FGS, insets) | none |
| `device_class` | `Configuration.smallestScreenWidthDp` (<600 = phone, ≥600 = tablet) | layout/inset errors | **no** device name, no serial number |
| `soc_model` (optional) | `Build.SOC_MODEL` (API 31+, `ASSUMPTION:` present) | NPU/CPU comparison (Pixel vs. others) | no device name |
| `locale`, `layout_id` | `currentLanguage()` / `LayoutRepository` | "wrong language/wrong layout" | none |
| `runtime` | `OrtEnvironment` version (or LiteRT, if B) | version mismatch | none |
| **`route`** | `VoiceKeyboardService.kt:408` `audioRoute = routeLabel(router?.currentRoute ?: Route.BUILTIN_MIC)`; labels `:468-472` (`intern` / `BT-Headset` / `Kabel-Headset`); source `audio/AudioRouter.kt:17-20` | says **from which microphone** recording happened (routing error = silent/quiet input) | three German short labels, no closed enum in the export |
| **`src`** | `VoiceKeyboardService.kt:409` `audioSource = AudioRecordSource.SOURCE_NAME`; constant `audio/AudioRecordSource.kt:42` = `"VOICE_RECOGNITION"`; actually used `:93` `MediaRecorder.AudioSource.VOICE_RECOGNITION` | says **which Android AudioSource** is active → explains device-specific attenuation (NR/AGC) | the name only, no parameters (NR/AGC are not queryable) |
| **`peak_dbfs`** | `debug/LevelPeak.kt:41-44` `dbfs()` = `20·log10(maxRMS)`, `null` in silence (`max <= 0`); set in `debug/DebugInfoCollector.kt:89`; formatted `%.1f` (`DebugInfoFormatter.format`) | **resolves the level** where `peak_pct` rounds to 0/2/3: −34 dBFS is immediately recognizable as "far too quiet" | only one maximum per session, no curve (rule 9) |

## 2. Rule 9 — decisions (with rationale)

**Rule 9 forbids audio/text contents, URLs, and paths.**

1. **Paths: short version name YES, full path NO.** `files/models/versions/v-1730…`
   contains the app sandbox path (`/data/user/0/de.voicekeyboard/…`) and the storage
   structure. The **short name** suffices for diagnosis ("which version is
   active?") and is an opaque token with no personal reference. So: field
   `model_version_short: v-1730…`, **never** `modelDir().absolutePath`. The same
   applies to `cacheDir`, `filesDir`, the APK path.
2. **Peak value invertible to content?** `LevelPeak` holds **one** maximum
   (0–100%, integer), no time series, no samples (`LevelPeak.kt:6-10` documents
   this). From a single percentage value, **no** content and no statement about
   speech/words can be reconstructed — only "there was noise of magnitude X". That
   is metadata like the dictation duration. **Permissible**, but: (a) integer
   only, (b) only on explicit button press (never automatic/telemetry), (c) never
   multiple sessions (no history).
3. **Never export:** transcript/hypothesis text, audio/samples, `error.message`,
   stack traces, **target app package name** (reveals which app was dictated in),
   clipboard contents, device name/serial number/IMEI, accounts, URLs (download
   host).
4. **No raw logcat text:** too large, contains third-party content (other apps,
   titles).

## 3. (a) Short format (≤ 20 lines, one block, `key: value`)

```
# VK-DEBUG v1 keine inhalte
app_version: 0.1.6 (7)
build: release
api_level: 37
model_status: READY
model_variant: ONNX_CPU
model_version: v-114124734651754
model_bytes: 670619706
last_error: -
rtf: 0.80
first_hyp_ms: 1116
threads: intra=4 inter=1
permissions: record=yes notif=no
peak_pct: 2
route: intern
src: VOICE_RECOGNITION
peak_dbfs: -34.0
```

**Actual format = 43 lines** (1 header + **42** fields; as of F42 — before that
30 = 1+29 (F21 era), 29 = 1+28 (F28b), 31 = 1+30 (F31 `loud_s`), 35 = 1+34 (F26a/b
+ F28 `mic_rel`/`status_shown`), now **43 = 1+42** (F42: +6 fields). The order is
fixed and enforced by `format()` via `check(lines.size == KEYS.size + 1)`.
Rules: ASCII, `key: value`, fixed order (deterministic), **43 lines**
(1 header + 29 fields; the earlier upper limit "≤ 20" was dropped with
F13b2/F17b), unknown values `-`, **no** line breaks in values (sanitizing),
header with `keine inhalte` as a visible promise. Sanitizing replaces `:` in
values with `=` (hence `last_error: audioInit=SecurityException`).

## 4. (b) What does NOT belong in the share text

Transcript/hypotheses · audio/samples/peak curves · error `message`/stack trace ·
file paths (short version name only) · URLs/download host · target app package ·
device name/serial number · installed app list · clipboard · logcat.

## 5. (c) Where the key comes from + which function builds the text

- **Location: IME status bar, not the Q row.** Reason (correcting the P1 plan):
  the Q row is wired to the 15 layout assets + parser allowlist; a new action
  `share-debug` would change **all** assets + `LAYOUT_REQUIREMENTS` (rule 4/4a)
  and break a parser test. The status bar (`ImeRootView.statusText`, `:34/:48/:68`)
  is pure UI, already exists in every layer, and is always reachable.
  Implementation: a small `🐞` `ImageButton` (48 dp touch target) **on the right
  of the status row**, `contentDescription = "Copy/share diagnostics"`.
- **Builder function (pure JVM, testable):** `debug/DebugInfoFormatter.kt`
  `fun format(s: DebugSnapshot): String` + `data class DebugSnapshot(...)` (only
  primitive fields/enums). The Android thin layer `debug/DebugInfoCollector.kt`
  fills `DebugSnapshot` (PackageManager, `Build`, `ModelStore`,
  `DictationCoordinator`) and calls `ClipboardManager` or `Intent.ACTION_SEND`
  (`text/plain`); without a handler → clipboard fallback. No network.
- **8 unit tests (JVM, `DebugInfoFormatterTest`):**
  1. `format_hasAtMost20Lines` · 2. `everyLineMatchesKeyValuePattern`
  (`^[a-z0-9_]+: .*$`) · 3. `containsNoPathsUrlsEmails` (regex against `/`,
  `http`, `@`, `\`) · 4. `versionAndCodeAppearVerbatim` · 5.
  `unknownValuesRenderAsDash` · 6. `valuesWithNewlineOrColonAreSanitized` · 7.
  `modelStatusAndByteSumMatchInput` · 8. `outputIsDeterministic` (format twice
  ⇒ identical).

## ASSUMPTION lines

- `ASSUMPTION: BuildConfig is disabled in this module (no buildFeatures block in`
  `app/build.gradle.kts) → version via PackageManager.`
- `ASSUMPTION: Build.SOC_MODEL returns "Tensor G5" on API 36; if empty, drop the field.`
- `ASSUMPTION: The target app package name is dispensable; the error class (E5)`
  `explains "dictation silent" sufficiently.`

---

## 6. The three diagnosis fields in detail (A19, added 2026-10-28)

The source of truth is the implementation (`debug/DebugInfoFormatter.kt`,
`debug/LevelPeak.kt`, `debug/DebugInfoCollector.kt`, `audio/AudioRecordSource.kt`),
not the wish list from §1.

| Field | Meaning | Range / format | What the value reveals in a failure | Which user finding it decides |
|---|---|---|---|---|
| `route` | capture **input** path per `AudioRouter` (not the output!) — `intern`, `BT-Headset`, `Kabel-Headset` | 3 short labels from `routeLabel()` (`VoiceKeyboardService.kt:468-472`), fallback `intern`; `-` if no router | `BT-Headset` for a user **without** a headset ⇒ wrong/orphaned SCO routing (communication microphone, often quieter); `Kabel-Headset` without a cable ⇒ routing stuck | "dictation silent/quiet despite speaking" — separates the **microphone path** from **engine/commit** |
| `src` | active `MediaRecorder.AudioSource` name, today the constant `VOICE_RECOGNITION` (`AudioRecordSource.kt:42`, used `:93`) | string, currently only one value | attenuating device DSPs (noise suppression/AEC/limiter) hang **on the source** "depending on device" — the code comment `:93` says so explicitly | enables the A/B comparison `VOICE_RECOGNITION` ↔ `MIC` ↔ `UNPROCESSED` **without** logcat: the value changes visibly in the export |
| `peak_dbfs` | session peak **RMS** in dBFS = `20·log10(maxRMS)` (`LevelPeak.kt:41-44`) | `≤ 0.0` with one decimal place; `-` (**null**) if no level ever arrived (silence/no read) | **`≤ −40 dBFS` ⇒ input practically deaf** (microphone attenuated, wrong source, routing). `−20…−10` = normal speaking distance. `-` = **no** block ever arrived (recording never started) | **phone finding `peak_pct: 2`**: dBFS turns that into "−34 dB" — clearly too quiet, while our reference WAVs sit at RMS **0.067–0.095** (≈ −23.5 … −20.4 dBFS) ⇒ **factor ~30** level difference |
| `peak_pct` (old) | `percent()` = `(maxRMS·100).toInt()` (`LevelPeak.kt:27`) | `0…100` integer | rounds to `0/2/3` for quiet recordings and is **no longer resolving** there — exactly why `peak_dbfs` was added (`LevelPeak.kt:34-37`: "peak_pct drops to 0..2 for quiet recordings") | only as a **rough** second opinion; decisions via `peak_dbfs` |

**Computation for the user case:** `peak_pct: 2` ⇒ `maxRMS ≈ 0.02` ⇒
`20·log10(0.02) = −34.0 dBFS` ⇒ int16-RMS ≈ 655. Reference WAVs: RMS
0.067…0.095 ⇒ −23.5 … −20.4 dBFS ⇒ int16-RMS ≈ 2,200 … 3,100. **Factor ≈ 3 in
amplitude (≈ 10 dB) up to ≈ 30 in power** — too much for "spoke quietly",
plausible for an attenuated capture chain (`src`/`route`).

## 6a. Decode path: C2 workload + F21 pacing (5 fields; addendum D20/D22)

**Purpose:** these three fields substantiate the **C2 hypothesis** — partial
decoding re-decodes the **entire audio so far** per tick
(`dictation/DictationController.kt` `window()` = `PcmWindow(pcm.copyOf(totalSamples), …)`),
so the work per tick grows with the recording duration. Signature in the export:
`buffer_s` rising together with `decode_ms_max`/`decode_ms_p95`. **RULE 9:** only
numbers and key names — no audio/text contents, no user paths/URLs.

| Field | Meaning | Unit | Special values |
|---|---|---|---|
| `decode_ms_max` | longest decode runtime of a `transcribe` call so far (`decodeMsMax()`) | ms (integer) | `0` = never decoded |
| `decode_ms_p95` | p95 of the last ≤ 64 decode runs — ring sample, **not** the whole session (`decodeMsP95()`: sorted sample, index `ceil(0.95·n)−1`) | ms (integer) | `0` = never decoded; one run ⇒ max == p95 |
| `buffer_s` | buffer length at the **last** decode run — not the current recording duration (`bufferSeconds()`); correlates directly with `decode_ms_*` | seconds, 1 decimal place | `0.0` = never decoded |
| `decode_pace_ms` | currently effective minimum interval between two partial decodes (`decodePaceMs()`; rule in `dictation/DecodePace.kt`: twice the last decode time, clamped to `AsrThreadConfig.decodePaceBaseMs`/`MaxMs` = 2000/6000 ms) | ms (integer) | `0` = pacing off (pacing disabled) |
| `decode_skips` | number of partial runs skipped because of this pacing — **not an error, no `fail()`** (`decodeSkips()`; reset on every start) | count (integer) | `0` = never skipped or pacing off; grows only during recording |

**Purpose of F21:** these two fields together show whether the C2 mitigation works —
many `decode_skips` with a large `decode_pace_ms` (near the 6000 cap) means: the
engine cannot keep up with the growing buffer and is being braked by the pacing.

## 7. Comparison `KEYS` ↔ documentation (actual state, 42 fields / 43 lines)

`DebugInfoFormatter.KEYS` (order = output order):

```
app_version, build, api_level, model_status, model_variant, model_version,
model_bytes, last_error, rtf, first_hyp_ms, threads, permissions,
peak_pct, route, src, peak_dbfs,
ctl_state, ctl_failed, dropped, hyp_count, hyp_gap_ms, audio_s, start_ms,
starting, decode_ms_max, decode_ms_p95, buffer_s,
decode_pace_ms, decode_skips, cap_blocks, cap_stall_ms,
abort_reason, hyp_last_buffer_s, mic_rel, status_shown, loud_s,
had_output, output_chars, first_loud_ms,           → 42 fields (F42 additions below)
first3s_loud_blocks, first3s_peak_dbfs, fgs_stopped_by
```

### F42 — 6 new fields (as of 0.1.28+, `DebugInfoFormatter.kt:159-160`,
`DebugInfoCollector.kt:44` among others; all numbers/codes only, rule 9)
| Field | Meaning |
|---|---|
| `had_output` | 0/1 — at least one hypothesis commit in this session (`DebugInfoFormatter.kt:118`) |
| `output_chars` | gross character count of the commits (just the number, `:122`) |
| `first_loud_ms` | ms after session start at the `everLoud` latch; `-1` = never loud (`:126`) |
| `first3s_loud_blocks` | loud blocks (RMS ≥ `SILENCE_RMS`) in the first 3 s — start window (`:129`) |
| `first3s_peak_dbfs` | peak block RMS of the first 3 s in dBFS (`:132-134`) |
| `fgs_stopped_by` | cause code of the last mic FGS stop (F44, `:136`; values include `autostop`/`micup`/`hidden`) |

**Sanitizer:** value lines convert `:`→`=` (`DebugInfoFormatter.kt:250`) — the
first `: ` after the key remains; later colons in values (e.g. `mic_rel =
up:1234`) become `=`.

`format()` returns `HEADER + 42` = **43 lines** (enforced via
`check(lines.size == KEYS.size + 1)`; `DebugInfoFormatterTest` checks ≤ 20).

### Deviations I found in the implementation

1. **Outdated KDoc counter:** the class comment and the `KEYS` comment say "EXACTLY
   13 lines" and "trimmed to 13 lines" — in reality there are **16 fields / 17
   lines**. Comment only, no functional deviation (the `check` computes correctly
   with `KEYS.size`).
2. **`route` is the input path, not the output path** and returns **German UI
   labels** (`intern`/`BT-Headset`/`Kabel-Headset`) instead of an enum
   (`BUILTIN`/`BLUETOOTH_SCO`/`WIRED`). Usable for remote diagnosis, but
   language-dependent — an English device would still show German labels (the
   labels are hard-coded, not from resources).
3. **`last_error` appears with `=`, not `:`** — sanitizing replaces `:` in values
   with `=` (`AudioInit:SecurityException` → `audioInit=SecurityException`). That
   is intended (the line format stays unambiguous), but the documentation must
   state it.
4. **§1 catalog lists fields that do not (yet) exist:** `soc_model`, `locale`,
   `layout_id`, `runtime`, `chunk_ms`, `min_audio_s`, `audio_last_s`,
   `model_files`. They are **planned**, not implemented — whoever evaluates the
   export must not expect them.
5. **`peak_pct` remains as a field** (compatibility of user exports), although
   `peak_dbfs` supersedes it functionally; both are computed from **the same**
   `LevelPeak.max`, so they can never contradict each other.
6. **No `class`/`soc`** in the export (the A14 idea "device_class") — today the
   export contains only `api_level`; that is privacy-friendly but makes "phone vs.
   emulator" classification harder. `peak_dbfs`/`route`/`src` cover the practical
   need.

## ASSUMPTION lines (A19)

- `ASSUMPTION: the three fields are wired in `KEYS` and `format()` as of this`
  `commit; an export from v0.1.6 (7) already contains them (the user's 🐞 finding`
  `comes from a version without `peak_dbfs`).`
- `ASSUMPTION: `route` returns `intern` when the router is missing (fallback in`
  `the call `VoiceKeyboardService.kt:408`), not `-`.`
