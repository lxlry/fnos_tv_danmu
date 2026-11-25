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
        _bind_frosted_glass(window)
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


def _bind_frosted_glass(window):
    def apply():
        hwnd = _native_hwnd(window)
        if hwnd:
            _apply_acrylic(hwnd)
            _clear_host_background(window)

    def reapply():
        def later():
            hwnd = _native_hwnd(window)
            if hwnd:
                _apply_acrylic(hwnd)

        threading.Timer(0.4, later).start()

    window.events.shown += apply
    window.events.shown += reapply
    window.events.loaded += apply


def _apply_acrylic(hwnd):
    """Windows 11 亚克力。页面本身半透明时，桌面会被磨砂后透出来。"""
    import ctypes
    from ctypes import wintypes

    hwnd = int(hwnd)
    user32 = ctypes.windll.user32
    GWL_EXSTYLE = -20
    WS_EX_NOREDIRECTIONBITMAP = 0x00200000
    get_long = user32.GetWindowLongW
    get_long.restype = ctypes.c_uint32
    get_long.argtypes = [wintypes.HWND, ctypes.c_int]
    style = get_long(hwnd, GWL_EXSTYLE)
    if not (style & WS_EX_NOREDIRECTIONBITMAP):
        user32.SetWindowLongW(hwnd, GWL_EXSTYLE, style | WS_EX_NOREDIRECTIONBITMAP)
        user32.SetWindowPos(hwnd, 0, 0, 0, 0, 0, 0x0027)
    dwm = ctypes.windll.dwmapi
    dwm.DwmSetWindowAttribute.argtypes = [
        wintypes.HWND,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    dwm.DwmSetWindowAttribute.restype = ctypes.c_long
    dark = ctypes.c_int(1)
    dwm.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(dark), 4)
    backdrop = ctypes.c_int(3)  # DWMSBT_TRANSIENTWINDOW，亚克力
    result = dwm.DwmSetWindowAttribute(hwnd, 38, ctypes.byref(backdrop), 4)
    if result != 0:
        mica = ctypes.c_int(2)
        result = dwm.DwmSetWindowAttribute(hwnd, 38, ctypes.byref(mica), 4)
    if result != 0:
        _apply_acrylic_fallback(hwnd)


def _apply_acrylic_fallback(hwnd):
    import ctypes
    from ctypes import wintypes

    class ACCENT_POLICY(ctypes.Structure):
        _fields_ = [
            ("AccentState", ctypes.c_int),
            ("AccentFlags", ctypes.c_int),
            ("GradientColor", ctypes.c_uint),
            ("AnimationId", ctypes.c_int),
        ]

    class WINDOWCOMPOSITIONATTRIBDATA(ctypes.Structure):
        _fields_ = [
            ("Attribute", ctypes.c_int),
            ("Data", ctypes.POINTER(ACCENT_POLICY)),
            ("SizeOfData", ctypes.c_size_t),
        ]

    accent = ACCENT_POLICY()
    accent.AccentState = 4
    accent.AccentFlags = 2
    accent.GradientColor = 0x9910141A
    data = WINDOWCOMPOSITIONATTRIBDATA()
    data.Attribute = 19
    data.Data = ctypes.pointer(accent)
    data.SizeOfData = ctypes.sizeof(accent)
    try:
        set_attribute = ctypes.windll.user32.SetWindowCompositionAttribute
        set_attribute.argtypes = [wintypes.HWND, ctypes.POINTER(WINDOWCOMPOSITIONATTRIBDATA)]
        set_attribute.restype = wintypes.BOOL
        set_attribute(int(hwnd), ctypes.byref(data))
    except Exception:
        return


def _clear_host_background(window):
    native = getattr(window, "native", None)
    if native is None:
        return
    try:
        from System import Array, Boolean, Object
        from System.Drawing import Color
        from System.Reflection import BindingFlags
        from System.Windows.Forms import ControlStyles

        method = native.GetType().GetMethod(
            "SetStyle",
            BindingFlags.Instance | BindingFlags.NonPublic,
        )
        if method is not None:
            method.Invoke(
                native,
                Array[Object]([ControlStyles.SupportsTransparentBackColor, Boolean(True)]),
            )
        native.BackColor = Color.Transparent
    except Exception:
        pass
    control = getattr(native, "webview", None)
    if control is None:
        browser = getattr(native, "browser", None)
        control = getattr(browser, "webview", None) if browser is not None else None
    if control is None:
        return
    try:
        from System.Drawing import Color

        control.DefaultBackgroundColor = Color.Transparent
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
