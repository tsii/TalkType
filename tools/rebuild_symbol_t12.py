#!/usr/bin/env python3
"""T12 (docs/PLAN_T11_T12.md §3): symbol layer redistribution — a PURE
PERMUTATION of the existing keys of symbols/symbolsShift in ALL 15 assets
(ru-ru included: the symbol layers are not D27-locked).

  symbols (language-neutral, identical in content in all 15 — PLAN D2=A):
    r1 = [- + / ( ) & @ = # %] à 0.9 + ⌫ 1.0 (unchanged letter slot);
        ': ;' down from r1, '% +' up from r2 ("plus next to minus", percent
        at the row end — PLAN T12 ': ;' down / '% +' up).
    r2 = ⇧ 1.5 + [: ; , ' " _ * ! ? .] à 0.75 + ⏎ 1.0 (unchanged letter slot);
        mirrored sequence, dot far right (before ⏎, like the T11 letter r2).
  Menus travel id-keyed with their key: percent keeps ‰, plus keeps ±, … —
  no menu is rehung, no character invented, r0/r3 and the letter layers stay
  byte-identical.

  symbolsShift (PLAN D4 "full analog", de-de example of §3 as ground truth):
    - keys with the SLOT ids 'colon'/'semicolon' (in these assets the ids
      mark the slots analogous to the symbols-layer ': ;' — de-de: ¶/·,
      cs/pl/ru/sv/tr: °/§, en-us: ^/°, da-dk/fi-fi/el-gr: {/}) move DOWN
      to the r2 lead, the two rightmost r2 cells move UP to the r1 end,
      and the remaining r2 block is MIRRORED (r2 sequence mirrored, dot
      family far right). de-de check: r1 -> € _ \ [ ] § { } „ “ ;
      r2 -> ¶ · $ ~ > < ¡ ¿ — … (the § position in the plan example §8 is
      an illustrative grouping; the mechanical rule keeps the remaining r1
      order and appends the up-movers).
    - es-es/fr-fr/it-it/nl-nl/pt-pt carry NO 'colon'/'semicolon' slot ids in
      symbolsShift (neither before nor after T6 — their r1 block is
      own-character-keyed). The id-marked exchange is therefore vacuous for
      them: their symbolsShift layer stays UNCHANGED (inventing a down-mover
      pair is out of spec; PLAN §3 names the mechanism strictly id-keyed).

Invariants (the script asserts per asset, HARD):
  - character inventory before == after (direct labels+values ∪ menu entries
    of BOTH symbol layers) — otherwise abort (pure permutation proof);
  - Σ per row [10, 10, 10, 13] in both layers, layer-wide unique ids,
    ⌫ exactly 1× at the r1 end (1.0), ⇧ r2[0] (1.5), ⏎ exactly 1× at the r2
    end (1.0), r1 text cells 10 à 0.9, r2 text cells 10 à 0.75 (M rule).

Idempotent: a second run on already migrated assets detects the
'colon'/'semicolon' ids in the symbolsShift r2 lead and skips the exchange;
the symbols rebuild re-orders to the fixed plan order (no-op).

Format style: like rebuild_symbol_layers.py (T6) — 2-space indent, one key per
line; only the two layer sections are replaced (splice), the rest of the
file stays byte-identical.
"""
import json
import sys

LAYOUTS = "app/src/main/assets/layouts"
ASSETS = ["cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
          "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr"]

# T12 PLAN orders (D2=A), language-neutral identical in all 15 assets.
SYMBOLS_R1_ORDER = ["-", "+", "/", "(", ")", "&", "@", "=", "#", "%"]
SYMBOLS_R2_ORDER = [":", ";", ",", "'", "\"", "_", "*", "!", "?", "."]
# symbolsShift: the slot ids whose keys move down to the r2 lead (D4 analog).
DOWN_IDS = ("colon", "semicolon")


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


def char_inventory(layers):
    """Direct values+labels ∪ menu entries of both symbol layers (hard invariant)."""
    chars = set()
    for layer in layers:
        for row in layer["rows"]:
            for k in row:
                if k.get("kind") == "text":
                    chars.add(k["label"])
                    chars.add(k["value"])
                for alt in k.get("alternates") or []:
                    chars.add(alt)
    return chars


def rebuild_symbols(layer):
    """symbols r1/r2 onto the T12 plan order (r0/r3 stay untouched)."""
    rows = layer["rows"]
    r1text, r2text = text_keys(rows[1]), text_keys(rows[2])
    by_label = {}
    for k in r1text + r2text:
        assert k["label"] not in by_label, f"duplicate label {k['label']!r}"
        by_label[k["label"]] = k
    want = set(SYMBOLS_R1_ORDER) | set(SYMBOLS_R2_ORDER)
    assert set(by_label) == want, \
        f"plan characters deviate: -{sorted(want - set(by_label))} +{sorted(set(by_label) - want)}"
    r1 = [clone(by_label[c]) for c in SYMBOLS_R1_ORDER]
    for k in r1:
        set_width(k, 0.9)
    r1.append(clone(action_by_id(rows, "backspace")))
    set_width(r1[-1], 1.0)
    r2 = [clone(action_by_id(rows, "shift"))]
    set_width(r2[0], 1.5)
    r2 += [clone(by_label[c]) for c in SYMBOLS_R2_ORDER]
    for k in r2[1:]:
        set_width(k, 0.75)
    r2.append(clone(action_by_id(rows, "enter")))
    set_width(r2[-1], 1.0)
    layer["rows"] = [rows[0], r1, r2, rows[3]]


