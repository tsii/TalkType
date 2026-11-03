# Contingency plan (operating protocol for the orchestrator)

**Status:** 2026-10-27 · **Basis:** 11 logged incidents (I1–I11) + verified harness
facts (below, with sources). **Purpose:** avoid standstill, don't lose work, don't
postpone verification.
**Scope:** binding for every orchestrator run in this project.

## 0. Verified harness facts (levers, not guesses)

| Fact | Value / behavior | Source |
|---|---|---|
| Tool watchdog | **20 min** per tool call, changeable via `PI_WEB_TOOL_TIMEOUT_MS` (ms, `0` = off) | `pi-web-ui/dist/server/client-state.js` `DEFAULT_TOOL_WATCHDOG_TIMEOUT_MS` |
| Watchdog deferral via bash | `bash` with `args.timeout` (seconds) sets the watchdog to `timeout + 5 s`, **if** that is larger than the base | `client-state.js` `effectiveToolWatchdogMs()` |
| Hard abort | If `session.abort()` yields no `agent_end`: after **15 s** `forceResetConversation` → runtime gone, conversation rebuilt from disk | `agent-service.js` `HARD_ABORT_TIMEOUT_MS = 15_000`, `interruptRun()` |
| Dangling tool repair | Force reset discards the tool result in RAM → `toolCall` without `toolResult` → synthetic text gets appended | `pi-web-ui/dist/server/dangling-tools.js` `DANGLING_TOOL_RESULT_TEXT` |
| Subagent limit | **16** simultaneously *living* subagents in the conversation map (`isSubagent`); `stop` **only aborts**, it does **not remove** → the slot stays occupied | `agent-service.js` `MAX_SUBAGENTS = 16`; `stopSubagent` only checks `isStreaming/isIdle` |
| Slot release | Only **dismiss/remove** of the subagent conversation (UI/`collectSubagentDescendantIds`) frees the slot | `subagents.js` module header "完成即移出 (= dismiss)" |
| Persistent subagents | `persist=true` creates a **normal** conversation, its own cap of **8** per project | `agent-service.js` `MAX_OPEN_CONVERSATIONS = 8` |
| Compaction checkpoint | Extension writes `state/compaction/<sha256(sessionId)[:24]>.latest.json` with snapshots of `HANDOFF.md`, `reminders/ALWAYS.md`, `state/progress.md`, `reports/NEXT.md`, `reports/PROGRESS.md`, `state/project.json` — **only if `AGENTS.md` OR `HANDOFF.md` exists in the project root** | `~/.pi/agent/compaction-handoff.mjs` (`FILES`, formerly `return`) |
| Actual state of the project root | **no** `AGENTS.md`, **no** `HANDOFF.md`, **no** `state/`, `reports/`, `reminders/` → the checkpoint was never written | `ls` in `<workdir>` |
| `schedule_task` | cron or delay (min. **60 s**), one-shot/recurring, delivers the prompt back into the **same** conversation, rebinds after compaction | tool schema |
| `wait_all` | max. **60 s** per call (no permanent blocking) | tool schema |
| `conversation_read` | `status` = last tool call + last assistant text + open question; `files` = files created/modified | tool description |
| `claim_files` | advisory, first-wins, TTL 30 min, conflicts are reported, never blocking | tool description |

> Not verified (**guess**): whether the bash tool has its own default timeout
> below the watchdog. Irrelevant for the plan, because `sleep` within a turn is
> forbidden anyway (§2.5).

## 1. (a) RCA table

