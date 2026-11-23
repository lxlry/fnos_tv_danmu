"""把 dist/FNTV 打成 7-Zip 自解压 exe。双击后复制到本机目录并启动。"""

import json
import shutil
import subprocess
import tempfile
import urllib.request
from pathlib import Path

from fetch_mpv import seven_zip

SDK_URLS = [
    "https://www.7-zip.org/a/lzma2604.7z",
    "https://www.7-zip.org/a/lzma2501.7z",
]
ROOT = Path(__file__).resolve().parent
CONFIG = (
    ";!@Install@!UTF-8!\n"
    'Title="飞牛TV"\n'
    'Progress="yes"\n'
    'RunProgram="install.bat"\n'
    ";!@InstallEnd@!\n"
)
INSTALL_BAT = r"""@echo off
set "DEST=%LocalAppData%\FNTV"
taskkill /IM FNTV.exe /F >nul 2>&1
mkdir "%DEST%" 2>nul
robocopy "%~dp0." "%DEST%" /E /NFL /NDL /NJH /NJS /nc /ns /np >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$w=New-Object -ComObject WScript.Shell; $s=$w.CreateShortcut((Join-Path $w.SpecialFolders('Desktop') '飞牛TV.lnk')); $s.TargetPath=(Join-Path $env:LocalAppData 'FNTV\FNTV.exe'); $s.WorkingDirectory=(Join-Path $env:LocalAppData 'FNTV'); $s.Save()"
start "" "%DEST%\FNTV.exe"
exit /b 0
"""


def main():
    source = ROOT / "dist" / "FNTV"
    exe = source / "FNTV.exe"
    if not exe.is_file():
        raise SystemExit("还没有 dist\\FNTV\\FNTV.exe，请先完成打包")
    version = json.loads((ROOT / "version.json").read_text(encoding="utf-8"))["versionName"].strip()
    if not version:
        raise SystemExit("desktop/version.json 里没有 versionName")
    (source / "install.bat").write_text(INSTALL_BAT, encoding="ascii")
    payload = ROOT / "dist" / "payload.7z"
    if payload.exists():
        payload.unlink()
    subprocess.run(
        [seven_zip(), "a", "-t7z", "-mx=5", "-mmt=on", str(payload), "*"],
        cwd=source,
        check=True,
    )
    sfx = sfx_module()
    out = ROOT / "dist" / ("FNTV_windows_v%s.exe" % version)
    with out.open("wb") as dest:
        dest.write(Path(sfx).read_bytes())
        dest.write(CONFIG.encode("utf-8"))
        with payload.open("rb") as packed:
            shutil.copyfileobj(packed, dest)
    payload.unlink(missing_ok=True)
    info = ROOT / "dist" / "release-info.txt"
    info.write_text(
        "version=%s\ntag=desktop-%s\nfile=desktop/dist/%s\n" % (version, version, out.name),
        encoding="utf-8",
    )
    print("已生成 %s" % out)


def sfx_module():
    cached = Path(tempfile.gettempdir()) / "fntv-lzma-sdk"
    found = list(cached.rglob("7zSD.sfx")) if cached.exists() else []
    if not found:
        archive = Path(tempfile.gettempdir()) / "lzma-sdk.7z"
        download_sdk(archive)
        if cached.exists():
            shutil.rmtree(cached)
        cached.mkdir(parents=True, exist_ok=True)
        subprocess.run([seven_zip(), "x", str(archive), "-o%s" % cached, "-y"], check=True)
        found = list(cached.rglob("7zSD.sfx"))
    if not found:
        raise SystemExit("LZMA SDK 里没有 7zSD.sfx")
    return found[0]


def download_sdk(dest):
    last = None
    for url in SDK_URLS:
        try:
            print("下载 %s" % url)
            urllib.request.urlretrieve(url, dest)
            if dest.stat().st_size > 10000:
                return
        except Exception as err:
            last = err
    raise SystemExit("下载 7-Zip 自解压模块失败：%s" % last)


if __name__ == "__main__":
    main()
