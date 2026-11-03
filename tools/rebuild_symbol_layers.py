#!/usr/bin/env python3
"""T6 (v0.1.36) + T7-Korrektur (v0.1.37, docs/PLAN_SYMBOL_RASTER.md M-Regel):
Symbol-Raster mit stabilen Buchstaben-Slots. Migriert symbols/symbolsShift
ALLER 15 Layout-Assets auf das Einheitsraster Σ=[10,10,10,13]; T7 macht den
Lauf IDEMPOTENT (Zweitlauf auf bereits migrierten Assets) und erzwingt die
M-Regel: r2 höchstens 10 Direktzellen à 0,75 (Budget 7,5 in Σ10,000 — 11/12
Zellen à 0,75 sind arithmetisch unmöglich); Überlaufzeichen fallen und bleiben
über Bestands-Menüs erreichbar (Menü-Weitergabe, scharf geprüft).

  symbols (sprachneutral, inhaltlich identisch in allen 15):
    r0 = 10 Ziffern à 1,0 (Digit-IDs asset-lokal);
    r1 = [- / : ; ( ) & @ = #] à 0,9 + ⌫ 1,0 am ENDE (Buchstaben-Slot);
    r2 = ⇧ 1,5 + [., , ?, !, ', \", %, +, _, *] à 0,75 + ⏎ 1,0 am ENDE;
    r3 = ABC 1,5 / 🌐 1 / 😊 1 / space 7,5 / 🎤 2 (kein ⏎, keine Cursor-Zellen).

  symbolsShift (Struktur 10/10/10/13, asset-eigene Pflichtzeichen bleiben):
    r0 = erste 10 Tasten à 1,0; der r0-Schwanz (11. Taste: €/$/_/~/…) wandert
         an den r1-Blockanfang (Plan: de-de „€ r0→r1“);
    r1 = Block à 9,0/n + ⌫ 1,0 (Überlauf > 10 Tasten wandert in den r2-Block);
    r2 = ⇧ 1,5 + Block à 7,5/n + ⏎ 1,0;
    r3 = wie symbols.
    da-dk/fi-fi/el-gr: € (symbols r0, Platz 11) wandert als symbolsShift-
    Direkttaste auf den r0-Endplatz; der bisherige r0-Schwanz-Key (id „euro“,
    Label „>“) rückt in den r1-Block (id → „greater“, Kollisionsfreiheit).
    da-dk/fi-fi: ‰-Direkttaste entfällt (Plan-Abnahme; erreichbar über das
    T4-Menü percent→[‰]).

Invarianten (Skript assertiert je Asset): kein Zeichen geht verloren
(Direkttasten ∪ Menü-Einträge vor==nach), Layer-ID-Eindeutigkeit,
Σ je Reihe, genau 1× ⌫/⏎/space/mic/emoji je Ebene.

Formatstil: 2-Space-Indent, Key pro Zeile, Arrays ein Element je Zeile —
nur die beiden Layer-Sektionen werden ersetzt (Splice), der Rest der
Datei bleibt byte-identisch (nur handgepackte period-alternates-Kurzzeilen
werden ggf. vom Parser-Standard umgebrochen — kosmetisch).
"""
import json
import sys

LAYOUTS = "app/src/main/assets/layouts"
ASSETS = ["cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
          "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr"]

R1_ORDER = ["-", "/", ":", ";", "(", ")", "&", "@", "=", "#"]
R2_ORDER = [".", ",", "?", "!", "'", "\"", "%", "+", "_", "*"]
PERMILLE_DROP = {"da-dk", "fi-fi"}  # Plan-Abnahme: ‰ → T4-Menü percent→[‰]
R2_MAX_CELLS = 10  # T7-M-Regel: r2-Direktzellen à 0,75 im Budget 7,5 (Σ10,000)

