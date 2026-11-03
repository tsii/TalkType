#!/usr/bin/env python3
"""T8 (v0.1.38, Y-row alignment, planner approval vc39): @ key removed from
base/shift r2 (user confirmed), ⇧ narrower, ⏎ wider — Y centered under A/S,
M centered under K/L. Edits ONLY layers.base/layers.shift row 2 of all
14 letter assets (ru-ru untouched, D27 exception).

Target geometry (unit grid u, KEY_GAP_DP=0 ⇒ px-exact: u = 108 px):
  - 13 seven-key assets (da-dk de-de el-gr en-us es-es fi-fi fr-fr it-it nl-nl
    pl-pl pt-pt sv-se tr-tr): ⇧ 0.89352 · 7 letters à 1.10185 · ⏎ 1.39352
    (px: 96.5 + 7×119 + 150.5 = 1080). Derivation: r1 center of the i-th
    letter c1(i) = 0.5 + (i+0.5)·0.94444; required
    c(r2[0]) = (c1(0)+c1(1))/2 = 13/9 = 1.44444 (Y under A/S),
    c(r2[6]) = (c1(7)+c1(8))/2 = 145/18 = 8.05556 (M under K/L) ⇒ w = 1.10185,
    ⇧ = 0.89352, ⏎ = 1.39352.
  - cs-cz (6-key x..m, no y): @ out, ⇧ 1.88889 + 6 à 1.03704 stay,
    ⏎ 1.88889 (planner approval: ⇧=⏎ symmetric, Σ 10.00002 within tolerance);
    M centering under K/L in the 6-key grid is arithmetically unreachable
    (documented exception).
  - ru-ru: r2 = D27 full row ⇧1 + 10 à 1.0 + ⏎1 (Σ12), no @ on the
    letter layers — UNTOUCHED (no @ alternate invented; @ stays
    reachable via the symbol layer).

@ stays reachable: the M key (last r2 letter, id per asset: m resp.
el-gr μ) gets alternates ["@"] (≤ 8, no hint) — in base AND shift.

Invariants (asserted per asset): character set base+shift before==after (@ moves
from the direct key into the M menu), Σ r2 within tolerance, layer ID uniqueness.
Idempotent (a second run changes nothing).
"""
import json
import sys

LAYOUTS = "app/src/main/assets/layouts"
SEVEN = ["da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr", "it-it",
         "nl-nl", "pl-pl", "pt-pt", "sv-se", "tr-tr"]
CS = "cs-cz"
RU = "ru-ru"

SHIFT_7, LETTER_7, ENTER_7 = 0.89352, 1.10185, 1.39352
CS_SHIFT, CS_LETTER, CS_ENTER = 1.88889, 1.03704, 1.88889


def num(w):
    """json number: integer where possible, otherwise rounded to 10 digits."""
    r = round(w, 10)
    return int(r) if abs(r - round(r)) < 1e-9 else r


def set_width(key, w):
    key["width"] = num(w)


def text_keys(row):
    return [k for k in row if k.get("kind") == "text"]


def char_set(doc):
    """Character set (direct keys ∪ menu entries) of the letter layers."""
    chars = set()
    for layer_name in ("base", "shift"):
        for row in doc["layers"][layer_name]["rows"]:
            for k in row:
                if k.get("kind") == "text":
                    chars.add(k["label"])
                    chars.add(k["value"])
                for alt in k.get("alternates") or []:
                    chars.add(alt)
    return chars


