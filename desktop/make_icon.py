"""用 APK 启动图标生成 Windows 的 fntv.ico。"""

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
CANDIDATES = [
    ROOT.parent / "app" / "src" / "main" / "ic_launcher-playstore.png",
    ROOT.parent / "app" / "src" / "main" / "res" / "mipmap-xxxhdpi" / "ic_launcher.png",
]
SIZES = [(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)]


def main():
    source = next((path for path in CANDIDATES if path.is_file()), None)
    if source is None:
        raise SystemExit("没有找到 APK 图标")
    image = Image.open(source).convert("RGBA")
    dest = ROOT / "fntv.ico"
    image.save(dest, format="ICO", sizes=SIZES)
    print("已生成 %s，来源 %s" % (dest, source))


if __name__ == "__main__":
    sys.exit(main())
