"""检查电脑版更新。规则与 Android 端 UpdateManager 一致：读更新说明、比对版本号、确认发布页已有安装包后再提示下载。"""

import json
import os
import re
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

UPDATE_URLS = [
    "https://raw.githubusercontent.com/lxlry/fnos_tv_danmu/master/desktop/update.json",
    "https://jsd.onmicrosoft.cn/gh/lxlry/fnos_tv_danmu@master/desktop/update.json",
    "https://cdn.jsdelivr.net/gh/lxlry/fnos_tv_danmu@master/desktop/update.json",
    "https://fastly.jsdelivr.net/gh/lxlry/fnos_tv_danmu@master/desktop/update.json",
]
RELEASE_URL = re.compile(r"github\.com/([^/]+/[^/]+)/releases/download/([^/]+)/([^?#]+)")
WEB_SSL = ssl.create_default_context()


def local_version():
    for path in _version_files():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        name = str(data.get("versionName") or "").strip()
        try:
            code = int(data.get("versionCode") or 0)
        except (TypeError, ValueError):
            code = 0
        if name or code:
            return {"versionName": name or "0", "versionCode": code}
    return {"versionName": "0", "versionCode": 0}


def _version_files():
    import sys
    paths = []
    if getattr(sys, "frozen", False):
        meipass = getattr(sys, "_MEIPASS", "")
        if meipass:
            paths.append(Path(meipass) / "version.json")
    paths.append(Path(__file__).resolve().parents[1] / "version.json")
    return paths


class Updater:
    def __init__(self):
        self._lock = threading.Lock()
        self._download = {"status": "idle", "received": 0, "total": 0, "path": "", "message": ""}

    def check(self, quiet=False):
        current = local_version()
        try:
            info = _fetch_update_json()
        except Exception:
            if quiet:
                return {"status": "failed"}
            return {"status": "failed", "message": "检查更新失败，无法连接更新服务器"}
        remote_code = int(info.get("versionCode") or 0)
        version_name = str(info.get("versionName") or "")
        exe_url = str(info.get("exeUrl") or "")
        changelog = str(info.get("changelog") or "暂无更新说明")
        force = bool(info.get("forceUpdate"))
        if remote_code <= current["versionCode"]:
            if quiet:
                return {"status": "latest", "versionName": current["versionName"]}
            return {"status": "latest", "message": "已是最新版本", "versionName": current["versionName"]}
        if not exe_url or not _release_ready(exe_url):
            if quiet:
                return {"status": "pending"}
            return {"status": "pending", "message": "新版本还在打包，请稍后再试"}
        return {
            "status": "available",
            "versionName": version_name,
            "changelog": changelog,
            "exeUrl": exe_url,
            "force": force,
            "title": "发现新版本 v" + version_name,
        }

    def start_download(self, exe_url):
        with self._lock:
            if self._download.get("status") == "downloading":
                return {"ok": True}
            self._download = {"status": "downloading", "received": 0, "total": 0, "path": "", "message": ""}
        threading.Thread(target=self._download_file, args=(exe_url,), daemon=True).start()
        return {"ok": True}

    def progress(self):
        with self._lock:
            return dict(self._download)

    def apply(self):
        with self._lock:
            path = self._download.get("path") or ""
            status = self._download.get("status")
        if status != "done" or not path or not Path(path).is_file():
            raise RuntimeError("安装包还没下载完成")
        _launch_and_exit(path)
        return {"ok": True}

    def _download_file(self, exe_url):
        name = "FNTV-update.exe"
        match = RELEASE_URL.search(exe_url or "")
        if match:
            name = urllib.parse.unquote(match.group(3))
        dest = Path(tempfile.gettempdir()) / name
        try:
            request = urllib.request.Request(exe_url, headers={"User-Agent": "FNTV"})
            with urllib.request.urlopen(request, context=WEB_SSL, timeout=60) as response:
                total = int(response.headers.get("Content-Length") or 0)
                received = 0
                with dest.open("wb") as output:
                    while True:
                        chunk = response.read(256 * 1024)
                        if not chunk:
                            break
                        output.write(chunk)
                        received += len(chunk)
                        with self._lock:
                            self._download["received"] = received
                            self._download["total"] = total
            with self._lock:
                self._download.update({"status": "done", "path": str(dest), "received": dest.stat().st_size})
        except Exception as err:
            with self._lock:
                self._download.update({"status": "failed", "message": "下载失败：%s" % err})


def _fetch_update_json():
    stamp = time.strftime("%Y%m%d%H%M")
    last_error = None
    for url in UPDATE_URLS:
        target = url
        if "raw.githubusercontent.com" not in url:
            target += ("&" if "?" in url else "?") + "t=" + stamp
        try:
            request = urllib.request.Request(target, headers={"User-Agent": "FNTV"})
            with urllib.request.urlopen(request, context=WEB_SSL, timeout=8) as response:
                if response.status != 200:
                    continue
                return json.loads(response.read().decode("utf-8"))
        except Exception as err:
            last_error = err
    raise RuntimeError(last_error or "no update source")


def _release_ready(exe_url):
    match = RELEASE_URL.search(exe_url or "")
    if not match:
        return False
    repo, tag, file_name = match.group(1), urllib.parse.unquote(match.group(2)), urllib.parse.unquote(match.group(3))
    if not tag or not file_name:
        return False
    api = "https://api.github.com/repos/%s/releases/tags/%s" % (repo, urllib.parse.quote(tag))
    for url in (api, "https://ghfast.top/" + api):
        ready = _release_has_asset(url, file_name)
        if ready is not None:
            return ready
    return False


def _release_has_asset(url, file_name):
    try:
        request = urllib.request.Request(url, headers={
            "User-Agent": "FNTV",
            "Accept": "application/vnd.github+json",
        })
        with urllib.request.urlopen(request, context=WEB_SSL, timeout=8) as response:
            if response.status == 404:
                return False
            if response.status != 200:
                return None
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        if err.code == 404:
            return False
        return None
    except Exception:
        return None
    assets = payload.get("assets") if isinstance(payload, dict) else None
    if not isinstance(assets, list):
        return False
    for asset in assets:
        if not isinstance(asset, dict) or asset.get("name") != file_name:
            continue
        state = asset.get("state") or ""
        return state in ("", "uploaded")
    return False


def _launch_and_exit(installer):
    script = Path(tempfile.gettempdir()) / "fntv-update.bat"
    script.write_text(
        '@echo off\r\ntimeout /t 2 /nobreak >nul\r\nstart "" "%s"\r\n' % installer,
        encoding="ascii",
        errors="replace",
    )
    subprocess.Popen(
        ["cmd", "/c", str(script)],
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        close_fds=True,
    )
    threading.Timer(0.4, lambda: os._exit(0)).start()
