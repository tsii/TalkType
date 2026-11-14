#!/usr/bin/env python3
"""V0.1.49 geometry migration (docs/PLAN_GEOMETRY_V0149.md §1/§2/§3/§4.9).

Applies the AOSP-style row realignment to ALL 15 layout assets. The tables
below are the ONLY source of truth; cs-cz and ru-ru are their own table rows
(§3). ONE backspace in Z1 (§0, variant A — NOT two).

Letter layers (base+shift):
  - 13 standard assets (da-dk de-de el-gr en-us es-es fi-fi fr-fr it-it
    nl-nl pl-pl pt-pt sv-se tr-tr):
      Z1 = m-left 0.500 + 9 × 0.888889 + ⌫ 1.500   (px: 54 + 9×96 + 162)
      Z2 = ⇧ 1.500 + 7 × 0.928571 + ⏎ 2.000       (px: 162 + 7×100.29 + 216)
  - cs-cz: Z1 = 0.500 + 10 × 0.800 + ⌫ 1.500;  Z2 = ⇧ 2.000 + 6 × 1.000 + ⏎ 2.000
  - ru-ru: Z0 UNTOUCHED; Z1 = 0.250 + 11 × 0.772727 + ⌫ 1.250 (Σ 10; the
    old m-right margin is REPLACED by the backspace key); Z2 = ⇧ 1.000 +
    10 × 0.900 + ⏎ 2.000 (Σ 12 → ⏎ = 180 px); Z3 untouched (already
    symbols|language|emoji|space|period|mic with space 6.5).
  - period (id 'period', F54 menu , ! ? : ; ( )) moves from Z2 to Z3,
    inserted between space and mic; space 7.5 -> 6.5 (Σ stays 13).

Symbol layers (symbols+symbolsShift, all 15 identical):
      Z1 = NEW margin 0.500 + 10 × 0.850 + ⌫ 1.000
      Z2 = NEW toggle 1.000 + 10 × 0.750 + ⏎ 1.500   (no shift key anymore)
      Z3 untouched (base|language|emoji|space 7.5|mic)
  - toggle keys: symbols Z2[0]  = {id symbols-toggle, label #+=, width 1.0,
    action layer-symbols-shift}  <- NEW action; the LayoutParser accepts it
    only from Teil B on (LAYER_SYMBOLS_ALT in KeyActions.ALL) — expected,
    documented intermediate state;
    symbolsShift Z2[0] = {id symbols-back, label ?123, width 1.0,
    action layer-symbols}.

Invariants (HARD-asserted per asset+layer):
  - character inventory (TEXT values ∪ labels ∪ alternates) before == after
    — nothing lost, nothing invented (the period only MOVES Z2 -> Z3 within
    the same layer; margins/actions carry no characters);
  - Σ per row == target ± 0.001 (letters 10/10/10[ru 12]/13, symbols
    10/10/10/13);
  - layer-wide unique key ids; letter Z3 ids == [symbols,language,emoji,
    space,period,mic], symbol Z3 ids == [base,language,emoji,space,mic];
  - output format == input format (2-space JSON, one key per line):
    the UNMODIFIED layers section must re-render byte-identically BEFORE
    the migration is applied (format guard) — hence idempotency is proven
    by a byte-identical second run.

Idempotent: a second run detects the migrated state and re-applies the
widths as a no-op; the written file is byte-identical.
"""
import json
import sys

LAYOUTS = "app/src/main/assets/layouts"
SEVEN = ["da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr", "it-it",
         "nl-nl", "pl-pl", "pt-pt", "sv-se", "tr-tr"]
CS = "cs-cz"
RU = "ru-ru"
ALL = SEVEN + [CS, RU]
LAYER_NAMES = ["base", "shift", "symbols", "symbolsShift"]
F54 = [",", "!", "?", ":", ";", "(", ")"]

