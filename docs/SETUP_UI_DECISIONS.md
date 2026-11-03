# Setup/download decisions (E1–E4) — binding for the coder

**Status:** 2026-10-27 · **Role:** analyst/design (no code, manifest, or
asset changes)
**Basis:** `docs/DESIGN.md` §8 (UX/privacy) + §2.2,
`docs/TECH_FACTS.md` §3/§4.1, `docs/IMPLEMENTATION_PLAN.md` M4/M5/M7,
`docs/REVIEW_STAGE4B.md` B-4, `model/{ModelStore,ModelDownloader,
DownloadController}.kt`, `ime/*`, `HANDOFF.md`.

---

## E1 — foreground service type for the 671-MB download (API 36)

### Decision

**A `dataSync` foreground service in a dedicated `ModelDownloadService`.**
No download in the activity lifecycle, no `specialUse`, no "download" type
(that does not exist).

### Reasoning (options individually)

| Option | Assessment |
|---|---|
| (a) no FGS, bound to `SetupActivity` | **Not sufficient.** True, the process survives as long as the activity is visible — but as soon as the user switches away or the screen goes off, the process is merely *cached* and killable under memory pressure (671 MB download + the ONNX runtime in the same process!). Additionally, without an FGS the **Doze network restrictions** for idle apps apply: the transfer can fall asleep mid-run. Thanks to `.part`+ETag an abort is *repairable*, but the user sees a progress bar standing still with no reason. |
| (b) `dataSync` | **Chosen.** The canonical type for data transfer; keeps the process alive, exempt from Doze network throttling, a visible notification with progress and cancel. Price: **6 h per 24 h** from `targetSdk 35` and a mandatory `onTimeout()` (TECH_FACTS §4.1) — irrelevant at a real 5–20 min download time, but **implement defensively** (rule L7). The Play Store justification for `dataSync` ("data transfer use case") concerns store distribution only; we sideload (GrapheneOS) → **not** relevant, but record it in a manifest comment. |
| (c) `specialUse` | **Rejected.** Requires `FOREGROUND_SERVICE_SPECIAL_USE` **and** the property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` with a justification; the benefit over `dataSync` is zero, but with more manifest/review effort and a semantically wrong type. |
| (d) `download` type | **Does not exist.** API 36 types: camera, connectedDevice, dataSync, health, location, mediaPlayback, mediaProcessing, mediaProjection, microphone, phoneCall, remoteMessaging, shortService, specialUse, systemExempted. `shortService` is limited to ~3 min → unusable for 671 MB. |

**Why this fits `DownloadController`:** the controller is already UI-free,
executor-based, cancelable, and resumable via `.part` (class comment:
"CANCELLED, `.part` remains (resume possible)"). The service is thus **only**
a process/notification host — no second state machine:

- The service **owns** the controller instance (not the activity).
- The activity **observes** `DownloadController.state` via `listener` and
  displays it; it starts/stops nothing except via an intent.
- Progress/errors come from `DownloadProgress`/`ModelError` — the service
  maps them 1:1 to notification + `State`. No new persistence needed: the
  truth after a process death is `ModelStore.status()`
  (Absent/Partial/Ready).

### Minimal manifest lines

```xml
<!-- Network + FGS dataSync (E2: INTERNET permanent, see below) -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- The base permission is already present (dictation FGS) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- in <application>: -->
<service
    android:name=".model.ModelDownloadService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

No `onTimeout` extra needed in the manifest; `Service.onTimeout(int, int)` is
called by the system from `targetSdk 35` (TECH_FACTS §4.1). The existing
`TelemetryInitializer` removal remains **unchanged** in place.

### Lifecycle rules (rule text for the coder — binding)

