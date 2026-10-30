#!/usr/bin/env python3
"""Messreihe fuer `min_audio_s` mit dem KORRIGIERTEN Referenzdecoder.

Aufruf:  .venv-asr/bin/python tools/measure_min_audio.py [--json OUT] [--markdown]

Vergleicht jedes Fixture aus `testdata/cut/` (erzeugt von `tools/make_cut_windows.py`)
gegen den Gesamtkontext-Text der jeweiligen Quelldatei und gibt eine
Markdown-Tabelle aus, die direkt in `docs/ASR_CONTRACT.md` §4.4 verwendet wird.

Kennzahlen je Fenster
---------------------
`tok`        Anzahl emittierter Tokens (ohne Sondertokens, ohne <blk>)
`tok/s`      Emissionsrate — relevant fuer das Token-Budget (§6)
`Praefix`    Anzahl fuehrender Token-IDs, die exakt mit dem Gesamtkontext
             uebereinstimmen (Kopf-Fenster: "sauberer Anfang" vs. Muell)
`Aehnl.`     Zeichenaehnlichkeit = 1 - Levenshtein(text, gesamt) / max(len)
             1,00 = identisch; bei Kopf-Fenstern ist der Erwartungswert < 1,
             weil nur ein Teil des Satzes im Fenster liegt
`im Text`    Anteil der Fensterzeichen, die als zusammenhaengender Block auch im
             Gesamttext vorkommen (difflib-Blöcke). Fuer Fenster, die mitten im
             Material beginnen, ist das das aussagekraeftige Mass: 1,00 = das
             Fenster liefert nur Woerter aus dem Gesamttext, kein Muell.
             Leerer Text wird als `— (leer)` ausgewiesen (Totalausfall, nicht 1,00).
`exakt`      Token-IDs des Fensters sind ein exaktes Praefix der Gesamt-IDs
"""
from __future__ import annotations

import argparse
import json
import sys
from difflib import SequenceMatcher
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import reference_decode as rd  # Pfad-abhaengiger Import (nach sys.path.insert)

CUT = ROOT / "testdata" / "cut"
FULL = {"de_smoke": ROOT / "testdata" / "de_smoke.wav",
        "de_pause": ROOT / "testdata" / "de_pause.wav"}


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a or not b:
        return len(a) or len(b)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def similarity(a: str, b: str) -> float:
    m = max(len(a), len(b))
    return 1.0 if m == 0 else 1.0 - levenshtein(a, b) / m


def in_text_frac(part: str, full: str) -> float:
    if not part:
        return 1.0
    sm = SequenceMatcher(None, part, full, autojunk=False)
    matched = sum(bl.size for bl in sm.get_matching_blocks())
    return matched / len(part)


