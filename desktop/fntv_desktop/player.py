"""用 mpv 播放。进度写到本地文件，避免依赖 Windows 管道。"""

import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path


LUA = r"""
local status_path = [[%s]]
local end_path = status_path .. ".end"
local last_write = 0

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

mp.register_event("file-loaded", function()
  local tracks = mp.get_property_native("track-list") or {}
  for i = #tracks, 1, -1 do
    local track = tracks[i]
    if track.type == "sub" and track.external then
      mp.set_property("secondary-sid", track.id)
      break
    end
  end
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

    def start(self, mpv_path, url, title, guid, start_sec=0, subtitle=""):
        with self._lock:
            self._generation += 1
            generation = self._generation
            self._stop_locked()
            self._clear_markers()
            self.script_path.write_text(LUA % str(self.status_path), encoding="utf-8")
            self.position = float(start_sec or 0)
            self.duration = 0
            self.title = title or ""
            self.guid = guid or ""
            command = [
                mpv_path,
                "--force-window=yes",
                "--no-terminal",
                "--keep-open=no",
                "--script=%s" % self.script_path,
                "--title=%s" % (title or "飞牛TV"),
                url,
            ]
            if start_sec and start_sec > 1:
                command.insert(-1, "--start=%.3f" % float(start_sec))
            if subtitle:
                command.insert(-1, "--sub-file=%s" % subtitle)
            self.proc = subprocess.Popen(command)
            self.thread = threading.Thread(target=self._watch, args=(self.proc, generation), daemon=True)
            self.thread.start()

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