# §1/§3 letter tables, per asset class:
# (z1_margin, z1_letter, z1_backspace, n_z1, z2_shift, z2_letter, n_z2, z2_enter)
LETTER = {
    "std": (0.5, 0.888889, 1.5, 9, 1.5, 0.928571, 7, 2.0),
    "cs":  (0.5, 0.8, 1.5, 10, 2.0, 1.0, 6, 2.0),
    "ru":  (0.25, 0.772727, 1.25, 11, 1.0, 0.9, 10, 2.0),
}
# §2 symbol table (all 15 identical)
SYM = {"margin": 0.5, "z1_letter": 0.85, "backspace": 1.0, "toggle": 1.0,
       "z2_letter": 0.75, "enter": 1.5}

TOGGLE_FWD = {"id": "symbols-toggle", "label": "#+=", "width": 1.0,
              "kind": "action", "value": "layer-symbols-shift"}
TOGGLE_BACK = {"id": "symbols-back", "label": "?123", "width": 1.0,
               "kind": "action", "value": "layer-symbols"}


def num(w):
    """json number: integer where possible, otherwise rounded to 10 digits."""
    r = round(w, 10)
    return int(r) if abs(r - round(r)) < 1e-9 else r


def set_width(key, w):
    key["width"] = num(w)


def text_keys(row):
    return [k for k in row if k.get("kind") == "text"]


def is_action(key, value):
    return key.get("kind") == "action" and key.get("value") == value


def inventory(doc):
    """Character inventory per layer: TEXT values ∪ labels ∪ all alternates."""
    out = {}
    for name in LAYER_NAMES:
        chars = set()
        for row in doc["layers"][name]["rows"]:
            for k in row:
                if k.get("kind") == "text":
                    chars.add(k["label"])
                    chars.add(k["value"])
                for alt in k.get("alternates") or []:
                    chars.add(alt)
        out[name] = sorted(chars)
    return out


def letter_class(asset_id):
    return "cs" if asset_id == CS else ("ru" if asset_id == RU else "std")


def rebuild_letter_layer(rows, cls):
    """§1/§3: Z1 backspace row, Z2 enter row, Z3 period footer."""
    m, l1, bs, n1, sh, l2, n2, en = LETTER[cls]
    r1, r2, r3 = rows[1], rows[2], rows[3]

    # --- Z0 untouched (Σ hard-asserted in check_invariants) ---
    # --- Z1: margin + n1 letters + ONE backspace (§0 variant A) ---
    assert r1[0].get("kind") == "margin", "Z1[0] must be a margin key"
    set_width(r1[0], m)
    letters = text_keys(r1)
    assert len(letters) == n1, f"Z1 needs {n1} letters (has {len(letters)})"
    for k in letters:
        set_width(k, l1)
    if cls == "ru":
        # old form: margin + 11 letters + margin (no backspace) -> the
        # trailing margin is REPLACED by the backspace key.
        if not is_action(r1[-1], "backspace"):
            assert r1[-1].get("kind") == "margin", "ru Z1 must end with a margin pre-migration"
            r1[-1] = {"id": "backspace", "label": "⌫", "width": num(bs),
                      "kind": "action", "value": "backspace"}
        assert len(r1) == n1 + 2, "ru Z1 = margin + 11 letters + backspace"
    else:
        assert is_action(r1[-1], "backspace"), "Z1 must end with the backspace"
    set_width(r1[-1], bs)
    assert sum(1 for k in r1 if is_action(k, "backspace")) == 1, "exactly ONE ⌫ in Z1"

    # --- Z2: shift + n2 letters + enter; period moves to the footer ---
    assert is_action(r2[0], "shift"), "Z2[0] must be the shift key"
    set_width(r2[0], sh)
    assert is_action(r2[-1], "enter"), "Z2 must end with the enter key"
    set_width(r2[-1], en)
    in_r2 = [k for k in r2 if k.get("id") == "period" and k.get("kind") == "text"]
    if in_r2:
        assert len(in_r2) == 1, "at most one period key in Z2"
        assert not any(k.get("id") == "period" for k in r3), "period in Z2 AND Z3?"
        space = [k for k in r3 if k.get("id") == "space"]
        assert len(space) == 1, "footer needs exactly one space key"
        r2.remove(in_r2[0])
        set_width(in_r2[0], 1.0)  # §1: period takes one footer unit in Z3
        r3.insert(r3.index(space[0]) + 1, in_r2[0])  # between space and mic
    else:
        assert any(k.get("id") == "period" for k in r3), \
            "period must exist (in Z2 pre-migration, in Z3 afterwards)"
    letters = text_keys(r2)
    assert len(letters) == n2, f"Z2 needs {n2} letters (has {len(letters)})"
    for k in letters:
        set_width(k, l2)
    assert not any(k.get("id") == "period" for k in r2), "no period in Z2 after migration"

    # --- Z3: symbols|language|emoji|space|period|mic, space 6.5, mic last ---
    ids = [k["id"] for k in r3]
    assert ids == ["symbols", "language", "emoji", "space", "period", "mic"], \
        f"letter footer ids: {ids}"
    assert ids.index("space") + 1 == ids.index("period"), "period directly after space"
    by_id = {k["id"]: k for k in r3}
    if cls != "ru":
        assert list(by_id["period"].get("alternates") or []) == F54, \
            f"period keeps the F54 menu {F54} (is {by_id['period'].get('alternates')})"
    set_width(by_id["space"], 6.5)
    assert abs(by_id["period"]["width"] - 1.0) < 1e-9, "period takes one footer unit"
    for kid, w in (("symbols", 1.5), ("language", 1), ("emoji", 1), ("mic", 2)):
        assert abs(by_id[kid]["width"] - w) < 1e-9, f"footer {kid} width {w}"
    assert r3[-1]["id"] == "mic"


