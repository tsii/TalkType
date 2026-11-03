#!/usr/bin/env python3
"""V2-Tastatur-Preview (DESIGN_TASTATUR_V2.md): PNG hell/dunkel + GIF, 1080x1440, offline."""
import math
from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1440
KB_TOP, ROW = 126, 144
U = 108

THEMES = {
    "hell":   dict(bg=(242, 243, 245), key=(255, 255, 255), press=(220, 231, 230),
                   line=(224, 226, 229), text=(26, 28, 30), accent=(46, 125, 116),
                   editor=(248, 249, 250), popbg=(255, 255, 255)),
    "dunkel": dict(bg=(32, 33, 36), key=(43, 45, 49), press=(58, 69, 67),
                   line=(60, 63, 68), text=(232, 234, 237), accent=(128, 203, 196),
                   editor=(27, 28, 30), popbg=(52, 54, 58)),
}

F40 = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 44)
F28 = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 28)

# Zeilen: (label, links, rechts) — r0 10x108, r1 54+9x102+@108, r2 204+7x96+96+108, r3 Footer
R0 = [(c, i * U, (i + 1) * U) for i, c in enumerate("qwertzuiop")]
R1 = [("a", 54, 156), ("s", 156, 258), ("d", 258, 360), ("f", 360, 462), ("g", 462, 564),
      ("h", 564, 666), ("j", 666, 768), ("k", 768, 870), ("l", 870, 972), ("@", 972, 1080)]
R2 = [("shift", 0, 204)] + [(c, 204 + i * 96, 204 + (i + 1) * 96) for i, c in enumerate("yxcvbnm")] \
   + [("back", 876, 972), ("enter", 972, 1080)]
R3 = [("sym", 0, 125), ("lang", 125, 208), ("emoji", 208, 291), ("space", 291, 831),
      ("dot", 831, 914), ("mic", 914, 1080)]

# Endonyme laut LanguageCycle.ENDONYMS (VoiceKeyboardService.kt:45-61), Fallback "Space"
ENDONYMS = [("de-de", "Deutsch"), ("en-us", "English (US)"), ("fr-fr", "Français"),
            ("es-es", "Español"), ("it-it", "Italiano"), ("pt-pt", "Português"),
            ("nl-nl", "Nederlands"), ("pl-pl", "Polski"), ("tr-tr", "Türkçe"),
            ("ru-ru", "Русский"), ("cs-cz", "Čeština"), ("sv-se", "Svenska"),
            ("da-dk", "Dansk"), ("fi-fi", "Suomi"), ("el-gr", "Ελληνικά")]


def icon(draw, kind, cx, cy, s, col, width=9):
    """Icon in 1x1-Box, s = Kantenlaenge, (cx,cy) = Mitte. Punkte aus DESIGN §7."""
    def P(x, y):
        return (cx - s / 2 + x * s, cy - s / 2 + y * s)
    lw, cap = width, "round"
    if kind == "shift":
        draw.line([P(.5, .78), P(.5, .30)], fill=col, width=lw, joint="curve")
        draw.line([P(.28, .52), P(.5, .30), P(.72, .52)], fill=col, width=lw, joint="curve")
        draw.line([P(.32, .84), P(.68, .84)], fill=col, width=lw)
    elif kind == "back":   # reiner Links-Pfeil + Sockel
        draw.line([P(.80, .50), P(.30, .50)], fill=col, width=lw)
        draw.line([P(.52, .30), P(.30, .50), P(.52, .70)], fill=col, width=lw, joint="curve")
        draw.line([P(.18, .32), P(.18, .68)], fill=col, width=lw)
    elif kind == "enter":
        draw.line([P(.74, .26), P(.74, .60), P(.32, .60)], fill=col, width=lw, joint="curve")
        draw.line([P(.50, .44), P(.32, .60), P(.50, .76)], fill=col, width=lw, joint="curve")
    elif kind == "globe":
        r = s * .32
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=7)
        draw.ellipse([cx - r * .45, cy - r, cx + r * .45, cy + r], outline=col, width=5)
        draw.line([cx - r, cy, cx + r, cy], fill=col, width=5)
    elif kind == "emoji":
        r = s * .32
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=7)
        d = s * .055
        for dx in (-.14, .14):
            draw.ellipse([cx + dx * s - d, cy - .12 * s - d, cx + dx * s + d, cy - .12 * s + d], fill=col)
        draw.arc([cx - .18 * s, cy - .05 * s, cx + .18 * s, cy + .28 * s], 20, 160, fill=col, width=5)
    elif kind == "mic":
        r = s * .17
        x0, y0, x1, y1 = cx - r, cy - s * .34, cx + r, cy + s * .10
        draw.rounded_rectangle([x0, y0, x1, y1], radius=r, outline=col, width=7)
        draw.arc([cx - s * .26, cy - s * .18, cx + s * .26, cy + s * .30], 0, 180, fill=col, width=7)
        draw.line([cx, cy + s * .30, cx, cy + s * .40], fill=col, width=7)
        draw.line([cx - s * .14, cy + s * .40, cx + s * .14, cy + s * .40], fill=col, width=7)
    elif kind in (".", ",", "@"):
        draw.text((cx, cy), kind, font=F40, fill=col, anchor="mm")
    elif kind == "space":
        pass


def text_c(draw, s, cx, cy, col, font=F40):
    draw.text((cx, cy), s, font=font, fill=col, anchor="mm")


