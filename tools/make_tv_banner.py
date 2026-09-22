#!/usr/bin/env python3
"""Generate the 320x180 Android TV home-row banner."""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
ICON = os.path.join(ROOT, "app", "src", "main", "res", "mipmap-xxxhdpi", "ic_launcher.png")
OUT_DIRS = [
    os.path.join(ROOT, "app", "src", "main", "res", "drawable-xhdpi"),
    os.path.join(ROOT, "app", "src", "main", "res", "drawable"),
]


def main():
    banner = Image.new("RGB", (320, 180), (11, 15, 20))
    px = banner.load()
    for y in range(180):
        t = y / 179.0
        color = (
            int(11 + 10 * t),
            int(15 + 16 * t),
            int(20 + 32 * t),
        )
        for x in range(320):
            px[x, y] = color

    icon = Image.open(ICON).convert("RGBA").resize((128, 128), Image.LANCZOS)
    banner_rgba = banner.convert("RGBA")
    banner_rgba.paste(icon, (20, 26), icon)

    draw = ImageDraw.Draw(banner_rgba)
    font_path = "C:/Windows/Fonts/segoeuib.ttf"
    if not os.path.isfile(font_path):
        font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    try:
        title = ImageFont.truetype(font_path, 36)
    except OSError:
        title = ImageFont.load_default()
    draw.text((164, 68), "FN TV", fill=(244, 246, 248, 255), font=title)

    out = banner_rgba.convert("RGB")
    for d in OUT_DIRS:
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "tv_banner.png")
        out.save(path, "PNG")
        print("wrote", path)


if __name__ == "__main__":
    main()