def rebuild_symbols_shift(layer):
    """Returns 'migrated' | 'already-migrated' | 'no-exchange' (id-keyed, D4)."""
    rows = layer["rows"]
    r1text, r2text = text_keys(rows[1]), text_keys(rows[2])
    assert len(r1text) == 10 and len(r2text) == 10, \
        f"expected 10+10 text cells, found {len(r1text)}+{len(r2text)}"
    down = [k for k in r1text if k.get("id") in DOWN_IDS]
    if len(down) == 2:
        # first run: colon/semicolon slot keys down to the r2 lead,
        # two rightmost r2 cells up to the r1 end, r2 block mirrored.
        assert {k["id"] for k in down} == set(DOWN_IDS), "colon/semicolon ids only"
        up = r2text[-2:]
        new_r1 = [k for k in r1text if k.get("id") not in DOWN_IDS] + [clone(k) for k in up]
        new_r2 = [clone(k) for k in down] + [clone(k) for k in reversed(r2text[:-2])]
        _assemble_shift(layer, new_r1, new_r2)
        return "migrated"
    if [k.get("id") for k in r2text[:2]] == list(DOWN_IDS):
        # second run (idempotent): the slot ids already lead r2 — skip.
        return "already-migrated"
    assert not down, f"exactly one of {DOWN_IDS} in r1: {[k['id'] for k in down]}"
    # no colon/semicolon slot ids in this asset's symbolsShift (es/fr/it/nl/pt):
    # the id-marked exchange is vacuous — layer unchanged.
    return "no-exchange"


def _assemble_shift(layer, new_r1, new_r2):
    rows = layer["rows"]
    assert len(new_r1) == 10 and len(new_r2) == 10
    for k in new_r1:
        set_width(k, 0.9)
    for k in new_r2:
        set_width(k, 0.75)
    r1 = new_r1 + [clone(action_by_id(rows, "backspace"))]
    set_width(r1[-1], 1.0)
    r2 = [clone(action_by_id(rows, "shift"))]
    set_width(r2[0], 1.5)
    r2 += new_r2
    r2.append(clone(action_by_id(rows, "enter")))
    set_width(r2[-1], 1.0)
    layer["rows"] = [rows[0], r1, r2, rows[3]]


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
        before = char_inventory([doc["layers"]["symbols"], doc["layers"]["symbolsShift"]])
        rebuild_symbols(doc["layers"]["symbols"])
        state = rebuild_symbols_shift(doc["layers"]["symbolsShift"])
        after = char_inventory([doc["layers"]["symbols"], doc["layers"]["symbolsShift"]])
        # HARD gate: pure permutation — no character lost, none invented.
        assert before == after, f"{asset_id}: character inventory changed: " \
            f"-{sorted(before - after)} +{sorted(after - before)}"
        inv = []
        for layer_name in ("symbols", "symbolsShift"):
            layer = doc["layers"][layer_name]
            ids = [k["id"] for row in layer["rows"] for k in row]
            assert len(ids) == len(set(ids)), f"{asset_id}/{layer_name}: duplicate ids"
            sums = [sum(k["width"] for k in row) for row in layer["rows"]]
            for i, expect in enumerate((10.0, 10.0, 10.0, 13.0)):
                assert abs(sums[i] - expect) < 1e-6, f"{asset_id}/{layer_name} Σ r{i}={sums[i]}"
            r1, r2 = layer["rows"][1], layer["rows"][2]
            assert r1[-1]["id"] == "backspace" and r1[-1]["width"] == 1.0
            assert r2[0]["id"] == "shift" and r2[0]["width"] == 1.5
            assert r2[-1]["id"] == "enter" and r2[-1]["width"] == 1.0
            r1t, r2t = text_keys(r1), text_keys(r2)
            assert len(r1t) == 10 and all(k["width"] == 0.9 for k in r1t)
            assert len(r2t) == 10 and all(k["width"] == 0.75 for k in r2t)  # M rule
            inv.append(f"{layer_name} Σ=[10,10,10,13] ⌫r1-end ⇧1.5 ⏎r2-end "
                       f"r1=10×0.9 r2=10×0.75")
        splice(path, doc)
        sym = doc["layers"]["symbols"]
        r1 = "".join(k["label"] for k in text_keys(sym["rows"][1]))
        r2 = "".join(k["label"] for k in text_keys(sym["rows"][2]))
        report.append(
            f"{asset_id}: OK | symbolsShift {state} | inventory {len(before)}=={len(after)} "
            f"| symbols r1 '{r1}' r2 '{r2}'\n    {inv[0]}\n    {inv[1]}")
    print("\n".join(report))


if __name__ == "__main__":
    sys.exit(main())
