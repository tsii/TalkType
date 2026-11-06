#!/usr/bin/env python3
"""T22e (PLAN_V0146 §6): golden fixture generator for QwenTokenizer.

HOST-ONLY documentation tool (no app code, never runs on Android). Uses the
HF 'tokenizers' library from .venv-asr to ENCODE reference texts and to DECODE
the ids with skip_special_tokens=True — the golden pairs pin EXACTLY what
asr/qwen/QwenTokenizer.detokenize(ids) must produce from raw decoder ids.

Outputs (deterministic, re-runnable):
  app/src/test/resources/qwen/tokenizer_goldens.json — >=16 pairs + metadata
  app/src/test/resources/qwen/tokenizer.json        — verbatim copy of the
      source tokenizer.json (the SAME file the model package will ship, T22b').

Source note: the HF cache snapshot models--Qwen--Qwen3-ASR-0.6B contains only
config.json / generation_config.json / preprocessor_config.json /
tokenizer_config.json — NO tokenizer.json. The Rust 'tokenizers'
serialization lives in the parallel local export
(/home/dev/models/qwen-export/Qwen3-ASR-0.6B-hf/tokenizer.json, read-only);
override with --tokenizer.
"""
import argparse
import hashlib
import json
import platform
from datetime import datetime, timezone
from pathlib import Path

import tokenizers
from tokenizers import Tokenizer

REPO = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = Path("/home/dev/models/qwen-export/Qwen3-ASR-0.6B-hf/tokenizer.json")
OUT_DIR = REPO / "app" / "src" / "test" / "resources" / "qwen"
HF_SNAPSHOT = Path(
    "/home/dev/.cache/huggingface/hub/models--Qwen--Qwen3-ASR-0.6B/snapshots/5eb144179a02acc5e5ba31e748d22b0cf3e303b0"
)

# Decode goldens only — no BPE encoding needed on the device (PLAN §6 T22e).
# Labels are part of the contract with QwenTokenizerTest.kt.
CASES = [
    ("empty", ""),
    ("ascii_words", "hello world"),
    ("ascii_sentence", "The quick brown fox jumps over the lazy dog."),
    ("single_char", "a"),
    ("uppercase", "HALLO WELT SCHREIBEN"),
    ("umlauts", "ä ö ü ß Ä Ö Ü"),
    ("german_sentence", "Größe für Straße und Maß müsste flüssig sein."),
    ("euro", "Das kostet 12,50 €."),
    ("euro_repeated", "€ Eurozeichen € mitten im Text €"),
    ("leading_spaces", "   führende Leerzeichen"),
    ("multiple_spaces", "a  b   c    d"),
    ("trailing_spaces", "nachlauf  "),
    ("newline", "Zeile eins\nZeile zwei"),
    ("paragraphs", "\n\nErster Absatz.\n\nZweiter Absatz."),
    ("digits_punct", "17.3 mm, 42%; x=y+z (plus) [minus]"),
    ("tab", "ein\tTabulator"),
    ("emoji", "Feier 🎉 und Rakete 🚀 bunt 🎨"),
    ("special_mix", "Anfang <|im_end|> Ende"),
    ("special_only", "<|im_start|>system<|im_end|>"),
    (
        "long_sequence",
        "Die Diktation erkennt auch lange Passagen ohne Probleme: Der Nutzer "
        "spricht einfach los, und die Tastatur setzt die Wörter Satz für Satz "
        "in Text um, inklusive Zeichensetzung wie Komma, Punkt und Semikolon; "
        "Umlaute wie ä, ö, ü und das Eszett ß funktionieren ebenfalls, ebenso "
        "Ziffern wie 123 sowie Beträge in €.",
    ),
]


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--tokenizer",
        type=Path,
        default=DEFAULT_SOURCE,
        help=f"tokenizer.json source (default: {DEFAULT_SOURCE})",
    )
    args = ap.parse_args()
    source: Path = args.tokenizer
    if not source.is_file():
        raise SystemExit(f"tokenizer.json not found: {source}")

    tok = Tokenizer.from_file(str(source))

    pairs = []
    for label, text in CASES:
        ids = tok.encode(text).ids
        ref = tok.decode(ids, skip_special_tokens=True)
        pairs.append({"label": label, "ids": ids, "text": ref})

    # --- self-checks: the fixture must really pin what it claims ---
    assert len(pairs) >= 16, "need at least 16 golden pairs"
    by_label = {p["label"]: p for p in pairs}
    assert len(by_label["long_sequence"]["ids"]) >= 48, "long_sequence must be >= 48 tokens"
    for special_label in ("special_mix", "special_only"):
        p = by_label[special_label]
        assert any(i >= 151643 for i in p["ids"]), f"{special_label} must contain added/special ids"
        assert "<|" not in p["text"], f"{special_label} reference must have specials filtered"
    for byte_level_label in ("umlauts", "euro", "emoji", "leading_spaces", "newline"):
        assert by_label[byte_level_label]["ids"], f"{byte_level_label} must not be empty"
    assert by_label["empty"]["ids"] == [] and by_label["empty"]["text"] == ""

    meta = {
        "model": "Qwen/Qwen3-ASR-0.6B",
        "source_tokenizer": str(source),
        "source_tokenizer_sha256": sha256_of(source),
        "hf_cache_snapshot_files": sorted(p.name for p in HF_SNAPSHOT.iterdir()),
        "hf_cache_snapshot": str(HF_SNAPSHOT),
        "tokenizers_version": tokenizers.__version__,
        "python": platform.python_version(),
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "decode_reference": "tokenizers.Tokenizer.decode(ids, skip_special_tokens=True)",
        "scope": "decode-only goldens — no BPE encoding required on device (T22e)",
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    goldens_path = OUT_DIR / "tokenizer_goldens.json"
    goldens_path.write_text(
        json.dumps({"meta": meta, "pairs": pairs}, ensure_ascii=False, indent=1) + "\n",
        encoding="utf-8",
    )
    # verbatim copy of the tokenizer the app package will ship (T22b')
    tokenizer_out = OUT_DIR / "tokenizer.json"
    tokenizer_out.write_bytes(source.read_bytes())

    print(f"pairs: {len(pairs)}")
    print(f"long_sequence tokens: {len(by_label['long_sequence']['ids'])}")
    print(f"goldens: {goldens_path} ({goldens_path.stat().st_size} B)")
    print(f"tokenizer copy: {tokenizer_out} ({tokenizer_out.stat().st_size} B)")
    print(f"source sha256: {meta['source_tokenizer_sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
