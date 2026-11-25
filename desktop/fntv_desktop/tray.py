"""主窗口关闭时询问，最小化后只留托盘图标。"""

import ctypes
import sys
import threading
from ctypes import wintypes
from pathlib import Path

from .player import _native_hwnd

user32 = ctypes.windll.user32
shell32 = ctypes.windll.shell32
comctl32 = ctypes.windll.comctl32

WM_CLOSE = 0x0010
WM_DESTROY = 0x0002
WM_LBUTTONUP = 0x0202
WM_LBUTTONDBLCLK = 0x0203
WM_RBUTTONUP = 0x0205
WM_COMMAND = 0x0111
WM_APP = 0x8000
WM_TRAY = WM_APP + 1
WM_TRAY_REMOVE = WM_APP + 2
NIM_ADD = 0x00000000
NIM_DELETE = 0x00000002
NIF_MESSAGE = 0x00000001
NIF_ICON = 0x00000002
NIF_TIP = 0x00000004
NIF_SHOWTIP = 0x00000080
IMAGE_ICON = 1
LR_LOADFROMFILE = 0x00000010
LR_DEFAULTSIZE = 0x00000040
HWND_MESSAGE = wintypes.HWND(-3)
IDI_APPLICATION = 32512
MINIMIZE_ID = 1001
QUIT_ID = 1002
MENU_SHOW = 1
MENU_QUIT = 2
TDF_ALLOW_DIALOG_CANCELLATION = 0x0008
TDF_POSITION_RELATIVE_TO_WINDOW = 0x1000

LRESULT = ctypes.c_ssize_t
WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)

user32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.DefWindowProcW.restype = LRESULT
user32.CreateWindowExW.argtypes = [
    wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
    ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID,
]
user32.CreateWindowExW.restype = wintypes.HWND
user32.DestroyWindow.argtypes = [wintypes.HWND]
user32.RegisterClassW.argtypes = [ctypes.c_void_p]
user32.RegisterClassW.restype = wintypes.ATOM
shell32.ExtractIconW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR, wintypes.UINT]
shell32.ExtractIconW.restype = wintypes.HICON
user32.GetMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND, wintypes.UINT, wintypes.UINT]
user32.GetMessageW.restype = ctypes.c_int
user32.TranslateMessage.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.DispatchMessageW.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
user32.PostQuitMessage.argtypes = [ctypes.c_int]
user32.MessageBoxW.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.UINT]
user32.MessageBoxW.restype = ctypes.c_int
user32.CreatePopupMenu.restype = wintypes.HMENU
user32.AppendMenuW.argtypes = [wintypes.HMENU, wintypes.UINT, ctypes.c_size_t, wintypes.LPCWSTR]
user32.TrackPopupMenu.argtypes = [
    wintypes.HMENU, wintypes.UINT, ctypes.c_int, ctypes.c_int,
    ctypes.c_int, wintypes.HWND, ctypes.c_void_p,
]
user32.TrackPopupMenu.restype = wintypes.UINT
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
user32.SetForegroundWindow.argtypes = [wintypes.HWND]
shell32.Shell_NotifyIconW.argtypes = [wintypes.DWORD, ctypes.c_void_p]
shell32.Shell_NotifyIconW.restype = wintypes.BOOL
user32.LoadIconW.argtypes = [wintypes.HINSTANCE, ctypes.c_void_p]
user32.LoadIconW.restype = wintypes.HICON


class TASKDIALOG_BUTTON(ctypes.Structure):
    _pack_ = 1
    _fields_ = [
        ("nButtonID", ctypes.c_int),
        ("pszButtonText", wintypes.LPCWSTR),
    ]