def rebuild_symbol_layer(rows, layer_name):
    """§2: Z1 margin + 10 × 0.85 + ⌫ 1.0; Z2 toggle + 10 × 0.75 + ⏎ 1.5."""
    r1, r2, r3 = rows[1], rows[2], rows[3]

    # --- Z0 untouched; Z1: NEW left margin (idempotent) ---
    if r1[0].get("kind") != "margin":
        r1.insert(0, {"id": "m-left", "kind": "margin", "width": num(SYM["margin"])})
    else:
        assert r1[0]["id"] == "m-left", "Z1[0] margin must be m-left"
        set_width(r1[0], SYM["margin"])
    letters = text_keys(r1)
    assert len(letters) == 10, f"Z1 needs 10 characters (has {len(letters)})"
    for k in letters:
        set_width(k, SYM["z1_letter"])
    assert is_action(r1[-1], "backspace"), "Z1 must end with the backspace"
    set_width(r1[-1], SYM["backspace"])

    # --- Z2: shift REPLACED by the toggle (idempotent) ---
    toggle = TOGGLE_FWD if layer_name == "symbols" else TOGGLE_BACK
    if is_action(r2[0], "shift"):
        r2[0] = dict(toggle)
    else:
        assert r2[0].get("id") == toggle["id"] and r2[0].get("value") == toggle["value"], \
            f"Z2[0] must be the {toggle['id']} toggle"
    set_width(r2[0], SYM["toggle"])
    letters = text_keys(r2)
    assert len(letters) == 10, f"Z2 needs 10 characters (has {len(letters)})"
    for k in letters:
        set_width(k, SYM["z2_letter"])
    assert is_action(r2[-1], "enter"), "Z2 must end with the enter key"
    set_width(r2[-1], SYM["enter"])

    # --- Z3 untouched: base|language|emoji|space 7.5|mic ---
    ids = [k["id"] for k in r3]
    assert ids == ["base", "language", "emoji", "space", "mic"], f"symbol footer ids: {ids}"
    by_id = {k["id"]: k for k in r3}
    for kid, w in (("base", 1.5), ("language", 1), ("emoji", 1), ("space", 7.5), ("mic", 2)):
        assert abs(by_id[kid]["width"] - w) < 1e-9, f"footer {kid} width {w}"
    assert not any(is_action(k, "shift") for row in rows for k in row), \
        "no shift key on the symbol layers after migration"


