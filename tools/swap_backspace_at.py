#!/usr/bin/env python3
"""F40 — ⌫/@ place swap in all layouts with @ at the r1 end and ⌫ in r2.

Usage: python3 tools/swap_backspace_at.py snapshot | swap | check
(baseline in /tmp/vk_swap_baseline.json; NO commit, NO Gradle.)

Rule (user feedback v0.1.27, F40): in EVERY layout that has @ at the end of r1
(width 1.0) AND ⌫ in r2 (0.889, between m and ⏎), the places swap:
  r1 ends with backspace 1.0 (action) instead of at 1.0;
  r2 gets at 0.88889 (text, value "@") between the last letter and ⏎.
Widths/sums stay unchanged (r1: 1.0<->1.0, r2: 0.889<->0.889).
ru-ru: NO swap (r1 has no @, ⌫ only on the symbol layers) — verified only.
The inventory per layer (id/kind/value, without margins) must match the baseline
(no key lost, none invented).
"""
import json
import sys

BASE = "app/src/main/assets/layouts"
BL = "/tmp/vk_swap_baseline.json"
TOL = 1e-3
W_AT_R2 = 0.88889
W_BS_R1 = 1.0
ALL = ["cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
       "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr"]


def texts(row):
    return [k for k in row if k.get("kind") == "text"]


def acts(row, v):
    return [k for k in row if k.get("kind") == "action" and k.get("value") == v]


def inventory(asset):
    a = json.load(open(f"{BASE}/{asset}.json"))
    out = {}
    for layer in ("base", "shift"):
        rows = a["layers"][layer]["rows"]
        out[layer] = sorted([k["id"], k.get("kind"), k.get("value")]
                            for r in rows for k in r if k.get("kind") != "margin")
    return out


def snapshot():
    json.dump({i: inventory(i) for i in ALL}, open(BL, "w"))
    print(f"BASELINE saved: {len(ALL)} assets ({BL})")


def swap_asset(aid):
    path = f"{BASE}/{aid}.json"
    a = json.load(open(path))
    for layer in ("base", "shift"):
        rows = a["layers"][layer]["rows"]
        r1, r2 = rows[1], rows[2]
        # actual-state validation before the swap (guessing nothing):
        at_keys = [k for k in r1 if k.get("kind") == "text" and k.get("value") == "@"]
        assert len(at_keys) == 1 and r1[-1] is at_keys[0] and abs(at_keys[0]["width"] - 1.0) < 1e-9, \
            f"{aid}/{layer}: @ must be the last r1 key with width 1.0 (actual {len(at_keys)}x)"
        bs = acts(r2, "backspace")
        assert len(bs) == 1 and abs(bs[0]["width"] - W_AT_R2) < TOL, \
            f"{aid}/{layer}: r2 needs exactly 1 backspace 0.889 (actual {len(bs)})"
        i_bs = r2.index(bs[0])
        letters = texts(r2)
        assert i_bs == r2.index(letters[-1]) + 1 and r2[i_bs + 1].get("value") == "enter", \
            f"{aid}/{layer}: ⌫ must sit between the last letter and ⏎"
        # swap (adopt the widths of the target places):
        at = at_keys[0]
        at["width"] = W_AT_R2
        bs[0]["width"] = W_BS_R1
        rows[1] = r1[:-1] + [bs[0]]
        rows[2] = r2[:i_bs] + [at] + r2[i_bs + 1:]
    with open(path, "w") as f:
        json.dump(a, f, indent=2, ensure_ascii=False)
        f.write("\n")


def check_r2_at_before_enter(r2, ctx):
    ats = [k for k in r2 if k.get("kind") == "text" and k.get("value") == "@"]
    assert len(ats) == 1 and ats[0].get("id") == "at" and abs(ats[0]["width"] - W_AT_R2) <= TOL, \
        f"{ctx}: @ expected exactly 1x in r2 (id 'at', 0.889)"
    letters = [k for k in r2 if k.get("kind") == "text" and k.get("value") != "@"]
    i_at = r2.index(ats[0])
    assert i_at == r2.index(letters[-1]) + 1 and r2[i_at + 1].get("value") == "enter", \
        f"{ctx}: @ does not sit between the last letter and ⏎"
    assert not acts(r2, "backspace"), f"{ctx}: r2 must no longer carry a ⌫ (F40)"


def swap(ids):
    for i in ids:
        if i == "ru-ru":
            print("SKIP ru-ru (no @ in r1, ⌫ only on symbol layers — NO swap, check only)")
            continue
        swap_asset(i)
        print(f"SWAPPED {i}")


def check(ids):
    bl = json.load(open(BL))
    for i in ids:
        a = json.load(open(f"{BASE}/{i}.json"))
        is_ru = i == "ru-ru"
        for layer in ("base", "shift"):
            rows = a["layers"][layer]["rows"]
            ctx = f"{i}/{layer}"
            sums = [round(sum(k["width"] for k in r), 5) for r in rows[:4]]
            exp = [10.0, 10.0, 12.0, 13.0] if is_ru else [10.0, 10.0, 10.0, 13.0]
            for s, e in zip(sums, exp):
                assert abs(s - e) <= TOL, f"{ctx}: sum {sums} != {exp} (F40 must not change Σ)"
            r1, r2 = rows[1], rows[2]
            assert not acts(rows[0], "backspace"), f"{ctx}: r0 without ⌫"
            if is_ru:
                assert not [k for k in r1 if k.get("value") == "@"] and not acts(r2, "backspace"), \
                    f"{ctx}: ru-ru stays without swap (D27)"
            else:
                bs1 = acts(r1, "backspace")
                assert len(bs1) == 1 and r1[-1] is bs1[0] and abs(bs1[0]["width"] - W_BS_R1) < 1e-9, \
                    f"{ctx}: ⌫ expected exactly 1x at the r1 END (width 1.0)"
                check_r2_at_before_enter(r2, ctx)
            inv = sorted([k["id"], k.get("kind"), k.get("value")]
                         for r in rows for k in r if k.get("kind") != "margin")
            assert inv == bl[i][layer], f"{ctx}: key lost/added vs. baseline"
            # r2 block center unchanged (the letter block ends before @/⏎; @ does NOT count):
            tl = [k for k in r2 if k.get("kind") == "text" and k.get("value") != "@"]
            center = r2[0]["width"] + sum(k["width"] for k in tl) / 2
            want = 6.0 if is_ru else 5.0
            assert abs(center - want) <= 0.01, f"{ctx}: block center {center} != {want}u"
        print(f"CHECK OK {i}: {'RU special case (no swap)' if is_ru else '⌫ at the r1 end, @ in r2 before ⏎'} | Σ={'/'.join(str(s) for s in sums)}")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    ids = sys.argv[2].split(",") if len(sys.argv) > 2 else []
    if mode == "snapshot":
        snapshot()
    elif mode == "swap":
        swap(ids)
    elif mode == "check":
        check(ids)
    else:
        print("usage: swap_backspace_at.py snapshot | swap <ids> | check <ids>")
        sys.exit(1)


if __name__ == "__main__":
    main()
