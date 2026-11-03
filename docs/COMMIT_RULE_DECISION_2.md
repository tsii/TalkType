# Decision E8: divergence handling, commit restraint, visible ordering

**Status:** 2026-10-27 · **Role:** rule authority (no code/test changes)
**Revision 2 (2026-10-27):** B1 (units, §4a), B3 (short sessions/final decode,
§4b) and B4 (`ownStart` shift, §2a) have been updated verbatim after the
**green device proof** (`connectedDebugAndroidTest` 6/6, `s1_realEngine_realWav`
PASSED, unit 204/0) — including tests T7–T9. The rules E8.1–E8.3 themselves are
unchanged.
**Partially supersedes:** `docs/COMMIT_RULE_DECISION.md` (§2/§4 there remain
valid: word boundary = `rfind(' ')` in the common prefix, no commit without a
separator; **new** are rollback, commit hysteresis, and the separation
"commit ≠ write").
**Basis:** `docs/KONZEPT.md` §2.6/§2.7 (live correction, committed
"untouchable"), `docs/ASR_CONTRACT.md` §4.1 (golden texts/token IDs),
`docs/DESIGN.md` §5.4/§6, `commit/{LocalAgreement,CommitPolicy}.kt`,
`editor/{DeltaTyper,EditorSink,OwnershipTracker}.kt`, `dictation/DictationController.kt`.

---

## 1. Measured damage and causal chain (evidenced)

Measurement (<device>, `connectedDebugAndroidTest`, `s1_realEngine_realWav`):

```
Target: So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.
Actual: So, jetzt bin ich gespannt, ob die Viktifunktion auf dem T.
```

The decoder is provably correct (identical token IDs, 27 tokens, `incomplete=false`,
golden tests green). The chain in the commit/revision part:

1. An early hypothesis contains `… ob die Vikti` (error detection).
2. Two hypotheses agree on it → `LocalAgreement` releases the prefix,
   `CommitPolicy` reports the length, `DeltaTyper.commitUpTo()` freezes `… Vikti`.
3. All later (correct) hypotheses diverge at position "V|D".
4. `DeltaTyper.revise()` checks **before** the ownership check:
   `if (!target.regionMatches(0, currentText, 0, committedLength)) return null`
   (`DeltaTyper.kt:63-71`) → **every** subsequent hypothesis is discarded.
5. `writeHypothesis()` (`DictationController.kt:189-199`) only writes if `revise()`
   yields a delta → **text is never written again**. What remains visible is the
   state at the time of the faulty commit ("… auf dem T." is the last tail that
   was written before the commit).

Three structural defects, not a one-off:

| Defect | Location | Effect |
|---|---|---|
| **D1** divergence ⇒ total refusal | `DeltaTyper.revise()` lines 63-71 | a single early frozen word blocks **the rest of the dictation** |
| **D2** `committedLength` is monotonic | `DeltaTyper.commitUpTo()` line 45 (`coerceIn(committedLength, …)`) | a faulty commit is **not correctable** by construction |
| **D3** commit gates the writing | `DictationController.writeHypothesis()` | "commit" and "write text" are conflated; a commit error becomes a write error |

The user thus sees exactly the opposite of KONZEPT §2.7 (live correction at the
cursor position): the wrong text stays, the correct one never arrives.

---

## 2. E8.1 — Divergence handling (replaces "discard everything on deviation")

### Terms (binding)

- **Own area** = the text that *this session* has written:
  `own = typer.currentText`, `ownStart = cursorPos − own.length`.
  Everything **before** `ownStart` is foreign (user text/other app) and is
  **never** touched.
- **Ownership proven** = `OwnershipTracker.isOwned(own, textBeforeCursor)` **and**
  `sink.ownLength()` matches **and** `!ownershipLost` (unchanged from DESIGN §6).
- **Divergence** = `commonPrefixLen(own, target) < typer.committedLength`.

### Rule (if/then, in this order — the order is binding)

