#!/usr/bin/env python3
"""F36/0.1.27: rendert die ECHTE Umsetzung (assets/layouts/*.json + KeyboardTheme) —
nicht den Entwurf. Doku/Grafik-only, offline. Output: docs/preview/keyboard_v2_impl_*."""
import json, os
from PIL import Image, ImageDraw, ImageFont

U10, W = 108, 1080
KB_TOP, ROW = 126, 144
H = KB_TOP + 4 * ROW + 738          # 1440
H10 = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 44)
H11 = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 30)

# Theme direkt aus KeyboardTheme.kt:131-155 (V2 §7)
T = {
    "hell":   dict(bg=0xFFF2F3F5, key=0xFFFFFFFF, press=0xFFDCE7E6, line=0xFFE0E2E5,
                   text=0xFF1A1C1E, accent=0xFF2E7D74, editor=0xFFF8F9FA, pop=0xFFFFFFFF),
    "dunkel": dict(bg=0xFF202124, key=0xFF2B2D31, press=0xFF3A4543, line=0xFF3C3F44,
                   text=0xFFE8EAED, accent=0xFF80CBC4, editor=0xFF1B1C1E, pop=0xFF34363A),
}


def c16(v):
    v &= 0xFFFFFF
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


def load(layout):
    d = json.load(open(f"app/src/main/assets/layouts/{layout}.json"))
    return d["layers"]["base"]["rows"], d["language"]["label"]


def icon(d, kind, cx, cy, s, col, lw=9):
    def P(x, y):
        return (cx - s / 2 + x * s, cy - s / 2 + y * s)
    j = "curve"
    if kind == "shift":
        d.line([P(.5, .78), P(.5, .30)], fill=col, width=lw, joint=j)
        d.line([P(.28, .52), P(.5, .30), P(.72, .52)], fill=col, width=lw, joint=j)
        d.line([P(.32, .84), P(.68, .84)], fill=col, width=lw)
    elif kind == "back":
        d.line([P(.80, .50), P(.30, .50)], fill=col, width=lw)
        d.line([P(.52, .30), P(.30, .50), P(.52, .70)], fill=col, width=lw, joint=j)
        d.line([P(.18, .32), P(.18, .68)], fill=col, width=lw)
    elif kind == "enter":
        d.line([P(.74, .26), P(.74, .60), P(.32, .60)], fill=col, width=lw, joint=j)
        d.line([P(.50, .44), P(.32, .60), P(.50, .76)], fill=col, width=lw, joint=j)
    elif kind == "globe":
        r = s * .34
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=7)
        d.ellipse([cx - r * .45, cy - r, cx + r * .45, cy + r], outline=col, width=5)
        d.line([cx - r, cy, cx + r, cy], fill=col, width=5)
    elif kind == "emoji":
        r = s * .34
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=7)
        dd = s * .055
        for dx in (-.14, .14):
            d.ellipse([cx + dx * s - dd, cy - .12 * s - dd, cx + dx * s + dd, cy - .12 * s + dd], fill=col)
        d.arc([cx - .18 * s, cy - .05 * s, cx + .18 * s, cy + .28 * s], 20, 160, fill=col, width=5)
    elif kind == "mic":
        r = s * .17
        d.rounded_rectangle([cx - r, cy - s * .34, cx + r, cy + s * .10], radius=r, outline=col, width=7)
        d.arc([cx - s * .26, cy - s * .18, cx + s * .26, cy + s * .30], 0, 180, fill=col, width=7)
        d.line([cx, cy + s * .30, cx, cy + s * .40], fill=col, width=7)
        d.line([cx - s * .14, cy + s * .40, cx + s * .14, cy + s * .40], fill=col, width=7)
    elif kind == "dots":
        for i in range(3):
            d.ellipse([cx - s * .28 + i * s * .28 - s * .07, cy - s * .07,
                       cx - s * .28 + i * s * .28 + s * .07, cy + s * .07], fill=col)


ICON_BY_ID = {"shift": "shift", "backspace": "back", "enter": "enter",
              "language": "globe", "emoji": "emoji", "mic": "mic"}
ICON_BY_LABEL = {"🌐": "globe", "😊": "emoji", "🎤": "mic", "⇧": "shift", "⌫": "back", "⏎": "enter"}


def frame(theme, rows, endonym, pressed=None, shift=False):
    Tm = T[theme]
    img = Image.new("RGB", (W, H), c16(Tm["bg"]))
    d = ImageDraw.Draw(img)
    d.ellipse([40, 54, 70, 84], outline=c16(Tm["accent"]), width=5)
    d.line([0, KB_TOP, W, KB_TOP], fill=c16(Tm["line"]), width=2)
    d.rounded_rectangle([24, KB_TOP + 4 * ROW + 40, W - 24, KB_TOP + 4 * ROW + 190], radius=16,
                        outline=c16(Tm["line"]), width=3)
    d.line([60, KB_TOP + 4 * ROW + 62, 60, KB_TOP + 4 * ROW + 168], fill=c16(Tm["accent"]), width=4)
    for ri, row in enumerate(rows):
        tot = sum(k["width"] for k in row)
        y0 = KB_TOP + ri * ROW
        x = 0.0
        for k in row:
            kw = k["width"] / tot * W
            x0, x1 = int(x), int(x + kw)
            x += kw
            label = k.get("label")
            if k["kind"] == "margin" or label is None:
                continue
            X0, Y0, X1, Y1 = x0 + 2, y0 + 2, x1 - 2, y0 + ROW - 2
            cx, cy = (X0 + X1) / 2, (Y0 + Y1) / 2
            is_press = (k["id"] == "d" and pressed)
            d.rounded_rectangle([X0, Y0, X1, Y1], radius=10,
                                fill=c16(Tm["press"] if is_press else Tm["key"]))
            if is_press:
                d.rounded_rectangle([X0, Y0, X1, Y1], radius=10, outline=c16(Tm["accent"]), width=4)
            kind = ICON_BY_ID.get(k["id"]) or ICON_BY_LABEL.get(label)
            if k["id"] == "space":
                # SpaceLabelRule: Asset-Label „Leer" überschreibt das Endonym NICHT
                d.text((cx, cy), endonym, font=H10, fill=c16(Tm["text"]), anchor="mm")
            elif k["id"] == "symbols":
                d.text((cx, cy), "123", font=H11, fill=c16(Tm["text"]), anchor="mm")
            elif kind:
                icon(d, kind, cx, cy, (X1 - X0) * .40, c16(Tm["accent"]))
            elif len(label) == 1 and label in ".,@":
                d.text((cx, cy), label, font=H10, fill=c16(Tm["text"]), anchor="mm")
            else:
                ch = label.upper() if (shift and any(c.isascii() and c.isalpha() for c in label)) else label
                d.text((cx, cy), ch, font=H10, fill=c16(Tm["text"]), anchor="mm")
    return img


