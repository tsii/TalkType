#!/usr/bin/env python3
"""T11 (docs/PLAN_T11_T12.md §2, decision D1=VARIANT B): base/shift r2 boundary
grid + footer slimming in the 14 letter assets (13 std + cs-cz; ru-ru is
D27-locked and read-only — hard-asserted below).

  - 13 seven-key assets (da-dk de-de el-gr en-us es-es fi-fi fr-fr it-it
    nl-nl pl-pl pt-pt sv-se tr-tr): r2 = ⇧ 1.22224 + 8 cells à 0.94444
    (7 letters + NEW period key, slot 8 before ⏎) + ⏎ 1.22224 (Σ 10; px:
    132.00 + 8×102.00 + 132.00). Variant B (symmetric): ⇧ = ⏎, the center
    chain c(r2) is symmetric about 5.0 (every center pair sums to 10).
  - cs-cz (6-key x..m, B'): r2 = ⇧ 2.02500 + 7 cells à 0.85 (6 letters +
    period) + ⏎ 2.02500 (Σ 10; px: 218.7 + 7×91.8 + 218.7).
  - period key (slot before ⏎, base AND shift): id 'period', kind text,
    value '.', width = cell width; alternates = the EXACT cells of the
    previous footer period (F54 standard , ! ? : ; ( ) — copied, not
    invented). The M key (last letter, id per asset: m resp. el-gr μ) KEEPS
    its @ alternate (T8).
  - footer r3 (14 assets): period OUT, space 6.5 -> 7.5 (Σ stays 13,
    u_f = 1080/13); 5 keys: symbols 1.5, language 1, emoji 1, space 7.5,
    mic 2 (mic last).
  - ru-ru: COMPLETELY untouched (D27: r2 ⇧1 + 10 at 1.0 + ⏎1 = Σ12, 6-key
    footer with period carrying @ as alternates[0]). The script opens ru-ru
    READ-ONLY and asserts its D27 state; the write helper hard-rejects
    ru-ru paths.

Invariants (asserted per asset, HARD):
  - character set base+shift before == after (the period including its F54
    menu only MOVES from r3 to r2 — nothing lost, nothing invented);
  - Σ r2 = 10 ± 0.001, Σ r3 = 13 ± 0.001;
  - center chain of the FULL r2 symmetric about 5.0 (variant B): every
    center pair c[i] + c[n-1-i] = 10 ± 0.001;
  - layer-wide unique ids; period exactly 1x per layer (in r2);
  - M key keeps @; no @ as direct key in r2.

Idempotent: a second run detects period already in r2 and re-applies the
widths as a no-op; the footer is already 5-key and space stays 7.5.

Format style: like rebuild_letter_rows.py (T8) — 2-space indent, one key per
line; only the base/shift sections are replaced (splice), the rest of the
file (incl. the T12 symbol layers) stays byte-identical.
"""
import json
import sys

LAYOUTS = "app/src/main/assets/layouts"
SEVEN = ["da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr", "it-it",
         "nl-nl", "pl-pl", "pt-pt", "sv-se", "tr-tr"]
CS = "cs-cz"
RU = "ru-ru"
F54 = [",", "!", "?", ":", ";", "(", ")"]