| # | Incident | Cause | Impact | Detection | Permanent avoidance | Residual risk |
|---|---|---|---|---|---|---|
| I1 | GLM coder aborts after 21–25 min with "This operation was aborted"; 2× the same assignment, 3 files + 1 instrumented test | assignment too large (feature + instrumented verification); model/provider stream abort ends the session without intermediate state | **2× ~22 min + stage-2 standstill** until decomposition; all work lost | No partial result on disk; `conversation_read`-`status` ends with abort text | assignment ≤ 4 files / ≤ 2 builds / ≤ 10 min (§2.1); instrumented test **never** in the coder brief; intermediate state on disk after every file block | provider aborts can hit any run |
| I2 | large assignment 15 layout JSONs + test, ~20 min, abort without output | batch too large (15 files), a single verification run at the end | ~20 min lost; afterwards 4 parallel small assignments 4/4 green | no output, no files | batch limit 4 files; distribute similar files across **parallel** short runs (§2.3) | 4 parallel runs → slot/resource pressure |
| I3 | "Subagent limit reached (16 live)"; done/error slots are not freed; `stop` does not help; 5 min waiting/compaction do not help; `spawn` + `delegate_task` blocked | the limit counts **living conversations**, not running ones; `stop` does not remove the conversation | **15–20 min delegation impossible** | error message on spawn | slot budget (§2.4): max. 6 simultaneously, recycle the rest via `steer`; after takeover request **dismiss** in the UI; prefer `persist=false` | without UI dismiss the slot stays occupied; recycling is the emergency exit |
| I4 | force-termination messages with 2× `steer` in the same block, 1× `stop`, 1× `sleep 240` ("Command aborted") | watchdog/hard abort + dangling repair; `sleep` blocks a turn inside a tool call | uncertainty "work lost?" → partly re-delegation, partly waiting time | message in the transcript (`dangling-tools.js`) | decision tree §2.6; **never** `sleep`; issue `stop`/`steer` individually | force reset can hit any long tool call |
| I5 | session `sa-0fe79…`/`sa-de264…` 30 min "running / (no output yet)" although edits had been on disk since 22:00 | `get_result` shows output only when the model produces text; file edits happen without stream text | ~30 min pseudo-waiting; "hung vs. working" unclear | `git status`/mtime delta vs. `conversation_read(status)` | liveness is measured **on the file system**, not on output (§2.5.4) | pure thinking phases without edits remain invisible |
| I6 | no alarm; blocking `sleep` 300–580 s; turn ends, job keeps running, nobody checks → "missed the deadline", standstill until the user asks | no wake-up mechanism used (`schedule_task` never used) | **most frequent standstill**: estimated 6–10 cases × 15–30 min = **2–4 h** | turn end without a planned wake-up is the error itself | §2.5: `schedule_task` instead of `sleep`, mandatory wake-up before every turn end with a running job | wake-ups fire only from 60 s; no "immediately" signal |
| I7 | "just write, no gradle"; compilation only ~30 min later → red (`ABSTENT` instead of `ABSENT`) | write-only assignment without immediate verification; verification without a deadline | ~30 min + standstill until the micro fix | only the late build | **coupling rule**: writing without verification is inadmissible; if the Gradle lock is occupied: schedule the verification as a `schedule_task` within ≤ 5 min (§2.2) | third-party builds can delay verification further |
| I8 | two agents in the same directory, ad-hoc conflicts; `claim_files` never used | no file/resource protocol; Gradle is project-wide | ad-hoc conflicts, debugging time, "no gradle" improvisation (→ I7) | harness hint "1 other run in same project directory" | §2.3: `claim_files` before every multi-file assignment; **Gradle = global mutex** with an announcement in the turn | advisory: claims block nothing |
| I9 | emulator `-no-audio` → dictation not E2E-checkable; after every reinstall, set IME manually; disable Gboard; `adb reboot` needed | setup knowledge not pinned as a checklist | 5–15 min each, recurring | setup step missing from the notes | checklist §3(iii), work through once and record the result in `state/progress.md` | emulator instability remains |
| I10 | after compaction: "HANDOFF.md missing / state/progress.md missing / reports/NEXT.md missing / file checkpoint not present" | checkpoint extension is **opt-in** via `AGENTS.md`/`HANDOFF.md` in the project root — both were missing | context loss loomed; only `docs/PROJECT_STATE.md` saved it | system message at compaction | §2.7: create `HANDOFF.md` + `state/progress.md` + `reports/NEXT.md` **in the project root** and keep updating them at every milestone | the extension writes only file snapshots, no semantics |
| I11 | reference decoder bug (PredNet state on blank) → 3/4 golden texts wrong, `min_audio_s` measurement round repeated | reference not validated against an independent source (istupakov/onnx-asr, NeMo) before building on it | a whole measurement round + reviews + 2 documentation revisions | contradiction between documentation claims (2.8-s window "worse") and reproduction | §2.8: validate every reference implementation against a third-party implementation + 2 counter-checks, **before** starting dependent measurements | an external reference can itself be wrong |

**Time balance of the incidents (estimate, from the reports):** I6 2–4 h · I1 ≈ 45 min ·
I2 ≈ 20 min · I7 ≈ 35 min · I5 ≈ 30 min · I3 ≈ 20 min · I9 ≈ 30–60 min total ·
I4 ≈ 15 min · I8 ≈ 10 min · I10 ≈ 5 min (luck) · I11 ≈ 40 min.
→ **The biggest lever is not the watchdog but the missing alarm (I6).**

## 2. (b) Binding operating protocol

### 2.1 Assignment size (hard upper limits)

| Size | Limit | Rationale |
|---|---|---|
| Files per assignment | **≤ 4** (3 production + 1 test); similar mass files count as **1** *if* generated (e.g. 15 layout JSONs via script) — otherwise ≤ 4 | I1, I2 |
| Changed lines | ≤ ~150 | I1 |
| Build/verification runs | **≤ 2** (1× target test, max. 1× retry) | I1, I7 |
| Expected work time | ≤ **8–10 min**; abort mark 15 min | 25% buffer against the 20-min watchdog |
| Verification depth | in the assignment: `./gradlew :app:testDebugUnitTest --tests "<class>"` (or `test`), **never** `connectedDebugAndroidTest` | I1 |
| Emulator/instrumented | separate assignment, instrumented test only, no feature code | I1 |

### 2.2 Verification duty (coupling writing ↔ checking)

1. **No write-only assignment.** Whoever writes verifies — or the verification is
   scheduled as a `schedule_task` within ≤ 5 min (I7).
2. After every accepted assignment, the **orchestrator** itself checks at least:
   `git status --porcelain -- <paths>` (target vs. actual) **and** a build/test command.
   "Coder says green" is not proof (I7).
3. If Gradle is occupied by a third-party run: request the test command **once**; if
   blocked, claim it and follow up within ≤ 5 min; until then the assignment counts as
   **not accepted** (status row in `state/progress.md`: `UNVERIFIED`).

### 2.3 Parallelism (file and resource scopes)

| Resource | Rule |
|---|---|
| Files | Before every assignment, `claim_files` with the exact paths + reason. Conflict report ⇒ do **not** start the assignment, split the scope. |
| Gradle | **global mutex**: exactly one Gradle run project-wide. Announce in the turn before starting (`Gradle lock: me, duration ~Xs`), release afterwards. No second agent in parallel. |
| Emulator/adb | exclusive; also announce in the turn. No `assembleDebugAndroidTest` parallel to `connectedDebugAndroidTest`. |
| ASR models | never run model loads (0.9 GB RSS) in parallel with the emulator. |
| Parallel subagents | max. **4** simultaneously (not 16), max. 2 in the same package |

### 2.4 Slot management (I3)

1. **Budget:** ≤ **6** living subagents simultaneously (reserve for retries).
2. **After takeover:** record the result in `state/progress.md` → have the session
   removed via **dismiss in the UI** (actively tell the user: "please close finished
   subagent conversations"), otherwise the slot stays occupied (code frees the slot
   only via conversation removal; `stop` merely aborts).
