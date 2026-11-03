# E11 – SPACE labeling and accessibility for v0.1.x

**Status:** ready for decision, no implementation. The only new file is this
document. No code/asset changes, no Gradle, no adb; parallel work on
KeyboardView/LayoutRepository remains unaffected.
**Basis:** the handed-over asset/device measurements and the limited personal
sideload approval from `FINAL_REVIEW_M8.md`.

## A – Binding SPACE values in canonical order

**The word label from the layout wins.** The eight given translations stay;
the remaining seven are fixed as follows.

| Layout ID | Canonical word label | Reason – one sentence each |
|---|---|---|
| de-de | **Leer** | Given and sufficiently short compared to "Leertaste". |
| en-us | **Space** | The usual short English label instead of the incorrect German label. |
| fr-fr | **Espace** | The already correct given asset label stays. |
| es-es | **Espacio** | The already correct given asset label stays. |
| it-it | **Spazio** | The already correct given asset label stays. |
| pt-pt | **Espaço** | The already correct given asset label including ç stays. |
| nl-nl | **Spatie** | The already correct given asset label stays. |
| pl-pl | **Spacja** | The short Polish name for the space character without the longer keyboard word. |
| tr-tr | **Boşluk** | The short Turkish name with the correct ş instead of a German foreign label. |
| ru-ru | **Пробел** | The usual compact Russian name, shorter than a description of the whole key. |
| cs-cz | **Mezera** | The short Czech name for the space character instead of "Leer". |
| sv-se | **Mellanslag** | The usual Swedish name without an invented word abbreviation. |
| da-dk | **Mellemrum** | The explicitly given correct Danish asset label is not renamed. |
| fi-fi | **Väli** | Deliberately short Finnish label instead of the longer "Välilyönti". |
| el-gr | **Διάστημα** | The given Greek word including the accent stays. |

### Space rule – also for a key actually only one weight unit wide

**A word must not be silently truncated, given an ellipsis, or freely
abbreviated by the coder.** The only approved compact label, for **all 15
languages, is `␣`** (U+2423). On a SPACE key only one weight unit wide this
compact form is binding; on wider keys the canonical word applies provided
it fits fully legibly, otherwise likewise `␣`. No arbitrary shrinking of the
type down to illegibility. In particular, RU is **Пробел**, not an invented
shortening; under space pressure `␣`.

This separates translation from space representation: the word values stay
in the asset, the compact form replaces exclusively the visible label, never
the SPACE action value or the inserted text. No new layout widths are
demanded.

**ASSUMPTION:** "1-wide" really means only a normal key weight unit, not
today's wider space bar. The eight fixed words, especially "Mellemrum" and
"Διάστημα", cannot be credibly promised to fit on such a narrow key without
a concrete font/width measurement; the binding compact form resolves this
conflict of goals instead of secretly mutilating correct translations. The
glyph rendering of `␣` must be evidenced before use.

## B – symbols and symbolsShift: word label, not blank

**All four layers of the same language receive the same canonical word label
from A; `" "` and empty labels are not intended design.** The space rule from
A applies identically on every layer; at the same width the same label
results.

Reasoning: SPACE keeps its meaning when the layer switches, and a labeled
action target is easier to find than a nameless surface. Against "the space
key must be called the same everywhere": **the same action and consistency
within a language yes, German "Leer" across all languages no**; moreover,
with real width differences full legibility takes precedence over literal
uniformity. A deliberately blank symbol label is explicitly rejected.

The just-implemented fallback `R.string.key_space_label = "Space"` remains a
**defensive stopgap for broken/empty labels**, not a translation policy and
not a substitute for the asset correction. In the cleaned-up 15 assets it
must no longer be needed on any layer in normal operation; the SPACE output
stays exactly one space character.

## C – Accessibility in v1: **c3, deliberately deferred to R8**

For the personally tested v0.1.x the translation of the five accessibility
names stays deliberately **open in R8**; neither the German hardcodes nor a
blanket English replacement are an accessible acceptance. A 15-language set
is only commissioned together with a clarified announcement language and a
real TalkBack check, instead of issuing 75 unchecked translations now as
completed accessibility.

**Consequence:** no new `values-*/strings.xml` for these five names now and
no new English universal label. The visible SPACE word and the screen
reader name are separate problems; in particular `␣` does not mean that a
screen reader should announce only the symbol. "Löschen", "Eingabetaste",
"Leertaste", "Umschalt", and "Feststell shift" remain known German
announcements and an honest i18n/a11y deficiency. This does not block the
already limited personal sideload testing, but it does block a claim
"accessibility checked in all 15 languages".

## D – Measurement method and exactly expected evidence

No new device check in this task; the following evidence applies to the
later coder/orchestrator verification. **uiautomator is not evidence of the
IME labels**, because the IME content in question is not visible in it.

| Check | Expected visibility/function evidence |
|---|---|
| Canonical values | Screenshot of the SPACE key for each activated layout in base, with a clearly assigned layout ID; each word matches A, in particular EN Space, PL Spacja, TR Boşluk, RU Пробел, CS Mezera, SV Mellanslag, and FI Väli. |
| Longest/other scripts | DA Mellemrum, EL Διάστημα, and RU Пробел on screenshots fully inside their key, without painting over the neighbor key or cut-off letters. |
| Layer rule | Screenshots of base, symbols, and symbolsShift for DA and FI each show the same word at the same width, no blank label and no silent switch to the English Space fallback. |
| Real 1-wide key / space pressure | If such a configuration is checked: the screenshot shows only the fully legible `␣` inside the key; a legible glyph is not missing and is not replaced by a box. |
| Function unchanged | Tap SPACE: a screenshot of the field shows `a b` instead of `ab` at a controlled insertion point; an accompanying `VK-HIT` log assigns the tap to the SPACE action, not to the emoji/language switch. |
| Accessibility | No title evidence is demanded for this c3 scope; a later `VK-HIT` log value can prove the configured announcement, but does not replace a real TalkBack listening/operation check. |

The label evidence is not settled by a device check of the text field alone:
an inserted space proves the action, not the visibly correct language.
Conversely, a screenshot showing "Пробел" does not prove the actually
configured announcement.

## Conclusion

**DONE:** 15 word values, the only allowed compact form, the uniform layer
rule, and the c3 scope fixed.
**OPEN:** implementation/asset cleanup by the coder, visibility evidence, and
the accessibility remainder R8.
**ASSUMPTION:** in the handover "seven incorrect assets" lists six IDs; fi-fi
is the seventh language still to be fixed and is explicitly covered by A.
**APPROVAL_NEEDED:** none for this pure documentation decision; no
device/build work requested or performed.
