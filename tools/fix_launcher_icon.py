#!/usr/bin/env python3
"""Keep the iOS squircle, knock black canvas out to transparent."""
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

    # Soften the anti-aliased fringe next to knocked-out pixels.
    out = im.copy()
    src = im.load()
    dst = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = src[x, y]
            if a == 0:
                continue
            near_clear = False
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < w and 0 <= ny < h and src[nx, ny][3] == 0:
                    near_clear = True
                    break
            if not near_clear:
                continue
            luma = (r + g + b) / 3.0
            if luma < 48:
                alpha = max(0, min(255, int(255 * (luma / 48.0))))
                dst[x, y] = (r, g, b, alpha)
    return out


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


def fit_square(im, size):
    """Crop to the squircle, then fill the launcher square (keep rounded corners)."""
    cropped = im.crop(content_bbox(im))
    w, h = cropped.size
    side = max(w, h)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(cropped, ((side - w) // 2, (side - h) // 2), cropped)
    return square.resize((size, size), Image.LANCZOS)


def save(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, "PNG")
    print("wrote", os.path.relpath(path, ROOT), im.size, im.mode)


def main():
    src_path = find_source()
    print("source", src_path)
    src = Image.open(src_path)
    print("loaded", src.size, src.mode)
    knocked = knock_out(src)
    corner = knocked.getpixel((0, 0))
    mid = knocked.getpixel((knocked.size[0] // 2, knocked.size[1] // 2))
    print("corner", corner, "center", mid)

    for folder, size in LAUNCHER.items():
        out = fit_square(knocked, size)
        save(out, os.path.join(RES, folder, "ic_launcher.png"))
        save(out, os.path.join(RES, folder, "ic_launcher_round.png"))
        save(out, os.path.join(RES, folder, "ic_launcher_foreground.png"))


if __name__ == "__main__":
    main()
