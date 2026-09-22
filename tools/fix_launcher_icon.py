#!/usr/bin/env python3
"""Build Android launcher icons: iOS squircle on opaque navy, plus adaptive layers."""
import os
from collections import deque
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
SRC_CANDIDATES = [
    os.path.join(
        os.path.expanduser("~"),
        ".cursor", "projects", "d-myCode-fnos-tv-danmu", "assets",
        "c__Users_18210_AppData_Roaming_Cursor_User_workspaceStorage_empty-window_images_FNTV_danmu-39be56dc-1205-4161-85b0-1d85ebbd9510.png",
    ),
    os.path.join(ROOT, "tools", "icon_source.png"),
]
RES = os.path.join(ROOT, "app", "src", "main", "res")

LAUNCHER = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
FOREGROUND = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}


def find_source():
    for p in SRC_CANDIDATES:
        if os.path.isfile(p):
            return p
    raise SystemExit("source icon not found")


def is_blackish(p):
    r, g, b = p[0], p[1], p[2]
    return r <= 18 and g <= 18 and b <= 22


def knock_out(im):
    im = im.convert("RGBA")
    w, h = im.size
    pix = im.load()
    seen = bytearray(w * h)

    def idx(x, y):
        return y * w + x

    q = deque()
    for x, y in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        if is_blackish(pix[x, y]):
            q.append((x, y))
            seen[idx(x, y)] = 1

    while q:
        x, y = q.popleft()
        pix[x, y] = (0, 0, 0, 0)
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if nx < 0 or ny < 0 or nx >= w or ny >= h:
                continue
            i = idx(nx, ny)
            if seen[i]:
                continue
            if is_blackish(pix[nx, ny]):
                seen[i] = 1
                q.append((nx, ny))
    return im


def content_bbox(im, alpha_min=16):
    pix = im.load()
    w, h = im.size
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            if pix[x, y][3] > alpha_min:
                if x < minx:
                    minx = x
                if y < miny:
                    miny = y
                if x > maxx:
                    maxx = x
                if y > maxy:
                    maxy = y
    if maxx < 0:
        raise SystemExit("no opaque pixels found")
    return minx, miny, maxx + 1, maxy + 1


def sample_fill(im):
    pix = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(max(0, w // 2 - 8), min(w, w // 2 + 8)):
            p = pix[x, y]
            if p[3] > 200:
                return (p[0], p[1], p[2], 255)
    return (26, 38, 68, 255)


def squircle_on_navy(im, size, fill):
    cropped = im.crop(content_bbox(im))
    w, h = cropped.size
    side = max(w, h)
    square = Image.new("RGBA", (side, side), fill)
    square.paste(cropped, ((side - w) // 2, (side - h) // 2), cropped)
    return square.resize((size, size), Image.LANCZOS)


def save(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, "PNG")
    print("wrote", os.path.relpath(path, ROOT), im.size, im.mode)


def write_adaptive(fill):
    bg = os.path.join(RES, "drawable", "ic_launcher_background.xml")
    os.makedirs(os.path.dirname(bg), exist_ok=True)
    with open(bg, "w", encoding="utf-8") as f:
        f.write("""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FF%02X%02X%02X"
        android:pathData="M0,0h108v108h-108z" />
</vector>
""" % (fill[0], fill[1], fill[2]))
    print("wrote", os.path.relpath(bg, ROOT))

    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""
    folder = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(folder, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(folder, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(xml)
        print("wrote", os.path.relpath(path, ROOT))


def main():
    src_path = find_source()
    print("source", src_path)
    knocked = knock_out(Image.open(src_path))
    cropped = knocked.crop(content_bbox(knocked))
    fill = sample_fill(cropped)
    print("fill", fill)

    for folder, size in LAUNCHER.items():
        out = squircle_on_navy(knocked, size, fill)
        save(out, os.path.join(RES, folder, "ic_launcher.png"))
        save(out, os.path.join(RES, folder, "ic_launcher_round.png"))
    for folder, size in FOREGROUND.items():
        out = squircle_on_navy(knocked, size, fill)
        save(out, os.path.join(RES, folder, "ic_launcher_foreground.png"))
    write_adaptive(fill)


if __name__ == "__main__":
    main()