3. **Recycling instead of spawn** is the accepted emergency exit: `steer` on a
   `done` session with a new brief (works, I3 workaround) — but **one carrier
   session per model**, not arbitrarily many.
4. On "subagent limit reached": **immediately** (a) running `wait_all(60)` until the
   end, (b) request dismissal of finished sessions, (c) do the work yourself until
   then — do not wait 5 min and do not compact (both proven ineffective).

### 2.5 Poll/wake-up strategy (I5, I6) — fully replaces `sleep`

**Principle:** a turn must **never** wait with `sleep`. Waiting = end turn +
`schedule_task`. The user then sees an intermediate status instead of a hang.

| Situation | Action |
|---|---|
| Job started, expected duration ≤ 2 min | max. **2×** `wait_all(timeoutSeconds=60)` in the turn (≈ 120 s), then continue with 2.5 |
| Job started, expected duration > 2 min | end the turn with `schedule_task(schedule="in 4m", prompt="Check: <runId>, check canaries §4, record result in state/progress.md")` |
| Risky job (Gradle/emulator, > 10 min expected) | wake-up in **3 min**, afterwards recurring `*/4 * * * *` until completion |
| Running job + turn end | set a **mandatory** wake-up (never without) + status row to the user |
| Wake-up fires, job still running | liveness check (2.5.4), then a new wake-up in 4 min |
| Job done | cancel the wake-up (`schedule_cancel`), verify the result, update `state/progress.md` |

**2.5.4 Liveness is measured on the file system, not on output (I5):**
`conversation_read(id=<runId>, action=read)` → `status` (last tool call/text) and
`files` (modified files); additionally `git status --porcelain` + `find <scope> -newer <marker>`.
Criterion "working": a new file mtime **or** a new tool call within the last
**6 min**. Otherwise "hung" → §2.6.

### 2.6 Force termination: decision tree (I4)

As soon as the message "force-terminated / synthetic result" appears:

```
1. Delegate NOTHING new.
2. get_result(runId)
   ├─ "still running"  → liveness check (2.5.4): file delta?
   │     ├─ yes  → let it continue, wake-up 4 min, status to the user
   │     └─ no   → trigger canary §4 → stop(runId)
   └─ "done"/"error"   → step 3
3. Target vs. actual on the file system: git status --porcelain (only expected paths?)
   + spot-check of the required symbols (rg "class X|fun y")
   ├─ complete → step 4
   └─ incomplete → steer(runId, "repair/complete only X"); no new spawn
4. Verify (yourself): ./gradlew … --tests "…"  → green?
   ├─ green → take over, state/progress.md, cancel wake-up, dismiss slot
   └─ red   → micro-fix assignment (≤ 2 files, 1 build)
```
**Mnemonic:** the message is a *transcript* defect, not proof about the work.
Issue every `stop`/`steer` **individually** per tool block (I4a).

### 2.7 Before EVERY compaction (I10) — file checkpoint

Mandatory files **in the project root** (`<workdir>/`), because the
extension finds them only there (`compaction-handoff.mjs`, `FILES`):

| File | Mandatory | Content (max. ~40 lines) |
|---|---|---|
| `HANDOFF.md` | **yes — activates the checkpoint in the first place** | current assignment, next concrete step, open questions, pitfalls |
| `state/progress.md` | yes | milestone table with status + **verification state** (green/red/UNVERIFIED) |
| `reports/NEXT.md` | yes | the next 3–5 tasks in order |
| `reports/PROGRESS.md` | optional | actual state per module |
| `state/project.json` | optional | machine-readable short version (phase, active jobs, locks) |
| `reminders/ALWAYS.md` | optional | non-negotiable rules (e.g. "no sleep") |

Procedure: call `compact_context` **proactively** (with `focus`), **after**
HANDOFF/progress/NEXT have been written — not only when the system compacts
forcibly. Before every turn end with a running job: add a `HANDOFF.md` line
"running: <runId> <what> <since when>".

### 2.8 Reference/gate rule (I11)

Before a measurement/decision rests on a self-built reference:
(a) name the third-party reference implementation (repo/function), (b) define
**two** counter-checks with predictable outcome (one must fail), (c) only then
start measurement series. Record the validation result in `state/progress.md`.

### 2.9 Actively report standstill (instead of silence)

**Rule:** if a turn ends while any job is running, the answer is **incomplete**
unless (1) a `schedule_task` is set **and** (2) the user gets a status row:

> **Status:** `<job>` running since `<mm:ss>`; last file change `<mm:ss>`; next
> check `<HH:MM>` (automatic). I will report back with a result or an abort.

Additionally, for every delay > 5 min without progress: an interim report with
reason + options (keep waiting / abort / take over yourself).

## 3. (c) Templates

### (i) Coder assignment brief (copy-ready)

```markdown
ASSIGNMENT: <one sentence, one deliverable>
SCOPE (exactly these files, no others):
  - <path/new.kt>
  - <path/test.kt>
TIME BUDGET: 10 min of work / hard abort mark 15 min. If exceeded: stop and
  report an intermediate state (what is done, what is missing).
VERIFICATION (exactly 2 runs, in this order):
  1) ./gradlew :app:testDebugUnitTest --tests "*<class>*"
  2) only on failure: the same run again, max. 1×
  FORBIDDEN: connectedDebugAndroidTest, assembleDebugAndroidTest, clean, daemon restart,
  emulator, adb, network downloads.
MUST DO:
  - <rule 1, checkable>
  - <rule 2, checkable>
  - Save after every file (a partial result must be on disk, even on abort).
MUST NOT DO:
  - Touch no other files. No "incidental" refactorings. No new dependencies.
  - Do not delete/weaken tests.
OUTPUT: 5 lines — changed files, test result (exact), open points, risks.
```

### (ii) `HANDOFF.md` + `state/progress.md` (templates)

