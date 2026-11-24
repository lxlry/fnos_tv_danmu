"""用 mpv 播放。进度写到本地文件，设置通过 mpv 的本地管道切换。"""

import ctypes
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from ctypes import wintypes
from pathlib import Path

PIPE_NAME = r"\\.\pipe\fntv-mpv"
KERNEL32 = ctypes.WinDLL("kernel32", use_last_error=True)
KERNEL32.CreateFileW.restype = wintypes.HANDLE
KERNEL32.CreateFileW.argtypes = [
    wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
    wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE,
]
KERNEL32.WriteFile.argtypes = [
    wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p,
]
KERNEL32.ReadFile.argtypes = [
    wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p,
]
KERNEL32.CloseHandle.argtypes = [wintypes.HANDLE]
INVALID_HANDLE = wintypes.HANDLE(-1).value
_IPC_LOCK = threading.Lock()

LUA = r"""
local status_path = [[__STATUS__]]
local danmu_path = [[__DANMU__]]
local danmu_on = __DANMU_ON__
local play_speed = 1
local end_path = status_path .. ".end"
local last_write = 0
mp.set_property("user-data/fntv/danmaku", danmu_on and "on" or "off")

local function external_sub()
  local tracks = mp.get_property_native("track-list") or {}
  for i = #tracks, 1, -1 do
    local track = tracks[i]
    if track.type == "sub" and track.external then
      return track.id
    end
  end
  return nil
end

local function apply_danmu()
  if danmu_path == "" then return end
  local found = external_sub()
  if found == nil then
    mp.commandv("sub-add", danmu_path, "auto")
    found = external_sub()
  end
  if found == nil then return end
  if danmu_on then
    mp.set_property("secondary-sid", found)
  else
    mp.set_property("secondary-sid", "no")
  end
end

local function write_status()
  local pos = mp.get_property_number("time-pos")
  local dur = mp.get_property_number("duration")
  if pos == nil then return end
  local now = mp.get_time()
  if now - last_write < 2 then return end
  last_write = now
  local file = io.open(status_path, "w")
  if file then
    file:write(tostring(pos), " ", tostring(dur or 0))
    file:close()
  end
end

mp.observe_property("time-pos", "number", write_status)
local ignore_speed = false
mp.observe_property("speed", "number", function(_, value)
  if ignore_speed or value == nil then return end
  play_speed = value
end)
mp.register_event("file-loaded", function()
  apply_danmu()
  ignore_speed = true
  mp.set_property("speed", play_speed)
  mp.add_timeout(0.2, function()
    mp.set_property("speed", play_speed)
    ignore_speed = false
  end)
end)
mp.register_script_message("danmu", function(state)
  danmu_on = state == "on"
  mp.set_property("user-data/fntv/danmaku", danmu_on and "on" or "off")
  apply_danmu()
end)
mp.register_script_message("danmu-file", function(path)
  danmu_path = path or ""
  apply_danmu()
end)
mp.observe_property("user-data/fntv/danmaku", "string", function(_, value)
  if value ~= "on" and value ~= "off" then return end
  local on = value == "on"
  if on == danmu_on then return end
  danmu_on = on
  apply_danmu()
end)
mp.register_script_message("speed", function(value)
  play_speed = tonumber(value) or 1
  mp.set_property("speed", play_speed)
end)

mp.register_event("end-file", function(event)
  local pos = mp.get_property_number("time-pos") or 0
  local dur = mp.get_property_number("duration") or 0
  local file = io.open(status_path, "w")
  if file then
    file:write(tostring(pos), " ", tostring(dur))
    file:close()
  end
  local marker = io.open(end_path, "w")
  if marker then
    marker:write(event.reason or "")
    marker:close()
  end
end)
"""


def uosc_config_dir():
    candidates = []
    if getattr(sys, "frozen", False):
        candidates.append(Path(sys.executable).resolve().parent / "uosc")
    candidates.append(Path(__file__).resolve().parents[1] / "vendor" / "uosc")
    for path in candidates:
        if (path / "scripts" / "uosc" / "main.lua").is_file():
            return path
    return None