class TASKDIALOGCONFIG(ctypes.Structure):
    # commctrl.h 里这个结构和按钮都按 1 字节对齐，64 位下大小是 160。
    _pack_ = 1
    _fields_ = [
        ("cbSize", wintypes.UINT),
        ("hwndParent", wintypes.HWND),
        ("hInstance", wintypes.HINSTANCE),
        ("dwFlags", wintypes.UINT),
        ("dwCommonButtons", wintypes.UINT),
        ("pszWindowTitle", wintypes.LPCWSTR),
        ("hMainIcon", wintypes.HICON),
        ("pszMainInstruction", wintypes.LPCWSTR),
        ("pszContent", wintypes.LPCWSTR),
        ("cButtons", wintypes.UINT),
        ("pButtons", ctypes.POINTER(TASKDIALOG_BUTTON)),
        ("nDefaultButton", ctypes.c_int),
        ("cRadioButtons", wintypes.UINT),
        ("pRadioButtons", ctypes.POINTER(TASKDIALOG_BUTTON)),
        ("nDefaultRadioButton", ctypes.c_int),
        ("pszVerificationText", wintypes.LPCWSTR),
        ("pszExpandedInformation", wintypes.LPCWSTR),
        ("pszExpandedControlText", wintypes.LPCWSTR),
        ("pszCollapsedControlText", wintypes.LPCWSTR),
        ("hFooterIcon", wintypes.HICON),
        ("pszFooter", wintypes.LPCWSTR),
        ("pfCallback", ctypes.c_void_p),
        ("lpCallbackData", ctypes.c_ssize_t),
        ("cxWidth", wintypes.UINT),
    ]


comctl32.TaskDialogIndirect.argtypes = [
    ctypes.POINTER(TASKDIALOGCONFIG),
    ctypes.POINTER(ctypes.c_int),
    ctypes.c_void_p,
    ctypes.c_void_p,
]
comctl32.TaskDialogIndirect.restype = ctypes.c_long


class NOTIFYICONDATAW(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("hWnd", wintypes.HWND),
        ("uID", wintypes.UINT),
        ("uFlags", wintypes.UINT),
        ("uCallbackMessage", wintypes.UINT),
        ("hIcon", wintypes.HICON),
        ("szTip", wintypes.WCHAR * 128),
        ("dwState", wintypes.DWORD),
        ("dwStateMask", wintypes.DWORD),
        ("szInfo", wintypes.WCHAR * 256),
        ("uVersion", wintypes.UINT),
        ("szInfoTitle", wintypes.WCHAR * 64),
        ("dwInfoFlags", wintypes.DWORD),
        ("guidItem", ctypes.c_byte * 16),
        ("hBalloonIcon", wintypes.HICON),
    ]


class WNDCLASSW(ctypes.Structure):
    _fields_ = [
        ("style", wintypes.UINT),
        ("lpfnWndProc", WNDPROC),
        ("cbClsExtra", ctypes.c_int),
        ("cbWndExtra", ctypes.c_int),
        ("hInstance", wintypes.HINSTANCE),
        ("hIcon", wintypes.HICON),
        ("hCursor", wintypes.HANDLE),
        ("hbrBackground", wintypes.HBRUSH),
        ("lpszMenuName", wintypes.LPCWSTR),
        ("lpszClassName", wintypes.LPCWSTR),
    ]


def ask_close_action(hwnd=0):
    """返回 minimize、quit，或 None（取消，窗口保持原样）。"""
    buttons = (TASKDIALOG_BUTTON * 2)(
        TASKDIALOG_BUTTON(MINIMIZE_ID, "最小化"),
        TASKDIALOG_BUTTON(QUIT_ID, "直接关闭"),
    )
    config = TASKDIALOGCONFIG()
    config.cbSize = ctypes.sizeof(TASKDIALOGCONFIG)
    config.hwndParent = wintypes.HWND(hwnd or 0)
    config.dwFlags = TDF_ALLOW_DIALOG_CANCELLATION | (TDF_POSITION_RELATIVE_TO_WINDOW if hwnd else 0)
    config.pszWindowTitle = "飞牛TV"
    config.pszMainInstruction = "要最小化还是直接关闭？"
    config.pszContent = "最小化后窗口会收起，只在任务栏右下角保留小图标。播放不会中断。"
    config.cButtons = 2
    config.pButtons = buttons
    config.nDefaultButton = MINIMIZE_ID
    selected = ctypes.c_int()
    result = comctl32.TaskDialogIndirect(
        ctypes.byref(config),
        ctypes.byref(selected),
        None,
        None,
    )
    if result != 0:
        return _ask_close_fallback(hwnd)
    if selected.value == MINIMIZE_ID:
        return "minimize"
    if selected.value == QUIT_ID:
        return "quit"
    return None


