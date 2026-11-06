#!/usr/bin/env python3
"""U2-GATE: deterministic synthetic typo goldens for the spelling-correction
parity test (20 sentences per language).

Own everyday sentences (keyboard/dictation context, NO personal data), one
deterministic synthetic typo per sentence, seeded (random.Random(42)) — same
style as testdata/typing_goldens. Output: testdata/bart_goldens/{de,en}.tsv
with columns: typo_input <TAB> clean_reference <TAB> class.

Typo classes (de: neighbour/transposition/missing/doubled/umlaut/spacing,
en: neighbour/transposition/missing/doubled/caps/spacing) — neighbour keys
follow the physical QWERTZ (de) / QWERTY (en) layout.

Usage: .venv-asr/bin/python tools/bart_ref/gen_bart_goldens.py
"""
import random
from pathlib import Path

SEED = 42

DE_CLEAN = [
    "ich gehe morgen zum Arzt",
    "das Wetter wird heute besser",
    "wir treffen uns am Bahnhof",
    "der Zug kommt um acht Uhr",
    "ich brauche Hilfe beim Umzug",
    "am Freitag fahren wir nach Berlin",
    "das Buch liegt auf dem Tisch",
    "wir haben Pizza gegessen",
    "die Katze schläft auf dem Sofa",
    "er hat die Rechnung bezahlt",
    "das Meeting beginnt um zehn Uhr",
    "wir müssen noch einkaufen gehen",
    "sie arbeitet in der Bibliothek",
    "der Kaffee ist schon kalt",
    "ich habe die E-Mail verschickt",
    "das Kind spielt im Garten",
    "wir schauen heute Abend einen Film",
    "die Tür ist noch offen",
    "er liest die Zeitung in der Küche",
    "ich rufe dich später an",
]

EN_CLEAN = [
    "i will call you tomorrow",
    "the meeting starts at ten",
    "we are going to the park",
    "she works in the library",
    "the coffee is already cold",
    "he sent the email yesterday",
    "the door is still open",
    "we ordered pizza for dinner",
    "the train leaves at eight",
    "i need help with the move",
    "the book is on the table",
    "they live near the station",
    "she reads the paper every morning",
    "we watch a movie tonight",
    "the cat sleeps on the sofa",
    "i forgot my keys at home",
    "the weather is getting better",
    "he paid the bill in cash",
    "our train arrives on time",
    "please send me the document",
]

# de distribution (20): neighbour 5, transposition 4, missing 3, doubled 3,
# umlaut 3, spacing 2   |  en: neighbour 5, transposition 4, missing 4,
# doubled 3, caps 2, spacing 2
DE_CLASSES = (["neighbour"] * 5 + ["transposition"] * 4 + ["missing"] * 3
              + ["doubled"] * 3 + ["umlaut"] * 3 + ["spacing"] * 2)
EN_CLASSES = (["neighbour"] * 5 + ["transposition"] * 4 + ["missing"] * 4
              + ["doubled"] * 3 + ["caps"] * 2 + ["spacing"] * 2)

NEIGHBOUR_DE = {  # QWERTZ
    "a": "s", "s": "d", "d": "f", "f": "g", "g": "h", "h": "j", "j": "k",
    "k": "l", "l": "k", "q": "w", "w": "e", "e": "r", "r": "t", "t": "z",
    "z": "u", "u": "i", "i": "o", "o": "p", "p": "o", "y": "x", "x": "c",
    "c": "v", "v": "b", "b": "n", "n": "m", "m": "n",
}
NEIGHBOUR_EN = {  # QWERTY
    "a": "s", "s": "d", "d": "f", "f": "g", "g": "h", "h": "j", "j": "k",
    "k": "l", "l": "k", "q": "w", "w": "e", "e": "r", "r": "t", "t": "y",
    "y": "u", "u": "i", "i": "o", "o": "p", "p": "o", "z": "x", "x": "c",
    "c": "v", "v": "b", "b": "n", "n": "m", "m": "n",
}


def apply_typo(sentence: str, cls: str, rng: random.Random, lang: str) -> str:
    words = sentence.split()
    nbr = NEIGHBOUR_DE if lang == "de" else NEIGHBOUR_EN

    def pick(pred):
        eligible = [i for i, w in enumerate(words) if pred(w)]
        if not eligible:  # fallback: longest word, drop second letter
            eligible = [max(range(len(words)), key=lambda i: len(words[i]))]
            return eligible[0], 1
        return eligible[rng.randrange(len(eligible))], None

    if cls == "neighbour":
        idx, _ = pick(lambda w: any(ch.lower() in nbr for ch in w))
        w = words[idx]
        cands = [i for i, ch in enumerate(w) if ch.lower() in nbr]
        i = cands[rng.randrange(len(cands))]
        words[idx] = w[:i] + nbr[w[i].lower()] + w[i + 1:]
    elif cls == "transposition":
        idx, forced = pick(lambda w: len(w) >= 3)
        w = words[idx]
        cands = range(1, len(w) - 1) if forced is None else [forced]
        i = cands[rng.randrange(len(list(cands)))] if forced is None else forced
        words[idx] = w[:i - 1] + w[i] + w[i - 1] + w[i + 1:]
    elif cls == "missing":
        idx, forced = pick(lambda w: len(w) >= 2)
        w = words[idx]
        i = forced if forced is not None else rng.randrange(1, len(w))
        words[idx] = w[:i] + w[i + 1:]
    elif cls == "doubled":
        idx, forced = pick(lambda w: len(w) >= 1)
        w = words[idx]
        i = forced if forced is not None else rng.randrange(len(w))
        words[idx] = w[:i] + w[i] + w[i:]
    elif cls == "umlaut":
        mapping = {"a": "ä", "o": "ö", "u": "ü"}
        idx, forced = pick(lambda w: any(ch in mapping for ch in w))
        w = words[idx]
        cands = [i for i, ch in enumerate(w) if ch in mapping]
        i = forced if forced is not None or not cands else cands[rng.randrange(len(cands))]
        words[idx] = w[:i] + mapping.get(w[i], "ä") + w[i + 1:]
    elif cls == "caps":
        idx, _ = pick(lambda w: True)
        words[idx] = words[idx].upper()
    elif cls == "spacing":
        n = len(words)
        idx, _ = pick(lambda w: True)
        if rng.random() < 0.5 and idx < n - 1:
            words[idx] = words[idx] + words[idx + 1]
            del words[idx + 1]
        else:
            words[idx] = words[idx] + words[idx]  # repeated word
    return " ".join(words)


def main():
    out_dir = Path("/home/dev/Voice-Keyboard/testdata/bart_goldens")
    out_dir.mkdir(parents=True, exist_ok=True)
    for lang, clean, classes in (("de", DE_CLEAN, DE_CLASSES),
                                  ("en", EN_CLEAN, EN_CLASSES)):
        rng = random.Random(SEED)
        lines = [f"# TalkType U2-GATE bart spelling-correction goldens — {lang}",
                 "# 20 own synthetic everyday sentences, NO personal data.",
                 f"# deterministic: gen_bart_ref gen_bart_goldens.py, seed {SEED}, one typo class per sentence",
                 "# columns: typo_input <TAB> clean_reference <TAB> class"]
        for sent, cls in zip(clean, classes):
            typo = apply_typo(sent, cls, rng, lang)
            lines.append(f"{typo}\t{sent}\t{cls}")
        path = out_dir / f"{lang}.tsv"
        path.write_text("\n".join(lines) + "\n")
        print(f"wrote {path} ({len(clean)} sentences)")


if __name__ == "__main__":
    main()