- **L1 start:** only on an explicit "Modell herunterladen" tap **in a RESUMED
  `SetupActivity`** (`ContextCompat.startForegroundService`). Do not start
  from the background (Android 12+ prohibition; a
  `ForegroundServiceStartNotAllowedException` is caught and shown as "App
  erneut öffnen", never as a crash).
- **L2 `startForeground`:** in the service immediately (≪ 5 s) with
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (API ≥ 29), channel `model_download`
  (`IMPORTANCE_LOW`), text "Modell wird geladen · <x> % · <MB>/<MB>".
- **L3 activity destroy:** **no** cancel. The download keeps running; on
  return the activity shows the state from `DownloadController.state` resp.
  `ModelStore.status()`.
- **L4 abort:** exclusively by an explicit user wish (button or notification
  action) → `controller.cancel()`; `State.Cancelled`, `.part` remains, the
  service calls `stopSelf()`.
- **L5 termination:** on `Ready` / `Failed` / `Cancelled` the service calls
  `stopSelf()` (within ~1 s) and removes the notification. `Ready`
  additionally triggers "Modell aktiv, Mikrofon freigeschaltet" in the UI.
- **L6 process death/reboot:** no forced cleanup. At the next start
  `ModelStore.status()` decides: `ABSENT` → download button; `PARTIAL` →
  "Fortsetzen" (resume via `.part` + ETag); `READY` → setup counts as
  completed.
- **L7 `onTimeout()`** (API 35+): immediately `controller.cancel()`,
  `stopSelf()`, state `Paused` with the hint "Download pausiert
  (Systemlimit) — fortsetzen". **Never** keep running without `stopSelf()`
  (otherwise an internal exception).
- **L8 notification permission:** `POST_NOTIFICATIONS` denied ⇒ start the
  download **anyway** (the service runs, the notification invisible); not a
  blocker, but a hint in the setup UI. No `WAKE_LOCK` needed — an FGS is
  exempt from Doze network throttling, the screen may go off.
- **L9 no parallel operation:** `DownloadController.start()` is already
  idempotent; the service may hold at most one instance (`START_NOT_STICKY`,
  no `START_REDELIVER_INTENT` — the state comes from the store, not from
  intents).

---

## E2 — INTERNET permission policy

### Decision

**Option (a): `INTERNET` + `ACCESS_NETWORK_STATE` permanently in the
manifest, plus an in-app switch "Netzwerkzugriff für Downloads erlauben"
(default: on until setup is complete, off afterwards).**
`tools:node="remove"` **is dropped** (B-4), the `TelemetryInitializer`
removal **stays**.

### Reasoning

- **There is exactly one APK** (sideload, M6). A flavor/"download build"
  (option b) would produce two artifacts, of which the distributed one
  could not load a model — and the model installation is a mandatory part of
  the initial setup (M7: "setup installs 671 MB from a local test server").
- **Option (c) "comment only"** is honest but not decent enough: it does not
  tell the user *that* he can turn the access off and *what* happens then.
- The in-app switch is **not** a substitute for the permission (Android
  `INTERNET` is normal and cannot be revoked at runtime) — it prevents the
  app from even **building** requests after setup. That is exactly what
  DESIGN §8 already says ("an in-app network off switch additionally
  prevents all new download requests").
- On GrapheneOS the user can revoke the network permission **in the system**
  (a per-app network switch). Both together are the credible promise:
  manifest right + app switch + OS revocation.

**Rule text (coder):**
1. `INTERNET`/`ACCESS_NETWORK_STATE` are in the manifest; `tools:node="remove"`
   for both is **removed**, the manifest comment rewritten to "nur
   Modelldownload" (model download only).
2. `ModelDownloader` is called **only** via `PromptedDownloadGate` (or
   similar): `settings.allowNetworkDownloads && store.status() != READY &&
   !userOptOut`. If the switch is off, no request starts — the UI shows
   "Download deaktiviert".
3. After a successful `Ready`: switch automatically to **off**, a visible
   hint "Netzwerkzugriff für Downloads ist jetzt aus".
4. No second network path: no update checks, no telemetry, no crash
   uploads, no cloud ASR. `SecurityException`/`UnknownHostException` are
   mapped as their own `ModelError` kind (B-4), not as `Storage`.

**Privacy text for the setup page (3–5 sentences, adoptable verbatim):**

> "Ihre Sprache und Ihr Text bleiben auf diesem Gerät. Die App nutzt das
> Netzwerk ausschließlich für den einmaligen Download des
> Spracherkennungsmodells (ca. 671 MB) von der angegebenen Adresse; es gibt
> keine Telemetrie, keine Konten und keine Cloud-Erkennung. Nach dem
> Download schaltet die App den Netzwerkzugriff für sich selbst ab; Sie
> können ihn hier jederzeit wieder erlauben oder dauerhaft aus lassen.
> Zusätzlich können Sie der App in den Android-Einstellungen die
> Netzwerkberechtigung komplett entziehen — die Tastatur funktioniert
> danach unverändert offline. Ohne installiertes Modell bleibt die Tastatur
> nutzbar, die Sprachfunktion ist dann deaktiviert."

**Effect on `docs/TECH_FACTS.md`:** §3 point 3 remains substantively
correct ("INTERNET stays in only for the model download"), but must be
extended by: (i) the permission is **permanently** in (no more staging, B-4
done), (ii) the in-app switch + OS revocation as the second/first level,
(iii) `FOREGROUND_SERVICE_DATA_SYNC` as a new, operationally justified
permission. The promise "no network connection" becomes precisely **"no
network connection except the one-time model download"**. DESIGN §8's line
"the user can revoke the GrapheneOS network access after the download"
remains valid unchanged.

---

## E3 — start path for users without a model

### Decision

**The keyboard never blocks.** It always types. Only the **voice function**
is disabled without a model and leads into the setup (DESIGN §8: "Without a
model: the mic opens setup instead of hiding a download in the
background").

### Checkable UX rules

1. **Start:** app start (launcher `SetupActivity`) shows the model status
   from `ModelStore.status()` and exactly one primary action ("Modell
   herunterladen" / "Fortsetzen" / "Fertig").
2. **IME without a model:** the keyboard appears and types normally. The
   mic key shows the state "Modell fehlt" (grayed out + label/icon,
   `contentDescription` "Sprachfunktion: Modell nicht installiert") and is
   **not** silent: a tap opens `SetupActivity` (`FLAG_ACTIVITY_NEW_TASK`),
   no download starts automatically.
3. **No background download:** a download begins exclusively through an
   explicit tap in the visible setup page (E1/L1). Never on IME start, never
   by timer, never after a reboot.
4. **Abort:** state `Cancelled`, `.part` remains, the UI offers
   "Fortsetzen"; the already loaded amount is displayed (no restart at
   0 %).
5. **Network error:** `ModelError.Network` → "Keine Verbindung. Erneut
   versuchen", an automatic retry with backoff (max. 3, only in the
   foreground/FGS), then manual. With the in-app network off: hint + switch
   to turn it on, **no** request.
6. **Hash mismatch:** `ModelError.Hash` → the affected file is discarded and
   **only that one** re-downloaded; the active version stays untouched
   (`ModelStore` atomicity), message "Datei war beschädigt, wird erneut
   geladen".
7. **Little storage:** a plausibility check before the start
   (recommendation: **≥ 2.5 GB** free, i.e. ~3.7× the model size because of
   staging + `.part`); on too little space abort **before** the first byte
   with a concrete number ("2,5 GB benötigt, 0,8 GB frei"). During the
   download treat `ModelError.Storage` the same way.
8. **Doze/battery/process death:** the download runs only in the
   `dataSync` FGS (E1); screen-off is allowed. If the system kills the
   service, the setup page shows "unterbrochen — fortsetzen" on the next
   opening and continues via a Range request (no data loss, no progress at
   0).
9. **After success:** without an app restart the mic key becomes active
   (the engine loads lazily, cf. C-2/R-1: load errors must become visible in
   `lastError`, never "Bereit" with a missing model).

---

## E4 — M4d test strategy "dictation" without a real microphone
(`<device>`, `-no-audio`)

### Decision

**One instrumented test (`DictationWavInstrumentedTest`) with two
injections: `AudioSource` → `WavAudioSource` (fixture from
`/data/local/tmp/testdata/`) and `InputConnectionSink` against real host
editors in a test activity.** What this proves is the **entire** chain from
the audio buffer to the text in the field — everything except the
microphone driver/route (that stays a device gate, DESIGN §10.4).

**Measurement points the test must log** (TextWatcher log +
`EditorSink` spy): exact final text states, number/position of the
`commitText`/`deleteSurroundingText` calls, order (never deleting behind the
commit boundary), no `setText`, no `setComposingText`, no writing after an
ownership loss.

### The 3 most valuable scenarios

| # | Editor variant | Expected text edit sequence | What it proves |
|---|---|---|---|
| S1 | `EditText`, single-line, empty, `inputType=textCapSentences` | Monotonic append blocks: `"Das ist "` → `"Das ist der "` → … → final text **exactly** the `de_pause` golden (§4.1 ASR_CONTRACT); no deleting before the respective commit boundary; total backspaces only in the tail | Golden text identity (M5) **plus** DeltaTyper compatibility in a real field; separates decode errors from typing errors |
| S2 | `EditText`, multi-line, **cursor set in the middle of the text** (user clicks), then dictation continues | After the click: **no more** `commitText`/`deleteSurroundingText` (ownership lost, `Rejected`), the text stays unchanged; only a new dictation writes again | Ownership/foreign text protection (M4 gate: "no foreign text deleted") under a realistic interruption |
| S3 | `EditText`, already ~80 characters of content (above `max_revision_chars=60`), `textNoSuggestions` | No rewrite-back > 60 characters: either append-only or `Rejected`; never a delete delta > 60; the final text contains the old text unchanged as a prefix | The 60-character revision boundary + "no destroying foreign/old text" in a real editor |

**Addition (cheap, high value):** S1 with **`de_pause.wav`** (pause ≥ 4 s)
also evidences the pause regression; S1 with a **0.3-s silence fixture** must
yield an **empty** result (no `"Yeah."` → the guard also works in the field).

**Boundary conditions for the task:** its own instrumented task (no feature
code), `-no-audio` is permitted and is flagged in the test report as "not E2E
(microphone route untested)"; push the WAV via `adb push` to
`/data/local/tmp/testdata/` beforehand; no
`connectedDebugAndroidTest` combination with feature work
(CONTINGENCY_PLAN §2.1).

---

## Appendix: checklist before the merge of M5b-2 / M7

- [ ] `INTERNET` + `ACCESS_NETWORK_STATE` visible in the merged manifest
      (`aapt2 dump permissions`), `TelemetryInitializer` still removed.
- [ ] `FOREGROUND_SERVICE_DATA_SYNC` present; the service with
      `foregroundServiceType="dataSync"`, `exported=false`.
- [ ] The notification channel `model_download` exists; `startForeground`
      < 5 s.
- [ ] Cancel → `State.Cancelled`, `.part` remains, resume does not start at
      0 %.
- [ ] `onTimeout` implemented (code path present, callable via a unit
      test/fake).
- [ ] A hash error re-downloads only the affected file; the active version
      unchanged.
- [ ] Space check before the first byte, concrete numbers in the message.
- [ ] IME without a model: types, the mic opens setup, no auto-download.
- [ ] The setup page's privacy text matches E2 (verbatim).
- [ ] `docs/TECH_FACTS.md` §3 extended by permission permanence + switch.
