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
    mp.commandv("sub-add", danmu_path, "cached")
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
  local found = external_sub()
  if found ~= nil then
    mp.set_property("secondary-sid", "no")
    mp.commandv("sub-remove", found)
  end
  apply_danmu()
end)
mp.observe_property("user-data/fntv/danmaku", "string", function(_, value)
  if value ~= "on" and value ~= "off" then return end
  local on = value == "on"
  if on == danmu_on then return end
  danmu_on = on
  apply_danmu()
end)
local state_path = status_path .. ".panel.json"
local action_path = status_path .. ".action"
local panel = {danmaku = {}, skip = {title = "", opening = 0, ending = 0}, ready = false}
local open_kind = ""
local live_timer = nil
local last_live = ""

local function defaults()
  return {
    on = true, area = 35, speed = 1, opacity = 0.85, fontsize = 22,
    scroll = true, top = true, bottom = true, time_scale = false, outline = true,
    density = 100, max_active = 40, row_spacing = 1.8, offset = 0, max_comments = 50000,
  }
end

local function same_num(a, b)
  return math.abs((tonumber(a) or 0) - (tonumber(b) or 0)) < 0.001
end

local function clock(sec)
  sec = math.max(0, math.floor(tonumber(sec) or 0))
  return math.floor(sec / 60) .. "分" .. (sec % 60) .. "秒"
end

local function trim_num(value)
  local text = string.format("%.2f", tonumber(value) or 0)
  text = text:gsub("0+$", ""):gsub("%.$", "")
  return text
end