def bundled_mpv_paths():
    paths = []
    if getattr(sys, "frozen", False):
        paths.append(Path(sys.executable).resolve().parent / "mpv" / "mpv.exe")
    desktop_dir = Path(__file__).resolve().parents[1]
    paths.append(desktop_dir / "vendor" / "mpv" / "mpv.exe")
    return paths


def find_mpv(configured=""):
    candidates = []
    if configured:
        candidates.append(configured)
    candidates.extend(str(path) for path in bundled_mpv_paths())
    found = shutil.which("mpv")
    if found:
        candidates.append(found)
    program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
    local = os.environ.get("LOCALAPPDATA", "")
    candidates.extend([
        str(Path(program_files) / "mpv" / "mpv.exe"),
        str(Path(local) / "mpv" / "mpv.exe") if local else "",
        str(Path.home() / "scoop" / "apps" / "mpv" / "current" / "mpv.exe"),
    ])
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate))
    return ""


class MpvPlayer:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.status_path = self.directory / "status.txt"
        self.end_path = self.directory / "status.txt.end"
        self.script_path = self.directory / "progress.lua"
        self.proc = None
        self.thread = None
        self.position = 0.0
        self.duration = 0.0
        self.title = ""
        self.guid = ""
        self.on_exit = None
        self._lock = threading.Lock()
        self._generation = 0

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def start(self, mpv_path, url, title, guid, start_sec=0, subtitle="", danmaku_on=True, danmaku_scale=1):
        with self._lock:
            self._generation += 1
            generation = self._generation
            self._stop_locked()
            self._clear_markers()
            script = LUA.replace("__STATUS__", str(self.status_path).replace("]", ""))
            script = script.replace("__DANMU__", str(subtitle or "").replace("]", ""))
            script = script.replace("__DANMU_ON__", "true" if danmaku_on else "false")
            self.script_path.write_text(script, encoding="utf-8")
            self.position = float(start_sec or 0)
            self.duration = 0
            self.title = title or ""
            self.guid = guid or ""
            command = [mpv_path]
            ui = uosc_config_dir()
            if ui:
                command.extend([
                    "--config-dir=%s" % ui,
                    "--osc=no",
                    "--osd-bar=no",
                ])
            command.extend([
                "--force-window=immediate",
                "--no-terminal",
                "--keep-open=no",
                "--input-ipc-server=%s" % PIPE_NAME,
                "--sub-ass-override=scale",
                "--sub-scale=%s" % _scale(danmaku_scale),
                "--script=%s" % self.script_path,
                "--title=%s" % (title or "飞牛TV"),
                url,
            ])
            if start_sec and start_sec > 1:
                command.insert(-1, "--start=%.3f" % float(start_sec))
            self.proc = subprocess.Popen(command)
            self.thread = threading.Thread(target=self._watch, args=(self.proc, generation), daemon=True)
            self.thread.start()

    def command(self, payload):
        if not self.running():
            raise OSError("播放器没有在运行")
        return ipc_command(payload)

    def load(self, url, start):
        self.command(["loadfile", url, "replace", "start=%.3f" % max(0, float(start or 0))])

    def seek(self, seconds):
        self.command(["seek", max(0, float(seconds or 0)), "absolute"])

    def set_audio(self, track_id):
        self.command(["set_property", "aid", _track_value(track_id)])

    def set_subtitle(self, track_id):
        self.command(["set_property", "sid", _track_value(track_id)])

    def set_danmaku(self, enabled):
        self.command(["script-message", "danmu", "on" if enabled else "off"])

    def set_danmaku_file(self, path):
        self.command(["script-message", "danmu-file", path or ""])

    def set_scale(self, scale):
        self.command(["set_property", "sub-ass-override", "scale"])
        self.command(["set_property", "sub-scale", _scale(scale)])

    def set_speed(self, speed):
        self.command(["script-message", "speed", str(_playback_speed(speed))])

    def tracks(self):
        result = self.command(["get_property", "track-list"])
        tracks = result.get("data") if isinstance(result.get("data"), list) else []
        secondary = self.command(["get_property", "secondary-sid"]).get("data")
        scale = self.command(["get_property", "sub-scale"]).get("data") or 1
        speed = self.command(["get_property", "speed"]).get("data") or 1
        audio = []
        subtitle = [{"id": "no", "label": "关闭", "selected": True}]
        danmaku_on = False
        for track in tracks:
            if not isinstance(track, dict):
                continue
            track_id = track.get("id")
            label = track.get("title") or track.get("lang") or ""
            if track.get("type") == "audio":
                audio.append({
                    "id": track_id,
                    "label": label or "音轨 %s" % track_id,
                    "selected": bool(track.get("selected")),
                })
            elif track.get("type") == "sub" and not track.get("external"):
                selected = bool(track.get("selected"))
                if selected:
                    subtitle[0]["selected"] = False
                subtitle.append({
                    "id": track_id,
                    "label": label or "字幕 %s" % track_id,
                    "selected": selected,
                })
            elif track.get("type") == "sub" and track.get("external"):
                danmaku_on = str(secondary) == str(track_id)
        return {
            "audio": audio,
            "subtitle": subtitle,
            "danmaku_on": danmaku_on,
            "danmaku_scale": _scale(scale),
            "speed": _playback_speed(speed),
        }

    def stop(self):
        thread = None
        with self._lock:
            self._generation += 1
            thread = self.thread
            self._stop_locked()
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=2)

    def _stop_locked(self):
        proc = self.proc
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                proc.kill()
        self.proc = None

    def _watch(self, proc, generation):
        eof = False
        while proc.poll() is None:
            self._read_status()
            if self.end_path.is_file():
                reason = self.end_path.read_text(encoding="utf-8", errors="replace").strip()
                eof = reason == "eof"
                break
            time.sleep(1)
        self._read_status()
        if self.end_path.is_file() and not eof:
            reason = self.end_path.read_text(encoding="utf-8", errors="replace").strip()
            eof = reason == "eof"
        callback = self.on_exit
        if callback and generation == self._generation:
            callback(eof)

    def _read_status(self):
        try:
            text = self.status_path.read_text(encoding="utf-8", errors="replace").strip()
        except OSError:
            return
        parts = text.split()
        if not parts:
            return
        try:
            self.position = float(parts[0])
        except ValueError:
            return
        if len(parts) > 1:
            try:
                self.duration = float(parts[1])
            except ValueError:
                pass

    def _clear_markers(self):
        for path in (self.status_path, self.end_path):
            try:
                path.unlink()
            except OSError:
                pass