def common_prefix(a: list[int], b: list[int]) -> int:
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--models", default=str(ROOT / ".models"))
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--json", default=str(CUT / "min_audio_measurements.json"))
    p.add_argument("--markdown", action="store_true", help="nur Markdown-Tabellen")
    args = p.parse_args(argv)

    engine = rd.TdtReference(Path(args.models), args.threads)

    full = {}
    for name, path in FULL.items():
        audio = rd.load_waveform(path)
        res = engine.run(audio)
        full[name] = {"seconds": audio.shape[0] / 16000.0, "text": res["text"],
                      "tokens": res["tokens"]}

    manifest = json.loads((CUT / "manifest.json").read_text(encoding="utf-8"))
    rows = []
    for item in manifest["items"]:
        path = CUT / item["file"]
        audio = rd.load_waveform(path)
        res = engine.run(audio)
        secs = audio.shape[0] / 16000.0
        row = {
            "file": item["file"], "kind": item["kind"], "source": item["source"],
            "offset_s": item["offset_s"], "seconds": round(secs, 3),
            "tokens": len(res["tokens"]), "tokens_per_s": round(len(res["tokens"]) / secs, 2),
            "text": res["text"], "token_ids": res["tokens"],
            "steps": res["steps"], "frames": res["frames"],
            "frame_seconds": round(res["frames"] * 0.08, 2),
            "truncated": res["truncated"],
        }
        if item["kind"] in ("head", "mid"):
            ref = full[item["source"]]
            row["prefix_tokens"] = common_prefix(res["tokens"], ref["tokens"])
            row["exact_prefix"] = res["tokens"] == ref["tokens"][:len(res["tokens"])] and bool(res["tokens"])
            row["similarity"] = round(similarity(res["text"], ref["text"]), 3)
            row["in_text"] = round(in_text_frac(res["text"], ref["text"]), 3)
            row["empty"] = not res["text"].strip()
            row["hallucination"] = None
        else:
            # Stille/Rauschen: jeder Text ist per Definition erfunden.
            row["hallucination"] = bool(res["text"].strip())
            row["similarity"] = None
            row["in_text"] = None
        rows.append(row)

    out = {"models": str(args.models), "decoder": "tools/reference_decode.py (korrigiert, Rev 2)",
           "full_context": {k: {"seconds": round(v["seconds"], 3), "text": v["text"],
                                "tokens": len(v["tokens"])} for k, v in full.items()},
           "rows": rows}
    Path(args.json).write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.markdown:
        print("#### Gesamtkontext (Referenz)")
        print()
        print("| Datei | Dauer | Tokens | Text |")
        print("|---|---|---|---|")
        for k, v in full.items():
            print(f"| `{k}.wav` | {v['seconds']:.2f} s | {len(v['tokens'])} | `{v['text']}` |")
        print()
        print("#### Kopffenster (Schnitt ab 0 s)")
        print()
        print("| Fenster | Dauer | Tokens | tok/s | Präfix-Tokens | exakt | Ähnl. | im Text | Text |")
        print("|---|---|---|---|---|---|---|---|---|")
        for r in rows:
            if r["kind"] != "head":
                continue
            print(f"| `{r['file']}` | {r['seconds']:.1f} s | {r['tokens']} | {r['tokens_per_s']} | "
                  f"{r['prefix_tokens']}/{len(full[r['source']]['tokens'])} | "
                  f"{'ja' if r['exact_prefix'] else 'nein'} | {r['similarity']:.2f} | "
                  f"{'— (leer)' if r['empty'] else format(r['in_text'], '.2f')} | `{r['text']}` |")
        print()
        print("#### Fenster mitten im Material")
        print()
        print("| Fenster | Dauer | Tokens | tok/s | Präfix | exakt | Ähnl. | im Text | Text |")
        print("|---|---|---|---|---|---|---|---|---|")
        for r in rows:
            if r["kind"] != "mid":
                continue
            print(f"| `{r['file']}` | {r['seconds']:.1f} s | {r['tokens']} | {r['tokens_per_s']} | "
                  f"{r['prefix_tokens']} | {'ja' if r['exact_prefix'] else 'nein'} | "
                  f"{r['similarity']:.2f} | {'— (leer)' if r['empty'] else format(r['in_text'], '.2f')} | "
                  f"`{r['text']}` |")
        print()
        print("#### Stille / Rauschen (Halluzinationstest)")
        print()
        print("| Probe | Dauer | frames | Tokens | Halluzination | Text |")
        print("|---|---|---|---|---|---|")
        for r in rows:
            if r["kind"] not in ("silence", "noise"):
                continue
            print(f"| `{r['file']}` | {r['seconds']:.1f} s | {r['frames']} | {r['tokens']} | "
                  f"{'JA' if r['hallucination'] else 'nein'} | `{r['text']}` |")
    else:
        for r in rows:
            extra = ""
            if r["kind"] in ("head", "mid"):
                extra = (f" prefix={r['prefix_tokens']} exact={r['exact_prefix']} "
                         f"sim={r['similarity']:.2f} intext={r['in_text']:.2f}")
            else:
                extra = f" hallucination={r['hallucination']}"
            print(f"{r['file']:<26} {r['seconds']:>5.2f}s tokens={r['tokens']:>2} "
                  f"{r['tokens_per_s']:>4.2f}/s{extra}  {r['text']!r}")
    print(f"\n# JSON: {args.json}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
