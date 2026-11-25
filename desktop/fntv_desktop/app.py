"""打开电脑版窗口。Android 工程不会被这条启动路径编译。"""

import sys
import threading
import webbrowser

from .player import _native_hwnd, main_window_bounds
from .server import serve
from .service import Desktop
from .tray import bind_close_prompt


def main():
    desktop = Desktop()
    httpd = serve(desktop)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    url = "http://127.0.0.1:%s/" % desktop.port
    try:
        _open_window(url, desktop)
    finally:
        try:
            desktop.stop()
        except Exception:
            pass
        httpd.shutdown()


def _open_window(url, desktop):
    try:
        import webview
        width, height, x, y = main_window_bounds()
        options = {
            "width": width,
            "height": height,
            "min_size": (min(960, width), min(640, height)),
            "background_color": "#10141a",
        }
        if x is not None:
            options["x"] = x
            options["y"] = y
        window = webview.create_window("飞牛TV", url, **options)
        desktop.attach_window(window)
        bind_close_prompt(window)
        _bind_dark_frame(window)
        webview.start()
        return
    except Exception:
        pass
    if getattr(sys, "frozen", False):
        _tk_fallback(url)
        return
    webbrowser.open(url)
    print("飞牛TV 电脑版已在浏览器打开：%s" % url)
    print("关闭这个窗口会退出。安装 pywebview 后会使用独立窗口。")
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        return


def _bind_dark_frame(window):
    def apply():
        hwnd = _native_hwnd(window)
        if hwnd:
            _use_dark_title_bar(hwnd)
        _paint_dark_host(window)

    window.events.shown += apply
    window.events.loaded += apply


def _use_dark_title_bar(hwnd):
    import ctypes
    from ctypes import wintypes

    dwm = ctypes.windll.dwmapi
    dwm.DwmSetWindowAttribute.argtypes = [
        wintypes.HWND,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    dwm.DwmSetWindowAttribute.restype = ctypes.c_long
    dark = ctypes.c_int(1)
    dwm.DwmSetWindowAttribute(int(hwnd), 20, ctypes.byref(dark), 4)


def _paint_dark_host(window):
    native = getattr(window, "native", None)
    if native is None:
        return
    try:
        from System.Drawing import Color

        color = Color.FromArgb(255, 16, 20, 26)
        native.BackColor = color
    except Exception:
        return
    control = getattr(native, "webview", None)
    if control is None:
        browser = getattr(native, "browser", None)
        control = getattr(browser, "webview", None) if browser is not None else None
    if control is None:
        return
    try:
        from System.Drawing import Color

        control.DefaultBackgroundColor = Color.FromArgb(255, 16, 20, 26)
    except Exception:
        return


def _tk_fallback(url):
    import tkinter as tk

    webbrowser.open(url)
    root = tk.Tk()
    root.title("飞牛TV")
    root.geometry("420x160")
    tk.Label(root, text="已在浏览器中打开飞牛TV。\n关闭这个小窗口会退出播放。", padx=24, pady=18).pack()
    tk.Button(root, text="退出", command=root.destroy, width=12).pack(pady=8)
    root.mainloop()
