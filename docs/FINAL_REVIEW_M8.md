# FINAL REVIEW M8 – Final verdict on the sideload handover

**Date:** 28.10.2026
**Responsibility:** Design and release decision based on the available evidence, no new device job.
**Verdict: Release for sideload to the user for personal evaluation of v0.1.0.**

The four P1s confirmed in M7 are accepted as resolved. The open points
L-01/UI-01 and L-03 are **P2 and not handover-blocking**. L-01 does not have to be fixed before
the handover. This release is not an assurance of a fully tested, physically verified on
GrapheneOS, unrestrictedly everyday-usable dictation solution.

## 1. Standard of evidence and the verified artifact

I read `docs/REVIEW_STAGE8_UI.md` in full, looked at the six screenshots named below myself,
and spot-checked existing sources purely by reading. No adb use, no Gradle/build, no commit
and no code/asset changes. The only newly created project file is this report.

The handover additionally reports 237 unit tests without failures, 6/6 instrumented tests
with a real engine WAV run and golden final text, plus a machine
15/15-language sample. I accept these results as an **orchestrator protocol**;
I did not re-run the tests here and do not claim my own inspection
of all raw logs. The same applies to the prefs readings and the logged
symbol toggle sequence. The DeepSeek review reports 0 P0 / 0 P1 / 1 P2 within its UI
scope; that is an additional finding, not a complete system/hardware certification.

The released local artifact was re-verified by me **read-only** with `stat` and
`sha256sum`:

| Feature | Binding value |
|---|---|
| File | `dist/voice-keyboard-0.1.0.apk` |
| Size | **81,148,054 bytes** |
| SHA-256 | **54c74a45280aaa7a60549add2f5c5b25d08d88b60b1eb762aac4481c124abd8f** |
| Download | HTTP 200, `Content-Disposition: attachment` and Range 206 per the orchestrator; not re-fetched here |

The release applies to exactly this hash, not to arbitrary later builds.
The hash check identifies the file; it does not replace an independent signature check.

**ASSUMPTION:** The orchestrator has attributed the cited functional results to the
corresponding current implementation state of this dist artifact.
Screenshots alone do not prove this build attribution. There is no report
of a deviating or subsequently functionally changed dist state.

## 2. Decision on the four former P1s

| Former P1 | Status | Named evidence and own verification | Does the evidence hold for me? |
|---|---|---|---|
| **P1-01 – language switch without effect** | **resolved** | Looked at myself: `/tmp/m8b_de.png` shows "Deutsch" active (translated from German), 11 keys in row 1 including ü and DE-QWERTZ; `/tmp/m8b_en2.png` shows "English (US)" active (translated from German), 10 keys and z in row 3. Per the orchestrator, a globe tap also switches `vk_layout/current_language` de-de ⇄ en-us. 15/15 device samples deliver the expected first character per language, including FR a, EL ς, RU й. Setup with 15 checkboxes, minimum count and recording guard are implemented per the handover. | **Yes.** The visible layout switch plus the reported persistence and real character input fix the formerly unreachable language switch. One character per language does not prove its entire linguistic quality; the sample is not a full test of all keys. |
| **P1-02 – caps double-tap without effect** | **resolved** | `/tmp/m8a_caps.png` looked at myself: field **QwQW**, uppercase board and green-highlighted caps symbol. Orchestrator sequence: single shift q/w → Qw, double-tap → QwQW. A read-only code comparison confirms the double-tap branch before the disable branch, `DOUBLE_TAP_MS=350`. | **Yes.** The previously reproduced second-tap disable failure is refuted by the visibly continued uppercase. |
| **P1-03 – symbolsShift unreachable** | **resolved** | Orchestrator device protocol: **2 → 2² → 2²2** on two shift presses. Per T3, all 30 layers (15 × symbols/symbolsShift) are corrected to `id:shift`, `label:⇧`, `value:shift`. A read-only comparison shows the switching path for `KeyActions.SHIFT`; the UI review confirms the correction. No separately named symbol screenshot was handed over here. | **Yes, based on the reported device protocol plus implementation review.** Not claimed as a newly observed adb measurement of my own. The character sequence proves both directions, not merely a changed label. |
| **P1-04 – emoji action without a usable operating path** | **resolved** | `/tmp/p1_emoji.png` and `/tmp/v2_probe.png` looked at myself: status, emoji grid, **"Gesichter 1/2"**, a full-width native **"Zur Tastatur"** button, no keyboard sub-row underneath. `/tmp/v2_closed.png` shows the complete four-row keyboard again without overlay remnants. The subsequent z in the field is reported as an orchestrator input observation; the read closed image alone does not show this text. A service read-check confirms the existing `onEmojiSelected → onTextInput → commitText(text,1)` path and the recording guards; the UI review confirms the native return path. | **Yes, for the formerly missing panel/return path.** No further overlay trick, but the decided V2 mechanism with its own back button. Complete emoji rendering and insertion behavior on the physical target device remain point R7, not categorically "all emoji tested". |