STD_CELL, STD_EDGE = 0.94444, 1.22224   # 13 seven-key assets (variant B)
CS_CELL, CS_EDGE = 0.85, 2.025           # cs-cz (variant B')


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
    """r2 boundary grid (variant B) + footer without period. Returns state."""
    r2, r3 = rows[2], rows[3]
    assert r2[0].get("kind") == "action" and r2[0]["value"] == "shift"
    assert r2[-1].get("kind") == "action" and r2[-1]["value"] == "enter"
    cell, edge, n_letters = (CS_CELL, CS_EDGE, 6) if is_cs else (STD_CELL, STD_EDGE, 7)

    # period key: already in r2 (idempotent) or taken from the footer (exact cells).
    in_r2 = [k for k in r2 if k.get("id") == "period" and k.get("kind") == "text"]
    if in_r2:
        assert len(in_r2) == 1, "at most 1 period key in r2"
        period = in_r2[0]
        assert not any(k.get("id") == "period" for k in r3), "period in r2 AND r3?"
        state = "already-migrated"
    else:
        in_r3 = [k for k in r3 if k.get("id") == "period" and k.get("kind") == "text"]
        assert len(in_r3) == 1, "footer must carry exactly 1 period key"
        period = in_r3[0]
        r3.remove(period)
        r2.insert(len(r2) - 1, period)  # slot before ⏎
        state = "migrated"
    assert period["value"] == "." and period["label"] == "."
    assert list(period.get("alternates") or []) == F54, \
        f"period menu must be the F54 standard {F54} (is {period.get('alternates')})"
    assert not period.get("hint"), "no hint on the period key"

    # widths: ⇧ + cells (letters + period) + ⏎ — variant B (symmetric edges).
    letters = [k for k in text_keys(r2) if k is not period]
    assert len(letters) == n_letters, \
        f"expected {n_letters} letters, found {len(letters)}"
    set_width(r2[0], edge)
    for k in letters + [period]:
        set_width(k, cell)
    set_width(r2[-1], edge)

    # M key (last letter) keeps its @ alternate (T8).
    m = letters[-1]
    assert "@" in (m.get("alternates") or []), "M key must keep @ (T8)"

    # footer: space 6.5 -> 7.5, 5 keys, mic last, no period.
    by_id = {k["id"]: k for k in r3}
    assert [k["id"] for k in r3] == ["symbols", "language", "emoji", "space", "mic"], \
        f"footer ids: {[k['id'] for k in r3]}"
    set_width(by_id["space"], 7.5)
    for kid, w in (("symbols", 1.5), ("language", 1), ("emoji", 1), ("mic", 2)):
        assert abs(by_id[kid]["width"] - w) < 1e-9, f"footer {kid} width {w}"
    assert r3[-1]["id"] == "mic"
    return state, r2


def check_invariants(asset_id, doc, is_cs):
    """Σ + symmetry (variant B) per asset/layer — returns the symmetry delta."""
    edge, n_cells = (CS_EDGE, 7) if is_cs else (STD_EDGE, 8)
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
        assert len(texts) == n_cells, \
            f"{asset_id}/{layer_name}: r2 needs {n_cells} text cells (has {len(texts)})"
        for k in texts:
            assert abs(k["width"] - (CS_CELL if is_cs else STD_CELL)) < 1e-9
        assert abs(rows[2][0]["width"] - edge) < 1e-9
        assert abs(rows[2][-1]["width"] - edge) < 1e-9
        assert sum(1 for k in rows[2] if k.get("id") == "period") == 1
        assert not any(k.get("id") == "period" for k in rows[3])
        assert not any(k.get("kind") == "text" and k.get("value") == "@" for k in rows[2])
        # variant B: the FULL r2 center chain is symmetric about 5.0.
        x, centers = 0.0, []
        for k in rows[2]:
            centers.append(x + k["width"] / 2)
            x += k["width"]
        for i in range(len(centers)):
            delta = abs(centers[i] + centers[-1 - i] - 10.0)
            worst = max(worst, delta)
        assert worst < 0.001, \
            f"{asset_id}/{layer_name}: center chain not symmetric about 5.0 (Δ={worst})"
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
        cell, edge = (CS_CELL, CS_EDGE) if is_cs else (STD_CELL, STD_EDGE)
        n = 7 if is_cs else 8
        report.append(
            f"{asset_id}: OK {states['base']}/{states['shift']} — r2 = ⇧{edge} + "
            f"{n - 1} letters + period à {cell} + ⏎{edge} (Σ10) | r3 = 5 keys, "
            f"space 7.5, no period (Σ13) | symmetry about 5.0 Δ={worst:.6f} | M keeps @"
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