def key_rects():
    out = []
    for row, keys in enumerate((R0, R1, R2, R3)):
        y0 = KB_TOP + row * ROW
        for label, x0, x1 in keys:
            out.append((label, x0 + 2, y0 + 2, x1 - 2, y0 + ROW - 2))
    return out


def frame(theme, pressed=None, shift=False, popup=False):
    T = THEMES[theme]
    img = Image.new("RGB", (W, H), T["bg"])
    d = ImageDraw.Draw(img)
    # Statusleiste (126 px) + Trennlinie
    d.rectangle([0, 0, W, KB_TOP], fill=T["bg"])
    d.ellipse([40, 54, 70, 84], outline=T["accent"], width=5)          # Mic-Punkt (ohne Text)
    d.line([0, KB_TOP, W, KB_TOP], fill=T["line"], width=2)
    # Editorenfläche (leer, nur Feld-Kontur + Cursor)
    d.rounded_rectangle([24, KB_TOP + 576 + 40, W - 24, KB_TOP + 576 + 190], radius=16,
                        outline=T["line"], width=3)
    d.line([60, KB_TOP + 576 + 62, 60, KB_TOP + 576 + 168], fill=T["accent"], width=4)
    # Tasten
    for label, x0, y0, x1, y1 in key_rects():
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        col = T["press"] if (pressed == label) else T["key"]
        d.rounded_rectangle([x0, y0, x1, y1], radius=10, fill=col)
        if pressed == label:
            d.rounded_rectangle([x0, y0, x1, y1], radius=10, outline=T["accent"], width=4)
        ic = T["accent"] if (label in ("shift", "back", "enter") and not shift) or label == "shift" and shift else T["text"]
        ic = T["accent"] if label in ("shift", "back", "enter") else T["text"]
        if label in ("shift", "back", "enter"):
            icon(d, label, cx, cy, (x1 - x0) * .40, T["accent"])
        elif label in ("globe", "lang", "emoji", "mic", "sym", "dot"):
            icon(d, {"sym": ",", "lang": "globe", "emoji": "emoji", "mic": "mic", "dot": "."}[label],
                 cx, cy, (x1 - x0) * 0.55, T["text"])
        elif label == "space":
            # Endonym der aktiven Sprache (de-de-Darstellung: „Deutsch“, s. DESIGN §4a)
            text_c(d, "Deutsch", cx, cy, T["text"])
        else:
            ch = label.upper() if (shift and label.isalpha()) else label
            text_c(d, ch, cx, cy, T["text"])
    # Popup-Andeutung ueber "d" (r1, x 258-360): 4 Zellen, leere Zaehn + Hinweis-Punkt
    if popup:
        px0, pw, ph, gap = 216, 264, 92, 26
        py1 = KB_TOP + ROW - gap                 # untere Kante = 26 px ueber Tasten-Unterkante? -> ueber Taste
        py0 = KB_TOP + 0 - ph + 40                # in r0-Richtung
        py0, py1 = max(KB_TOP - 0, KB_TOP + ROW - gap - ph), KB_TOP + ROW - gap
        d.rounded_rectangle([px0, py0, px0 + pw, py1], radius=12, fill=T["popbg"],
                            outline=T["line"], width=3)
        cw = (pw - 8) / 4
        for i in range(1, 4):
            d.line([px0 + 4 + i * cw, py0 + 10, px0 + 4 + i * cw, py1 - 10], fill=T["line"], width=2)
        for i in range(4):
            d.ellipse([px0 + 4 + i * cw + cw / 2 - 6, (py0 + py1) / 2 - 6,
                       px0 + 4 + i * cw + cw / 2 + 6, (py0 + py1) / 2 + 6],
                      fill=T["accent"] if i == 1 else T["line"])
    return img


def space_strip():
    """Mini-Grafik: Space-Beschriftung je Sprache (Endonyme, de-DE/en-US zuerst)."""
    T = THEMES["hell"]
    row, pad = 64, 20
    w, h = 1080, 40 + row * 15 + pad
    img = Image.new("RGB", (w, h), T["bg"])
    d = ImageDraw.Draw(img)
    d.line([20, 40, w - 20, 40], fill=T["line"], width=2)
    for i, (lid, end) in enumerate(ENDONYMS):
        y0 = 40 + i * row
        text_c(d, lid, 120, y0 + row // 2, T["text"], F28)
        d.rounded_rectangle([260, y0 + 6, 1040, y0 + row - 6], radius=10, fill=T["key"])
        text_c(d, end, 650, y0 + row // 2, T["text"], F28)
    return img


def main():
    import os
    os.makedirs("docs/preview", exist_ok=True)
    frames = [
        frame("hell"), frame("hell", pressed="d"), frame("hell", popup=True),
        frame("hell", shift=True), frame("hell"),
        frame("dunkel"), frame("dunkel", pressed="d"), frame("dunkel", shift=True),
        frame("dunkel", popup=True),
    ]
    frames[0].save("docs/preview/keyboard_v2_hell.png")
    frames[5].save("docs/preview/keyboard_v2_dunkel.png")
    frames[0].save("docs/preview/keyboard_v2.gif", save_all=True, append_images=frames[1:],
                   duration=700, loop=0)
    space_strip().save("docs/preview/space_endonyme.png")
    for f in ("keyboard_v2_hell.png", "keyboard_v2_dunkel.png", "keyboard_v2.gif", "space_endonyme.png"):
        import pathlib
        print(f, pathlib.Path("docs/preview/" + f).stat().st_size, "Bytes")
    print("frames:", len(frames))


main()
