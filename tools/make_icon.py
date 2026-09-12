from PIL import Image, ImageDraw
import math

src = Image.open("e:/Project/Indflix/tools/vega_logo.webp").convert("RGBA")
print("src", src.size)

# Match Multimovies icon dims if square; check it.
mm = Image.open("e:/Project/Indflix/Multimovies/icon.png")
print("mm", mm.size, mm.mode)

SIZE = 512
# Dark background like a plugin badge.
out = Image.new("RGBA", (SIZE, SIZE), (10, 15, 13, 255))
# Draw logo centered, preserving aspect, scaled to ~78% of the shorter side.
target = int(SIZE * 0.78)
sc = target / max(src.size)
nw, nh = int(src.width * sc), int(src.height * sc)
logo = src.resize((nw, nh), Image.LANCZOS)
out.alpha_composite(logo, ((SIZE - nw) // 2, (SIZE - nh) // 2))

# Rounded-corner crop via mask.
mask = Image.new("L", (SIZE, SIZE), 0)
d = ImageDraw.Draw(mask)
d.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=110, fill=255)
final = Image.new("RGBA", (SIZE, SIZE), (10, 15, 13, 0))
final.paste(out.convert("RGB"), (0, 0))
px = final.load()
mp = mask.load()
for y in range(SIZE):
    for x in range(SIZE):
        r, g, b, a = px[x, y]
        px[x, y] = (r, g, b, mp[x, y])
final.save("e:/Project/Indflix/Vegamovies/icon.png", "PNG", optimize=True)
import os
print("saved", os.path.getsize("e:/Project/Indflix/Vegamovies/icon.png"), "bytes")
