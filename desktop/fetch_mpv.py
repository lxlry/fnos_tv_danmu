"""下载 Windows 版 mpv 到 desktop/vendor/mpv，供本机运行和打包使用。"""

import json
import re
import shutil
import subprocess
import tempfile
import urllib.request
from pathlib import Path

API = "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest"
ASSET = re.compile(r"^mpv-x86_64-\d{8}-git-[0-9a-f]+\.7z$")
SEVEN_ZIP = "https://www.7-zip.org/a/7zr.exe"
DEST = Path(__file__).resolve().parent / "vendor" / "mpv"


def main():
    archive = download_mpv()
    extract_root = Path(tempfile.mkdtemp(prefix="fntv-mpv-"))
    try:
        extract(archive, extract_root)
        exes = list(extract_root.rglob("mpv.exe"))
        if not exes:
            raise SystemExit("压缩包里没有 mpv.exe")
        source = min(exes, key=lambda path: len(path.parts)).parent
        if DEST.exists():
            shutil.rmtree(DEST)
        DEST.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(source, DEST)
    finally:
        shutil.rmtree(extract_root, ignore_errors=True)
    print("已放到 %s" % (DEST / "mpv.exe"))


def download_mpv():
    request = urllib.request.Request(API, headers={
        "User-Agent": "FNTVDesktop",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(request, timeout=60) as response:
        release = json.loads(response.read().decode("utf-8"))
    asset = next(item for item in release.get("assets", []) if ASSET.match(item.get("name", "")))
    archive = Path(tempfile.gettempdir()) / asset["name"]
    if not archive.is_file() or archive.stat().st_size < 1000000:
        print("下载 %s" % asset["name"])
        urllib.request.urlretrieve(asset["browser_download_url"], archive)
    else:
        print("使用已下载的 %s" % asset["name"])
    return archive


def extract(archive, dest):
    tool = seven_zip()
    dest.mkdir(parents=True, exist_ok=True)
    subprocess.run([tool, "x", str(archive), "-o%s" % dest, "-y"], check=True)


def seven_zip():
    candidates = [
        shutil.which("7z"),
        shutil.which("7za"),
        shutil.which("7zr"),
        r"C:\Program Files\7-Zip\7z.exe",
        r"C:\Program Files (x86)\7-Zip\7z.exe",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    tool = Path(tempfile.gettempdir()) / "7zr.exe"
    if not tool.is_file():
        print("下载 7zr.exe")
        urllib.request.urlretrieve(SEVEN_ZIP, tool)
    return str(tool)


if __name__ == "__main__":
    main()