```
W0  BEFORE every decision: re-derive `ownStart` (not from a stored
      constant): `ownStart = cursorPos − own.length` from the current
      selection/`textBeforeCursor`; `own` must be the SUFFIX before the cursor
      (`OwnershipTracker.isOwned`). If that fails → W1.

W1  If ownership NOT proven
      → write NOTHING (no deleting, no appending), mark the session as
        `divergent`, status row "Editor changed – dictation stopped", done.
        (Foreign text/overwriting: never touch it.)

W2  If no divergence (common >= committedLength)
      → as before: write the minimal delta in the tail; committedLength may grow.

W3  If divergence (common < committedLength) AND ownership proven
      → THIS IS OUR OWN EARLIER MISTAKE (case c). Rollback is ALLOWED:
      a) `committedLength := common`   (shrinking explicitly allowed, lifts D2)
      b) `toRemove = own.length − common` (own area only, never < ownStart)
         — `toRemove` is a **UTF-16 length** (string indices), see §4a.
      c) if `codePointCount(common, own.length) <= 60` → delete `toRemove`
         characters, type `target[common:]` in ONE revision (one delta), cursor
         stays at the end.
      d) if `codePointCount(common, own.length) > 60` → **no** live rollback:
         `divergent := true`, leave the text standing, status row "correction
         too large – will be corrected on completion". The final decode
         (committed_len = 0) corrects the entire own span.

W4  If divergence AND ownership proven AND the divergence lay before `ownStart`
      → cannot occur (common is by definition ≤ own.length relative to the own
        area); if it does anyway (inconsistent state) → like W1: write nothing.
```

**"Deliberately leaving text standing" is only allowed in W1 and W3d** — never in
W3c. Rationale from the measured damage: in W3c, correcting costs **one**
revision (≤ 60 characters, one backspace block, one rewrite) and yields the
golden text; leaving it standing costs **the rest of the dictation** (measured:
27 tokens target → 12 tokens actual, 41 characters lost) and is not
recognizable to the user as an error.

**Invariant (new, binding):** *foreign text is never deleted; own text may be
corrected at any time within the 60-character limit.* This sharpens review rule
1 ("no foreign text deleted"), it does not weaken it: `ownStart` is the hard
boundary, `ownershipVerified()` is the gate.

**Additional invariant (fixes D3):** commit decisions must **not** block writing
the tail. `writeHypothesis()` writes the tail **always** when W2/W3c applies;
`commitUpTo` is exclusively a marking for the protected area, not a write gate.

### 2a. `ownStart` shift / foreign text before our area (B4)

`ownStart` is **not a stored position** but is re-derived before every write
from the cursor position and our own text length (`ownStart = cursorPos −
own.length`, W0). The following is binding:

1. **Foreign text BEFORE our area** (user types to the left of it) is allowed.
   `isOwned()` only checks whether `own` is the **suffix** before the cursor —
   the number of inserted foreign characters automatically shifts `ownStart` to
   the right; `own` remains valid unchanged. A rollback then deletes relative
   to the cursor (`own.length − common` characters before it) and can by
   construction hit **no** foreign text.
2. **Change INSIDE our area** or cursor movement/selection: `onUpdateSelection`
   without an expected own callback ⇒ `lost = true` ⇒ **W1** (write nothing).
3. **Additional pre-check before W3c:** `sink.ownLength() == own.length`
   (length comparison, **not** an absolute position) **and** `common`/`own`/
   `committedLength` are indices into `own`, never into `actual`. If `ownLength`
   is reported inconsistently or our own text is no longer a suffix ⇒ W1.
4. **Forbidden:** using an absolute `ownStart` number remembered at session start
   for deleting, or going past `ownStart` with `deleteSurroundingText` — both
   would delete foreign characters when foreign text precedes ours.

---

## 3. E8.2 — Commit restraint (numbers)

A prefix becomes `committed` **only** if **all** conditions hold:

| # | Condition | Value | Purpose |
|---|---|---|---|
| C1 | Agreements for **commit** (not for display) | **N = 3** consecutive hypotheses | kills "2× identically wrong" — exactly the measured case (`Vikti` had 2 agreements) |
| C2 | Minimum distance from the stable edge | **M = 2 words** remain unprotected (the last two words of the stable prefix are **not** committed) | a word at the growth edge is never "firm" |
| C3 | Minimum size | only from **≥ 3 words** in the stable prefix (otherwise commit nothing) | with 1–2 words there is nothing safe to protect |
| C4 | Word boundary | still: only up to `rfind(' ')` in the common prefix (COMMIT_RULE_DECISION §3) | never mid-word |
| C5 | Budget | `budgetExceeded` ⇒ no commit progress (existing rule 4) | a truncated hypothesis is no proof |
| C6 | Divergence case | after W3a the count restarts (3 new agreements from `common`) | no immediate re-commit of the same mistake |

