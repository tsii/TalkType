# Decision: commit boundary with LocalAgreement

**Status:** 2026-10-27 · **Role:** analyst (design decision, no implementation)
**Status:** binding for the coder; **no code changed**, `docs/KONZEPT.md` unchanged.

## 0. Sources read

- `docs/KONZEPT.md` §2.6 (LocalAgreement, commit **only up to the last word
  boundary**, pseudocode `cut = common_str.rfind(" ")`), §2.7 (delta output,
  committed untouchable).
- `docs/DESIGN.md` §5.4 ("never commit a partially typed word", partials as a
  revisable tail), §6 (DeltaTyper, ownership, 60-character limit), §9 (failure
  modes: "duplicate words / sentence-part loss at window boundaries", "cursor
  shifted").
- `app/src/main/java/de/voicekeyboard/commit/LocalAgreement.kt`
  (`stablePrefix()`, lines 71–92), `CommitPolicy.kt`, `editor/DeltaTyper.kt`
  (`revise()`, lines 60–100), `app/src/test/.../commit/LocalAgreementTest.kt`.

## 1. Finding in the current code (the actual problem)

`stablePrefix()` releases the full common prefix as soon as **any** hypothesis
ends there:

```kotlin
// LocalAgreement.kt:83-87
val endsAtWordBoundary = (0 until count).any { i ->
    t.length == len || t[len] == ' '
}
if (endsAtWordBoundary) return oldest.substring(0, len)
```

The term `t.length == len` is **always** true in the normal case: `len` is the
common prefix and therefore ≤ every hypothesis length; as soon as the shorter
hypothesis is a prefix of the longer one (the typical streaming case "old
hypothesis + grown text"), `t.length == len` holds. Consequence: the word
boundary rule is effectively a **dead rule** — what gets committed is almost
always the **whole** common prefix including the last word. That is exactly what
the KDoc does **not** describe ("if it ends in the middle of a word … only up to
the last space"); code and comment contradict each other.

The unit tests codify this reading: `wordBoundaryDoesNotCommitPartialWord`
expects the value `"Das ist der"` for `("Das ist der", "Das ist derzw")` — i.e.
the release of the partially typed word `der`, which the test name excludes.

## 2. (a) Decision

**The KONZEPT reading applies (strict): what is committed is exclusively the
common prefix up to and including the last space; the last word always remains
revisable. The `endsAtWordBoundary` release in `LocalAgreement.kt` is removed
without replacement.**

Rationale (5–10 sentences):

1. The text is a prefix of **continuously growing** audio. The last token is
   therefore always at the *audio edge* — and at that edge, "word finished" is
   not provable, only "output by the decoder that way".
2. "Looks like a complete word" is not proof: audio cut off at a word end
   regularly yields a **valid short word** ("der", "ist", "hat") for a long one
   ("derzeitig", "ist's", "hatte"). The difference between "whole word" and
   "half word" at the audio edge is therefore **undecidable in principle** —
   decidable is only the existence of a separator.
3. A separator (`' '`), in contrast, is real evidence: the space **after** a word
   in the common prefix shows that the audio has audibly finished that word.
   Only this evidence justifies a boundary — not the length of the hypothesis
   and not the appearance of the last token. A separator that lies only
   *beyond* the common prefix (only in the longer hypothesis) is deliberately
   not used (rationale §3).
4. If an unconfirmed word is frozen, a later correction can lie **within** the
   protected prefix. `DeltaTyper.revise()` rejects that
   (`!target.regionMatches(0, currentText, 0, committedLength) → null`), and
   because `committedLength` never shrinks, **every** subsequent hypothesis then
   fails at the same place: the output stream stalls until the final decode
   (DESIGN §9 "sentence-part loss at the window boundary"). That is not a
   cosmetic flaw but a session stall.
5. The price of the strict reading is **no** UX loss: the last word still
   appears immediately as a revisable partial in the target editor (DESIGN §5.4
   explicitly allows that). Only what may no longer change gets protected.
6. Nothing is lost at the end of the dictation: the final decode sets the
   protection to zero (KONZEPT §2.7 rule 3) and corrects the entire own span.
7. The rule thereby becomes **simpler** (one exit condition instead of three)
   and its invariant is checkable: *the committed prefix always ends on `' '`.*

### Answers to the three sub-questions

**(1) Hypothesis ends with a complete word — is that a half word?**
Lexically no, functionally yes: it is an **unconfirmed** word at the audio edge.
"Word boundary" in the KONZEPT sense does not mean "the last token is a
dictionary word" but "there is an evidenced separator". Without a separator the
word is **not** committed — it remains visible and correctable as a tail.

**(2) Hypothesis ends in the middle of a word ("Das ist derz")?**
Unambiguous: commit only up to the last space (`"Das ist "`). There is no dispute
here — both readings agree; the current code violates this intent precisely
when the shorter hypothesis is a prefix of the longer one (`"Das ist der"` +
`"Das ist derz"` → it wrongly commits `"Das ist der"`).

**(3) Flicker/frozen fragments?** Two cases, cleanly separated:

- **Extension is harmless:** `"Das ist der"` commits, afterwards `"Das ist derm"`
  → `regionMatches` fits, the delta just appends `"m"`. No flicker, no problem.
- **Replacement is the killer:** `"Das ist der"` commits, afterwards
  `"Das ist die …"` (gender/case/number only becomes clear with the next word:
  "der Preis" vs. "die Preise") → `regionMatches` fails → revision discarded →
  **all** subsequent hypotheses fail → standstill until the final decode.
  Exactly this case is excluded by the strict rule because the last word never
  lies in the protected area.

**Minimal rule that satisfies KONZEPT fidelity *and* flicker freedom:** cut at
the last space of the common prefix, never at the hypothesis end; monotonic
commit length; no exceptions (no separator → no commit).

## 3. (b) Binding rule formulation for the coder

```kotlin
// applies to the common prefix of the last `commitAgreements` hypotheses
val len = commonPrefixLen(hyps)                 // grapheme-safe, as existing
if (len <= currentCommitted) return currentCommitted
val cut = hyps[0].lastIndexOf(' ', len - 1)     // last EVIDENCED separator
if (cut < 0) return currentCommitted            // no space → commit nothing
return maxOf(currentCommitted, cut + 1)         // includes the space, never backwards
```

**Invariants (checkable, to be pinned in tests):**

1. `committedLength > 0 ⇒ currentText[committedLength - 1] == ' '` — the
   protected prefix always ends on a space, never inside a word.
2. `committedLength` is monotonically non-decreasing (`CommitPolicy` already
   clamps).
3. No space in the common prefix ⇒ **no** commit (`null`/unchanged).
4. Agreement count (2), "same audio state does not count twice" and the budget
   rule (`budgetExceeded ⇒ no progress`) remain unchanged.
5. The cut lies on an ASCII space ⇒ no grapheme/surrogate boundary can be
   violated.

**Code to be removed:** `LocalAgreement.kt:83-87` (the `endsAtWordBoundary`
block incl. `any`, `t.length == len` and `t[len] == ' '`) plus the KDoc paragraph
`LocalAgreement.kt:75-82` describing the release at the word boundary.

**Deliberately *not* adopted (variant B):** an exception for separator evidence
*outside* the common prefix (`∃h: h[len] == ' ' ⇒ commit len`). It would freeze
the last word token one step earlier — exactly the class that (3) describes:
if e.g. "der" only becomes clear as "die" with the following word
(gender/case), the session stands. In addition, the invariant "committed prefix
ends on `' '`" would not be preserved (the separator would lie beyond the
prefix). The latency gain is invisible because the partial is in the editor
immediately anyway — KONZEPT fidelity and a hard invariant take precedence over
micro-optimization.

## 4. (c) Test cases

| # | Hypothesis 1 (older audio state) | Hypothesis 2 (newer) | Expected commit | Rationale |
|---|---|---|---|---|
| T1 | `Das ist der` | `Das ist der` | **`Das ist `** (8) | audio edge, no evidenced separator → last word stays revisable (KONZEPT) |
| T2 | `Das ist der` | `Das ist derz` | **`Das ist `** (8) | mid-word; today wrongly `Das ist der` (bug §1) |
| T3 | `Das ist der` | `Das ist die` | **`Das ist `** (8) | divergence in the last word; must never be frozen (otherwise stall per §2.3) |
| T4 | `Das ist der` | `Das ist der zweite` | **`Das ist `** (8) | strict reading: commit the word `der` only once **it itself** is followed by a separator, i.e. at `Das ist der zweite` → next step `Das ist der zweite Teil` ⇒ `Das ist der ` (12) |
| T5 | `Hallo` | `HalloWelt` | **no commit** (`null`) | no space in the common prefix; the final decode covers the single word |
| T6 | `Eins zwei` | `Eins zwei drei` | **`Eins `** (5) | the separator after `zwei` lies **outside** the common prefix and is deliberately not used; `zwei` remains a (harmless) tail one step longer. With `Eins zwei drei` + `Eins zwei drei vier` → `Eins zwei ` |
| T7 | `Schöne 🤖 Tastatur` | `Schöne 🤖 Tastaturen` | **`Schöne 🤖 `** | cut at the space before the growing word; surrogate pair stays intact |

**Existing tests that pin the old reading and must be adapted**
(`app/src/test/java/de/voicekeyboard/commit/LocalAgreementTest.kt`):

- `wordBoundaryDoesNotCommitPartialWord`: `"Das ist der"` + `"Das ist derzw"` →
  new: **`"Das ist "`** (the test name finally matches the expectation).
- `sameAudioStateDoesNotCountTwice`: `"Hallo"` + `"HalloWelt"` → new: **`null`**.
- `unicodeSurrogatePairStaysIntact`: `"Schöne 🤖 Tastatur"` + `"… Tastaturen"`
  → new: **`"Schöne 🤖 "`** (the surrogate-pair assertion remains sensible: the
  cut must not lie inside `🤖`).
- `twoAgreeingHypothesesReleasePrefix`: `"So, da bin ich"` + `"So, da bin ich mal"`
  → new: **`"So, da bin "`** (the separator after `ich` lies outside the prefix).
- `resetRequiresTwoAgain`: `"Eins zwei"` + `"Eins zwei drei"` → new: **`"Eins "`**.
- `singleHypothesisYieldsNothing` and `contradictoryPrefixYieldsNothing` remain
  green unchanged.

**Consequence for UX (deliberately accepted):** the committed area lags the
visible text by **one** token. That is not a latency problem — the token stands
in the editor as a partial (DESIGN §5.4) — but the price for never having an
unconfirmed word in the protected area.

## 5. (d) Does `docs/KONZEPT.md` need a sharpening?

**No deviation** — the decision implements KONZEPT verbatim (`rfind(" ")`).
**But yes, a sharpening is sensible**, because (i) the coder read the rule
differently on the first pass, (ii) "word boundary" is misleading ("word looks
complete" ≠ evidenced boundary), (iii) the special case "common prefix itself
ends on a space" is only implicit in the pseudocode, and (iv) the connection
with the untouchability of the commit should be explained.

**Proposed text (to be inserted into `docs/KONZEPT.md` §2.6, directly after the
pseudocode — the file is locked, therefore deposited here):**

> **Sharpening of the word boundary (Rev 3).** "Only up to the last word
> boundary" means: what is committed is up to **and including the last space**
> in the common prefix; the word after it always remains revisable — even if it
> looks like a complete word. Reason: the text is a prefix of growing audio, the
> last word therefore lies at the audio edge, and there "word finished" is not
> provable (cut-off audio often yields a valid short word, e.g. "der" for
> "derzeitig"). A space, in contrast, **is** the proof: it shows that the audio
> has audibly finished the word. Three rules follow: (1) No space in the common
> prefix ⇒ **no** commit. (2) Cutting happens only at a space, never at the
> hypothesis end and never mid-word. (3) The committed prefix therefore always
> ends on a space and is never changed again; a word corrected later can
> therefore never lie in the protected area (otherwise every further revision
> would fail at `regionMatches` and the output stream would stand still until
> the final decode). Until then, the last word appears as a revisable partial;
> at the end of the dictation, the final decode corrects everything
> (`committed_len = 0`).

Until this paragraph is adopted, this file serves as the interpretive basis.

## 6. Residual risk and recommendations

- **Residual risk (accepted, covered by KONZEPT):** a hypothesis may later
  decode an already committed word differently (e.g. "ist" → "ist's"). Then
  `DeltaTyper` blocks the revision. The frequency is small (the separator was
  evidenced), but the consequence is harsh. **Recommendation:** do not swallow
  this case silently — log it once and visibly pause the output stream until
  the final decode (DESIGN §9, "no write attempts without ownership/match"),
  instead of failing again on every hypothesis.
- **Test-gate recommendation:** invariant 1 ("committed prefix ends on `' '`",
  or length 0) as a property test over a hypothesis sequence, so that the rule
  is not "optimized away" again.
- **Out of scope:** languages without space separation (CJK/Thai) — the 15
  launch layouts are all space-separated; the rule uses U+0020 as separator.
