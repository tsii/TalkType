#!/usr/bin/env python3
"""Normalizes the label of the SPACE key in all layout assets (E11).

M9b: every layer (base, symbols, symbolsShift) gets the same language-appropriate
word label from docs/SPACE_LABEL_DECISIONS.md. The script changes
EXCLUSIVELY the `label` field of keys with `value == "space"` —
no other field, no order, no new keys.

Idempotent: a second run changes nothing. --check verifies without writing
(exit code 1 on deviation). Formatting: 2-space indent, ensure_ascii=False.
"""
import json
import pathlib
import sys

ASSET_DIR = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/layouts"

# E11 values (binding, from docs/SPACE_LABEL_DECISIONS.md)
LABELS = {
    "de-de": "Leer",
    "en-us": "Space",
    "fr-fr": "Espace",
    "es-es": "Espacio",
    "it-it": "Spazio",
    "pt-pt": "Espaço",
    "nl-nl": "Spatie",
    "pl-pl": "Spacja",
    "tr-tr": "Boşluk",
    "ru-ru": "Пробел",
    "cs-cz": "Mezera",
    "sv-se": "Mellanslag",
    "da-dk": "Mellemrum",
    "fi-fi": "Väli",
    "el-gr": "Διάστημα",
}


def main() -> int:
    check_only = "--check" in sys.argv
    total_files = 0
    total_keys = 0
    for path in sorted(ASSET_DIR.glob("*.json")):
        asset_id = path.stem
        expected = LABELS.get(asset_id)
        if expected is None:
            print(f"{path.name}: no E11 specification, skipped")
            continue
        text = path.read_text(encoding="utf-8")
        data = json.loads(text)
        changed = 0
        for layer_name, layer in data.get("layers", {}).items():
            for row in layer.get("rows", []):
                for key in row:
                    if key.get("value") != "space":
                        continue
                    if key.get("label") == expected:
                        continue
                    old = key.get("label")
                    print(f"  {asset_id}/{layer_name}: value=space label {old!r} -> {expected!r}")
                    key["label"] = expected
                    changed += 1
        if changed:
            total_files += 1
            total_keys += changed
            if not check_only:
                path.write_text(
                    json.dumps(data, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8",
                )
        else:
            print(f"{path.name}: 0 changes (already normalized)")
    print(f"total: {total_files} file(s), {total_keys} key(s) changed")
    return 1 if (check_only and total_keys > 0) else 0


if __name__ == "__main__":
    sys.exit(main())