```markdown
<!-- HANDOFF.md — max. 40 lines, snapshotted at compaction -->
## Current assignment
<runId> · <what> · started <HH:MM> · scope: <paths>
## Next concrete step
1. <command/file/decision — concrete enough to be executable without context>
## Open decisions
- <question> → options A/B, recommendation, blocks <what>
## Pitfalls (from incidents)
- no sleep in a turn · Gradle = global mutex · instrumented test never in the coder brief
```

```markdown
<!-- state/progress.md -->
| Milestone | Status | Verification | Evidence |
|---|---|---|---|
| M2a commit/LocalAgreement | done | `test` green 21:12 | commit=abc123 |
| M2b editor delta | UNVERIFIED | build blocked (Gradle lock) | — |
| M3 layouts 15/15 | done | `LayoutCompletenessTest` green | 15 assets |
## Running jobs
- <runId> <what> since <HH:MM>, last file change <HH:MM>, wake-up <HH:MM>
```

### (iii) Emulator checklist (I9) — after every reinstall, in this order

```bash
# 1) Emulator (only for WAV/UI tests; without -no-audio, dictation E2E is NOT checkable)
$ANDROID_HOME/emulator/emulator -avd <avd> -no-snapshot -no-audio &
adb wait-for-device
# 2) Disable third-party IMEs (otherwise Gboard/Google voice steals the focus)
adb shell ime list -s                       # shows active IMEs
adb shell ime disable com.google.android.inputmethod.latin
adb shell ime disable com.google.android.googlequicksearchbox
# 3) Install our own IME and SET it (necessary after every reinstall!)
./gradlew :app:installDebug
adb shell ime enable de.voicekeyboard/.ime.VoiceKeyboardService
adb shell ime set    de.voicekeyboard/.ime.VoiceKeyboardService
adb shell settings get secure default_input_method     # must be de.voicekeyboard/…
# 4) Only if the IME still does not take effect (known from I9): reboot
adb reboot && adb wait-for-device
# 5) Dictation E2E: only possible with audio. Without -no-audio: check the mic route.
#    With -no-audio: WavAudioSource injection only, mark the result as "not E2E".
```

Documentation duty: record the result (which steps were necessary) in
`state/progress.md` under "device".

## 4. (d) Canaries — 5 hard abort thresholds

| # | Threshold | Measurement | Action |
|---|---|---|---|
| K1 | tool call running **> 15 min** without returning (watchdog at 20 min) | timestamp of the tool block vs. now | `stop` + decision tree §2.6 |
| K2 | **> 6 min** no file-mtime change in the declared scope **and** no new tool call | `find <scope> -newer <marker>` + `conversation_read(status)` | `steer` with "status + next step in 60 s", otherwise `stop` |
| K3 | **> 2 Gradle runs** in the assignment **or** one run **> 5 min** | transcript/build time | immediate `stop`, halve the assignment |
| K4 | **> 60%** of the time budget consumed and verification **not started** | start time vs. now | `stop`, halve the scope (I1/I7) |
| K5 | file set leaves the agreed list (**foreign paths** in `git status`) **or** > 4 files changed | `git status --porcelain` | `stop` + `steer` "only X/Y/Z" (I2/I8) |

Additional guard (not a canary but a pre-check): `subagent list` shows
**> 10** living slots → no new spawns, first dismiss/recycle (§2.4).

## 5. Prioritization: the 3 measures with the biggest time gain

1. **Alarm instead of `sleep` (§2.5 + §2.9) — I6, ~2–4 h.**
   Per the log, I6 was the *most frequent* cause of standstill: turn ended, job kept
   running, nobody checked until the user asked. Every missed deadline cost 15–30 min
   of pure dead time. `schedule_task` (delay ≥ 60 s, recurring) + a mandatory status
   row eliminates this class entirely and costs 10 s per turn end.
2. **Assignment slicing + coupling writing↔verification (§2.1/§2.2) — I1, I2, I7, ~1.5 h.**
   I1 burned the same 22-min assignment **twice** and held up stage 2 until it was
   decomposed; I2 lost 20 min on the 15-file batch; I7 cost 30 min of standstill plus
   a red build because of two typos. The 4-files/2-builds rule plus immediate
   verification addresses all three with one rule.
3. **Slot recycling + liveness on the file system (§2.4/§2.5.4) — I3, I5, ~50 min.**
   I3 blocked delegation for 15–20 min completely (and the obvious countermeasures
   `stop`, waiting, compaction are provably ineffective, because the slot is freed
   only on dismiss); I5 generated 30 min of pseudo-waiting on "(no output yet)"
   although the files had long been written. Both are decidable in 20 s with a look
   at `git status`/`files`.

Afterwards (smaller but cheap): create the I10 checkpoint (**5 min one-time effort,
prevents context loss**), I8 `claim_files` + Gradle mutex, I9 checklist,
I11 reference gate.

---

## 12. Harness aborts & blocked delegations (addendum 2026-10-28, incident I12)

**Case I12 (documented):** `subagent(steer)` for M8a at 01:47 UTC returned the
force-termination message instead of a result ("tool timeout / hung model stream",
synthetic result). The target session kept running, wrote `KeyboardView.kt` +
`tools/normalize_symbol_shift.py` (01:47) and all 15 layout assets (01:48), but then
ended with a **question** ("Ich frage kurz nach:" — "let me ask quickly"). No
`schedule_task` was set; the last alarm had been processed at 01:32. Result: 6 h of
standstill until the manual `get_result` at 07:46.

**Root causes (three, independent):** (1) the force reset discarded the orchestrator's
turn — the planned check step existed only in RAM; (2) no alarm set ⇒ nobody checks;
(3) the assignment allowed a question ⇒ the coder session parked **waiting** (no
compute progress, no timeout).

### R12.1 — The alarm is part of the delegation (mandatory, no exception)

