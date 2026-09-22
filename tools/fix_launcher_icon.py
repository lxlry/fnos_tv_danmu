#!/usr/bin/env python3
"""Build launcher icons from the iOS squircle: knock out black, no extra plate."""
import os
import shutil
from collections import deque
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
SRC_CANDIDATES = [
    os.path.join(
        os.path.expanduser("~"),
        ".cursor", "projects", "d-myCode-fnos-tv-danmu", "assets",
        "c__Users_18210_AppData_Roaming_Cursor_User_workspaceStorage_empty-window_images_FNTV_danmu-fd017826-8388-494e-b5af-8e26d213d395.png",
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


def ios_squircle(im, size):
    """Tight iOS rounded square on transparent, filling the mipmap."""
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


def write_tv_banner(squircle):
    """320x180 Leanback banner using the same iOS squircle."""
    from PIL import ImageDraw, ImageFont

    w, h = 320, 180
    bg = Image.new("RGBA", (w, h), (22, 28, 44, 255))
    icon = squircle.resize((118, 118), Image.LANCZOS)
    bg.paste(icon, (24, (h - 118) // 2), icon)
    draw = ImageDraw.Draw(bg)
    font = None
    for path in (
        r"C:\Windows\Fonts\segoeuib.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ):
        if os.path.isfile(path):
            font = ImageFont.truetype(path, 42)
            break
    if font is None:
        font = ImageFont.load_default()
    draw.text((156, 90), "FN TV", font=font, fill=(255, 255, 255, 255), anchor="lm")
    out = bg.convert("RGB")
    save(out, os.path.join(RES, "drawable", "tv_banner.png"))
    save(out, os.path.join(RES, "drawable-xhdpi", "tv_banner.png"))


def remove_adaptive_layers():
    """API 26+ adaptive mask is what clips the art or adds a gray plate."""
    removed = []
    folder = os.path.join(RES, "mipmap-anydpi-v26")
    if os.path.isdir(folder):
        shutil.rmtree(folder)
        removed.append(os.path.relpath(folder, ROOT))
    bg = os.path.join(RES, "drawable", "ic_launcher_background.xml")
    if os.path.isfile(bg):
        os.remove(bg)
        removed.append(os.path.relpath(bg, ROOT))
    for folder in LAUNCHER:
        fg = os.path.join(RES, folder, "ic_launcher_foreground.png")
        if os.path.isfile(fg):
            os.remove(fg)
            removed.append(os.path.relpath(fg, ROOT))
    for path in removed:
        print("removed", path)


def main():
    src_path = find_source()
    print("source", src_path)
    local = os.path.join(ROOT, "tools", "icon_source.png")
    if os.path.normpath(src_path) != os.path.normpath(local):
        shutil.copy2(src_path, local)
        print("copied", os.path.relpath(local, ROOT))

    knocked = knock_out(Image.open(src_path))
    box = content_bbox(knocked)
    print("content_bbox", box)

    remove_adaptive_layers()
    master = ios_squircle(knocked, 1024)
    for folder, size in LAUNCHER.items():
        out = master.resize((size, size), Image.LANCZOS)
        save(out, os.path.join(RES, folder, "ic_launcher.png"))
        save(out, os.path.join(RES, folder, "ic_launcher_round.png"))
    write_tv_banner(master)


if __name__ == "__main__":
    main()
