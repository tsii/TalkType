# Review Stage 4 part 2a — commit/ + editor/ (LocalAgreement, CommitPolicy, DeltaTyper)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Subject:** `commit/{CommitPolicy,LocalAgreement}.kt`, `editor/{EditorSink,TextDelta,DeltaTyper}.kt` + associated tests;
additionally stage 4 part 1 (audio/FGS) reviewed cursorily. Reference: KONZEPT §2.6/§2.7, DESIGN §5.4/§6/§2.3.
**Not executed:** `./gradlew` (parallel). Pure (read-only) code/test review.

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| S4-01 | **P2** | `LocalAgreement.kt:83-87` | **Word boundary rule deviates from KONZEPT §2.6 and can commit a partial word.** `endsAtWordBoundary = any(t.length==len \|\| t[len]==' ')`. `t.length==len` means "the hypothesis ends there", **not** "word boundary": with two identical hypotheses that end identically mid-word (deterministic ASR on two short, equally cut windows ⇒ "…derz"), `"…derz"` is released in full — exactly the half-word error that §2.6 ("Das müsste jetzt **de**" instead of **"deutlich**", translated from German) is supposed to prevent. KONZEPT §2.6 always cuts at `rfind(' ')`. `t[len]==' '` is the unambiguous boundary signal; `t.length==len` is not. | Lines 83-85 `(0 until count).any { … t.length == len \|\| t[len] == ' ' }`; line 87 `return oldest.substring(0, len)`. Cross-check: two identical `"Das ist derz"` ⇒ `t.length==len` true ⇒ release of `"derz"`. | See **decision proposal** below. Minimal: drop `t.length==len` (only `t[len]==' '` as the boundary), or KONZEPT-literal `rfind(' ')`; adjust the tests `wordBoundaryDoesNotCommitPartialWord`/`twoAgreeingHyph…` accordingly. |
| S4-02 | **P2** | `DeltaTyper.kt:85-91` | **`committedLength` is mutated before the sink write → violates the promise "state unchanged on sink error".** In the `toRemove > maxRevisionChars` branch, `committedLength = currentText.length` is set **before** `apply(...)`. If the sink throws, `currentText` is unchanged, but `committedLength` is already frozen to the full length → the next `revise` attempt of the same revision fails at `regionMatches` (the prefix does not match) and is **permanently discarded**. | Line 86 `committedLength = currentText.length` before line 91 `return apply(...)`; `apply` (lines 96-100) sets `currentText` only **after** `sink.replaceBeforeCursor`. | Set `committedLength` only after a successful `apply` (separate the success path from the error path), so a sink throw leaves no partial state mutation. |
| S4-03 | P2 | `LocalAgreement.kt:78,87` | **Surrogate pairs in the common prefix not protected.** The prefix comparison runs as a pure UTF-16 character comparison (`while (l<m && oldest[l]==t[l]) l++`) and `substring(0,len)` — if two hypotheses diverge **within** a surrogate pair (two astral characters with the same high surrogate), `len` lands on the high surrogate and `substring` produces a lone high surrogate. `DeltaTyper.commonPrefixLength` (lines 117-124) has exactly this guard (`isHighSurrogate`→`i--`), `LocalAgreement` does not reuse it. DESIGN §6: "align deletion boundaries with grapheme clusters, do not split surrogate pairs". | Line 78 `while (l < m && oldest[l] == t[l]) l++`; line 87 `substring(0, len)` without surrogate correction; cf. `DeltaTyper.kt:120-123`. | Apply the surrogate correction like `commonPrefixLength` to `len` before running `substring(0,len)` or `lastIndexOf(' ', len-1)`. |
| S4-04 | Note | `DeltaTyper.kt:101-109` | **Ownership = length + exact context, not the full DESIGN §6 protocol.** What is checked is `ownLength()==currentText.length` and `textBeforeCursor(len)==currentText`. That covers cursor movement and foreign edits (via context mismatch) fail-closed. **Not** represented are `editorGeneration`, starting selection, "expected suffix", `onUpdateSelection` callbacks from DESIGN §6 — those must live in the (still missing) `InputConnectionSink` (2b) and become visible there as `ownLength()==-1` / `textBeforeCursor()==null`. As a pure JVM abstraction this is the right layering, but only with 2b is §6 fully met. | Lines 101-109 `ownershipVerified` (only ownLength + trailing context). | In 2b: deliver a generation/selection-verifying `InputConnectionSink` that returns `-1`/`null` on an editor switch; DeltaTyper remains unchanged. |
| S4-05 | Note | `capture/CaptureForegroundService.kt:67` + `audio/*` | **Audio/FGS (part 1) rule-conformant.** `startForeground(…, FOREGROUND_SERVICE_TYPE_MICROPHONE)` immediately in `onStartCommand` (before any init), `START_NOT_STICKY` + null intent→`stopSelf` (no restarting), no binder (`onBind=null`, no audio over IPC), 16 kHz/mono/PCM16 (`AudioRecordSource` CHANNEL_IN_MONO/ENCODING_PCM_16BIT), no disk access, logs only durations (never audio/text). Routing `WIRED > BLUETOOTH_SCO > BUILTIN`, `OTHER`/A2DP never microphone (`AudioRouter.kt:28,33-42,130-133`). The manifest carries `RECORD_AUDIO` + `FOREGROUND_SERVICE(_MICROPHONE)` + `POST_NOTIFICATIONS` + `foregroundServiceType="microphone"` (TECH_FACTS §4.1). Open (caller responsibility, not here): runtime grant of `RECORD_AUDIO` before the start. | Line 67 `startForeground`; `AudioRouter.kt:40-42` priority; manifest lines 20-31/52-55. | Ensure the RECORD_AUDIO runtime request in the IME before the FGS start (next stage). |