**No delegation without an alarm.** Before **every** `steer`/`delegate_task`/`spawn`:
(1) call `schedule_list` (see R12.9), (2) set a `schedule_task` with a **deadline
≤ 6 min**, prompt = "Check <runId>: §4 canaries, `git status` delta, last output;
record result in `state/progress.md`; on standstill §2.6 decision tree". The alarm
must fire even if the **current turn dies** (it is a persistent scheduler job, not a
turn construct). A turn that ends with a running job and **no** alarm is a rule
violation (§2.9). After receiving the result: `schedule_cancel`. Deadlines: coder
assignment **≤ 6 min**, instrumented/Gradle **10 min** (a second alarm if needed),
research **≤ 6 min** — better to check too early than to wait 6 h.

### R12.2 — Assignments never end open-ended (no questions rule)

Every coder brief contains the line:
> "If anything is unclear, decide yourself according to the rules in
> `docs/LAYOUT_REQUIREMENTS.md` / `docs/SETUP_UI_DECISIONS.md`; document the
> assumption in your final report. **Do not ask a question** — a question blocks
> the run and counts as an abort."

Known ambiguities are resolved in the brief **before** delegating (paths,
expectation lists, prohibitions, target state). Whatever the assigner cannot decide
upfront is delegated as "decide yourself, report deviations".

### R12.3 — Gap rule (what does the coder do when the assignment does not suffice?)

If the coder finds a gap that breaks the assignment (structural change, missing key,
missing file, contradictory rule), the rule is **not** to ask, but:
1. finish the **subset** that the assignment safely covers,
2. write the gap as its own line `BLOCKER: <what> — <why> — <proposed rule>` in the
   final report,
3. change nothing outside the scope.
The orchestrator then decides in a **new, small** assignment.

### R12.4 — Scope complexity per delegation

| Size | Limit |
|---|---|
| Files (production) | ≤ 3, plus ≤ 1 script and ≤ 1 test file |
| Bulk assets (e.g. 15 layouts) | only via script; the script must have a **dry-run report** (what would change) and idempotency |
| Expectation list in the brief | ≤ 6 checkable points; more = split the assignment |
| Verification runs | ≤ 2 (1× target test, max. 1 retry) |
| Time budget | ≤ 10 min of work, abort mark 15 min |

### R12.5 — Rule sequence on "synthetic result" (concrete for this case)

1. Do **not** re-delegate, do **not** repeat the assignment.
2. `subagent list` + `get_result(<runId>)` → status and last output.
3. File-system target vs. actual: `git status --porcelain`, `ls -l --time-style=+%H:%M <scope>`
   against the paths named in the brief; expected files present? mtime plausible?
4. Only **then** verify yourself (`./gradlew … --tests …`).
5. Decision: **take over** / `steer` (close the gap, same session) / `stop`.
6. Result + wake-up status in `state/progress.md`, cancel the alarm.

### R12.6 — Recognizing and unblocking a blocked session

**Signal:** `get_result` reports `running`, but (a) the last assistant text ends with
a question (`?`, "let me ask quickly", "should I …") **and** (b) no new tool call and
no file-mtime change in the scope for > 5 min. That is **not computing** but waiting
for an answer — waiting can last indefinitely (no timeout).
**Reaction:** `steer` with the **answer** to the question as a terse assignment
("Decide: X. Implement only the test. No questions."); if there is no basis for a
decision, `stop` and re-cut the assignment.

### R12.7 — Never write paths from memory

File paths in the brief are verified with `rg --files | grep <name>` before sending.
A wrong path in the assignment (here: `keyboard/LayoutCompletenessTest.kt` instead
of `layouts/LayoutCompletenessTest.kt`) is an **orchestrator error** and produces
either a question (R12.2) or a wrong file in the coder.

### R12.8 — M8a addendum (product rule, until adopted into `LAYOUT_REQUIREMENTS.md`)

**Finding (measured, reproducible):** in `symbols`, all 15 assets have a ⇧ key with
`value:"shift"` (the app treats `SHIFT` in `symbols`/`symbolsShift` as a layer
toggle, `KeyboardView.handleAction`). In `symbolsShift` this key is missing in
**5** layouts: `cs-cz`, `pl-pl`, `ru-ru`, `sv-se`, `tr-tr` (the orchestrator's claim
"8 of 15" is **not** reproducible with this measurement; the 5 are it:
`for f in app/src/main/assets/layouts/*.json; do grep -c '"value": "shift"' $f; done`
or the Python scan script). In those 5, the top-left key carries the id `shift`, the
label `?123`, and the value `layer-base` — i.e. it leads to the **letter layer**
instead of back to `symbols`.

**Decision (binding): variant A — add the way back in `symbolsShift`.**
No rework of the symbol layers (variant B), no deliberate dead end (variant C:
violates KONZEPT "complete keyboard in 15 languages").

**Rule text for the coder (implementable without questions):**
1. In **every** asset, `symbolsShift` must contain exactly one key with
   `id:"shift"`, `kind:"action"`, `value:"shift"` and `label:"⇧"` — at the same
   position as in the 10 already-correct layouts (top-left corner, `width:1.5`).
2. Where this key currently carries `label:"?123"` / `value:"layer-base"` (the 5
   cases), **only** those two fields are changed (`label`→`⇧`, `value`→`shift`);
   `id`, `width`, position and all other keys remain unchanged.
3. An `ABC` key (`value:"layer-base"`, `label:"ABC"`) in `symbolsShift` stays
   **in addition** (a direct path to the letter layer, independent of the toggle
   state).
4. `symbols` keeps its ⇧ key with `value:"shift"`; **no** second ⇧/⇩ labeling is
   introduced (the meaning of ⇧/⇩ follows from the layer).
