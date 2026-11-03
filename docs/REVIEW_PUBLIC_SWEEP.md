# Review: UI language sweep + repo sanitization (pre-public)
Reviewer: DeepSeek V4 Pro 0813 (sa-4a156936), 2026-11-03 11:4x. Full text in the transcript.

## Verdict: P0=0, P1=8 (residual user-facing German), P2 several — not public-ready until fixed.

## PASSED
- No logic changes (81 diff files, literals/comments/resources only).
- No secrets: no <internal-net> IPs, no tokens/keys; SHA256: hit = public SSH host key (harmless).
- 25 modified test files synced byte-exact to new English strings; no test asserts old literals.
- README fully English.

## FIX (done via fix steers 11:4x)
- P1 user-facing German: CaptureForegroundService.kt:85/88/104 (channel name/desc, "Mikrofon ist aktiv"),
  KeyboardView.kt:222/1311 (contentDescription "Tastatur"), DebugInfoCollector.kt:199 ("Diagnose teilen"),
  DownloadController.kt:125 ("Staging-Move fehlgeschlagen").
- P2: internal exceptions (ParakeetAsrEngine:36, ModelStore:334/340), dead BUILTIN_MIC uiLabel,
  6 comment citations, DownloadPolicyTest/DownloadControllerTest fixtures ("für "→"for ", keys stay).
- P2 sanitization residuals: <workdir> in docs/IMPLEMENTATION_PLAN.md:183/393/396; <device> in
  RUNTIME_SWITCH_NPU_ASSESSMENT + GPU_ACCELERATION_ANALYSIS; <device> in several docs +
  ModelStore.kt:148 + LongPressMenu.kt:198/228 comments.
