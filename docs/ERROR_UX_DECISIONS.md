# Error UX and setup decisions (E5–E7) — binding

**Status:** 2026-10-27 · **Role:** analysis/rules (no code/resource changes)
**Basis:** `docs/REVIEW_STAGE5.md` (S5-2, S5-3, S5-4, S5-6),
`app/src/main/res/values/strings.xml`, `ime/SetupActivity.kt`,
`audio/AudioRecordSource.kt`, `ime/VoiceKeyboardService.kt`,
`docs/SETUP_UI_DECISIONS.md` E3, `docs/KONZEPT.md` §2.1 ("the keyboard never
blocks").

---

## E5 — Error differentiation at dictation start

### Current state (evidenced)

- `VoiceKeyboardService.onMicDown()` writes the same text for three completely
  different causes: `updateStatus("Start fehlgeschlagen")` (L.156) or
  `"Start fehlgeschlagen (${e.javaClass.simpleName})"` (L.163) — the latter is a
  **developer class in the user text** (`IllegalStateException`).
- `updateStatus()` (L.304) renders `c.visibleError` additionally as
  `"Fehler: Mikrofon/Route (<code>)"` — that too is a raw code
  (`audioInit:IllegalStateException`, `model:<msg>`, `startFailed`).
- Existing, already differentiated paths: model not ready
  (`!store.isReady()` → "Modell nicht installiert — Setup öffnen", L.144),
  permission (L.247/262).
- Available signals: `DictationCoordinator.lastError`/`visibleError`
  (`startFailed`, `audioInit:<class>`, `model:<msg>`, `audio:<code>:<detail>`),
  `AudioRecordSource.ERR_INIT | ERR_INVALID_OP | ERR_SYSTEM_STOP | ERR_MAX_DURATION`,
  `ModelStore.status()` = `ABSENT | DOWNLOADING | VERIFYING | READY | BROKEN`,
  `AudioRouter.routeListener`/`currentRoute` (`WIRED > BLUETOOTH_SCO > BUILTIN`).

### Decision: 7 user classes + 1 fallback (texts ≤ 45 characters)

| Class | User text (max 45 chars) | Chars | Action option in the UI | Technically distinguishable? |
|---|---|---|---|---|
| **(a)** no microphone | `Kein Mikrofon gefunden – Gerät prüfen` | 37 | no action in the app; hint line "connect microphone/check system", mic stays locked | **Yes, conditionally**: `AudioManager.getDevices(GET_DEVICES_INPUTS)` empty or `RoutingPolicy.preferredInput()` = `null`. Otherwise the case falls into (d). |
| **(b)** microphone occupied | — **no own text** → (d) | — | — | **No.** Other recordings are not visible to normal apps (`getActiveRecordingConfigurations` is privileged); "occupied" would be a guessed diagnosis. Only the *hint* in the explanation is allowed, not the claim. |
| **(c)** permission missing/revoked | `Mikrofon-Berechtigung fehlt` | 27 | Button "Grant permission" (in-app request); on `denied_forever` → "Open system settings" | **Yes**: `checkSelfPermission(RECORD_AUDIO)` before the start **or** `lastError == "audioInit:SecurityException"`. |
| **(d)** audio init/system error (+ b) | `Audio-Start fehlgeschlagen – erneut versuchen` | 45 | Press the mic key again (no auto retry loop); explanation: "Possible causes: microphone occupied by another app, system error." | **Yes as a class**: `startFailed`, `audioInit:<not SecurityException>`, `ERR_INIT`, `ERR_INVALID_OP`, `ERR_SYSTEM_STOP` **without** a simultaneous route change. |
| **(e)** model not loaded | `Modell nicht geladen – Setup öffnen` | 35 | Tap → `SetupActivity` | **Yes**: `store.isReady() == false` **and** `status ∈ {ABSENT, DOWNLOADING, VERIFYING}`, or `lastError == "model:<…>"` (engine load error, C-2/R-1). |
| **(f)** model damaged/BROKEN | `Modelldatei defekt – neu herunterladen` | 38 | Tap → setup, there "check/download again" (hash repair) | **Yes**: `store.status() == BROKEN` (pointer present, `verifyInstalled()` false). |
| **(g)** memory low (runtime) | `Zu wenig Speicher – App neu starten` | 35 | only a hint + process restart by the user; no auto recovery | **Partially**: `OutOfMemoryError` (must be caught **explicitly** as an `Error`/`Throwable` — `catch (e: Exception)` does not see it) and ORT messages with `alloc`/`memory`/`bad_alloc`. Everything else is not classifiable → (d). |
| **(h)** Bluetooth SCO lost | `Bluetooth-Mikrofon getrennt` | 27 | End the recording **in a controlled way**, hint "continue via the device microphone?"; no automatic restart | **Yes**: `state == RECORDING` **and** `routeListener` reports a change from `BLUETOOTH_SCO` to `BUILTIN`/`WIRED`/`null` (or `ERR_SYSTEM_STOP` exactly at that moment). |
| **Fallback** | `Diktat-Start fehlgeschlagen` | 27 | try again; explanation "details in the system log" (no raw code in the text) | When no rule applies (mandatory: the only place without a diagnosis promise). |

**Binding rules (coder):**

1. **Never raw codes in the user text.** `visibleError` is **mapped**, not
   printed (`audioInit:IllegalStateException`, `startFailed`, `model:…` may only
   land in the log, not in the status row).
2. **Priority order** of evaluation (first match wins): permission (c) → model
   (f BROKEN before e) → no device (a) → SCO loss (h, only during RECORDING) →
   memory (g) → audio init (d) → fallback.
3. **Sticky semantics remain** (R-2): the error stays until the next deliberate
   action; `onMicUp` does not clear it.
4. **No diagnosis promise without evidence:** (b) does **not** exist as a text;
   the explanation for (d) names causes as a possibility ("Possible causes: …"),
   never as an assertion.
5. **The keyboard never blocks** (KONZEPT §2.1): every error class affects only
   the voice function; typing remains fully possible, no modal lock.
6. **No auto retry** for (a)/(d)/(g); for (h) no automatic recording restart (the
   mic route could change again → flickering).
7. **String resources:** all seven texts (plus fallback) as `dictation_error_*`
   in `strings.xml`; **no** assembled sentences in code (translatability).
   Proposed keys: `dictation_error_no_mic`, `_permission`, `_audio_start`,
   `_model_missing`, `_model_broken`, `_out_of_memory`, `_bt_lost`, `_unknown`.

### What we *cannot* do today (deliberately)

- **(b) not** — Android gives other apps no insight into foreign recordings.
- **(g) only roughly** — OOM is catchable; native ORT allocator errors are
  recognizable only via message patterns; both are best-effort and otherwise
  fall into (d).
- **(a) only conditionally** — an empty device catalog is unambiguous; an
  existing but non-functional device (e.g. emulator `-no-audio`) lands in (d).
  That is exactly why (d) is the **catch-all class** and not (a).

---

## E6 — Privacy text precision (S5-4)

**Facts:** `INTERNET`/`ACCESS_NETWORK_STATE` remain permanently in the manifest
(permissions cannot be toggled at runtime on Android); the shutdown after the
download is the **app switch** (`network_for_downloads`) that gates the download
start. Additionally, on GrapheneOS the user can revoke the network permission at
the system level.

**Variant A (recommended, minimally invasive, 2 sentences):**

> "Nach dem Download deaktiviert die App ihren Download-Schalter automatisch; es
> findet dann kein weiterer Netzwerkzugriff statt. Sie können den Schalter jederzeit
> wieder aktivieren oder der App in den Android-Einstellungen die
> Netzwerkberechtigung ganz entziehen — die Tastatur funktioniert danach unverändert
> offline."

**Variant B (more explicit about the permission, 3 sentences):**

> "Die Netzwerkberechtigung bleibt im App-Manifest, wird aber ausschließlich für
> diesen einen Download genutzt. Danach schaltet die App ihren Download-Schalter
> automatisch ab, sodass kein weiterer Netzwerkzugriff stattfindet. Sie können den
> Schalter jederzeit wieder aktivieren oder der App in den Android-Einstellungen die
> Netzwerkberechtigung ganz entziehen — die Tastatur funktioniert weiterhin offline."

**Recommendation: variant A.** Rationale: it removes the only ambiguity ("turns
off network access" → "deactivates its download switch") and keeps the second
sentence as an escalation path (OS revocation). Variant B names the "app manifest"
— developer jargon that only creates uncertainty for the user and makes nothing
decidable; the technically relevant promise ("no further network access") stands
in A just as well. **The rest of the text remains unchanged** (sentences 1–2 on
the local retention of audio/text, the closing remark about "without model").
`docs/TECH_FACTS.md` §3 already describes the manifest side correctly.

---

## E7 — `dataSync` FGS on API < 34 (S5-6)

**Correction of the review premise:** `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`
**and** the manifest attribute `android:foregroundServiceType` exist **since API
29** (minSdk = 29) — they are *not* API 34 concepts. New only from API 34: the type
is **mandatory**, every type class has its own `FOREGROUND_SERVICE_*` permission,
and violations throw `MissingForegroundServiceTypeException`/`SecurityException`.
*(Source: SDK constants doc "Added in API level 29"; not counter-checked via
Gradle in the container.)* The practical consequence remains anyway: **no
unguarded start**, but defensive starting with a fallback.

**Binding rule (coder):**

1. **Primary:** `startForeground(NOTIFICATION_ID, notif,
   ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)` (3-arg form, API ≥ 29) — no
   `SDK_INT` branch needed around the type itself.
2. **Fallback cascade** exactly once, in this order:
   `IllegalArgumentException` (type not in the manifest) → `SecurityException`
   (permission missing, possible only on API ≥ 34) →
   `ForegroundServiceStartNotAllowedException` (API ≥ 31, start from the
   background) ⇒ try `startForeground(NOTIFICATION_ID, notif)` **once** (2-arg,
   type = from the manifest, without a time limit on API 29–33).
3. **If that also fails:** `stopSelf()`, the download stays `Idle` (`.part`
   untouched) and the setup page shows the existing text
   `setup_error_fgs_blocked` ("Download konnte nicht gestartet werden. Öffnen Sie
   die App erneut und versuchen Sie es noch einmal."). No crash, no silent
   continuation without a notification.
4. **No own text for "old Android":** on API 29–33 the download runs unchanged
   with the 2-arg fallback (only without type semantics/6-h limit). The user
   needs **no** message there. `onTimeout` (API ≥ 35) remains present as an
   `override` method; on older APIs the system never calls it.
5. **Testability without an old device:** the fallback path is checkable as a unit
   test via an injectable "foreground starter" (a fake that throws on the 3-arg
   call); the emulator (API 36) does **not** cover it — hence a mandatory test, not
   emulator observation.

---

## Appendix: checklist (merge gate for S5-3/S5-4/S5-6)

- [ ] Eight `dictation_error_*` strings present, each ≤ 45 characters.
- [ ] No raw code (`startFailed`, `audioInit:…`, `model:…`) appears in the
      status row — log only.
- [ ] `store.status() == BROKEN` produces the damaged text, **not** the "not
      installed" text.
- [ ] `OutOfMemoryError` is caught (not just `Exception`).
- [ ] SCO loss during RECORDING ends the recording and shows `_bt_lost`.
- [ ] Privacy text matches variant A (verbatim).
- [ ] 3-arg `startForeground` with fallback cascade + unit test for the throw
      case.
- [ ] Typing remains possible in all error classes (no lock, no modal).