5. The layout test (`LayoutCompletenessTest.checkRequiredActionKeys`) must **close
   the gap**: for the layers `symbols` **and** `symbolsShift`, "exactly 1×
   `value == "shift"`" additionally applies (today the test accepts
   `layer-symbols` **or** `layer-base` and thus lets a dead end through).
   Counter-check: an asset without the ⇧ key in `symbolsShift` must turn **red**.

### R12.9 — Check the alarm situation BEFORE every delegation (`schedule_list` is mandatory)

**Finding (07:47 UTC, `schedule_list`):** `task-b0pdlx` ("check M7 final review",
every 300 s, one-shot, target `reports→c8`) was **6 h overdue** with
`next 01:46:09` and status **running**; `task-192pn2` (cron `*/5`, another project,
`reports→c2`) also with a deadline in the past. Neither was ever delivered.

**Mechanism (verified in the scheduler code, `pi-web-ui/dist/server/scheduler-tasks.js`):**
- `fire()` does `running.add(id)` → `await executor(task)` → `running.delete(id)` →
  only afterwards is `next` recomputed (`recordRun`).
- The executor calls `wakeConversation(...)` → `steerForPlugins` →
  `sendUserMessage(text, { deliverAs: "steer" })` and **waits** for it.
- If the target conversation's running turn never ends (hung stream / aborted
  session without `agent_end`), **the wake never resolves** ⇒ `running` stays set
  ⇒ the job is "running" forever, `next` stays in the past, no repetition, no
  delivery.
- **Sharpening of the thesis:** there is *no* global "delivery queue of this
  conversation". What is blocked is the **individual job** (its `running` flag).
  `tick()` keeps running as its own timer (`setInterval 10 s`); other jobs are
  thereby delayed by at most one tick, not starved. Two simultaneously hung jobs
  (different projects) mean **two** hung wakes, not one queue.

**Mandatory (before every delegation, 20 s):**
1. Call `schedule_list` and check for **overdue** jobs:
   `next` in the past **or** `running` for > 2 intervals **or**
   `one-shot` with `next` < now.
2. **Actively replace** every such job: `schedule_cancel(id)` and create a **new**
   verification alarm with a fresh deadline (≤ 6 min). Do not wait — a hung job does
   not heal itself (no timeout in the scheduler).
3. Only then delegate. An overdue job in `schedule_list` is an **abort signal**
   that a turn died in this conversation.

### R12.10 — Sequence AFTER every return from an abort (binding)

1. `schedule_list` → cancel overdue one-shots and create them **anew** (R12.9);
   only then is the follow-up check secured.
2. `subagent list` + `get_result(<runId>)` → status, last output (question pattern?
   R12.6).
3. File-system target vs. actual: `git status --porcelain`, `ls -l --time-style=+%H:%M <scope>`,
   expected symbols via `rg`.
4. **Self-verification** (`./gradlew … --tests …`) — "coder says green" does not count.
5. Only **then** `steer` (close the gap) or `stop` (re-cut the assignment).
6. Result + alarm status in `state/progress.md`.

**Rationale:** in case I12 the damage was not in the error message but in the time
window **01:48 → 07:46 = 5 h 58 min** without a single fired alarm. The user had to
intervene manually. Exactly this window is prevented by R12.1 (deadline ≤ 6 min) +
R12.9/R12.10 (actively replacing overdue jobs).

### R12.11 — The question clause was a contributing cause (assignment wording, binding)

**Assessment:** yes. The assignment text asked, in essence, "if unclear: ask
briefly". The coder did exactly that (gap: keys missing instead of a wrong value) —
and thereby **voluntarily ended** the run, without `agent_end` progress and without
a result. The wording is thus one of the three causes (besides the missing alarm
and the hung turn). An assignment must never be allowed to end with an open question.

**Replacement wording (verbatim into every coder brief, replaces any question clause):**

> "If anything is unclear, decide **yourself** according to the rules in
> `docs/LAYOUT_REQUIREMENTS.md`, `docs/COMMIT_RULE_DECISION_2.md` or
> `docs/SETUP_UI_DECISIONS.md`. Document every assumption you make in the final
> report as a line `ASSUMPTION: <what> — <why>`. If a gap breaks the assignment:
> finish the subset and write `BLOCKER: <what> — <why> — <proposal>` into the final
> report. **Do not ask a question** — a question ends the run without a result and
> counts as an abort."

Additionally: questions the orchestrator can foresee are answered in the brief
**before** sending (target state, prohibitions, paths, expectation list).

---

## 13. Hung delegations: prevention (U2, 2026-10-28)

**Trigger:** the user repeatedly sees "hung" subagents that "wait for a response to
their tool requests". Two documented cases: (case 1, see §12/I12) the coder asks a
question and ends the run → `steer` never gets a result → 5 h 58 min of standstill;
(case 2) the coder works through M8b-2 and **waits for a real tool approval**; only
the user's click lets the run continue (result: 232 tests, 0 failures, emoji panel +
L-02 fix).

> **Correction (U2-R, user note):** the earlier thesis "that is just an orphaned
> display, the work was finished" is **wrong** for case 2. There was a real,
> **blocking approval request** (confirmation for a tool call) that waits
> indefinitely until a human clicks. This state is **legal, no rule violation and
> no error** — the coder behaved correctly. "Asking is forbidden" applies only to
> **content/scope ambiguities**, not to tool approvals.

### R13.1 — Four states, one 10-second test (W1–W4)

Server-side there are exactly two "pending" maps: `pendingApprovals` (tool
approvals) and `pendingQuestions` (`ask_user_question`); the harness function
`isWaitingOnUser` checks both, and **only there is no timeout**. What matters is
**what** is pending: an approval (legal) or a content question (forbidden).

