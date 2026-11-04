#!/usr/bin/env python3
# DEPRECATED (T16, user 00:1x): replaced by the T16 rollback — geometry
# reverted to v0.1.42 (HEAD 7b1ddf4, T11-B state: period in r2 before ⏎,
# footer space 7.5). Script kept for the record; do NOT run it.
"""T13 (docs/PLAN_T13_T15.md §T13, variant V): r2 letters EXACTLY under the
home-row centers + period back to r3 in the 14 letter assets (13 std +
cs-cz; ru-ru is D27-locked and read-only — hard-asserted below).

  - 13 seven-key assets (da-dk de-de el-gr en-us es-es fi-fi fr-fr it-it
    nl-nl pl-pl pt-pt sv-se tr-tr): r2 = ⇧ 1.44444 + 7 letters à 0.94444
    (y..m, NO period key anymore) + ⏎ 1.94448 (Σ 10; px: 156.00 + 7×102.00
    + 210.00). The letter block starts at the a/s boundary (1.44444) and
    ends at the k/l boundary (8.05552): the y..m centers lie EXACTLY on the
    home-row centers s..k (a sits over ⇧, l+⌫ over ⏎).
  - cs-cz (6-key x..m): r2 = ⇧ 2.20000 + 6 letters à 0.85000 + ⏎ 2.70000
    (Σ 10; the x..m centers lie EXACTLY on the home centers d..k).
  - period key: moves from r2 (T11 state) BACK to r3, slot between space
    and mic (base AND shift). Target object: id 'period', kind text,
    value/label '.', width 1; alternates = the EXACT F54 standard
    , ! ? : ; ( ) — no hint, no @ (ru-ru was never affected by this move).
  - footer r3 (14 assets): 6 keys — symbols 1.5, language 1, emoji 1,
    space 6.5 (7.5 -> 6.5, Σ stays 13), period 1, mic 2 (mic last).
  - M key (last letter): KEEPS its @ alternate (T8).
  - ru-ru: COMPLETELY untouched (D27: r2 ⇧1 + 10 at 1.0 + ⏎1 = Σ12, 6-key
    footer with @ as period.alternates[0]). The script opens ru-ru
    READ-ONLY and asserts its D27 state; the write helper hard-rejects
    ru-ru paths.

Invariants (asserted per asset/layer, HARD):
  - character set base+shift before == after (the period only MOVES
    between r2 and r3 of the same layers — nothing lost, nothing invented);
  - Σ r2 = 10 ± 0.001, Σ r3 = 13 ± 0.001;
  - CENTER EQUALITY (replaces the T11 symmetry): the r2 text-key centers
    equal the home-row text-key centers s..k (offset 1, std) resp. d..k
    (offset 2, cs) within ±1e-3;
  - layer-wide unique ids; period exactly 1x per layer (in r3, NOT in r2);
  - M key keeps @; no @ as direct key in r2.

Idempotent: a second run detects the period already in r3 and re-applies
the widths as a no-op (byte-identical output).

Format style: like rebuild_letter_rows.py (T8) — 2-space indent, one key
per line; only the base/shift sections are replaced (splice), the rest of
the file (incl. the T12 symbol layers with their space 7.5) stays
byte-identical.
"""
import json
import sys

LAYOUTS = "app/src/main/assets/layouts"
SEVEN = ["da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr", "it-it",
         "nl-nl", "pl-pl", "pt-pt", "sv-se", "tr-tr"]
CS = "cs-cz"
RU = "ru-ru"
F54 = [",", "!", "?", ":", ";", "(", ")"]

STD_CELL, STD_SHIFT, STD_ENTER = 0.94444, 1.44444, 1.94448   # 13 seven-key assets (variant V)
CS_CELL, CS_SHIFT, CS_ENTER = 0.85, 2.2, 2.7                   # cs-cz (variant V, 6-key)


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


