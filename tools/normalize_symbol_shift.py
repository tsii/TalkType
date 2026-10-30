#!/usr/bin/env python3
"""Normalisiert die Shift-Taste in den Symbolebenen aller Layout-Assets.

P1-03/M8c: In den Ebenen `symbols` und `symbolsShift` muss die Shift-Taste
bei `rows[2][0]` (id "shift") exakt `kind:"action"`, `value:"shift"`,
`label:"⇧"` haben (LAYOUT_REQUIREMENTS Regel 4a). In `symbolsShift` ist sie
der Rueckweg nach `symbols`. Zusaetzlich bleibt `id:"base"`/`ABC`/`layer-base`
in Reihe 3 unberuehrt (direkter Weg zu den Buchstaben).

Idempotent: veraendert NUR die Felder kind/label/value dieser einen Taste
pro Ebene; alle anderen Felder, Tasten, Reihenfolge und Formatierung
(2-Space-Indent, ensure_ascii=False) bleiben erhalten.
"""
import json
import pathlib
import sys

ASSET_DIR = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/layouts"
TARGET_LAYERS = ("symbols", "symbolsShift")
SHIFT_LABEL = "⇧"


def normalize_layer_keys(rows, layer_name) -> int:
    changed = 0
    key = None
    if len(rows) > 2 and rows[2] and rows[2][0].get("id") == "shift":
        key = rows[2][0]
    else:
        # Fallback: jede Taste mit id "shift" in dieser Ebene
        for row in rows:
            for k in row:
                if k.get("id") == "shift":
                    key = k
    if key is None:
        return 0
    if key.get("kind") == "action" and key.get("value") == "shift" and key.get("label") == SHIFT_LABEL:
        return 0
    old = f"label={key.get('label')!r}, value={key.get('value')!r}, kind={key.get('kind')!r}"
    key["kind"] = "action"
    key["value"] = "shift"
    key["label"] = SHIFT_LABEL
    changed += 1
    print(f"  {layer_name}: id=shift -> label={SHIFT_LABEL!r}, kind=action, value=shift (war {old})")
    return changed


def main() -> int:
    total_files = 0
    total_keys = 0
    for path in sorted(ASSET_DIR.glob("*.json")):
        text = path.read_text(encoding="utf-8")
        data = json.loads(text)
        layers = data.get("layers", {})
        changed = 0
        print(f"{path.name}:")
        for name in TARGET_LAYERS:
            layer = layers.get(name)
            if not layer:
                continue
            changed += normalize_layer_keys(layer["rows"], name)
        if changed:
            path.write_text(
                json.dumps(data, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            total_files += 1
        else:
            print("  bereits normalisiert (0 Aenderungen)")
        total_keys += changed
    print(f"Gesamt: {total_files} Datei(en), {total_keys} Taste(n) geaendert")
    return 0


if __name__ == "__main__":
    sys.exit(main())