def check_invariants(asset_id, doc):
    """Σ per row + unique ids + footer ids — HARD, after the migration."""
    cls = letter_class(asset_id)
    for name in LAYER_NAMES:
        rows = doc["layers"][name]["rows"]
        ids = [k["id"] for row in rows for k in row]
        assert len(ids) == len(set(ids)), f"{asset_id}/{name}: duplicate key ids"
        is_letter = name in ("base", "shift")
        for r in range(4):
            if r == 3:
                target = 13.0
            elif is_letter and cls == "ru" and r == 2:
                target = 12.0
            else:
                target = 10.0
            s = sum(k["width"] for k in rows[r])
            assert abs(s - target) < 0.001, f"{asset_id}/{name}: Σ row {r} = {s} != {target}"
        expected_footer = (["symbols", "language", "emoji", "space", "period", "mic"]
                           if is_letter else
                           ["base", "language", "emoji", "space", "mic"])
        assert [k["id"] for k in rows[3]] == expected_footer, \
            f"{asset_id}/{name}: footer ids"


def layers_section_lines(doc):
    """The layers section EXACTLY as it appears in the file (2-space JSON):
    first line '  "layers": {', last line '  }'.
    """
    body = json.dumps({"layers": doc["layers"]}, indent=2, ensure_ascii=False)
    return body.split("\n")[1:-1]


def token_stream(section_lines):
    """JSON tokens without whitespace — content/format guard basis."""
    import re
    return re.findall(r'"(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?|[\[\]{}:,]',
                      "\n".join(section_lines))


def migrate(asset_id):
    path = f"{LAYOUTS}/{asset_id}.json"
    with open(path, encoding="utf-8") as f:
        text = f.read()
    lines = text.split("\n")
    doc = json.loads(text)

    start = next(i for i, ln in enumerate(lines) if ln == '  "layers": {')
    end = next(i for i in range(start + 1, len(lines)) if lines[i] == "  }")
    old_section = lines[start:end + 1]

    # Format guard BEFORE the migration: the UNMODIFIED layers section must
    # re-render with the same token stream (proves the writer reproduces the
    # content/style). ru-ru carries 2 legacy COMPACT one-line alternates
    # arrays (the period menus in base/shift Z3) — the only known deviation;
    # the migration normalizes them to the standard multi-line style.
    assert token_stream(old_section) == token_stream(layers_section_lines(doc)), \
        f"{path}: format guard failed — writer does not reproduce the layers section"

    before = inventory(doc)
    cls = letter_class(asset_id)
    for name in ("base", "shift"):
        rebuild_letter_layer(doc["layers"][name]["rows"], cls)
    for name in ("symbols", "symbolsShift"):
        rebuild_symbol_layer(doc["layers"][name]["rows"], name)
    after = inventory(doc)
    assert before == after, f"{asset_id}: character inventory changed" + \
        "".join(f" ({n}: -{sorted(set(before[n]) - set(after[n]))} "
                f"+{sorted(set(after[n]) - set(before[n]))})"
                for n in LAYER_NAMES if before[n] != after[n])
    check_invariants(asset_id, doc)

    new_section = layers_section_lines(doc)
    new_text = "\n".join(lines[:start] + new_section + lines[end + 1:])
    json.loads(new_text)  # validation before writing
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_text)
    # Canonical-form fixpoint: the WRITTEN section must re-render byte-
    # identically from the reparsed file — hence the second run is a no-op.
    with open(path, encoding="utf-8") as f:
        doc2 = json.load(f)
    assert layers_section_lines(doc2) == new_section, \
        f"{path}: written layers section is not in canonical render form"
    return cls


def main():
    report = []
    for asset_id in ALL:
        cls = migrate(asset_id)
        label = {"std": "standard table (§1)", "cs": "cs-cz exception (§3)",
                 "ru": "ru-ru exception (§3)"}[cls]
        report.append(f"{asset_id}: OK — {label}")
    print(f"migrated {len(ALL)} assets — letter Σ 10/10/10[ru 12]/13, "
          f"symbol Σ 10/10/10/13; inventory/sums/ids hard-asserted")
    print("\n".join(report))


if __name__ == "__main__":
    sys.exit(main())