# T7 (v0.1.37) Menü-Weitergabe: Zeichen, die aus einer überfüllten r2 fallen,
# MÜSSEN über ein Bestands-Menü erreichbar bleiben (vor==nach-Assert in main()
# bewacht es); Dokumentation der tatsächlichen Fälle (PLAN_SYMBOL_RASTER.md
# M-Regel): da/fi „…“ → dot-Menü [. → …], el-gr „–“ → hyphen-Menü [–, —],
# el-gr „…“ → dot-Menü.


def num(w):
    """json-Zahl: ganzzahlig wo möglich, sonst 10 Stellen (Σ bleibt exakt)."""
    r = round(w, 10)
    return int(r) if abs(r - round(r)) < 1e-9 else r


def clone(key):
    return json.loads(json.dumps(key))


def set_width(key, w):
    key["width"] = num(w)


def text_keys(row):
    return [k for k in row if k.get("kind") == "text"]


def action_by_id(rows, kid):
    for row in rows:
        for k in row:
            if k.get("id") == kid and k.get("kind") == "action":
                return k
    raise AssertionError(f"Aktionstaste id '{kid}' nicht gefunden")


def char_set(layers):
    """Direkttasten ∪ Menü-Einträge beider Symbolebenen (Migrations-Invariante)."""
    chars = set()
    for layer in layers:
        for row in layer["rows"]:
            for k in row:
                if k.get("kind") == "text":
                    chars.add(k["label"])
                for alt in k.get("alternates") or []:
                    chars.add(alt)
    return chars


def rebuild_symbols(layer):
    """symbols auf Plan-Raster; Rückgabe: auswandernder €-Key (da/fi/el) oder None."""
    rows = layer["rows"]
    flat = [k for row in rows for k in row]
    texts = [k for k in flat if k.get("kind") == "text"]
    digits = [k for k in texts if k["label"] in "0123456789"]
    assert len(digits) == 10, f"erwartet 10 Ziffern, gefunden {len(digits)}"
    by_label = {}
    for k in texts:
        if k["label"] in R1_ORDER or k["label"] in R2_ORDER:
            assert k["label"] not in by_label, f"Duplikat {k['label']}"
            by_label[k["label"]] = k
    missing = (set(R1_ORDER) | set(R2_ORDER)) - set(by_label)
    assert not missing, f"Plan-Zeichen fehlen: {sorted(missing)}"
    # Restliche Text-Keys: nur € darf auswandern (da/fi/el), sonst Fehler.
    accounted = {id(k) for k in digits} | {id(k) for k in by_label.values()}
    euro_out = None
    for k in texts:
        if id(k) not in accounted:
            assert k["label"] == "€", f"unerwartetes Restzeichen {k['label']!r}"
            assert euro_out is None
            euro_out = k
    shift = action_by_id(rows, "shift")
    backspace = action_by_id(rows, "backspace")
    enter = action_by_id(rows, "enter")
    footer = [action_by_id(rows, i) for i in ("base", "language", "emoji", "space", "mic")]
    r0 = [clone(k) for k in digits]
    for k in r0:
        set_width(k, 1.0)
    r1 = [clone(by_label[c]) for c in R1_ORDER]
    for k in r1:
        set_width(k, 0.9)
    bs = clone(backspace)
    set_width(bs, 1.0)
    r1 = r1 + [bs]
    sh = clone(shift)
    set_width(sh, 1.5)
    r2text = [clone(by_label[c]) for c in R2_ORDER]
    for k in r2text:
        set_width(k, 0.75)
    en = clone(enter)
    set_width(en, 1.0)
    r2 = [sh] + r2text + [en]
    r3 = [clone(k) for k in footer]
    for k, w in zip(r3, (1.5, 1.0, 1.0, 7.5, 2.0)):
        set_width(k, w)
    layer["rows"] = [r0, r1, r2, r3]
    return euro_out


