#!/usr/bin/env python3
"""F36/V2 — asset migration: base/shift r0-r2 onto the 10u grid (1u = 108 px).

Usage: python3 tools/migrate_v2_layouts.py snapshot | migrate <ids> | check <ids>
(ids = comma list; baseline lives in /tmp/vk_v2_baseline.json, NO commit.)

Rules (same values everywhere, docs/DESIGN_TASTATUR_V2.md):
  Standard (12 assets): r0 = 10 letters à 1.0 WITHOUT backspace;
    r1 = margin 0.5 + 9 letters à 0.94444 (102 px) + @ 1.0;
    r2 = shift 1.88889 (204 px) + 7 letters à 0.88889 (96 px)
         + backspace 0.88889 (NEW, between the last letter and enter) + enter 1.0.
  cs-cz: r1 = margin 0.5 + 10 letters à 0.85 (10 share the same 8.5u block);
         r2 = shift 1.88889 + 6 letters à 1.03704 (112 px) + backspace + enter.
  ru-ru: r0/r1 = margin 0.11111 (12 px) per side + 11 letters à 0.88889 (96 px,
         spec §6); r2 stays the D27 full row (Σ12, unchanged, no backspace).
  Footer r3 UNCHANGED (Σ13). Symbol layers untouched.
Before every mutation the actual structure is validated (guess nothing,
report loudly).
The non-margin inventory per layer must match the baseline (no key lost).
"""
import json
import sys

BASE = "app/src/main/assets/layouts"
BL = "/tmp/vk_v2_baseline.json"
TOL = 1e-3

W = {
    "r0": 1.0,          # 108 px
    "r1_margin": 0.5,   # 54 px
    "r1_9": 0.94444,    # 102 px
    "r1_cs": 0.85,      # 10 letters, 8.5u block (derived, reported)
    "at": 1.0,          # 108 px
    "r2_shift": 1.88889,  # 204 px
    "r2_7": 0.88889,    # 96 px
    "r2_cs": 1.03704,   # 112 px
    "r2_bs": 0.88889,   # 96 px
    "enter": 1.0,       # 108 px
    "ru_letter": 0.88889,  # 96 px (spec §6)
    "ru_margin": 0.11111,  # 12 px side grid (spec §6)
}
ALL = ["cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
       "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr"]


def texts(row):
    return [k for k in row if k.get("kind") == "text"]


def acts(row, v):
    return [k for k in row if k.get("kind") == "action" and k.get("value") == v]


def margins(row):
    return [k for k in row if k.get("kind") == "margin"]


def inventory(asset):
    """Non-margin inventory per layer (id, kind, value) — must match the baseline."""
    a = json.load(open(f"{BASE}/{asset}.json"))
    out = {}
    for layer in ("base", "shift"):
        rows = a["layers"][layer]["rows"]
        out[layer] = sorted((k["id"], k.get("kind"), k.get("value"))
                            for r in rows for k in r if k.get("kind") != "margin")
    return out


def snapshot():
    bl = {i: inventory(i) for i in ALL}
    json.dump(bl, open(BL, "w"))
    print(f"BASELINE saved: {len(bl)} assets ({BL})")


