#!/usr/bin/env python3
"""T8 (v0.1.38, Y-Reihen-Ausrichtung, Planer-Freigabe vc39): @-Taste raus aus
base/shift-r2 (Nutzer bestätigt), ⇧ schmaler, ⏎ breiter — Y mittig unter A/S,
M mittig unter K/L. Bearbeitet NUR layers.base/layers.shift Reihe 2 aller
14 Buchstaben-Assets (ru-ru unangetastet, D27-Ausnahme).

Ziel-Geometrie (Einheitsraster u, KEY_GAP_DP=0 ⇒ px-exakt: u = 108 px):
  - 13 7er-Assets (da-dk de-de el-gr en-us es-es fi-fi fr-fr it-it nl-nl
    pl-pl pt-pt sv-se tr-tr): ⇧ 0,89352 · 7 Buchstaben à 1,10185 · ⏎ 1,39352
    (px: 96,5 + 7×119 + 150,5 = 1080). Herleitung: r1-Mitte des i-ten
    Buchstabens c1(i) = 0,5 + (i+0,5)·0,94444; gefordert
    c(r2[0]) = (c1(0)+c1(1))/2 = 13/9 = 1,44444 (Y unter A/S),
    c(r2[6]) = (c1(7)+c1(8))/2 = 145/18 = 8,05556 (M unter K/L) ⇒ w = 1,10185,
    ⇧ = 0,89352, ⏎ = 1,39352.
  - cs-cz (6er x..m, kein y): @ raus, ⇧ 1,88889 + 6 à 1,03704 bleiben,
    ⏎ 1,88889 (Planer-Freigabe: ⇧=⏎ symmetrisch, Σ 10,00002 in Toleranz);
    M-Mittigkeit unter K/L im 6er-Raster arithmetisch unerreichbar
    (dokumentierte Ausnahme).
  - ru-ru: r2 = D27-Vollreihe ⇧1 + 10 à 1,0 + ⏎1 (Σ12), kein @ auf den
    Buchstaben-Ebenen — UNANGETASTET (kein @-Alternate erfunden; @ bleibt
    über die Symbolebene erreichbar).

@ bleibt erreichbar: die M-Taste (letzter r2-Buchstabe, id je Asset: m bzw.
el-gr μ) bekommt alternates ["@"] (≤ 8, kein hint) — in base UND shift.

Invarianten (assertiert je Asset): Zeichenmenge base+shift vor==nach (@ wandert
von der Direkttaste ins M-Menü), Σ r2 in Toleranz, Layer-ID-Eindeutigkeit.
Idempotent (Zweitlauf ändert nichts).
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
    """json-Zahl: ganzzahlig wo möglich, sonst auf 10 Stellen gerundet."""
    r = round(w, 10)
    return int(r) if abs(r - round(r)) < 1e-9 else r


def set_width(key, w):
    key["width"] = num(w)


def text_keys(row):
    return [k for k in row if k.get("kind") == "text"]


def char_set(doc):
    """Zeichenmenge (Direkttasten ∪ Menü-Einträge) der Buchstaben-Ebenen."""
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
    """r2 einer Buchstaben-Ebene: @ raus, Breiten nach T8, @ → M-Menü (idempotent)."""
    at = [k for k in row if k.get("kind") == "text" and k["value"] == "@"]
    assert len(at) <= 1, f"maximal 1 @-Taste in r2, gefunden {len(at)}"
    row = [k for k in row if k is not (at[0] if at else None)]
    assert row[0].get("kind") == "action" and row[0]["value"] == "shift"
    assert row[-1].get("kind") == "action" and row[-1]["value"] == "enter"
    letters = text_keys(row)
    if is_cs:
        assert len(letters) == 6, f"cs erwartet 6 Buchstaben, gefunden {len(letters)}"
        set_width(row[0], CS_SHIFT)
        for k in letters:
            set_width(k, CS_LETTER)
        set_width(row[-1], CS_ENTER)
    else:
        assert len(letters) == 7, f"erwartet 7 Buchstaben, gefunden {len(letters)}"
        set_width(row[0], SHIFT_7)
        for k in letters:
            set_width(k, LETTER_7)
        set_width(row[-1], ENTER_7)
    # @ bleibt erreichbar: M-Taste (letzter Buchstabe) bekommt @ ins Menü.
    m = letters[-1]
    assert m.get("hint") is None, f"hint auf M-Taste unerwartet: {m.get('hint')}"
    alts = list(m.get("alternates") or [])
    if "@" not in alts:
        alts.append("@")
    assert len(alts) <= 8, f"M-Taste > 8 alternates: {alts}"
    m["alternates"] = alts
    return row


def splice(path, doc):
    """Nur die Sektionen base/shift ersetzen — Rest der Datei bleibt stehen."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")

    def section_range(name):
        start = next(i for i, ln in enumerate(lines) if ln == f'    "{name}": {{')
        j = start + 1
        while lines[j] not in ("    }", "    },"):
            j += 1
        return start, j  # inklusive der schließenden Zeile

    s_b, e_b = section_range("base")
    s_s, e_s = section_range("shift")
    assert s_s == e_b + 1, "shift liegt nicht direkt hinter base"

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
    json.loads(text)  # Validierung vor dem Schreiben
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
            f"{asset_id}: Zeichenmenge geändert: -{sorted(before - after)} +{sorted(after - before)}"
        for layer_name in ("base", "shift"):
            layer = doc["layers"][layer_name]
            ids = [k["id"] for row in layer["rows"] for k in row]
            assert len(ids) == len(set(ids)), f"{asset_id}/{layer_name}: doppelte ids"
            s2 = sum(k["width"] for k in layer["rows"][2])
            assert abs(s2 - 10.0) < 0.001, f"{asset_id}/{layer_name} Σ r2={s2}"
        splice(path, doc)
        group = "6er (cs)" if is_cs else "7er"
        report.append(
            f"{asset_id}: OK {group} — @ raus, ⇧/{'1.88889' if is_cs else '0.89352'}, "
            f"⏎ {'1.88889' if is_cs else '1.39352'}, @→M-Menü (base+shift)"
        )
    # ru-ru: D27-Ausnahme — UNANGETASTET; nur prüfen, nicht schreiben.
    with open(f"{LAYOUTS}/{RU}.json", encoding="utf-8") as f:
        ru = json.load(f)
    for layer_name in ("base", "shift"):
        r2 = ru["layers"][layer_name]["rows"][2]
        assert not any(k.get("value") == "@" for k in r2), "ru-ru plötzlich mit @ in r2?"
        assert abs(sum(k["width"] for k in r2) - 12.0) < 1e-6, "ru-ru D27-Σ12 verletzt"
        m = [k for k in r2 if k.get("kind") == "text"][-1]
        assert "@" not in (m.get("alternates") or []), "ru-ru: @-Alternate erfunden?"
    report.append(f"{RU}: OK D27-Ausnahme unangetastet (kein @, Σ r2 = 12)")
    print("\n".join(report))


if __name__ == "__main__":
    sys.exit(main())
