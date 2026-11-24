# 飞牛TV 电脑版

独立于 Android 工程。不修改 `app`、Gradle 和 APK 打包，编译 exe 也不会把 Android 源码编进去。

## 能做什么

登录飞牛影视、浏览媒体库、搜索、查看详情和剧集，用 mpv 播放。鼠标移到画面底部会出现字幕、音轨、弹幕开关、倍速和全屏。平时只留一条细进度线。播放进度会写回飞牛。配置了弹幕服务器时，弹幕会转成字幕挂在 mpv 上。剧集可以自动下一集。关闭播放窗口即停止播放。

电脑版使用局域网地址，例如 `http://192.168.1.10:5666`。飞牛 ID 远程登录还没做。

## 直接运行

需要 Python 3.9 或更高版本。播放器用随包提供的 mpv，界面是 uosc。不用单独安装。源码直接运行前，先在 `desktop` 目录执行 `py -3 fetch_mpv.py` 和 `py -3 fetch_uosc.py`。

```bat
py -3 desktop\main.py
```

装了 `pywebview` 时会打开独立窗口；没装就用系统浏览器打开本机页面。

```bat
py -3 -m pip install -r desktop\requirements.txt
```

## 打成 exe

推送到 `main` 或 `master` 后，GitHub Actions 的 Build EXE 会在 Windows 上打安装程序。版本号写在 `desktop/version.json`，当前是 `1.0.4`。发布文件是 `FNTV_windows_v1.0.4.exe`，发布页标签是 `desktop-1.0.4`，和 APK 不是同一个发布页。双击后可以选择安装目录，桌面会有「飞牛TV」快捷方式，里面已带 mpv。卸载从 Windows 的“设置 → 应用”里找到飞牛TV。卸载向导第一页底部有“同时删除已保存的账号信息”，默认不勾选；只有勾上才会删除登录记录。

更新说明在 `desktop/update.json`。程序启动时会按 Android 端同样的方式检查：比对版本号，确认发布页里已经有这个 exe，再提示下载。只改电脑版时不会重新打 APK。也可以在 Actions 页单独手动运行。

本地打包：在 `desktop` 目录运行 `build_exe.bat`。设置里仍可以改用别的 `mpv.exe`。

登录信息保存在本机 `%APPDATA%\FNTVDesktop\config.json`。勾选记住密码时，密码写在这个文件里。
