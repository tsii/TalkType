# REVIEW — v0.1.33 Section A2 + A4 (DeepSeek V4 Pro 0813)

**Date:** 2026-11-02 · **Reviewer:** cortecs/deepseek-v4-pro-0813 (sa-d487be58, read-only) · **Verdict: P0/P1 = 0 · Push recommendation YES**
(Adopted verbatim from the reviewer report; file written by the orchestrator because the review slot had no write permission.)

## Evidence checked
- **A2a DeltaTyper**: `ReviseKind`/`ReviseOutcome` closed, no null (`DeltaTyper.kt:38-54`); W3d latch one-shot, reset only after successful apply (`:96,126-131,149`); full revision `replaceLength == own length`, foreign prefix untouched byte-for-byte (`DeltaTyperTest.kt:337-360` ForeignPrefixSink); `reviseFinal` ownership-first (`:180-184`); SAME without sink (`:272-277`).
- **A2b Controller/Tracker**: W1 re-baseline exactly E1c (`DictationController.kt:614-615`); `lost=false` only in the `suffixProven` branch (`OwnershipTracker.kt:236-242`), ownEditFresh (`:246-255`)/stillOurs (`:256-260`) do not clear, actual==null rejecting (`SchreibstoppTest.kt:150-172`); finale never W3d-blockable (`SchreibstoppTest.kt:130-165`).
- **migrated existing test** `foreignSelectionBlocksFurtherWrites` (`DictationControllerTest.kt:224-257`): cursor grab ⇒ re-baseline+write; foreign content ⇒ permanently W1 without sink access, `lost` remains — contract carried correctly.
- **A4a/A4b**: 53 legacy KEYS order unchanged +10 new, 64 lines (`DebugInfoFormatter.kt:341` check); `event=window` 17 metrics, rule-9 regex, closed codes; release effectiveness `VoiceKeyboardService.kt:1048-1050` outside `BuildConfig.DEBUG`.
- **Concurrency/A1**: counter @Volatile single-writer, finale after join, reset in `start()` (`:432-449`); A1 baselines AFTER `src.start()`/BEFORE thread start (`DictationCoordinator.kt:424-431`); thresholds 3000/3000/2500 unchanged.
- Test XMLs of all affected classes: failures=0 errors=0.

## Findings
### P2 — `ownershipDeniedAtS` set before the re-baseline attempt
- Location: `DictationController.kt:606-616` — `ownershipDeniedCount += 1`/`ownershipDeniedAtS` (`:606-609`) run BEFORE `tracker.rebaseline(...)` (`:614-615`).
- Effect: a successful suffix proof + write still leaves behind an ownership-loss timestamp ⇒ false-positive `owner_lost_at_s>=0` (tension with PLAN §3.3).
- Minimal suggestion: counter/timestamp only in the `if (!rebased || !isOwned)` branch. Pure diagnostics fix.

**No P0/P1.** Recommendation: take the P2 along as a micro-contribution in the B section, then final assembly.