---

## Point-by-point answers

1. **LocalAgreement:** 2 confirmations ⊗ distinct audio states ✓ (`audioStates[lastIndex]==audioSamples` ⇒ replaces instead of counting, lines 64-70). **Ring buffer `lastIndex = (head + size - 1) % size`** correct — also points at the newest entry when the ring is not yet full (line 63). Word-boundary logic see S4-01 (deviation + half-word risk). Surrogate gap see S4-03.
2. **CommitPolicy:** committed prefix never shrinks ✓ (`max(newLength, currentCommitted)`, line 66). Budget blocks ✓ (`budgetExceeded ⇒ return currentCommitted`, line 63). `onHypothesis` deterministic/idempotent: same audio state ⇒ `accept` returns `null` ⇒ no progress (no double counting). ✓
3. **DeltaTyper:** `max_revision_chars=60` — exceeding commits instead of destroying ✓ (`toRemove>60 ⇒ committedLength=length; apply(0, toAdd)`), but with state bug S4-02. Minimal delta via `commonPrefixLength` ✓. Surrogate protection in the delta ✓ (`commonPrefixLength` lines 120-123), **not** however in LocalAgreement (S4-03). Sink error: `currentText` consistent ✓ (except `committedLength` in the >60 branch, S4-02). Ownership length+context — sufficient as an abstraction, the full §6 protocol only in 2b (S4-04).
4. **Word boundary conflict of goals:** see decision proposal below.
5. **Part 1 (audio/FGS):** correct (S4-05).
6. **Test quality:** The commit/delta tests are **genuine cross-checks** (negative: `committedPrefixIsNeverTouched`, `ownershipMismatchIsRejected`, `contradictoryPrefixYieldsNothing`, `sameAudioStateDoesNotCountTwice`; error path `sinkErrorLeavesStateConsistent`). The "4 adjusted expectations" are the word-boundary cases (`twoAgreeingHypothesesReleasePrefix`, `wordBoundaryDoesNotCommitPartialWord`, `CommitPolicy.twoAgreeingHypothesesAdvanceCommit` with `"Hallo Welt"→10`, `streamingProgressively`) — they were switched from the KONZEPT-literal `rfind(' ')` length (e.g. `"Hallo "`=6) to the `endsAtWordBoundary` length (e.g. `"Hallo Welt"`=10), because the coder releases the **complete** final word. That is consistent **with** the implementation, **not** with KONZEPT §2.6 — the tests thereby pin the deviation (S4-01). Legitimate as a codification of the chosen reading, but **not** independent of the design decision.

---

## Decision proposal: word boundary (S4-01)

**Question:** May the final **complete** word of an intermediate hypothesis be committed, or is it **always** cut at the last space (KONZEPT §2.6 literally)?

**Context:** The stream yields `"Das ist der"` (window ends at the word end), then `"Das ist derzw"` (the word continues). Then intermediate forms again like `"…derz"`.

| | Option A — KONZEPT literal (`rfind(' ')`) | Option B — coder (`endsAtWordBoundary`) |
|---|---|---|
| Behavior | always cut at the last `' '` ⇒ commits `"Das ist "` (never the final word) | commits `"Das ist der"` (the complete final word) if `t.length==len` **or** `t[len]==' '` |
| Safe | **no** half word is ever committed | risk: two identical mid-word truncations ⇒ `"…derz"` gets committed (`t.length==len` is "end", not "word boundary") |
| Latency/flicker | the last word stays correctable until the following space (may be re-typed) | commits eagerly, less flicker |
| Concept fidelity | exactly the §2.6 pseudocode | meets the **intention** ("no half word"), but not the pseudocode |

**Recommendation:** **Keep option B, but drop `t.length == len` as a boundary trigger** — only `t[len] == ' '` is an *unambiguous* word-boundary signal (access `t[len]` only if `t.length > len`, otherwise `StringIndexOutOfBounds`). With that, a complete final word continues to be committed eagerly (as soon as the following space becomes visible), without a truncation counting as a "word end". Consequence: `"Das ist der"` vs. `"Das ist derzw"` then yields `"Das ist "` (8) instead of `"Das ist der"` (11) — **and** KONZEPT §2.6, the implementation and the affected tests must be synchronized for this (the "4 adjusted expectations" from point 6).

---

## Verdict

**Release only with fixes.**

No P0. The core logic (2 confirmations across different audio states, committed prefix untouchable, budget block, minimal delta with surrogate protection, 60-character emergency brake, sink error→state consistency in the normal case) is solid and well tested. Blocking are three P2s to be resolved before release — of which **S4-01 is a design decision that must be made now** (so far silently encoded in the tests), **S4-02** (a genuine state bug on the error path) and **S4-03** (surrogate protection in the agreement, consistent with DeltaTyper).

## Top-5 actions

1. **S4-01** — Decide the word-boundary rule (recommendation: option B without `t.length==len`), pull KONZEPT §2.6 + implementation + tests into sync.
2. **S4-02** — Set `committedLength` only after a successful sink write (error path leaves clean state).
3. **S4-03** — Build the surrogate correction into `LocalAgreement.stablePrefix` (reuse of `DeltaTyper.commonPrefixLength` logic).
4. **S4-04** — In part 2b deliver the `InputConnectionSink` with the full DESIGN §6 ownership protocol (editor generation, selection, suffix); only then is ownership complete.
5. Add a test for the S4-01 decision (two identical mid-word hypotheses ⇒ **no** commit of the half word) as a regression test of the chosen rule.
