#!/usr/bin/env python3
"""gen_icon.py — builds CineVood/icon.png (512x512): official circular
play mark (from 110px favicon, supersampled clean) + official wordmark,
composited on a soft white rounded card so it reads on any app theme."""
import os
from PIL import Image, ImageDraw, ImageFilter

BASE = os.path.dirname(__file__)
FAVICON = os.path.join(BASE, "fixtures", "cinevood", "favicon.webp")
WORDMARK = os.path.join(BASE, "fixtures", "cinevood", "cinewood_logo.webp")
OUT = os.path.join(BASE, "..", "CineVood", "icon.png")
SIZE = 512
UP = 2048


def clean_upscale(img, size):
    """110px -> size with alpha-premultiplied 2-step supersampling."""
    alpha = img.getchannel("A")
    rgb = img.convert("RGB")
    px, ap = rgb.load(), alpha.load()
    for y in range(img.height):
        for x in range(img.width):
            a = ap[x, y] / 255.0
            r, g, b = px[x, y]
            px[x, y] = (int(r * a + .5), int(g * a + .5), int(b * a + .5))
    rgb_big = rgb.resize((UP, UP), Image.LANCZOS).resize((size, size), Image.BOX)
    a_big = alpha.resize((UP, UP), Image.LANCZOS).resize((size, size), Image.BOX)
    rp, ap2 = rgb_big.load(), a_big.load()
    for y in range(size):
        for x in range(size):
            a = ap2[x, y]
            if a > 0:
                r, g, b = rp[x, y]
                rp[x, y] = (min(255, int(r * 255 / a + .5)),
                            min(255, int(g * 255 / a + .5)),
                            min(255, int(b * 255 / a + .5)))
    out = Image.merge("RGBA", (*rgb_big.split()[:3], a_big))
    out = out.filter(ImageFilter.MedianFilter(3))
    out = out.filter(ImageFilter.GaussianBlur(0.6))
    out = out.filter(ImageFilter.UnsharpMask(radius=2.6, percent=110, threshold=1))
    return out


# 1) circular play mark, de-pixelated
mark = clean_upscale(Image.open(FAVICON).convert("RGBA"), 272)

# 2) wordmark: keep original alpha, crop to the blue "CineVood" text band
wm_src = Image.open(WORDMARK).convert("RGBA")
sp = wm_src.load()
blue_rows = [y for y in range(wm_src.height) for x in range(wm_src.width)
             if (lambda p: p[3] > 40 and p[2] > 110 and p[2] - p[0] > 50)(sp[x, y])]
top, bottom = max(0, min(blue_rows) - 2), min(wm_src.height, max(blue_rows) + 3)
wm_crop = wm_src.crop((0, top, wm_src.width, bottom))
wm_w = 380
wm_h = int(wm_crop.height * wm_w / wm_crop.width)
wm = wm_crop.resize((wm_w, wm_h), Image.LANCZOS)
wm = wm.filter(ImageFilter.UnsharpMask(radius=1.4, percent=80, threshold=2))

# 3) near-black rounded card (26,26,26), 4x supersampled corners
S = 4
mask = Image.new("L", (SIZE * S, SIZE * S), 0)
d = ImageDraw.Draw(mask)
d.rounded_rectangle([0, 0, SIZE * S - 1, SIZE * S - 1], radius=104 * S, fill=255)
mask = mask.resize((SIZE, SIZE), Image.LANCZOS)

card = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
black = Image.new("RGBA", (SIZE, SIZE), (26, 26, 26, 255))
card = Image.composite(black, card, mask)

# 4) compose: mark upper-center, wordmark below (optically balanced)
card.alpha_composite(mark, ((SIZE - mark.width) // 2, 46))
card.alpha_composite(wm, ((SIZE - wm.width) // 2, SIZE - wm_h - 52))

card.save(OUT, "PNG", optimize=True)
print("wrote", os.path.abspath(OUT), os.path.getsize(OUT), "bytes", card.size)
