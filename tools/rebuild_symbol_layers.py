#!/usr/bin/env python3
"""T6 (v0.1.36) + T7 correction (v0.1.37, docs/PLAN_SYMBOL_RASTER.md M rule):
symbol grid with stable letter slots. Migrates symbols/symbolsShift
of ALL 15 layout assets to the unit grid Σ=[10,10,10,13]; T7 makes the
run IDEMPOTENT (second run on already migrated assets) and enforces the
M rule: r2 at most 10 direct cells à 0.75 (budget 7.5 in Σ10.000 — 11/12
cells à 0.75 are arithmetically impossible); overflow characters drop and stay
reachable via existing menus (menu handover, sharply checked).

  symbols (language-neutral, identical in content in all 15):
    r0 = 10 digits à 1.0 (digit IDs asset-local);
    r1 = [- / : ; ( ) & @ = #] à 0.9 + ⌫ 1.0 at the END (letter slot);
    r2 = ⇧ 1.5 + [., , ?, !, ', \", %, +, _, *] à 0.75 + ⏎ 1.0 at the END;
    r3 = ABC 1.5 / 🌐 1 / 😊 1 / space 7.5 / 🎤 2 (no ⏎, no cursor cells).

  symbolsShift (structure 10/10/10/13, asset-specific mandatory characters stay):
    r0 = first 10 keys à 1.0; the r0 tail (11th key: €/$/_/~/…) moves
         to the r1 block start (plan: de-de „€ r0→r1“);
    r1 = block à 9.0/n + ⌫ 1.0 (overflow > 10 keys moves into the r2 block);
    r2 = ⇧ 1.5 + block à 7.5/n + ⏎ 1.0;
    r3 = like symbols.
    da-dk/fi-fi/el-gr: € (symbols r0, position 11) moves as a symbolsShift
    direct key to the r0 end position; the previous r0 tail key (id „euro“,
    label „>") moves into the r1 block (id → „greater“, collision freedom).
    da-dk/fi-fi: the ‰ direct key is dropped (plan acceptance; reachable via the
    T4 menu percent→[‰]).

Invariants (the script asserts per asset): no character is lost
(direct keys ∪ menu entries before==after), layer ID uniqueness,
Σ per row, exactly 1× ⌫/⏎/space/mic/emoji per layer.

Format style: 2-space indent, one key per line, arrays one element per line —
only the two layer sections are replaced (splice), the rest of the
file stays byte-identical (only hand-packed period-alternates short lines
may get rewrapped by the parser standard — cosmetic).
"""
import json
import sys

LAYOUTS = "app/src/main/assets/layouts"
ASSETS = ["cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
          "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr"]

R1_ORDER = ["-", "/", ":", ";", "(", ")", "&", "@", "=", "#"]
R2_ORDER = [".", ",", "?", "!", "'", "\"", "%", "+", "_", "*"]
PERMILLE_DROP = {"da-dk", "fi-fi"}  # plan acceptance: ‰ → T4 menu percent→[‰]
R2_MAX_CELLS = 10  # T7 M rule: r2 direct cells à 0.75 within the budget 7.5 (Σ10.000)

# T7 (v0.1.37) menu handover: characters that drop out of an overcrowded r2
# MUST stay reachable via an existing menu (guarded by the before==after assert
# in main()); documentation of the actual cases (PLAN_SYMBOL_RASTER.md
# M rule): da/fi „…“ → dot menu [. → …], el-gr „–“ → hyphen menu [–, —],
# el-gr „…“ → dot menu.


def num(w):
    """json number: integer where possible, otherwise 10 digits (Σ stays exact)."""
    r = round(w, 10)
    return int(r) if abs(r - round(r)) < 1e-9 else r


def clone(key):
    return json.loads(json.dumps(key))


def set_width(key, w):
    key["width"] = num(w)


def text_keys(row):
    return [k for k in row if k.get("kind") == "text"]


def action_by_id(rows, kid):
    for row in rows:
        for k in row:
            if k.get("id") == kid and k.get("kind") == "action":
                return k
    raise AssertionError(f"action key id '{kid}' not found")


def char_set(layers):
    """Direct keys ∪ menu entries of both symbol layers (migration invariant)."""
    chars = set()
    for layer in layers:
        for row in layer["rows"]:
            for k in row:
                if k.get("kind") == "text":
                    chars.add(k["label"])
                for alt in k.get("alternates") or []:
                    chars.add(alt)
    return chars


