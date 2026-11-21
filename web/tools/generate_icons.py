#!/usr/bin/env python3
"""Génère les icônes de la page web (web/icon-192.png, web/icon-512.png,
web/favicon-32.png) : un bus de face vu de face avec « 24 » sur son bandeau
de destination, dans une palette proche TBM (jaune #FFDD00, gris #58595B).

Usage :
    python3 generate_icons.py

(Pillow requis ; l'icône 192 est aussi embarquée en data URI dans index.html
pour que le raccourci Android fonctionne même sans téléverser les fichiers PNG.)
"""
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = "/home/appuser/.local/lib/python3.12/site-packages/matplotlib/mpl-data/fonts/ttf/DejaVuSans-Bold.ttf"

YELLOW = (255, 221, 0, 255)     # jaune TBM (fond)
GREY = (88, 89, 91, 255)        # gris TBM (caisse du bus)
DARK = (29, 29, 27, 255)        # bandeau de destination
LIGHT = (238, 240, 243, 255)    # pare-brise

S = 512


def rounded(d, box, r, fill):
    d.rounded_rectangle(box, radius=r, fill=fill)


def draw_icon(scale_font=None, out_scale=None, maskable=False):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, S, S), radius=0, fill=YELLOW)

    zone = 512 if not maskable else int(512 * 0.72)
    def sc(v):
        return int(v * zone / 512 + (512 - zone) / 2)

    bus_l, bus_t, bus_r, bus_b = sc(96), sc(120), sc(416), sc(340)
    rounded(d, (bus_l, bus_t, bus_r, bus_b), sc(44), GREY)

    w_l, w_t, w_r, w_b = sc(140), sc(238), sc(372), sc(312)
    rounded(d, (w_l, w_t, w_r, w_b), sc(18), LIGHT)

    b_l, b_t, b_r, b_b = sc(120), sc(150), sc(392), sc(212)
    rounded(d, (b_l, b_t, b_r, b_b), sc(14), DARK)

    band_font = ImageFont.truetype(FONT, sc(52) if scale_font is None else scale_font(sc(52)))
    text = "   24"
    bb = d.textbbox((0, 0), text, font=band_font)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((b_l + b_r - tw) / 2 - bb[0], (b_t + b_b - th) / 2 - bb[1]),
           text, font=band_font, fill=YELLOW)

    for cx in (sc(168), sc(344)):
        d.ellipse((cx - sc(26), bus_b - sc(6), cx + sc(26), bus_b + sc(48)),
                  fill=DARK)

    for hx in (sc(132), sc(360)):
        rounded(d, (hx, sc(318), hx + sc(20), sc(336)), sc(6), YELLOW)

    if out_scale is None:
        return img
    w, h = img.size
    return img.resize((int(w * out_scale), int(h * out_scale)), Image.LANCZOS)


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