def rebuild_symbols_shift(layer, euro_in, asset_id):
    """Rückgabe: Liste der r2-Zeichen, die der M-Regel zum Opfer fallen."""
    rows = layer["rows"]
    r0text = text_keys(rows[0])
    r1text = text_keys(rows[1])
    r2text = text_keys(rows[2])
    assert len(rows[0]) == len(r0text), "r0 enthält Aktionen (unerwartet)"
    # da/fi: ‰-Direkttaste entfällt (Plan-Abnahme, Menü-Pfad bleibt). Idempotent:
    # im T7-Zweitlauf ist ‰ bereits Menü-erreichbar (percent→[‰]) — kein Assert.
    if asset_id in PERMILLE_DROP:
        r2text = [k for k in r2text if k["label"] != "‰"]
    r0, tail = r0text[:10], r0text[10:]
    assert len(tail) <= 1, f"r0-Schwanz >1 Taste: {[k['label'] for k in tail]}"
    # da/fi/el: der Schwanz-Key trägt id 'euro' wie der einwandernde € — eindeutig
    # machen ( '>' → id 'greater'); € wandert an den r1-Blockanfang (Plan-Regel
    # „€ → r1" wie de-de), NICHT in r0 (das Plan-Ziel „r0 10×1,0" bleibt gewahrt).
    if euro_in is not None:
        for k in tail:
            if k["id"] == "euro":
                k["id"] = "greater"
    queue = ([euro_in] if euro_in is not None else []) + tail + r1text
    r1block, overflow = queue[:10], queue[10:]
    r2block = r2text + overflow
    # T7-M-Regel: 11/12 Direktzellen à 0,75 sind im r2-Budget 7,5
    # (Σ10,000 − ⇧1,5 − ⏎1,0) arithmetisch unmöglich (11·0,75 = 8,25 > 7,5 —
    # der Erstlauf quetschte auf 0,6818/0,625). Max 10 Direktzellen à 0,75
    # (= 7,5 exakt); die Zellen ab Position R2_MAX_CELLS fallen — JEDES gefallene
    # Zeichen bleibt über ein Bestands-Menü erreichbar (Menü-Weitergabe, bewacht
    # vom vor==nach-Assert in main()).
    r2drop = [k["label"] for k in r2block[R2_MAX_CELLS:]]
    r2block = r2block[:R2_MAX_CELLS]
    n1, n2 = len(r1block), len(r2block)
    assert n1 >= 1 and n2 >= 1
    for k in r0:
        set_width(k, 1.0)
    for k in r1block:
        set_width(k, 9.0 / n1)
    for k in r2block:
        set_width(k, 7.5 / n2)
    shift = action_by_id(rows, "shift")
    backspace = action_by_id(rows, "backspace")
    enter = action_by_id(rows, "enter")
    footer = [action_by_id(rows, i) for i in ("base", "language", "emoji", "space", "mic")]
    sh = clone(shift)
    set_width(sh, 1.5)
    bs = clone(backspace)
    set_width(bs, 1.0)
    en = clone(enter)
    set_width(en, 1.0)
    r3 = [clone(k) for k in footer]
    for k, w in zip(r3, (1.5, 1.0, 1.0, 7.5, 2.0)):
        set_width(k, w)
    layer["rows"] = [list(r0), r1block + [bs], [sh] + r2block + [en], r3]
    return r2drop