def _ask_close_fallback(hwnd):
    answer = user32.MessageBoxW(
        wintypes.HWND(hwnd or 0),
        "选「是」最小化到托盘，只保留小图标。\n选「否」直接退出。\n选「取消」留在当前窗口。",
        "飞牛TV",
        0x00000003 | 0x00000020 | 0x00000000,
    )
    if answer == 6:
        return "minimize"
    if answer == 7:
        return "quit"
    return None


def _icon_handle():
    candidates = []
    if getattr(sys, "frozen", False):
        candidates.append(sys.executable)
    icon = Path(__file__).resolve().parents[1] / "fntv.ico"
    if icon.is_file():
        candidates.append(str(icon))
    for path in candidates:
        handle = shell32.ExtractIconW(0, path, 0)
        if handle and handle > 1:
            return handle
    return user32.LoadIconW(None, IDI_APPLICATION)


class TrayIcon:
    def __init__(self, on_show, on_quit):
        self._on_show = on_show
        self._on_quit = on_quit
        self._thread = None
        self._hwnd = None
        self._icon = None
        self._wndproc = None
        self._class_name = "FNTVTrayWindow"
        self._ready = threading.Event()
        self._failed = False
        self._lock = threading.Lock()

    def show(self):
        with self._lock:
            if self._ready.is_set() and self._hwnd:
                return True
            if self._thread and self._thread.is_alive():
                return self._ready.wait(2) and not self._failed
            self._ready.clear()
            self._failed = False
            self._thread = threading.Thread(target=self._run, name="fntv-tray", daemon=True)
            self._thread.start()
        return self._ready.wait(2) and not self._failed

    def remove(self):
        hwnd = self._hwnd
        if hwnd:
            user32.PostMessageW(hwnd, WM_TRAY_REMOVE, 0, 0)
        thread = self._thread
        if thread and thread.is_alive() and thread is not threading.current_thread():
            thread.join(timeout=2)
        self._ready.clear()

    def _run(self):
        self._wndproc = WNDPROC(self._wndproc_impl)
        instance = wintypes.HINSTANCE(ctypes.windll.kernel32.GetModuleHandleW(None))
        klass = WNDCLASSW()
        klass.lpfnWndProc = self._wndproc
        klass.hInstance = instance
        klass.lpszClassName = self._class_name
        atom = user32.RegisterClassW(ctypes.byref(klass))
        if not atom and ctypes.GetLastError() not in (0, 1410):
            self._failed = True
            self._ready.set()
            return
        hwnd = user32.CreateWindowExW(
            0, self._class_name, "飞牛TV", 0, 0, 0, 0, 0, HWND_MESSAGE, None, instance, None,
        )
        if not hwnd:
            self._failed = True
            self._ready.set()
            return
        self._hwnd = hwnd
        self._icon = _icon_handle()
        if not self._notify(NIM_ADD):
            self._failed = True
            self._ready.set()
            user32.DestroyWindow(hwnd)
            return
        self._ready.set()
        message = wintypes.MSG()
        while user32.GetMessageW(ctypes.byref(message), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(message))
            user32.DispatchMessageW(ctypes.byref(message))
        self._hwnd = None

    def _notify(self, action):
        data = NOTIFYICONDATAW()
        data.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
        data.hWnd = self._hwnd
        data.uID = 1
        data.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP
        data.uCallbackMessage = WM_TRAY
        data.hIcon = self._icon or 0
        data.szTip = "飞牛TV"
        return bool(shell32.Shell_NotifyIconW(action, ctypes.byref(data)))

    def _popup(self):
        menu = user32.CreatePopupMenu()
        user32.AppendMenuW(menu, 0, MENU_SHOW, "显示主窗口")
        user32.AppendMenuW(menu, 0, MENU_QUIT, "退出")
        point = wintypes.POINT()
        user32.GetCursorPos(ctypes.byref(point))
        user32.SetForegroundWindow(self._hwnd)
        command = user32.TrackPopupMenu(
            menu, 0x0100 | 0x0080, int(point.x), int(point.y), 0, self._hwnd, None,
        )
        user32.DestroyMenu(menu)
        user32.PostMessageW(self._hwnd, 0, 0, 0)
        if command == MENU_SHOW:
            self._on_show()
        elif command == MENU_QUIT:
            self._on_quit()

    def _wndproc_impl(self, hwnd, msg, wparam, lparam):
        if msg == WM_TRAY:
            if lparam in (WM_LBUTTONUP, WM_LBUTTONDBLCLK):
                self._on_show()
            elif lparam == WM_RBUTTONUP:
                self._popup()
            return 0
        if msg == WM_TRAY_REMOVE:
            self._notify(NIM_DELETE)
            user32.DestroyWindow(hwnd)
            return 0
        if msg == WM_DESTROY:
            user32.PostQuitMessage(0)
            return 0
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)