local function load_panel()
  panel.danmaku = defaults()
  panel.skip = {title = "", opening = 0, ending = 0}
  panel.ready = false
  panel.episodes = {}
  panel.episodes_ready = false
  panel.current = ""
  panel.series = ""
  panel.season = 0
  local file = io.open(state_path, "r")
  if not file then return end
  local raw = file:read("*a")
  file:close()
  local ok, data = pcall(utils.parse_json, raw)
  if not ok or type(data) ~= "table" then return end
  if type(data.danmaku) == "table" then
    for key, value in pairs(data.danmaku) do panel.danmaku[key] = value end
  end
  if type(data.skip) == "table" then
    panel.skip.title = data.skip.title or ""
    panel.skip.opening = tonumber(data.skip.opening) or 0
    panel.skip.ending = tonumber(data.skip.ending) or 0
  end
  panel.ready = data.ready and true or false
  panel.episodes_ready = data.episodes_ready and true or false
  panel.current = data.current or ""
  panel.series = data.series or ""
  panel.season = tonumber(data.season) or 0
  if type(data.episodes) == "table" then
    for _, ep in ipairs(data.episodes) do
      if type(ep) == "table" and ep.guid and ep.guid ~= "" then
        panel.episodes[#panel.episodes + 1] = ep
      end
    end
  end
end

local function write_state(play_guid)
  local body = {
    danmaku = panel.danmaku,
    skip = {opening = panel.skip.opening or 0, ending = panel.skip.ending or 0},
  }
  if play_guid and play_guid ~= "" then body.play = play_guid end
  local payload = utils.format_json(body)
  local file = io.open(action_path, "w")
  if file then
    file:write(payload, "\n", tostring(mp.get_time()))
    file:close()
  end
end

local function leaf(title, hint, value, active)
  local row = {title = title, hint = hint or "", value = value, keep_open = true}
  if active then row.active = true end
  return row
end

local function choices(title, hint, key, current, rows)
  local items = {}
  local found = false
  for _, row in ipairs(rows) do
    if same_num(row[1], current) then found = true end
  end
  if not found then
    table.insert(rows, 1, {current, hint})
  end
  for _, row in ipairs(rows) do
    items[#items + 1] = leaf(row[2], "", "set:" .. key .. ":" .. trim_num(row[1]), same_num(row[1], current))
  end
  return {title = title, hint = hint, keep_open = true, items = items}
end

local function danmaku_menu()
  local opt = panel.danmaku
  local function flag(key, title)
    local on = opt[key] and true or false
    return leaf(title, on and "开" or "关", "toggle:" .. key, on)
  end
  local items = {
    flag("on", "弹幕开关"),
    choices("显示区域", tostring(opt.area) .. "%", "area", opt.area, {{10, "10%"}, {20, "20%"}, {35, "35%"}, {50, "50%"}, {65, "65%"}, {80, "80%"}}),
    choices("滚动速度", trim_num(opt.speed) .. "x", "speed", opt.speed, {{0.5, "0.5x"}, {0.75, "0.75x"}, {1, "1x"}, {1.25, "1.25x"}, {1.5, "1.5x"}, {2, "2x"}, {3, "3x"}}),
    choices("不透明度", tostring(math.floor((tonumber(opt.opacity) or 0) * 100 + 0.5)) .. "%", "opacity", opt.opacity, {{0.3, "30%"}, {0.5, "50%"}, {0.7, "70%"}, {0.85, "85%"}, {1, "100%"}}),
    choices("字体大小", tostring(math.floor(tonumber(opt.fontsize) or 22)), "fontsize", opt.fontsize, {{14, "14"}, {18, "18"}, {22, "22"}, {26, "26"}, {32, "32"}, {40, "40"}}),
    flag("scroll", "滚动弹幕"),
    flag("top", "顶部弹幕"),
    flag("bottom", "底部弹幕"),
    flag("time_scale", "时间比例缩放"),
    flag("outline", "文字描边"),
    choices("密度", tostring(math.floor(tonumber(opt.density) or 100)) .. "%", "density", opt.density, {{50, "50%"}, {70, "70%"}, {85, "85%"}, {100, "100%"}}),
    choices("同屏最大", tostring(math.floor(tonumber(opt.max_active) or 40)), "max_active", opt.max_active, {{10, "10"}, {20, "20"}, {30, "30"}, {40, "40"}, {60, "60"}, {80, "80"}}),
    choices("行间距", trim_num(opt.row_spacing) .. "x", "row_spacing", opt.row_spacing, {{1.2, "1.2x"}, {1.5, "1.5x"}, {1.8, "1.8x"}, {2.2, "2.2x"}, {2.6, "2.6x"}, {3, "3x"}}),
    choices("时间偏移", tostring(math.floor(tonumber(opt.offset) or 0)) .. "s", "offset", opt.offset, {{-120, "-120s"}, {-60, "-60s"}, {-30, "-30s"}, {-10, "-10s"}, {0, "0s"}, {10, "10s"}, {30, "30s"}, {60, "60s"}, {120, "120s"}}),
    choices("加载上限", tostring(math.floor(tonumber(opt.max_comments) or 50000)), "max_comments", opt.max_comments, {{500, "500"}, {2000, "2000"}, {5000, "5000"}, {10000, "10000"}, {20000, "20000"}, {50000, "50000"}}),
  }
  local reset = leaf("重置", "", "reset", false)
  reset.separator = true
  items[#items + 1] = reset
  return {
    type = "fntv-danmaku",
    title = "弹幕设置",
    search_style = "disabled",
    callback = {"progress", "panel-event"},
    footnote = panel.ready and "" or "这一集还没有匹配到弹幕，设置会在匹配到之后生效。",
    items = items,
  }
end

local function second_menu(which, minute, current)
  local items = {}
  for sec = 0, 59 do
    local total = minute * 60 + sec
    items[#items + 1] = leaf(sec .. "秒", "", "skip:" .. which .. ":" .. tostring(total), current == total)
  end
  return {title = minute .. "分", hint = "", keep_open = true, items = items}
end

local function custom_menu(which, current)
  local items = {}
  for minute = 0, 20 do
    local hint = ""
    if math.floor(current / 60) == minute then hint = (current % 60) .. "秒" end
    items[#items + 1] = second_menu(which, minute, current)
    items[#items].hint = hint
  end
  return {title = "自定义", hint = clock(current), keep_open = true, items = items}
end

local function skip_side(which)
  local saved = math.floor(tonumber(panel.skip[which]) or 0)
  local pos = mp.get_property_number("time-pos") or 0
  local dur = mp.get_property_number("duration") or 0
  local live = which == "opening" and pos or (dur > 0 and math.max(0, dur - pos) or -1)
  local use = leaf(which == "opening" and "设为片头" or "设为片尾", live < 0 and "片长未知" or clock(live), "skip:" .. which .. ":live", false)
  local reset = leaf("重置", "0分0秒", "skip:" .. which .. ":0", saved == 0)
  return {
    title = which == "opening" and "片头时长" or "片尾时长",
    hint = clock(saved),
    keep_open = true,
    items = {use, reset, custom_menu(which, saved)},
  }
end

local function episode_hint(ep)
  if ep.guid == panel.current then return "播放中" end
  local dur = tonumber(ep.duration) or 0
  local ts = tonumber(ep.ts) or 0
  local watched = tonumber(ep.watched) or 0
  if dur > 0 and ts > 0 then
    local pct = math.floor(ts * 100 / dur + 0.5)
    if pct <= 0 then
      if watched == 1 then return "已观看99%" end
    else
      return "已观看" .. math.max(1, math.min(99, pct)) .. "%"
    end
  elseif watched == 1 then
    return "已观看99%"
  end
  return "未观看"
end

local function episode_label(ep, index)
  local num = tonumber(ep.number) or index
  local text = num < 100 and string.format("%02d", num) or tostring(num)
  local title = ep.title or ""
  if title == "" then return text end
  return text .. ". " .. title
end

local function episode_menu()
  panel.episode_page = ""
  local episodes = panel.episodes or {}
  local heading = panel.series or ""
  if heading == "" then heading = "选集" end
  if (tonumber(panel.season) or 0) > 0 then
    heading = heading .. " · 第" .. tostring(panel.season) .. "季"
  end
  local function note(text)
    local row = leaf(text, "", "ignore", false)
    row.selectable = false
    row.muted = true
    return {
      type = "fntv-episodes",
      title = heading,
      search_style = "disabled",
      callback = {"progress", "panel-event"},
      items = {row},
    }
  end
  if not panel.episodes_ready then return note("正在读取选集") end
  if #episodes == 0 then return note("这部没有分集") end
  local maxn = #episodes
  for index, ep in ipairs(episodes) do
    maxn = math.max(maxn, tonumber(ep.number) or index)
  end
  local function page_of(number)
    return math.floor((math.max(1, number) - 1) / 30)
  end
  local function page_items(page)
    local items = {}
    for index, ep in ipairs(episodes) do
      local number = tonumber(ep.number) or index
      if page_of(number) == page then
        local row = leaf(episode_label(ep, index), episode_hint(ep), "play:" .. ep.guid, ep.guid == panel.current)
        row.keep_open = false
        items[#items + 1] = row
      end
    end
    if #items == 0 then
      local row = leaf("这一段没有集", "", "ignore", false)
      row.selectable = false
      row.muted = true
      items[1] = row
    end
    return items
  end
  local pages = math.max(1, math.ceil(maxn / 30))
  if pages == 1 then
    return {
      type = "fntv-episodes",
      title = heading,
      search_style = "disabled",
      callback = {"progress", "panel-event"},
      items = page_items(0),
    }
  end
  local current_number = 1
  for index, ep in ipairs(episodes) do
    if ep.guid == panel.current then current_number = tonumber(ep.number) or index end
  end
  local items = {}
  for page = 0, pages - 1 do
    local start_number = page * 30 + 1
    local end_number = math.min((page + 1) * 30, maxn)
    local label = tostring(start_number) .. "-" .. tostring(end_number)
    if page_of(current_number) == page then panel.episode_page = label end
    items[#items + 1] = {title = label, hint = "", keep_open = true, items = page_items(page)}
  end
  return {
    type = "fntv-episodes",
    title = heading,
    search_style = "disabled",
    callback = {"progress", "panel-event"},
    items = items,
  }
end

local function skip_menu()
  local title = panel.skip.title or ""
  if title == "" then title = "当前视频" end
  return {
    type = "fntv-skip",
    title = "片头片尾",
    search_style = "disabled",
    callback = {"progress", "panel-event"},
    footnote = "生效范围：" .. title,
    items = {skip_side("opening"), skip_side("ending")},
  }
end

local function menu_data(kind)
  if kind == "skip" then return skip_menu() end
  if kind == "episodes" then return episode_menu() end
  return danmaku_menu()
end

local function show_menu(kind)
  local data = menu_data(kind)
  local submenu = kind == "episodes" and panel.episode_page or ""
  if submenu and submenu ~= "" then
    mp.commandv("script-message-to", "uosc", "open-menu", utils.format_json(data), submenu)
  else
    mp.commandv("script-message-to", "uosc", "open-menu", utils.format_json(data))
  end
end

local function refresh_menu()
  if open_kind ~= "danmaku" and open_kind ~= "skip" and open_kind ~= "episodes" then return end
  mp.commandv("script-message-to", "uosc", "update-menu", utils.format_json(menu_data(open_kind)))
end

local function watch_live()
  if live_timer then return end
  live_timer = mp.add_periodic_timer(0.5, function()
    if open_kind ~= "skip" then
      live_timer:kill()
      live_timer = nil
      last_live = ""
      return
    end
    local pos = math.floor(mp.get_property_number("time-pos") or 0)
    local dur = mp.get_property_number("duration") or 0
    local text = tostring(pos) .. ":" .. tostring(math.floor(dur))
    if text == last_live then return end
    last_live = text
    refresh_menu()
  end)
end

local function resolve(value)
  if value == "skip:opening:live" then
    return "skip:opening:" .. tostring(math.max(0, math.floor(mp.get_property_number("time-pos") or 0)))
  end
  if value == "skip:ending:live" then
    local dur = mp.get_property_number("duration") or 0
    if dur <= 0 then
      mp.osd_message("还不知道片长，稍后再设片尾", 2)
      return nil
    end
    local pos = mp.get_property_number("time-pos") or 0
    return "skip:ending:" .. tostring(math.max(0, math.floor(dur - pos)))
  end
  return value
end

local function apply_local(value)
  if value == "reset" then
    panel.danmaku = defaults()
    return true
  end
  local kind, key, raw = value:match("^([^:]+):([^:]+):(.*)$")
  if not kind then
    kind, key = value:match("^([^:]+):([^:]+)$")
    raw = ""
  end
  if kind == "toggle" then
    if panel.danmaku[key] == nil then return false end
    panel.danmaku[key] = not panel.danmaku[key]
    return true
  end
  if kind == "set" then
    local number = tonumber(raw)
    if not number or panel.danmaku[key] == nil then return false end
    panel.danmaku[key] = number
    return true
  end
  if kind == "skip" and (key == "opening" or key == "ending") then
    local number = tonumber(raw)
    if not number then return false end
    panel.skip[key] = math.max(0, math.min(20 * 60, math.floor(number)))
    return true
  end
  return false
end

local episode_timer = nil
local function watch_episodes()
  if episode_timer then return end
  episode_timer = mp.add_periodic_timer(0.4, function()
    if open_kind ~= "episodes" or panel.episodes_ready then
      episode_timer:kill()
      episode_timer = nil
      return
    end
    load_panel()
    if panel.episodes_ready then refresh_menu() end
  end)
end

local function open_overlay(kind)
  local ok, err = pcall(function()
    load_panel()
    open_kind = kind
    last_live = ""
    show_menu(kind)
    if kind == "skip" then watch_live() end
    if kind == "episodes" then watch_episodes() end
  end)
  if not ok then
    mp.osd_message("菜单没有打开：" .. tostring(err), 3)
  end
end

mp.register_script_message("panel-event", function(raw)
  local ok, event = pcall(utils.parse_json, raw or "")
  if not ok or type(event) ~= "table" then return end
  if event.type == "close" then
    open_kind = ""
    return
  end
  if event.type ~= "activate" then return end
  local raw_value = tostring(event.value or "")
  if raw_value:sub(1, 5) == "play:" then
    local guid = raw_value:sub(6)
    if guid ~= "" and guid ~= (panel.current or "") then write_state(guid) end
    mp.commandv("script-message-to", "uosc", "close-menu", "fntv-episodes")
    open_kind = ""
    return
  end
  local value = resolve(raw_value)
  if not value or value == "" or value == "ignore" then return end
  if not apply_local(value) then return end
  write_state()
  refresh_menu()
end)
mp.register_script_message("episode-list", function()
  open_overlay("episodes")
end)
mp.register_script_message("danmaku-settings", function()
  open_overlay("danmaku")
end)
mp.register_script_message("skip-settings", function()
  open_overlay("skip")
end)
mp.add_key_binding("", "episode-list", function() open_overlay("episodes") end)
mp.add_key_binding("", "danmaku-settings", function() open_overlay("danmaku") end)
mp.add_key_binding("", "skip-settings", function() open_overlay("skip") end)
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


def screen_work_area():
    try:
        import ctypes
        from ctypes import wintypes

        rect = wintypes.RECT()
        ok = ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(rect), 0)
        if not ok:
            user32 = ctypes.windll.user32
            return 0, 0, int(user32.GetSystemMetrics(0)), int(user32.GetSystemMetrics(1))
        return int(rect.left), int(rect.top), int(rect.right - rect.left), int(rect.bottom - rect.top)
    except Exception:
        return None


def main_window_bounds():
    width, height = 1120, 740
    area = screen_work_area()
    if not area:
        return width, height, None, None
    left, top, area_w, area_h = area
    margin = 32
    width = min(width, max(640, area_w - margin * 2))
    height = min(height, max(480, area_h - margin * 2))
    x = left + max(0, (area_w - width) // 2)
    y = top + max(0, (area_h - height) // 2)
    return width, height, x, y


def player_window_bounds():
    width, height, _, _ = main_window_bounds()
    area = screen_work_area()
    player_w = max(720, width - 80)
    player_h = max(460, height - 56)
    if not area:
        return player_w, player_h, None, None
    left, top, area_w, area_h = area
    player_w = min(player_w, max(640, area_w - 64))
    player_h = min(player_h, max(420, area_h - 64))
    x = left + max(0, (area_w - player_w) // 2)
    y = top + max(0, (area_h - player_h) // 2)
    return player_w, player_h, x, y


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
                "--sub-ass-override=no",
                "--script=%s" % self.script_path,
                "--title=%s" % (title or "飞牛TV"),
            ])
            player_w, player_h, player_x, player_y = player_window_bounds()
            if player_x is not None:
                command.append("--geometry=%dx%d+%d+%d" % (player_w, player_h, player_x, player_y))
            command.append("--autofit-larger=%dx%d" % (player_w, player_h))
            command.append(url)
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
        return

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
