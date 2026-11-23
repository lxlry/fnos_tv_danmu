"""下载 Windows 版 mpv 到 desktop/vendor/mpv，供本机运行和打包使用。"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

API = "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest"
PAGE = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/latest"
ASSET = re.compile(r"^mpv-x86_64-\d{8}-git-[0-9a-f]+\.7z$")
ASSET_IN_PAGE = re.compile(r"mpv-x86_64-\d{8}-git-[0-9a-f]+\.7z")
DOWNLOAD_PREFIXES = ("https://ghfast.top/", "https://gh-proxy.com/")
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
    release = latest_release()
    asset = next(item for item in release.get("assets", []) if ASSET.match(item.get("name", "")))
    archive = Path(tempfile.gettempdir()) / asset["name"]
    if not archive.is_file() or archive.stat().st_size < 1000000:
        print("下载 %s" % asset["name"])
        save_url(asset["browser_download_url"], archive)
    else:
        print("使用已下载的 %s" % asset["name"])
    return archive


def latest_release():
    headers = {
        "User-Agent": "FNTVDesktop",
        "Accept": "application/vnd.github+json",
    }
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(API, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        if err.code not in (403, 429):
            raise
        print("GitHub 接口被限流，改从发布页查找")
        return release_from_page()


def release_from_page():
    request = urllib.request.Request(PAGE, headers={"User-Agent": "FNTVDesktop"})
    with urllib.request.urlopen(request, timeout=60) as response:
        html = response.read().decode("utf-8", "replace")
        final = response.geturl()
    names = ASSET_IN_PAGE.findall(html)
    if not names:
        raise SystemExit("发布页里没有找到 mpv 压缩包")
    tag = final.rstrip("/").split("/")[-1]
    name = names[0]
    url = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/%s/%s" % (tag, name)
    return {"assets": [{"name": name, "browser_download_url": url}]}


def save_url(url, dest):
    urls = [url]
    if url.startswith("https://github.com/"):
        urls.extend(prefix + url for prefix in DOWNLOAD_PREFIXES)
    last = "未知错误"
    for one in urls:
        try:
            request = urllib.request.Request(one, headers={"User-Agent": "FNTVDesktop"})
            with urllib.request.urlopen(request, timeout=180) as response:
                dest.write_bytes(response.read())
            if dest.is_file() and dest.stat().st_size > 1000000:
                return
            last = "文件太小"
        except Exception as exc:
            last = str(exc)
    raise SystemExit("下载 mpv 失败: %s" % last)


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