def rebuild_r2_r3(rows, is_cs):
    """r2 center grid (variant V) + footer with period. Returns state."""
    r2, r3 = rows[2], rows[3]
    assert r2[0].get("kind") == "action" and r2[0]["value"] == "shift"
    assert r2[-1].get("kind") == "action" and r2[-1]["value"] == "enter"
    shift, enter, cell, n_letters = (
        (CS_SHIFT, CS_ENTER, CS_CELL, 6) if is_cs
        else (STD_SHIFT, STD_ENTER, STD_CELL, 7))

    # period key: T11 state = in r2 (move back) — T13 state = in r3 (reuse).
    in_r2 = [k for k in r2 if k.get("id") == "period" and k.get("kind") == "text"]
    in_r3 = [k for k in r3 if k.get("id") == "period" and k.get("kind") == "text"]
    if in_r2:
        assert not in_r3, "period in r2 AND r3?"
        period = in_r2[0]
        r2.remove(period)
        r3.insert(len(r3) - 1, period)  # slot between space and mic
        state = "migrated-back"
    else:
        assert len(in_r3) == 1, "footer must carry exactly 1 period key"
        period = in_r3[0]
        state = "already-migrated"
    assert period["value"] == "." and period["label"] == "."
    assert list(period.get("alternates") or []) == F54, \
        f"period menu must be the F54 standard {F54} (is {period.get('alternates')})"
    assert not period.get("hint"), "no hint on the period key"
    assert not period.get("cellActions"), "no cursor actions on the period key"
    assert "@" not in (period.get("alternates") or []), "no @ on the period key (ru only)"
    set_width(period, 1)

    # r2 widths: ⇧ + letters (NO period anymore) + ⏎ — variant V (center grid).
    letters = text_keys(r2)
    assert len(letters) == n_letters, \
        f"expected {n_letters} letters in r2 (period gone), found {len(letters)}"
    set_width(r2[0], shift)
    for k in letters:
        set_width(k, cell)
    set_width(r2[-1], enter)

    # M key (last letter) keeps its @ alternate (T8).
    m = letters[-1]
    assert "@" in (m.get("alternates") or []), "M key must keep @ (T8)"

    # footer: 6 keys — symbols 1.5, language 1, emoji 1, space 6.5, period 1, mic 2.
    assert [k["id"] for k in r3] == \
        ["symbols", "language", "emoji", "space", "period", "mic"], \
        f"footer ids: {[k['id'] for k in r3]}"
    by_id = {k["id"]: k for k in r3}
    set_width(by_id["space"], 6.5)
    for kid, w in (("symbols", 1.5), ("language", 1), ("emoji", 1), ("mic", 2)):
        assert abs(by_id[kid]["width"] - w) < 1e-9, f"footer {kid} width {w}"
    assert abs(by_id["period"]["width"] - 1) < 1e-9
    assert r3[-1]["id"] == "mic"
    return state, r2


