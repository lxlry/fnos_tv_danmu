"""下载 uosc 到 desktop/vendor/uosc，作为 mpv 的界面配置目录。"""

import io
import os
import shutil
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

API = "https://api.github.com/repos/tomasklaen/uosc/releases/latest"
PAGE = "https://github.com/tomasklaen/uosc/releases/latest"
DOWNLOAD_PREFIXES = ("https://ghfast.top/", "https://gh-proxy.com/")
DEST = Path(__file__).resolve().parent / "vendor" / "uosc"
CONF = """# 飞牛TV 播放器界面。底部控件按 uosc_danmaku 的排布。
timeline_style=line
timeline_line_width=2
timeline_size=40
progress=windowed
progress_size=2
controls=menu,gap,<has_many_audio>audio,<has_many_video>video,<has_many_edition>editions,gap,space,speed,space,gap,subtitles,command:toggle_on:script-message danmaku-settings?弹幕,command:schedule:script-message skip-settings?片头尾,gap,fullscreen
controls_size=32
controls_margin=8
controls_spacing=2
volume=right
volume_size=40
top_bar=no-border
top_bar_size=40
top_bar_controls=right
top_bar_title=yes
window_border_size=0
border_radius=4
autohide=yes
pause_indicator=flash
font_scale=1
text_border=1.2
color=foreground=ffffff,foreground_text=000000,background=000000,background_text=ffffff,curtain=111111,success=a5e075,error=ff616e,window_border=ffffff
languages=zh-hans
"""
MPV_CONF = """osc=no
osd-bar=no
border=yes
cursor-autohide=800
keep-open=no
"""
INPUT_CONF = """default-bindings start

mbtn_left cycle pause; script-binding uosc/flash-pause-indicator
mbtn_right script-binding uosc/menu-blurred
space cycle pause; script-binding uosc/flash-pause-indicator
right seek 5
left seek -5
shift+right seek 30; script-binding uosc/flash-timeline
shift+left seek -30; script-binding uosc/flash-timeline
up no-osd add volume 5; script-binding uosc/flash-volume
down no-osd add volume -5; script-binding uosc/flash-volume
m no-osd cycle mute; script-binding uosc/flash-volume
f cycle fullscreen
[ no-osd add speed -0.25; script-binding uosc/flash-speed
] no-osd add speed 0.25; script-binding uosc/flash-speed
\\ no-osd set speed 1; script-binding uosc/flash-speed

s script-binding uosc/subtitles #! 字幕
a script-binding uosc/audio #! 音轨
c script-binding uosc/chapters #! 章节
# script-binding uosc/audio-device #! 音频设备
ctrl+s async screenshot #! 截图
f cycle fullscreen #! 全屏
d script-message danmaku-settings #! 弹幕设置
i script-message skip-settings #! 片头尾
esc quit #! 退出
"""


def main():
    archive = download_zip()
    if DEST.exists():
        shutil.rmtree(DEST)
    DEST.mkdir(parents=True)
    with zipfile.ZipFile(io.BytesIO(archive)) as packed:
        packed.extractall(DEST)
    drop_other_platforms(DEST)
    main_lua = DEST / "scripts" / "uosc" / "main.lua"
    if not main_lua.is_file():
        raise SystemExit("uosc 压缩包里没有 scripts/uosc/main.lua")
    (DEST / "script-opts").mkdir(exist_ok=True)
    (DEST / "script-opts" / "uosc.conf").write_text(CONF, encoding="utf-8")
    (DEST / "mpv.conf").write_text(MPV_CONF, encoding="utf-8")
    (DEST / "input.conf").write_text(INPUT_CONF, encoding="utf-8")
    print("已放到 %s" % DEST)


def download_zip():
    asset = next(item for item in latest_release().get("assets", []) if item.get("name") == "uosc.zip")
    print("下载 %s" % asset["name"])
    return read_url(asset["browser_download_url"], minimum=100000)


def latest_release():
    headers = {"User-Agent": "FNTVDesktop", "Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(API, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return __import__("json").loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        if err.code not in (403, 429):
            raise
        print("GitHub 接口被限流，改从发布页查找")
        return {
            "assets": [{
                "name": "uosc.zip",
                "browser_download_url": PAGE + "/download/uosc.zip",
            }]
        }


def read_url(url, minimum):
    urls = [url]
    if "github.com/" in url:
        urls.extend(prefix + url for prefix in DOWNLOAD_PREFIXES)
    last = "未知错误"
    for one in urls:
        try:
            request = urllib.request.Request(one, headers={"User-Agent": "FNTVDesktop"})
            with urllib.request.urlopen(request, timeout=180) as response:
                data = response.read()
            if len(data) >= minimum:
                return data
            last = "文件太小"
        except Exception as exc:
            last = str(exc)
    raise SystemExit("下载 uosc 失败: %s" % last)


def drop_other_platforms(root):
    binary_dir = root / "scripts" / "uosc" / "bin"
    if not binary_dir.is_dir():
        return
    for path in binary_dir.iterdir():
        name = path.name.lower()
        if "windows" in name or name.endswith(".exe"):
            continue
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()


if __name__ == "__main__":
    main()
