"""Génère l'icône Keyra (clavier bleu) à partir d'une seule géométrie.

Sorties :
  res/drawable/ic_keyra_foreground.xml   calque avant de l'icône adaptative (vecteur)
  res/drawable/ic_keyra_monochrome.xml   calque des icônes à thème (Android 13+)
  res/mipmap-*dpi/ic_keyra.png           icône classique pour Android 7.0 à 7.1
Usage : python tools/make_icon.py [aperçu.png]
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
BLUE = "#1736EE"
# Géométrie relevée sur le logo fourni (image de 1254 px) : carré bleu, 8 touches, barre d'espace, curseur.
SQUARE = (255, 255, 999, 988)
KEYS = [(x, y, x + 110, y + 112) for y in (405, 558) for x in (355, 500, 645, 790)]
KEY_RADIUS = 25
SPACE = (355, 712, 900, 831)
SPACE_RADIUS = 35
CURSOR = [(790, 742), (858, 766), (832, 776), (816, 808)]
CENTER = ((355 + 900) / 2, (405 + 831) / 2)
SCALE = 0.094  # px → dp : le dessin tient dans le cercle de sécurité de 66 dp sur 108 dp.


def dp(x, y):
    return round(54 + (x - CENTER[0]) * SCALE, 3), round(54 + (y - CENTER[1]) * SCALE, 3)


def rounded(box, radius):
    (l, t), (r, b) = dp(box[0], box[1]), dp(box[2], box[3])
    k = round(radius * SCALE, 3)
    return (f"M{l + k},{t} H{r - k} A{k},{k} 0 0 1 {r},{t + k} V{b - k} A{k},{k} 0 0 1 {r - k},{b} "
            f"H{l + k} A{k},{k} 0 0 1 {l},{b - k} V{t + k} A{k},{k} 0 0 1 {l + k},{t} Z")


def path_data():
    keys = " ".join(rounded(k, KEY_RADIUS) for k in KEYS)
    cursor = "M" + " L".join("%s,%s" % dp(*p) for p in CURSOR) + " Z"
    return keys, rounded(SPACE, SPACE_RADIUS) + " " + cursor


def vector(color):
    keys, space = path_data()
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Généré par tools/make_icon.py : ne pas modifier à la main. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="{color}" android:pathData="{keys}" />
    <!-- La barre d'espace est percée par le curseur (règle pair-impair). -->
    <path android:fillColor="{color}" android:fillType="evenOdd" android:pathData="{space}" />
</vector>
"""


def legacy(size):
    """Icône classique : carré bleu arrondi plein cadre, dessiné en 4x puis réduit (anticrénelage)."""
    big = size * 4
    margin = big / 24
    side = big - 2 * margin
    sx = side / (SQUARE[2] - SQUARE[0])
    sy = side / (SQUARE[3] - SQUARE[1])

    def m(x, y):
        return margin + (x - SQUARE[0]) * sx, margin + (y - SQUARE[1]) * sy

    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([margin, margin, big - margin, big - margin], radius=side * 0.22, fill=BLUE)
    for k in KEYS:
        d.rounded_rectangle([*m(k[0], k[1]), *m(k[2], k[3])], radius=KEY_RADIUS * sx, fill="white")
    d.rounded_rectangle([*m(SPACE[0], SPACE[1]), *m(SPACE[2], SPACE[3])], radius=SPACE_RADIUS * sx, fill="white")
    d.polygon([m(*p) for p in CURSOR], fill=BLUE)
    return img.resize((size, size), Image.LANCZOS)


def preview(path):
    """Aperçu de l'icône adaptative masquée en cercle, comme sur un Pixel."""
    big = 432
    s = big / 108
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([18 * s, 18 * s, 90 * s, 90 * s], fill=BLUE)
    for k in KEYS:
        d.rounded_rectangle([c * s for c in (*dp(k[0], k[1]), *dp(k[2], k[3]))], radius=KEY_RADIUS * SCALE * s, fill="white")
    d.rounded_rectangle([c * s for c in (*dp(SPACE[0], SPACE[1]), *dp(SPACE[2], SPACE[3]))], radius=SPACE_RADIUS * SCALE * s, fill="white")
    d.polygon([tuple(c * s for c in dp(*p)) for p in CURSOR], fill=BLUE)
    img.crop((int(16 * s), int(16 * s), int(92 * s), int(92 * s))).save(path)


def main():
    (RES / "drawable/ic_keyra_foreground.xml").write_text(vector("#FFFFFF"), encoding="utf-8", newline="\n")
    (RES / "drawable/ic_keyra_monochrome.xml").write_text(vector("#FFFFFF"), encoding="utf-8", newline="\n")
    for folder, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        legacy(size).save(RES / f"mipmap-{folder}/ic_keyra.png", optimize=True)
    if len(sys.argv) > 1:
        preview(sys.argv[1])


if __name__ == "__main__":
    main()