| # | State | 10-second detection | Legal? | Correct reaction |
|---|---|---|---|---|
| **W1** | **Waiting for tool approval** (confirmation for a tool call) | `conversation_read(action="status")`/session view names a **pending confirmation** (tool + command); output ends **mid tool call**; no new file write for minutes; the run is not `done` | **yes, permanent state** | **Do not `stop`, do not re-delegate.** (a) name the approval to the user **explicitly** (tool, exact command, why needed); (b) if the orchestrator may grant it itself, grant it immediately; (c) the alarm text (R12.1) always contains the addendum "**an approval may be pending — then do NOT stop, only report**" |
| **W2** | **Content question** from the coder ("let me ask briefly", `ask_user_question` about content/scope/design) | `conversation_read(status)` reports a pending **question** **or** the last text ends with "?" / "should I …"; no pending confirmation | **no** — created by us | one-sentence `steer` with the decision (R13.7) + "now run the COMPLETION PROTOCOL"; for `ask_user_question`, answer in the UI or `stop` + a new brief with the decision in the text |
| **W3** | Harness force abort / synthetic result (U1 case) | transcript contains the synthetic-result text; file mtime stuck for > 15 min; possibly `running` without activity | no (harness) | §2.6 decision tree: `get_result` → file target vs. actual → verify yourself → `steer`/`stop` |
| **W4** | Done, just not picked up (display shows "hung") | `get_result` delivers the complete output **immediately**, `git status` shows the expected files, **no** pending confirmation/question | yes | take over the result, `state/progress.md`, dismiss the session |

**Mnemonic:** "waiting for tool response" describes **two** different things —
W1 (a human must click, correct) and W3 (the harness lost the call, error). The
test is always the same triad: `get_result` (status + last text) →
`conversation_read(status)` (**confirmation** or **question**?) → `git status`/mtime.

### R13.2 — COMPLETION PROTOCOL (mandatory part of every coder brief, wording)

> **COMPLETION PROTOCOL (binding).**
> 1. The run **always** ends with a result message, even if incomplete. The last
>    lines have exactly this format:
>    `DONE: <what is finished, with file paths>` / `OPEN: <what is missing>` /
>    `ASSUMPTION: <assumption made> — <why>` (0..n) /
>    `BLOCKER: <what> — <why> — <proposed next step>` (0..n) /
>    `VERIFICATION: <exact command + result>`.
> 2. **Content/scope uncertainties are never phrased as a question.** A sentence
>    ending in "?", containing "let me ask briefly", "should I …", or "please
>    confirm" is a **rule violation** and invalidates the delivery.
> 3. **Exception — tool approvals (W1):** if a tool call needs a human
>    confirmation, that is **not** a rule violation and **not** a question. Then:
>    write one line `APPROVAL_NEEDED: <tool> — <exact command> — <why needed>` and
>    make the call; the run may wait until someone clicks. This line must **not**
>    be relabeled as `BLOCKER:` to circumvent the approval — otherwise the coder
>    blocks silently instead of cleanly requesting approval.
> 3. If time does not suffice: finish the subset, write `OPEN:` + `BLOCKER:`,
>    **no** asking, **no** standstill.

**Why R12.11 alone did not suffice:** R12.11 said "no questions" but defined no
**output format** and no exception for approvals. The coder had no legal way to get
rid of his *content* uncertainty — so he chose the only one he knew: asking. The
protocol gives him a permitted channel (`ASSUMPTION:`/`BLOCKER:`) that does **not**
end the run — and cleanly separates from it the **approval** (W1), which remains
allowed and expected.

### R13.3 — Decision basis + authority (makes blocking structurally unnecessary)

Every brief names (i) the **documents** by which decisions may be made, and (ii)
this authority clause **verbatim**:

> "You have **decision authority** over everything these documents govern:
> `docs/LAYOUT_REQUIREMENTS.md`, `docs/COMMIT_RULE_DECISION_2.md`,
> `docs/SETUP_UI_DECISIONS.md`, `docs/LANGUAGE_EMOJI_DECISIONS.md`,
> `docs/ASR_CONTRACT.md`. If the assignment contradicts one of these rules, the
> **documentation** prevails; you document the contradiction as `BLOCKER:` and
> continue per the documentation. Content questions are forbidden, deciding is
> mandatory. **Tool approvals are excluded**: if a command needs a confirmation,
> report it with `APPROVAL_NEEDED: …` and wait — that is correct."

The precedence is thereby unambiguous: **documentation > assignment text > own guess**.

### R13.4 — Time mark with forced-abort note

The brief contains an absolute mark: "If I reach minute **8** without complete
verification, I end with `DONE:/OPEN:/BLOCKER:` (subset) and **without** further
research." The orchestrator sets the alarm for it (R12.1, ≤ 6 min). A run that
exceeds the mark is ended **from outside** (§13.6), not hoped away from inside.

### R13.5 — Checklist BEFORE every delegation (60 seconds, 5 points)