def rebuild_symbols(layer):
    """symbols onto the plan grid; returns: the emigrating € key (da/fi/el) or None."""
    rows = layer["rows"]
    flat = [k for row in rows for k in row]
    texts = [k for k in flat if k.get("kind") == "text"]
    digits = [k for k in texts if k["label"] in "0123456789"]
    assert len(digits) == 10, f"expected 10 digits, found {len(digits)}"
    by_label = {}
    for k in texts:
        if k["label"] in R1_ORDER or k["label"] in R2_ORDER:
            assert k["label"] not in by_label, f"duplicate {k['label']}"
            by_label[k["label"]] = k
    missing = (set(R1_ORDER) | set(R2_ORDER)) - set(by_label)
    assert not missing, f"plan characters missing: {sorted(missing)}"
    # remaining text keys: only € may emigrate (da/fi/el), otherwise error.
    accounted = {id(k) for k in digits} | {id(k) for k in by_label.values()}
    euro_out = None
    for k in texts:
        if id(k) not in accounted:
            assert k["label"] == "€", f"unexpected leftover character {k['label']!r}"
            assert euro_out is None
            euro_out = k
    shift = action_by_id(rows, "shift")
    backspace = action_by_id(rows, "backspace")
    enter = action_by_id(rows, "enter")
    footer = [action_by_id(rows, i) for i in ("base", "language", "emoji", "space", "mic")]
    r0 = [clone(k) for k in digits]
    for k in r0:
        set_width(k, 1.0)
    r1 = [clone(by_label[c]) for c in R1_ORDER]
    for k in r1:
        set_width(k, 0.9)
    bs = clone(backspace)
    set_width(bs, 1.0)
    r1 = r1 + [bs]
    sh = clone(shift)
    set_width(sh, 1.5)
    r2text = [clone(by_label[c]) for c in R2_ORDER]
    for k in r2text:
        set_width(k, 0.75)
    en = clone(enter)
    set_width(en, 1.0)
    r2 = [sh] + r2text + [en]
    r3 = [clone(k) for k in footer]
    for k, w in zip(r3, (1.5, 1.0, 1.0, 7.5, 2.0)):
        set_width(k, w)
    layer["rows"] = [r0, r1, r2, r3]
    return euro_out


def rebuild_symbols_shift(layer, euro_in, asset_id):
    """Returns: the list of r2 characters that fall victim to the M rule."""
    rows = layer["rows"]
    r0text = text_keys(rows[0])
    r1text = text_keys(rows[1])
    r2text = text_keys(rows[2])
    assert len(rows[0]) == len(r0text), "r0 contains actions (unexpected)"
    # da/fi: the ‰ direct key is dropped (plan acceptance, menu path stays). Idempotent:
    # in the T7 second run ‰ is already menu-reachable (percent→[‰]) — no assert.
    if asset_id in PERMILLE_DROP:
        r2text = [k for k in r2text if k["label"] != "‰"]
    r0, tail = r0text[:10], r0text[10:]
    assert len(tail) <= 1, f"r0 tail >1 key: {[k['label'] for k in tail]}"
    # da/fi/el: the tail key carries id 'euro' like the immigrating € — make it
    # unambiguous ( '>' → id 'greater'); € moves to the r1 block start (plan rule
    # „€ → r1" like de-de), NOT into r0 (the plan target „r0 10×1.0" stays intact).
    if euro_in is not None:
        for k in tail:
            if k["id"] == "euro":
                k["id"] = "greater"
    queue = ([euro_in] if euro_in is not None else []) + tail + r1text
    r1block, overflow = queue[:10], queue[10:]
    r2block = r2text + overflow
    # T7 M rule: 11/12 direct cells à 0.75 are arithmetically impossible in the
    # r2 budget 7.5 (Σ10.000 − ⇧1.5 − ⏎1.0) (11·0.75 = 8.25 > 7.5 —
    # the first run squeezed to 0.6818/0.625). At most 10 direct cells à 0.75
    # (= 7.5 exactly); the cells from position R2_MAX_CELLS drop — EVERY dropped
    # character stays reachable via an existing menu (menu handover, guarded
    # by the before==after assert in main()).
    r2drop = [k["label"] for k in r2block[R2_MAX_CELLS:]]
    r2block = r2block[:R2_MAX_CELLS]
    n1, n2 = len(r1block), len(r2block)
    assert n1 >= 1 and n2 >= 1
    for k in r0:
        set_width(k, 1.0)
    for k in r1block:
        set_width(k, 9.0 / n1)
    for k in r2block:
        set_width(k, 7.5 / n2)
    shift = action_by_id(rows, "shift")
    backspace = action_by_id(rows, "backspace")
    enter = action_by_id(rows, "enter")
    footer = [action_by_id(rows, i) for i in ("base", "language", "emoji", "space", "mic")]
    sh = clone(shift)
    set_width(sh, 1.5)
    bs = clone(backspace)
    set_width(bs, 1.0)
    en = clone(enter)
    set_width(en, 1.0)
    r3 = [clone(k) for k in footer]
    for k, w in zip(r3, (1.5, 1.0, 1.0, 7.5, 2.0)):
        set_width(k, w)
    layer["rows"] = [list(r0), r1block + [bs], [sh] + r2block + [en], r3]
    return r2drop