Configuration values: `commit_agreements = 3`, `commit_holdback_words = 2`,
`commit_min_words = 3` (defaults; `min_audio_s = 2.0` remains unchanged).

**Consequence for perceived latency:** none. The **text** still appears from
`min_audio_s` (2.0 s) as a partial and is continuously corrected; C1/C2 only
delay when a prefix counts as "firm". Arithmetic at `asr_interval_s = 1.0`: a
word is confirmed after 3 hypotheses ≈ **+2 s** and then lies ≥ 2 words behind
the edge ⇒ "firm" typically **3–4 s** after speaking (previously: ~2 s).
Visible character latency: unchanged. Price: the protected area is smaller —
which is exactly the intent, because the protection is the cause of the
measured failure.

---

## 4. E8.3 — What the user sees (visible ordering + limits)

1. **Partial (tail):** rewritten/corrected on every hypothesis, without
   confirmation. Deletion scope per revision ≤ **60** code points (existing limit).
2. **Committed:** visibly counts as firm and does not flicker — **except** in
   case W3c (own mistake, ownership proven).
3. **Rollback limits (W3c), all simultaneously:** ≤ **60** code points **and**
   ≤ **2** committed words **and** never across a sentence terminator (`. ! ? … :`)
   **and** never across a line break **and** never before `ownStart`.
   If any limit is violated ⇒ W3d (leave standing, final decode corrects).
4. **No cursor jump:** after W3c the cursor stands at the end of the newly
   written own text — the same position as after any ordinary delta. There is
   **no** cursor movement into foreign text, no selection, no `setText`, no
   `setComposingText` (DESIGN §6 unchanged).
5. **Visible scope of the rollback:** at most the last two words are visibly
   retyped (deleting + retyping in one batch, `beginBatchEdit`/`endBatchEdit`),
   typically "Vikti" → "Dikti" — a flicker of ≤ 5 characters, no cursor jump,
   no text loss before it.
6. **After the rollback:** `committedLength = common`; the hysteresis (C1–C3)
   restarts; until then everything further remains partial (visible, correctable).
7. **Display rule:** the user **never** sees a state in which written text
   disappears again without new text appearing at the same place (one delta,
   atomic in the batch).

### 4a. Units rule (B1 — binding, non-negotiable)

Two different units are in play and must **not** be mixed:

| What | Unit | Evidence in code |
|---|---|---|
| **Limit check** of the rollback (≤ 60) | **Code points** (`String.codePointCount`) | `DeltaTyper.kt:87` `toRemoveCodepoints = currentText.codePointCount(common, currentText.length)` |
| **Deletion length at the editor** | **UTF-16 code units** (`String.length`, `TextDelta.replaceLength`) | `EditorSink.replaceBeforeCursor(replaceLength, …)`; `deleteSurroundingText` counts UTF-16 (DESIGN §6) |

**Rule:** the limit ("roll back at most 60 characters") is a **code-point**
limit — that is the number the user sees as characters. The delete operation
itself uses the **UTF-16 length** `own.length − common`. Both values are
correct; swapping them produces either wrong refusals (UTF-16 check with
emoji) or text remnants (delete counted in code points).

**Warning (indices):** `committedLength`, `common`, `own` and `ownStart` are
**always** string indices (UTF-16) — they are used against
`currentText.length`, `regionMatches` and `TextDelta.replaceLength`. **Only**
the limit check is computed in code points. Converting the indices to
code-point positions is forbidden (it would shift `regionMatches`/substring
boundaries).

Examples:

| Case | Code points | UTF-16 units | Applies |
|---|---|---|---|
| (a) `ä` (U+00E4, precomposed) | 1 | 1 | limit and delete identical |
| (b) `😀` (U+1F600) | 1 | **2** | 1 character counts against the limit, the delete removes 2 units |
| (c) 60 mixed code points: 15 × `😀` + 45 × BMP characters | 60 | **75** | limit **held** (60 ≤ 60), the delete removes **75** UTF-16 units — correct as is |
| (d) counter-check to (c) with UTF-16 check | — | 75 > 60 | **wrong**: the rollback would be refused (W3d) although the user sees only 60 characters |

### 4b. Short sessions and "commit refused" (B3)

With C1 (N = 3) and C2 (M = 2 words), `committedLength = 0` at the end of a
short dictation is the **normal case**, not an error state. The following is
binding:

- **The final decode writes the complete text and needs no commit.** On
  release, the protection is set to 0 (`committed_len = 0`, KONZEPT §2.7 rule 3)
  and the **entire own span** is rewritten — "commit ≠ write" (D3) applies here
  too: a missing commit never prevents writing.