def _playback_speed(value):
    choices = (0.5, 0.75, 1, 1.25, 1.5, 2, 3)
    try:
        number = float(value)
    except (TypeError, ValueError):
        number = 1
    return min(choices, key=lambda item: abs(item - number))


def _scale(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        number = 1
    if number < 0.6:
        return 0.6
    if number > 2:
        return 2
    return round(number, 2)


def _track_value(track_id):
    if track_id in (None, "", "no", "off"):
        return "no"
    try:
        return int(track_id)
    except (TypeError, ValueError):
        return str(track_id)


def ipc_command(command):
    with _IPC_LOCK:
        handle = KERNEL32.CreateFileW(PIPE_NAME, 0xC0000000, 0, None, 3, 0, None)
        if handle == INVALID_HANDLE or handle is None:
            raise OSError("没有连上播放器")
        try:
            request_id = int(time.time() * 1000) % 1000000
            payload = json.dumps({"command": command, "request_id": request_id}, ensure_ascii=False) + "\n"
            raw = payload.encode("utf-8")
            written = wintypes.DWORD()
            if not KERNEL32.WriteFile(handle, raw, len(raw), ctypes.byref(written), None):
                raise OSError("播放器没有收到指令")
            buf = b""
            chunk = ctypes.create_string_buffer(8192)
            read = wintypes.DWORD()
            while b"\n" not in buf and len(buf) < 200000:
                if not KERNEL32.ReadFile(handle, chunk, 8192, ctypes.byref(read), None) or read.value == 0:
                    break
                buf += chunk.raw[:read.value]
            for line in buf.splitlines():
                try:
                    parsed = json.loads(line.decode("utf-8"))
                except Exception:
                    continue
                if parsed.get("request_id") == request_id:
                    if parsed.get("error") not in (None, "success"):
                        raise OSError(parsed.get("error") or "播放器拒绝了这个设置")
                    return parsed
            raise OSError("播放器没有响应")
        finally:
            KERNEL32.CloseHandle(handle)