def migrate_asset(aid):
    path = f"{BASE}/{aid}.json"
    a = json.load(open(path))
    is_ru = aid == "ru-ru"
    is_cs = aid == "cs-cz"
    for layer in ("base", "shift"):
        rows = a["layers"][layer]["rows"]
        r0, r1, r2 = rows[0], rows[1], rows[2]
        # ---- r0: backspace out (not ru), letters à 1.0 ----
        if is_ru:
            t = texts(r0)
            assert len(t) == 11 and not acts(r0, "backspace") and len(margins(r0)) == 2, \
                f"{aid}/{layer} r0: expected 11 letters + 2 margins, actual {len(t)}/{len(margins(r0))}"
            for k in t:
                k["width"] = W["ru_letter"]
            r0[0]["width"] = W["ru_margin"]
            r0[-1]["width"] = W["ru_margin"]
        else:
            t = texts(r0)
            bs = acts(r0, "backspace")
            assert len(t) == 10 and len(bs) == 1 and not margins(r0), \
                f"{aid}/{layer} r0: expected 10 letters + 1 backspace, actual {len(t)}/{len(bs)}"
            for k in t:
                k["width"] = W["r0"]
            rows[0] = [k for k in r0 if k not in bs]
        # ---- r1: margin 0.5 + letter block + @ 1.0 (fr-fr actual: 9 letters -> standard) ----
        if is_ru:
            t = texts(r1)
            assert len(t) == 11 and not any(k.get("value") == "@" for k in t) \
                and len(margins(r1)) == 2, f"{aid}/{layer} r1: 11-key structure expected"
            for k in t:
                k["width"] = W["ru_letter"]
            r1[0]["width"] = W["ru_margin"]
            r1[-1]["width"] = W["ru_margin"]
        else:
            at = [k for k in r1 if k.get("kind") == "text" and k.get("value") == "@"]
            t = [k for k in r1 if k.get("kind") == "text" and k.get("value") != "@"]
            assert r1[0].get("kind") == "margin" and len(at) == 1 and len(margins(r1)) == 2, \
                f"{aid}/{layer} r1: expected margin+letters+@+margin, actual margins={len(margins(r1))} at={len(at)}"
            if is_cs:
                assert len(t) == 10, f"{aid}/{layer} r1: cs expects 10 letters, actual {len(t)}"
                w = W["r1_cs"]
            else:
                assert len(t) == 9, f"{aid}/{layer} r1: expected 9 letters, actual {len(t)} (REPORT)"
                w = W["r1_9"]
            for k in t:
                k["width"] = w
            at[0]["width"] = W["at"]
            rows[1] = [r1[0]] + t + at
        # ---- r2: shift + letters + backspace (NEW) + enter ----
        if is_ru:
            assert abs(sum(k["width"] for k in r2) - 12.0) < 1e-9 and len(texts(r2)) == 10 \
                and not acts(r2, "backspace"), f"{aid}/{layer} r2: D27 full row expected"
            continue  # spec §6: r2 unchanged (D27)
        sh = r2[0]
        ent = acts(r2, "enter")
        mr2 = margins(r2)
        t = texts(r2)
        assert sh.get("kind") == "action" and sh.get("value") == "shift" \
            and len(ent) == 1 and len(mr2) == 1 and not acts(r2, "backspace"), \
            f"{aid}/{layer} r2: expected shift+letters+enter+1 margin, actual shift={sh.get('value')} enter={len(ent)} margins={len(mr2)}"
        if is_cs:
            assert len(t) == 6, f"{aid}/{layer} r2: cs expects 6 letters, actual {len(t)}"
            lw = W["r2_cs"]
        else:
            assert len(t) == 7, f"{aid}/{layer} r2: expected 7 letters, actual {len(t)}"
            lw = W["r2_7"]
        for k in t:
            k["width"] = lw
        sh["width"] = W["r2_shift"]
        ent[0]["width"] = W["enter"]
        bs = {"id": "backspace", "label": "⌫", "width": W["r2_bs"],
              "kind": "action", "value": "backspace"}
        rows[2] = [sh] + t + [bs, ent[0]]
    with open(path, "w") as f:
        json.dump(a, f, indent=2, ensure_ascii=False)
        f.write("\n")


def migrate(ids):
    for i in ids:
        migrate_asset(i)
        print(f"MIGRATED {i}")


def check(ids):
    bl = json.load(open(BL))
    for i in ids:
        a = json.load(open(f"{BASE}/{i}.json"))
        is_ru = i == "ru-ru"
        is_cs = i == "cs-cz"
        lines = []
        for layer in ("base", "shift"):
            rows = a["layers"][layer]["rows"]
            sums = [round(sum(k["width"] for k in r), 5) for r in rows[:4]]
            exp = [10.0, 10.0, 12.0, 13.0] if is_ru else [10.0, 10.0, 10.0, 13.0]
            for s, e in zip(sums, exp):
                assert abs(s - e) <= TOL, f"{i}/{layer}: sum {sums} != {exp} (Σ10-F36/V2)"
            assert not acts(rows[0], "backspace"), f"{i}/{layer}: r0 must not carry a backspace"
            bs2 = len(acts(rows[2], "backspace"))
            assert bs2 == (0 if is_ru else 1), f"{i}/{layer}: r2 backspace={bs2}"
            assert len(acts(rows[2], "enter")) == 1, f"{i}/{layer}: r2 enter not 1x"
            r2 = rows[2]
            tl = texts(r2)
            center = r2[0]["width"] + sum(k["width"] for k in tl) / 2
            want = 6.0 if is_ru else 5.0
            assert abs(center - want) <= 0.01, f"{i}/{layer}: block center {center} != {want}u"
            inv = sorted([k["id"], k.get("kind"), k.get("value")]
                         for r in rows for k in r if k.get("kind") != "margin")
            assert inv == bl[i][layer], f"{i}/{layer}: key lost/added vs. baseline"
            lines.append(f"{layer}:Σ={sums[0]}/{sums[1]}/{sums[2]}/{sums[3]}")
        print(f"CHECK OK {i}: {lines[0]} | {lines[1]} | r2 center={'6u' if is_ru else '5u'}"
              f"{' cs special case' if is_cs else ''}{' ru special case' if is_ru else ''}")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    ids = sys.argv[2].split(",") if len(sys.argv) > 2 else []
    if mode == "snapshot":
        snapshot()
    elif mode == "migrate":
        migrate(ids)
    elif mode == "check":
        check(ids)
    else:
        print("usage: migrate_v2_layouts.py snapshot | migrate <ids> | check <ids>")
        sys.exit(1)


if __name__ == "__main__":
    main()