def rebuild_r2(row, is_cs):
    """r2 of a letter layer: @ out, widths per T8, @ → M menu (idempotent)."""
    at = [k for k in row if k.get("kind") == "text" and k["value"] == "@"]
    assert len(at) <= 1, f"at most 1 @ key in r2, found {len(at)}"
    row = [k for k in row if k is not (at[0] if at else None)]
    assert row[0].get("kind") == "action" and row[0]["value"] == "shift"
    assert row[-1].get("kind") == "action" and row[-1]["value"] == "enter"
    letters = text_keys(row)
    if is_cs:
        assert len(letters) == 6, f"cs expects 6 letters, found {len(letters)}"
        set_width(row[0], CS_SHIFT)
        for k in letters:
            set_width(k, CS_LETTER)
        set_width(row[-1], CS_ENTER)
    else:
        assert len(letters) == 7, f"expected 7 letters, found {len(letters)}"
        set_width(row[0], SHIFT_7)
        for k in letters:
            set_width(k, LETTER_7)
        set_width(row[-1], ENTER_7)
    # @ stays reachable: the M key (last letter) gets @ into its menu.
    m = letters[-1]
    assert m.get("hint") is None, f"hint on the M key unexpected: {m.get('hint')}"
    alts = list(m.get("alternates") or [])
    if "@" not in alts:
        alts.append("@")
    assert len(alts) <= 8, f"M key > 8 alternates: {alts}"
    m["alternates"] = alts
    return row


def splice(path, doc):
    """Replace only the base/shift sections — the rest of the file stays."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")

    def section_range(name):
        start = next(i for i, ln in enumerate(lines) if ln == f'    "{name}": {{')
        j = start + 1
        while lines[j] not in ("    }", "    },"):
            j += 1
        return start, j  # including the closing line

    s_b, e_b = section_range("base")
    s_s, e_s = section_range("shift")
    assert s_s == e_b + 1, "shift does not lie directly after base"

    def render(layer):
        body = json.dumps({"rows": layer["rows"]}, indent=2, ensure_ascii=False)
        inner = body.split("\n")[1:-1]
        return ["    " + ln for ln in inner]

    new_lines = (
        lines[:s_b]
        + ['    "base": {']
        + render(doc["layers"]["base"])
        + ["    },"]
        + ['    "shift": {']
        + render(doc["layers"]["shift"])
        + ["    },"]
        + lines[e_s + 1:]
    )
    text = "\n".join(new_lines)
    json.loads(text)  # validation before writing
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    report = []
    for asset_id in SEVEN + [CS]:
        path = f"{LAYOUTS}/{asset_id}.json"
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        before = char_set(doc)
        is_cs = asset_id == CS
        for layer_name in ("base", "shift"):
            doc["layers"][layer_name]["rows"][2] = rebuild_r2(
                doc["layers"][layer_name]["rows"][2], is_cs,
            )
        after = char_set(doc)
        assert before == after, \
            f"{asset_id}: character set changed: -{sorted(before - after)} +{sorted(after - before)}"
        for layer_name in ("base", "shift"):
            layer = doc["layers"][layer_name]
            ids = [k["id"] for row in layer["rows"] for k in row]
            assert len(ids) == len(set(ids)), f"{asset_id}/{layer_name}: duplicate ids"
            s2 = sum(k["width"] for k in layer["rows"][2])
            assert abs(s2 - 10.0) < 0.001, f"{asset_id}/{layer_name} Σ r2={s2}"
        splice(path, doc)
        group = "6-key (cs)" if is_cs else "7-key"
        report.append(
            f"{asset_id}: OK {group} — @ out, ⇧/{'1.88889' if is_cs else '0.89352'}, "
            f"⏎ {'1.88889' if is_cs else '1.39352'}, @→M menu (base+shift)"
        )
    # ru-ru: D27 exception — UNTOUCHED; only check, do not write.
    with open(f"{LAYOUTS}/{RU}.json", encoding="utf-8") as f:
        ru = json.load(f)
    for layer_name in ("base", "shift"):
        r2 = ru["layers"][layer_name]["rows"][2]
        assert not any(k.get("value") == "@" for k in r2), "ru-ru suddenly with @ in r2?"
        assert abs(sum(k["width"] for k in r2) - 12.0) < 1e-6, "ru-ru D27 Σ12 violated"
        m = [k for k in r2 if k.get("kind") == "text"][-1]
        assert "@" not in (m.get("alternates") or []), "ru-ru: @ alternate invented?"
    report.append(f"{RU}: OK D27 exception untouched (no @, Σ r2 = 12)")
    print("\n".join(report))


if __name__ == "__main__":
    sys.exit(main())
