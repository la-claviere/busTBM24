#!/usr/bin/env python3
"""Génère les icônes de la page web (web/icon-192.png, web/icon-512.png,
web/favicon-32.png) : « 24 » en très gros sur fond blanc, rempli d'un dégradé
rose → bleu (palette TBM : rose, bleu, vert, fond blanc).

Usage :
    python3 generate_icons.py

(Pillow requis ; l'icône 192 est aussi embarquée en data URI dans index.html
pour que le raccourci Android fonctionne même sans téléverser les fichiers PNG.)
"""
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = "/home/appuser/.local/lib/python3.12/site-packages/matplotlib/mpl-data/fonts/ttf/DejaVuSans-Bold.ttf"

PINK = (226, 0, 116)    # rose TBM
BLUE = (0, 90, 170)     # bleu TBM
WHITE = (255, 255, 255)
S = 512


def diagonal_gradient(size, start, end):
    w, h = size
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / (w + h - 2)
            px[x, y] = tuple(round(s + (e - s) * t) for s, e in zip(start, end))
    return img


def draw_icon():
    img = Image.new("RGB", (S, S), WHITE)
    mask = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(mask)

    size = 300
    text = "24"
    while size < 460:
        font = ImageFont.truetype(FONT, size)
        bb = d.textbbox((0, 0), text, font=font, stroke_width=0)
        if bb[2] - bb[0] > 360 or bb[3] - bb[1] > 300:
            break
        size += 8
    bb = d.textbbox((0, 0), text, font=font)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((S - tw) / 2 - bb[0], (S - th) / 2 - bb[1]), text,
           font=font, fill=255)

    grad = diagonal_gradient((S, S), PINK, BLUE)
    img.paste(grad, (0, 0), mask)
    return img


def main():
    out512 = os.path.normpath(os.path.join(HERE, "..", "icon-512.png"))
    out192 = os.path.normpath(os.path.join(HERE, "..", "icon-192.png"))
    out32 = os.path.normpath(os.path.join(HERE, "..", "favicon-32.png"))

    icon = draw_icon()
    icon.save(out512, optimize=True)
    icon.resize((192, 192), Image.LANCZOS).save(out192, optimize=True)
    icon.resize((32, 32), Image.LANCZOS).save(out32, optimize=True)
    print(os.path.abspath(out512), os.path.getsize(out512), "octets")
    print(os.path.abspath(out192), os.path.getsize(out192), "octets")
    print(os.path.abspath(out32), os.path.getsize(out32), "octets")


if __name__ == "__main__":
    main()