- **There is no state "text forgotten".** Every written text is either
  (a) committed, (b) a partial in our own revisable area, or (c) replaced by
  the final decode. A `committedLength = 0` only says "nothing is protected",
  not "nothing is written".

| Case | Expected result |
|---|---|
| **(i)** 1 word ("Danke."), never committed (C3: < 3 words) | partial stands visibly in the field; final decode writes "Danke." completely (delta = common prefix of `own` and final ⇒ minimal, no duplicate) |
| **(ii)** 2 words, `committedLength = 0` | identical to (i): the final decode replaces the own area completely, no data loss, no double text, no retraction of foreign text |
| **(iii)** abort (UP) **mid-partial** | the last written partial **remains standing** (delete nothing on release); the final decode replaces it with the final result (full own span, `committed_len = 0`). If ownership is **not** proven: no writing at all, text remains as it is, status row "Editor changed – dictation stopped" (no loss, possibly incomplete) |

---

## 5. Test cases (binding, as unit tests without an emulator)

| # | Input (hypothesis sequence, 1 s each) | Expectation |
|---|---|---|
| T1 | `… ob die Vikti` ×2, then `… ob die Dikti`, `… ob die Dikti-Funktion`, … up to golden | **final text = golden** (§4.1). No `null` stall: every hypothesis yields a delta; rollback exactly once, `toRemove ≤ 60` |
| T2 | like T1, but the user types between the hypotheses (ownership lost) | no more writing (W1); text already written unchanged; status row "Editor changed" |
| T3 | divergence with `own.length − common = 75` | **no** rollback (W3d), text stays, `divergent=true`, status row "correction too large"; final decode corrects |
| T4 | 2 agreements on a wrong word, 3rd hypothesis correct | `committedLength` stays 0 or behind the faulty word (C1): the faulty word is **never** frozen |
| T5 | stable edge with 3 words | only the 1st word is committed (C2/C3), the last 2 are partials |
| T6 | rollback across a sentence period (`… Teil.`) | no rollback across `.` (W3d limit), text stays, hint |
| T7 | **Units (B1):** `own` = 15 × `😀` + 45 BMP characters, divergence at the start | limit check in code points (60 ≤ 60) ⇒ rollback **allowed**; `TextDelta.replaceLength == 75` (UTF-16); text afterwards exactly = target |
| T8 | **Short session (B3):** only 1 hypothesis "Danke.", then final | `committedLength == 0` before the final; final decode writes "Danke." exactly once (no duplicate, no remainder) |
| T9 | **Foreign text before (B4):** user types "Hallo " to the left before our area, then divergence | ownership stays valid (suffix check), `ownStart` shifts; rollback deletes only own characters, "Hallo " remains unchanged |

Regression: T1 is **exactly the reported device case** — it must be reproducible
in a unit test (DeltaTyper + CommitPolicy, without an emulator), so that this
failure never again surfaces only on the device.

---

## 6. Effects on existing documents/tests

- `docs/COMMIT_RULE_DECISION.md`: §2 (word boundary) and §3 (pseudocode) remain
  valid; the statement there "committedLength is monotonic" is **repealed**
  (W3a) and the section "consequences → stall risk" is replaced by E8.1.
- `LocalAgreementTest`: agreement count for commit 3 (display tests remain at
  2); expectations from COMMIT_RULE_DECISION §4 remain (word boundary), new
  tests T4/T5.
- `DeltaTyperTest`: new tests T1/T3/T6 (rollback, budget, sentence-period
  limit); `commitUpTo` must allow shrinking (D2) — the test "committed stays
  monotonic" is dropped or becomes "shrinkable only within W3a".
- `docs/KONZEPT.md` §2.6 ("committed text is never taken back") is thereby
  **deliberately sharpened**: what is taken back is exclusively *own*,
  ownership-confirmed text within the 60-character/2-word frame; foreign text
  remains untouchable. A sharpening paragraph is to be added to KONZEPT.md
  (file locked — text here: "Sharpening Rev 4: The committed prefix is
  untouchable against *foreign* changes and against revisions without proof
  of ownership. It is **not** untouchable against our own, provably wrong
  text: if the deviation lies in the own area, we go back to the common prefix
  (≤ 60 code points, ≤ 2 words, never across punctuation) and rewrite. That is
  live correction at the cursor position, not data loss.")
