"""打开电脑版窗口。Android 工程不会被这条启动路径编译。"""

import sys
import threading
import webbrowser

from .server import serve
from .service import Desktop


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
        window = webview.create_window("飞牛TV", url, width=1280, height=820, min_size=(960, 640))
        desktop.attach_window(window)
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


def _tk_fallback(url):
    import tkinter as tk

    webbrowser.open(url)
    root = tk.Tk()
    root.title("飞牛TV")
    root.geometry("420x160")
    tk.Label(root, text="已在浏览器中打开飞牛TV。\n关闭这个小窗口会退出播放。", padx=24, pady=18).pack()
    tk.Button(root, text="退出", command=root.destroy, width=12).pack(pady=8)
    root.mainloop()