def splice(path, doc):
    """Nur die beiden Layer-Sektionen ersetzen — Rest der Datei bleibt stehen."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")

    def section_range(name):
        start = next(i for i, ln in enumerate(lines) if ln == f'    "{name}": {{')
        j = start + 1
        while lines[j] not in ("    }", "    },"):
            j += 1
        return start, j  # [start, j] inklusive der schließenden Zeile

    s_sym, e_sym = section_range("symbols")
    s_sh, e_sh = section_range("symbolsShift")
    assert s_sh == e_sym + 1, "symbolsShift liegt nicht direkt hinter symbols"

    def render(layer):
        body = json.dumps({"rows": layer["rows"]}, indent=2, ensure_ascii=False)
        inner = body.split("\n")[1:-1]
        return ["    " + ln for ln in inner]

    new_lines = (
        lines[:s_sym]
        + ['    "symbols": {']
        + render(doc["layers"]["symbols"])
        + ["    },"]
        + ['    "symbolsShift": {']
        + render(doc["layers"]["symbolsShift"])
        + ["    }"]
        + lines[e_sh + 1:]
    )
    text = "\n".join(new_lines)
    json.loads(text)  # Validierung vor dem Schreiben
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    report = []
    for asset_id in ASSETS:
        path = f"{LAYOUTS}/{asset_id}.json"
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        before = char_set([doc["layers"]["symbols"], doc["layers"]["symbolsShift"]])
        euro_out = rebuild_symbols(doc["layers"]["symbols"])
        euro_in = clone(euro_out) if euro_out is not None else None
        if asset_id in ("da-dk", "fi-fi", "el-gr"):
            if euro_in is None:
                # T7-Zweitlauf (Assets bereits migriert): € muss bereits
                # symbolsShift-Direkttaste sein (Erstlauf hat gewandert).
                assert any(k.get("kind") == "text" and k["label"] == "€"
                           for row in doc["layers"]["symbolsShift"]["rows"] for k in row), \
                    f"{asset_id}: € fehlt in symbolsShift (Zweitlauf)"
        else:
            assert euro_out is None, f"{asset_id}: unerwartetes Restzeichen € in symbols"
        r2drop = rebuild_symbols_shift(doc["layers"]["symbolsShift"], euro_in, asset_id)
        after = char_set([doc["layers"]["symbols"], doc["layers"]["symbolsShift"]])
        assert before == after, f"{asset_id}: Zeichenmenge geändert: " \
            f"-{sorted(before - after)} +{sorted(after - before)}"
        for layer_name in ("symbols", "symbolsShift"):
            layer = doc["layers"][layer_name]
            ids = [k["id"] for row in layer["rows"] for k in row]
            assert len(ids) == len(set(ids)), f"{asset_id}/{layer_name}: doppelte ids"
            sums = [sum(k["width"] for k in row) for row in layer["rows"]]
            for i, expect in enumerate((10.0, 10.0, 10.0, 13.0)):
                assert abs(sums[i] - expect) < 1e-6, f"{asset_id}/{layer_name} Σ r{i}={sums[i]}"
        splice(path, doc)
        counts = [len(doc["layers"][l]["rows"][r]) for l in ("symbols", "symbolsShift") for r in range(3)]
        euro_note = ""
        if euro_out is not None:
            permille = "weg (Menü-Pfad percent→‰)" if asset_id in PERMILLE_DROP else "bleibt (el-gr)"
            euro_note = f" | €→symbolsShift-r1-Blockanfang, '>'→r1 (id greater), ‰-Direkttaste: {permille}"
        if r2drop:
            # Menü-Weitergabe scharf prüfen: jedes gefallene Zeichen muss über
            # Alternates (Bestands-Menüs) erreichbar bleiben — nur eine Direkt-
            # taste woanders würde den Menü-Vertrag der M-Regel verletzen.
            menus = {alt for layer in (doc["layers"]["symbols"], doc["layers"]["symbolsShift"])
                     for row in layer["rows"] for k in row
                     for alt in (k.get("alternates") or [])}
            lost = [c for c in r2drop if c not in menus]
            assert not lost, f"{asset_id}: r2-Überlauf ohne Menü-Weitergabe: {lost}"
            note = f" | r2-Überlauf → Menü-Weitergabe: {''.join(r2drop)}"
        else:
            note = ""
        report.append(f"{asset_id}: OK rows(symbols r0-r2)={counts[:3]} rows(shift r0-r2)={counts[3:]}{euro_note}{note}")
    print("\n".join(report))


if __name__ == "__main__":
    sys.exit(main())