**Count:** Of the four formerly confirmed P1s, **4 are resolved, 0 open**.
The presented evidence contains no new confirmed P0/P1. Unchecked
hardware areas are uncertainties and not automatically passed tests.

### Corrected geometry classification

The earlier "one row too high" report was withdrawn by the orchestrator as a reading
error. The reported hit rectangles and the UI review speak for the same model basis
in drawing and hit testing; I therefore do **not** keep the old suspicion open
as a confirmed defect. I did not perform a new touch measurement.

`pt-pt` has **five** rows and, per measurement, 745 instead of 630 px view height. Other
row positions are correct for it; a key positioned higher than on DE/EN alone is not an
offset error. The 15-language sample is compatible with this special case. V2 still must
not use any overlay geometry derived from a blanket four-row assumption.

## 3. Why the release is possible now compared to M7

M7 rejected a build with four actually failed promised keyboard functions.
These functions are now reachable, respectively the former failure is fixed with
comprehensible device/review evidence. A German space label is not as serious as a
non-working language switch or a panel without a return path. Holding on to the old
P1 verdict would therefore be factually wrong.

The physical audio/GrapheneOS acceptance was expressly named in M7 as a release gate
for a comprehensively tested delivery. I do **not** declare this gate passed: I now
expressly accept the narrower scope **personal sideload evaluation with disclosed
gaps**. This is a conscious risk acceptance, not a retroactive reinterpretation of
emulator WAV tests into microphone tests.

The personal APK handover requires neither a new device job nor an
L-01 fix beforehand. Marketing it as fully tested on GrapheneOS/headsets or as
a reliable replacement for the previous keyboard is **not**
covered by this release. Known data losses, continued recording after hiding, or a new
P0/P1 would change the verdict; such errors are not covered by "at your own risk".

## 4. Honest remainder list — exactly eight points

The list contains **two known P2s** and **six target-device/test gaps**. The
test gaps are not blocking for informed personal evaluation, but must be closed
before an unrestricted quality assurance toward other users.