def bind_close_prompt(window, desktop):
    """点主窗口关闭时，用页面里的设置弹窗询问。最小化只留托盘图标。"""
    state = {"allow": False}
    tray = TrayIcon(lambda: _show_window(window), lambda: None)

    def quit_from_tray():
        # 从托盘线程里同步 destroy 会和关闭回调互相等待，只把关闭消息交给主窗口。
        state["allow"] = True
        hwnd = _native_hwnd(window)
        if hwnd:
            user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
            return
        threading.Thread(target=lambda: _quit_window(window, state, tray), name="fntv-quit", daemon=True).start()

    tray._on_quit = quit_from_tray

    def minimize():
        if not tray.show():
            return False
        threading.Timer(0.05, lambda: _hide_window(window)).start()
        return True

    def quit():
        state["allow"] = True
        tray.remove()
        hwnd = _native_hwnd(window)
        if hwnd:
            user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
            return
        threading.Thread(target=lambda: _quit_window(window, state, tray), name="fntv-quit", daemon=True).start()

    def perform(action):
        if action == "minimize":
            minimize()
        elif action == "quit":
            quit()

    desktop.perform_close = perform

    def show_prompt():
        try:
            window.evaluate_js("window.fntvClosePrompt && window.fntvClosePrompt()")
        except Exception:
            return

    def on_closing():
        if state["allow"]:
            tray.remove()
            return True
        preference = "ask"
        try:
            preference = desktop.close_preference()
        except Exception:
            preference = "ask"
        if preference == "minimize":
            minimize()
            return False
        if preference == "quit":
            state["allow"] = True
            tray.remove()
            return True
        threading.Timer(0.05, show_prompt).start()
        return False

    window.events.closing += on_closing


def _hide_window(window):
    try:
        window.hide()
    except Exception:
        hwnd = _native_hwnd(window)
        if hwnd:
            user32.ShowWindow(hwnd, 0)


def _show_window(window):
    try:
        window.show()
        window.restore()
    except Exception:
        pass
    hwnd = _native_hwnd(window)
    if not hwnd:
        return
    user32.ShowWindow(hwnd, 9)
    user32.SetForegroundWindow(hwnd)


def _quit_window(window, state, tray):
    state["allow"] = True
    if tray is not None:
        tray.remove()
    try:
        window.destroy()
    except Exception:
        hwnd = _native_hwnd(window)
        if hwnd:
            user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