def check_invariants(asset_id, doc, is_cs):
    """Σ + center equality (variant V) per asset/layer — returns the center delta."""
    shift, enter, cell, n_letters, home_off, home_n = (
        (CS_SHIFT, CS_ENTER, CS_CELL, 6, 2, 10) if is_cs
        else (STD_SHIFT, STD_ENTER, STD_CELL, 7, 1, 9))
    worst = 0.0
    for layer_name in ("base", "shift"):
        layer = doc["layers"][layer_name]
        rows = layer["rows"]
        ids = [k["id"] for row in rows for k in row]
        assert len(ids) == len(set(ids)), f"{asset_id}/{layer_name}: duplicate ids"
        s2 = sum(k["width"] for k in rows[2])
        s3 = sum(k["width"] for k in rows[3])
        assert abs(s2 - 10.0) < 0.001, f"{asset_id}/{layer_name} Σ r2={s2}"
        assert abs(s3 - 13.0) < 0.001, f"{asset_id}/{layer_name} Σ r3={s3}"
        texts = text_keys(rows[2])
        assert len(texts) == n_letters, \
            f"{asset_id}/{layer_name}: r2 needs {n_letters} letters (has {len(texts)})"
        for k in texts:
            assert abs(k["width"] - cell) < 1e-9
        assert abs(rows[2][0]["width"] - shift) < 1e-9
        assert abs(rows[2][-1]["width"] - enter) < 1e-9
        assert not any(k.get("id") == "period" for k in rows[2])
        periods = [k for k in rows[3] if k.get("id") == "period"]
        assert len(periods) == 1, \
            f"{asset_id}/{layer_name}: exactly 1 period key in r3 (has {len(periods)})"
        assert periods[0]["kind"] == "text" and periods[0]["value"] == "."
        assert list(periods[0].get("alternates") or []) == F54
        assert abs(periods[0]["width"] - 1.0) < 1e-9
        assert not any(k.get("kind") == "text" and k.get("value") == "@" for k in rows[2])
        # variant V: the r2 letter centers sit EXACTLY on the home centers
        # (std: y..m over s..k, offset 1; cs: x..m over d..k, offset 2).
        x, home_c = 0.0, []
        for k in rows[1]:
            if k.get("kind") == "text":
                home_c.append(x + k["width"] / 2)
            x += k["width"]
        x, r2_c = 0.0, []
        for k in rows[2]:
            if k.get("kind") == "text":
                r2_c.append(x + k["width"] / 2)
            x += k["width"]
        # home row text keys: std a..l (9, s..k = 1..7), cs a..y (10, d..k = 2..7)
        assert len(home_c) == home_n and len(r2_c) == n_letters
        for i, c in enumerate(r2_c):
            delta = abs(c - home_c[i + home_off])
            worst = max(worst, delta)
            assert delta < 0.001, \
                f"{asset_id}/{layer_name}: r2 center {i} ({c:.5f}) != home center " \
                f"{i + home_off} ({home_c[i + home_off]:.5f}, Δ={delta:.6f})"
    return worst


def splice(path, doc):
    """Replace only the base/shift sections — the rest of the file stays."""
    assert "ru-ru" not in path, f"HARD GATE: {RU} is read-only (D27)"
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
        states = {}
        for layer_name in ("base", "shift"):
            rows = doc["layers"][layer_name]["rows"]
            states[layer_name] = rebuild_r2_r3(rows, is_cs)[0]
        after = char_set(doc)
        assert before == after, \
            f"{asset_id}: character set changed: -{sorted(before - after)} +{sorted(after - before)}"
        worst = check_invariants(asset_id, doc, is_cs)
        splice(path, doc)
        if is_cs:
            formula = "r2 = ⇧2.2 + 6 letters à 0.85 + ⏎2.7 (centers on d..k)"
        else:
            formula = "r2 = ⇧1.44444 + 7 letters à 0.94444 + ⏎1.94448 (centers on s..k)"
        report.append(
            f"{asset_id}: OK {states['base']}/{states['shift']} — {formula} (Σ10) | "
            f"r3 = 6 keys, space 6.5, period 1 (F54, no @) before mic (Σ13) | "
            f"center equality Δ={worst:.6f} | M keeps @"
        )
    # ru-ru: D27 exception — READ-ONLY, hard-asserted, never written.
    with open(f"{LAYOUTS}/{RU}.json", encoding="utf-8") as f:  # read-only
        ru = json.load(f)  # read-only handle; splice() hard-rejects ru-ru paths
    for layer_name in ("base", "shift"):
        rows = ru["layers"][layer_name]["rows"]
        r2, r3 = rows[2], rows[3]
        assert abs(sum(k["width"] for k in r2) - 12.0) < 1e-6, "ru-ru D27 Σ12 violated"
        assert len(text_keys(r2)) == 10, "ru-ru D27: 10 letters in r2"
        assert [k["id"] for k in r3] == \
            ["symbols", "language", "emoji", "space", "period", "mic"], \
            "ru-ru footer must keep the 6-key D27 form"
        assert abs(r3[3]["width"] - 6.5) < 1e-9, "ru-ru space stays 6.5"
        assert r3[4].get("alternates", [None])[0] == "@", "ru-ru @ on period[0]"
    report.append(f"{RU}: OK D27 untouched — Σ r2 = 12, 6-key footer, @ on period "
                  f"(read-only, write gate armed)")
    print("\n".join(report))


if __name__ == "__main__":
    sys.exit(main())