def splice(path, doc):
    """Replace only the two layer sections — the rest of the file stays."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")

    def section_range(name):
        start = next(i for i, ln in enumerate(lines) if ln == f'    "{name}": {{')
        j = start + 1
        while lines[j] not in ("    }", "    },"):
            j += 1
        return start, j  # [start, j] including the closing line

    s_sym, e_sym = section_range("symbols")
    s_sh, e_sh = section_range("symbolsShift")
    assert s_sh == e_sym + 1, "symbolsShift does not lie directly after symbols"

    def render(layer):
        body = json.dumps({"rows": layer["rows"]}, indent=2, ensure_ascii=False)
        inner = body.split("\n")[1:-1]
        return ["    " + ln for ln in inner]

    new_lines = (
        lines[:s_sym]
        + ['    "symbols": {']
        + render(doc["layers"]["symbols"])
        + ["    },"]
        + ['    "symbolsShift": {']
        + render(doc["layers"]["symbolsShift"])
        + ["    }"]
        + lines[e_sh + 1:]
    )
    text = "\n".join(new_lines)
    json.loads(text)  # validation before writing
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    report = []
    for asset_id in ASSETS:
        path = f"{LAYOUTS}/{asset_id}.json"
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        before = char_set([doc["layers"]["symbols"], doc["layers"]["symbolsShift"]])
        euro_out = rebuild_symbols(doc["layers"]["symbols"])
        euro_in = clone(euro_out) if euro_out is not None else None
        if asset_id in ("da-dk", "fi-fi", "el-gr"):
            if euro_in is None:
                # T7 second run (assets already migrated): € must already
                # be a symbolsShift direct key (the first run moved it).
                assert any(k.get("kind") == "text" and k["label"] == "€"
                           for row in doc["layers"]["symbolsShift"]["rows"] for k in row), \
                    f"{asset_id}: € missing in symbolsShift (second run)"
        else:
            assert euro_out is None, f"{asset_id}: unexpected leftover character € in symbols"
        r2drop = rebuild_symbols_shift(doc["layers"]["symbolsShift"], euro_in, asset_id)
        after = char_set([doc["layers"]["symbols"], doc["layers"]["symbolsShift"]])
        assert before == after, f"{asset_id}: character set changed: " \
            f"-{sorted(before - after)} +{sorted(after - before)}"
        for layer_name in ("symbols", "symbolsShift"):
            layer = doc["layers"][layer_name]
            ids = [k["id"] for row in layer["rows"] for k in row]
            assert len(ids) == len(set(ids)), f"{asset_id}/{layer_name}: duplicate ids"
            sums = [sum(k["width"] for k in row) for row in layer["rows"]]
            for i, expect in enumerate((10.0, 10.0, 10.0, 13.0)):
                assert abs(sums[i] - expect) < 1e-6, f"{asset_id}/{layer_name} Σ r{i}={sums[i]}"
        splice(path, doc)
        counts = [len(doc["layers"][l]["rows"][r]) for l in ("symbols", "symbolsShift") for r in range(3)]
        euro_note = ""
        if euro_out is not None:
            permille = "dropped (menu path percent→‰)" if asset_id in PERMILLE_DROP else "stays (el-gr)"
            euro_note = f" | €→symbolsShift r1 block start, '>'→r1 (id greater), ‰ direct key: {permille}"
        if r2drop:
            # check the menu handover sharply: every dropped character must stay
            # reachable via alternates (existing menus) — only a direct
            # key elsewhere would violate the menu contract of the M rule.
            menus = {alt for layer in (doc["layers"]["symbols"], doc["layers"]["symbolsShift"])
                     for row in layer["rows"] for k in row
                     for alt in (k.get("alternates") or [])}
            lost = [c for c in r2drop if c not in menus]
            assert not lost, f"{asset_id}: r2 overflow without menu handover: {lost}"
            note = f" | r2 overflow → menu handover: {''.join(r2drop)}"
        else:
            note = ""
        report.append(f"{asset_id}: OK rows(symbols r0-r2)={counts[:3]} rows(shift r0-r2)={counts[3:]}{euro_note}{note}")
    print("\n".join(report))


if __name__ == "__main__":
    sys.exit(main())
