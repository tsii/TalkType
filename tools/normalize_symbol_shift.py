#!/usr/bin/env python3
"""Normalizes the shift key in the symbol layers of all layout assets.

P1-03/M8c: in the layers `symbols` and `symbolsShift` the shift key must have
exactly `kind:"action"`, `value:"shift"`, `label:"⇧"` at `rows[2][0]`
(id "shift") (LAYOUT_REQUIREMENTS rule 4a). In `symbolsShift` it is the
way back to `symbols`. Additionally `id:"base"`/`ABC`/`layer-base` stays
untouched in row 3 (the direct way to the letters).

Idempotent: changes ONLY the fields kind/label/value of this single key
per layer; all other fields, keys, order and formatting
(2-space indent, ensure_ascii=False) stay intact.
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
        # fallback: any key with id "shift" in this layer
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
    print(f"  {layer_name}: id=shift -> label={SHIFT_LABEL!r}, kind=action, value=shift (was {old})")
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
            print("  already normalized (0 changes)")
        total_keys += changed
    print(f"total: {total_files} file(s), {total_keys} key(s) changed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