| No. | Open point | Classification / does it block this sideload release? | Concrete completion evidence |
|---|---|---|---|
| **R1** | **L-01/UI-01: space is called "Leer" everywhere** | **P2, no.** Visible i18n deficiency, not a failed space input. Name it clearly before the handover; do not sell it as a fully localized surface. | `app/src/main/java/de/voicekeyboard/keyboard/KeyboardView.kt`, `displayLabel`: layout label instead of blanket SPACE→Leer; afterwards EN "Space"/PT "Espaço" visible and space still inserts a space. |
| **R2** | **L-03: availableIds() knows only DE/EN** | **P2, no for the 15 fixed shipped languages.** The canonical language cycle and direct asset loading work independently of it per the 15/15 probe. Dynamic JSON extensibility remains incomplete; new assets must not yet be promised as automatically discoverable. | `app/src/main/java/de/voicekeyboard/layouts/LayoutRepository.kt`: asset scan; a test shows all 15 IDs and an admissible additional layout without a new hard-coded list. |
| **R3** | Real internal microphone on physical GrapheneOS | **Not provable on this emulator; no for evaluation.** WAV engine success proves neither the recording source nor the real permission/FGS/privacy integration or ARM64 latency. | On the target device: recording by holding, end on UP, comprehensible errors on privacy revocation, real field text; document offline operation after model setup and restart as well as latency/thermal behavior. |
| **R4** | Headsets and especially Bluetooth route switches | **Not checked; no for evaluation.** A successful internal microphone is not BT proof. | Evidence cable/USB and BT microphones each as the actual source; disconnecting/switching during recording ends in a controlled way or reports an error, instead of secretly switching to another mic. |
| **R5** | Dictation on app/editor switch or in the background | **Not checked; no for evaluation, safety-relevant test priority.** Wanted background recording is not a product goal. | Switch the target app, hide the IME and lock the screen: the microphone is released, no further recording and no delayed commit into a foreign editor. A violation would be P1/P0 depending on impact and a revocation of the release. |
| **R6** | Long real spoken sentence with live/final correction | **Not fully proven by the named golden final text; no for evaluation.** Final-text equality alone does not prove every intermediate revision or all foreign-text protection cases. | A longer sentence with a pause, live tail and final correction in the target app field; then move the cursor externally: no deletion before the own region and no output after loss of ownership; existing text preserved. |
| **R7** | Emoji glyphs and actual insertion on the target device | **Only partially evidenced by emulator images; no for evaluation.** The glyphs I looked at are visible; this does not imply a complete check of all 384 entries/fonts/sizes on GrapheneOS. | Browse categories/pages, insert representative emojis between old text, check cursor position and unchanged old text, tap the back button and keep writing; no truncated glyphs, no focus loss, check the PT five-row return separately. |
| **R8** | TalkBack / accessible operability | **Not checked; no for the evaluation not accepted as accessible.** A visible button or accessibility code does not prove usable screen reader operation. | Open the emoji panel, understand category/page, select an emoji, find and trigger "Zur Tastatur", operate the normal keyboard; sensible announcements, ordering and no focus trap. |

L-01 and L-03 are thereby **two distinct P2s**, even though the follow-up
UI review counts only L-01 as a finding within its narrower scope. I do not add
a new P1 from this. R3–R8 remain genuinely unchecked; "at your own risk" makes
them neither green nor meaningless.

## 5. May the APK be handed over now?

**Yes.** The APK with the hash confirmed above may be handed to the user for sideloading
now. **I do not demand L-01 beforehand.** A short-term label fix
would be welcome, but does not justify another untested build before this
handover. If the APK is nevertheless changed, the hash binding of this verdict no longer
applies automatically to the new artifact.

The bare phrase "at your own risk, unchecked areas" would be too unspecific. Permitted,
honest handover wording:

> "Voice Keyboard 0.1.0 is released for your personal sideload evaluation.
> Keyboard functions were tested on the Android 16 emulator, the local engine with WAV.
> Real GrapheneOS microphone, headsets/Bluetooth, stopping on app/background switch,
> long live corrections, complete emoji rendering and TalkBack have not yet been
> accepted on your device. The space bar is currently called 'Leer' in other languages
> too; additional JSON layouts are not yet listed dynamically.
> Keep another keyboard as a fallback for now and first test dictation in an
> uncritical empty field, not directly in important or sensitive texts."

No additional device evidence is needed for **this limited handover decision**;
therefore no new adb command sequence is requested here. For a later comprehensive
acceptance, R3–R8 are to be logged as standalone device tests.

## 6. Closing protocol

**DONE:** Four old P1s resolved; release for personal sideloading granted; two P2s not blocking; exactly eight remaining points including honest hardware/accessibility gaps documented.

**OPEN:** R1–R8. In particular, do not present physical GrapheneOS/audio behavior as passed.

**ASSUMPTION:** Orchestrator results belong to the current dist implementation state; no new contradicting P0/P1 evidence exists.

**RELEASE_REQUIRED:** No additional tool release for this verdict; no device operation or code work performed.

**VERIFICATION:** Six existing screenshots looked at myself, UI review read in full, relevant existing code paths compared by reading, APK size and SHA-256 confirmed myself; tests/prefs/touch protocols clearly marked as handed-over evidence.