def main():
    os.makedirs("docs/preview", exist_ok=True)
    rows, endonym = load("de-de")
    frames = [frame("hell", rows, endonym),
              frame("hell", rows, endonym, pressed=True),
              frame("hell", rows, endonym, shift=True),
              frame("dunkel", rows, endonym),
              frame("dunkel", rows, endonym, shift=True)]
    frames[0].save("docs/preview/keyboard_v2_impl_hell.png")
    frames[3].save("docs/preview/keyboard_v2_impl_dunkel.png")
    frames[0].save("docs/preview/keyboard_v2_impl.gif", save_all=True, append_images=frames[1:],
                   duration=650, loop=0)
    for f in ("keyboard_v2_impl_hell.png", "keyboard_v2_impl_dunkel.png", "keyboard_v2_impl.gif"):
        print(f, os.path.getsize("docs/preview/" + f), "Bytes")
    # Sonderfälle (Hell-Grundzustand, nebeneinander 540 px je Karte => 1080 breit)
    cards = [load(l) for l in ("fr-fr", "cs-cz", "ru-ru")]
    img = Image.new("RGB", (1080, 900), c16(T["hell"]["bg"]))
    d = ImageDraw.Draw(img)
    for i, ((rows, end), lid) in enumerate(zip(cards, ("fr-fr", "cs-cz", "ru-ru"))):
        sub = Image.new("RGB", (540, 252), c16(T["hell"]["bg"]))
        sd = ImageDraw.Draw(sub)
        tot_w = max(sum(k["width"] for k in row) for row in rows)
        y0 = 4
        for ri, row in enumerate(rows):
            x = 0.0
            for k in row:
                kw = k["width"] / tot_w * 524
                X0, X1 = int(x) + 1, int(x + kw) - 1
                x += kw
                lab = k.get("label")
                if k["kind"] == "margin" or lab is None:
                    continue
                sd.rounded_rectangle([X0, y0 + 1, X1, y0 + 61], radius=6, fill=c16(T["hell"]["key"]))
                kind = ICON_BY_ID.get(k["id"]) or ICON_BY_LABEL.get(lab)
                if kind:
                    ic = icon  # reuse
                    cx, cy = (X0 + X1) / 2, y0 + 32
                    def P2(x_, y_):
                        return (cx - 10 + x_ * 20, cy - 10 + y_ * 20)
                    if kind == "shift":
                        sd.line([P2(.5, .78), P2(.5, .30)], fill=c16(T["hell"]["accent"]), width=4, joint="curve")
                        sd.line([P2(.28, .52), P2(.5, .30), P2(.72, .52)], fill=c16(T["hell"]["accent"]), width=4, joint="curve")
                    elif kind == "back":
                        sd.line([P2(.80, .50), P2(.30, .50)], fill=c16(T["hell"]["accent"]), width=4)
                        sd.line([P2(.52, .30), P2(.30, .50), P2(.52, .70)], fill=c16(T["hell"]["accent"]), width=4, joint="curve")
                    elif kind == "enter":
                        sd.line([P2(.74, .26), P2(.74, .60), P2(.32, .60)], fill=c16(T["hell"]["accent"]), width=4, joint="curve")
                        sd.line([P2(.50, .44), P2(.32, .60), P2(.50, .76)], fill=c16(T["hell"]["accent"]), width=4, joint="curve")
                    continue
                if k["id"] == "space":
                    sd.text(((X0 + X1) / 2, y0 + 32), end, font=H11, fill=c16(T["hell"]["text"]), anchor="mm")
                elif k["id"] == "symbols":
                    sd.text(((X0 + X1) / 2, y0 + 32), "123", font=H11, fill=c16(T["hell"]["text"]), anchor="mm")
                else:
                    sd.text(((X0 + X1) / 2, y0 + 32), lab, font=H11, fill=c16(T["hell"]["text"]), anchor="mm")
            y0 += 61
        d.text((14, i * 300 + 4), lid, font=H11, fill=c16(T["hell"]["text"]))
        img.paste(sub, (8, i * 300 + 34))
    img.save("docs/preview/keyboard_v2_impl_sonderfaelle.png")
    print("keyboard_v2_impl_sonderfaelle.png", os.path.getsize("docs/preview/keyboard_v2_impl_sonderfaelle.png"), "Bytes")
    print("frames:", len(frames))


main()