1. **Goal-conflict check:** do two requirements exclude each other? (Negative
   example from case 1: "the test must require ⇧ in `symbolsShift`" **plus** "no
   structural layout changes" ⇒ unsatisfiable.) On conflict: do **not** send the
   assignment.
2. **Fact check against our own measurements:** check every "must" claim in the
   brief against the measured actual state (put numbers from `git`/scripts into
   the brief). If the brief asserts a fact I have not measured: measure it or mark
   it as `ASSUMPTION`.
3. **Scope consistency:** verify file paths with `rg --files | grep <name>`; the
   files in scope must be the files the required test touches.
4. **Decision basis:** name 1–3 documentation files (R13.3) + resolve all known
   ambiguities **in the brief**.
5. **Completion + alarm:** COMPLETION PROTOCOL (R13.2), time mark (R13.4), alarm
   (R12.1), and a fallback path ("if X does not work, do Y") are included.

### R13.6 — Operating rule for hung sessions (deadlines + stages)

**Detection (any one of three signals suffices):** (a) `conversation_read(status)`
reports a pending question; (b) the last output ends with a question and there has
been no new mtime/tool activity for **> 5 min**; (c) alarm overdue or `running` for
> 2 intervals.

**For W1 (tool approval), NO staged escalation with `stop` applies.** A `stop`
would destroy work that is close to delivery — exactly the M8b-2 case (after the
click: 232 tests green, emoji panel + L-02 fix). W1 is a legal permanent state.

| Stage | When (from delegation) | Action |
|---|---|---|
| 0 | immediately, always | `get_result` + `conversation_read(status)` (confirmation? question?) + `git status`/mtime — **never** blindly re-delegate |
| **W1** | **6 min** | **Only inform** (one line to the user: which tool, which command, why needed) and **keep waiting**; renew the alarm recurring (every 6–10 min), **never** `stop`, **never** re-delegate |
| W2 | **6 min** | **One** `steer` with exactly one sentence of decision + "no questions, now run the COMPLETION PROTOCOL" |
| W2 | **11 min** | Second `steer`: "No further research. Reply in 60 s with `DONE:/OPEN:/BLOCKER:`" |
| W2/W3 | **15 min** | `stop` → verify yourself (`./gradlew … --tests …`) → the gap as a **new, smaller** assignment (≤ 2 files) |
| — | **pending question** (`ask_user_question`) | **Immediately** (no deadline): answer in the UI **or** `stop` + new brief with the decision in the text — `steer` does not answer a pending question |

**Prevent double work:** before every new assignment, `git status --porcelain`
against the expected list; if there are already changes, phrase the assignment as
**"complete X"**, never as "implement X".

### R13.7 — What the orchestrator writes when unblocking (one-sentence answer, template)

> "Decision: <concrete rule/answer>. Continue accordingly, ask **no** question, and
> close with the COMPLETION PROTOCOL (`DONE:/OPEN:/BLOCKER:`)."

Example for case 1: "Decision: the ⇧ key in `symbolsShift` is **not** a structural
change — it is rule 4a; add it to the 8 assets via script and close with the
protocol."

### R13.8 — Is the problem avoidable? (honest assessment)

| Share | Assessment |
|---|---|
| **Harness mechanics** (A3 synthetic result, A4 wrong "hung" display, hung scheduler wake) | **Not** fixable by us. A4 is pure display and disappears only with `get_result`/dismiss; A3 remains a residual risk with the 20-min watchdog/hung streams. |
| **Assignment quality** (case 1: contradictory brief) | **Fully** within our influence — and the biggest lever. R13.5 prevents exactly this case. |
| **Role design** (the coder may ask) | **Fully** within our influence — R13.2/R13.3 remove both the necessity *and* the legality. |

**Realistic target:** **0 incidents is not achievable** — W1 (approval) is an
**intended** permanent state, W3 (watchdog) is harness mechanics, W4 display
semantics. Achievable and measurable: **detection ≤ 6 min** (alarm + R13.1 triad)
and **reporting ≤ 6 min** (for W1: approval named; for W2/W3: unblocking initiated)
⇒ **at most ~12 min of standstill** instead of 5 h 58 min. A promise of "never hung
again" would be wishful thinking; "never hung again **unnoticed**" is a checkable
operating rule.

### R13.9 — Preventing approvals (which classes occur here, how to avoid them)

**Empirical values of this project + built-in rule engine** (`approval-rules.js`,
default rules with `ask`): recursive deletion (`rm -rf`), destructive git
operations, `chmod`, writing to system directories, `.env`/SSH keys/shell profiles,
and **writing outside the workspace** (`outside_workspace`). Additionally there are
**client-side configured rules** (`<dataDir>/approval-rules.json`) — typically
`sudo …`, `adb …`, `./gradlew …`, network commands (`curl`, `wget`),
`pip/npm install` land there. Exactly those are the frequent approvals in this
project.

**Assignment wordings that avoid approvals:**

1. **Write commands out exactly and completely** (no `$(…)`, no `|`, no `&&`,
   no `xargs`, no here-docs) — the rule match sees only the command string;
   nested constructs are treated as "unknown" and land in `ask`.
2. **No paths outside the project** (`/tmp`, `$HOME`, `/sdcard`): scripts, test
   data, and artifacts belong under `tools/`, `testdata/`, `dist/`.
3. **No `sudo`** and no system packages in coder assignments; tool installation
   is a separate orchestrator step (once, announced).
4. **No emulator/adb and no full `./gradlew` runs in the coder brief** (already
   §2.1/R12.4) — only `./gradlew :app:testDebugUnitTest --tests "…"`, one call.
5. **Keep file writes inside the workspace**; delete/rename only via
   `git rm`/`mv` in the project, never `rm -rf`.

**What the user would have to approve ONCE** (so that these classes stop asking):
in the approval dialog choose "**allow same class**" or create one `allow` rule
per exact command prefix in `approval-rules.json` — e.g. `./gradlew ` (prefix),
`python3 tools/` (prefix), `adb shell ime ` (prefix). Afterwards only `sudo` and
destructive git remain approval-required — neither occurs in ordinary coder
assignments.

### R13.10 — Quick table "what do I see, what do I do"

| What I see | What I do | Forbidden |
|---|---|---|
| Session stuck, a **confirmation** for a tool pending (W1) | name the approval to the user **explicitly** (tool, command, purpose); grant it yourself if allowed; keep waiting | `stop`, re-delegate, relabel as `BLOCKER` |
| Session stuck, the last text is a **question** (W2) | one `steer` with one sentence of decision + "now the COMPLETION PROTOCOL" | a second question, re-delegation without `stop` |
| Session `running`, but synthetic result in the transcript (W3) | `get_result` → file target vs. actual → verify yourself → `steer`/`stop` | blindly re-delegate (double work) |
| Session "hung", `get_result` delivers the finished output **immediately** (W4) | take over the result, `state/progress.md`, dismiss the session | stare at "running", repeat the assignment |
