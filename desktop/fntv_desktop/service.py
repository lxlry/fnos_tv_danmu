"""登录、浏览和播放。进度按 Android 端 play/record 的字段回写。"""

import json
import threading
import time
import uuid
from pathlib import Path

from .api import FnClient, FnError, display_title, normalize_server
from .danmu import comments_to_ass, load_comments
from .player import MpvPlayer, find_mpv
from .update import Updater, local_version


def config_dir():
    appdata = Path.home()
    roaming = Path.home() / "AppData" / "Roaming"
    if roaming.is_dir():
        appdata = roaming
    folder = appdata / "FNTVDesktop"
    folder.mkdir(parents=True, exist_ok=True)
    return folder


class Desktop:
    def __init__(self):
        self.directory = config_dir()
        self.config_path = self.directory / "config.json"
        self.config = self._load_config()
        self.client = FnClient()
        self.player = MpvPlayer(self.directory)
        self.player.on_exit = self._on_player_exit
        self.updater = Updater()
        self.port = 0
        threading.Thread(target=self._progress_loop, daemon=True).start()
        self.sessions = {}
        self.session_lock = threading.Lock()
        self.job = None
        self.job_lock = threading.Lock()
        self._restore_client()

    def _load_config(self):
        defaults = {
            "server": "",
            "username": "",
            "password": "",
            "access_code": "",
            "token": "",
            "remember": True,
            "danmu_url": "",
            "danmu_on": True,
            "auto_next": True,
            "mpv_path": "",
        }
        try:
            saved = json.loads(self.config_path.read_text(encoding="utf-8"))
        except Exception:
            saved = {}
        if isinstance(saved, dict):
            defaults.update(saved)
        return defaults

    def _save_config(self):
        self.config_path.write_text(
            json.dumps(self.config, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def _restore_client(self):
        if not self.config.get("server") or not self.config.get("token"):
            return
        self.client.set_session(
            self.config.get("server") or "",
            self.config.get("token") or "",
            self.config.get("access_code") or "",
            self.config.get("username") or "",
        )

    def status(self):
        logged_in = False
        nickname = self.config.get("username") or ""
        if self.client.token and self.client.base:
            try:
                info = self.client.user_info()
                logged_in = True
                nickname = info.get("nickname") or info.get("username") or nickname
            except FnError:
                logged_in = False
        mpv_path = find_mpv(self.config.get("mpv_path") or "")
        version = local_version()
        return {
            "logged_in": logged_in,
            "username": nickname,
            "account": self.config.get("username") or "",
            "server": self.config.get("server") or "",
            "danmu_url": self.config.get("danmu_url") or "",
            "danmu_on": bool(self.config.get("danmu_on", True)),
            "auto_next": bool(self.config.get("auto_next", True)),
            "mpv_path": self.config.get("mpv_path") or "",
            "mpv_ready": bool(mpv_path),
            "version": version["versionName"],
            "playing": self.playing(),
        }

    def login(self, server, username, password, access_code="", remember=True, danmu_url=""):
        base, peeled = normalize_server(server)
        if not base:
            raise FnError("请填写服务器地址，例如 http://192.168.1.10:5666")
        if not username or not password:
            raise FnError("请填写账号和密码")
        code = (access_code or peeled or "").strip()
        self.client.set_session(base, "", code, username)
        probed = self.client.probe_access()
        if probed == "required" and not code:
            raise FnError("这台飞牛开了访问码，请填访问码后再登录", "access_code")
        if code:
            checked = self.client.probe_access(code)
            if checked == "rejected":
                raise FnError("访问码不正确", "access_code")
        self.client.login(username, password)
        self.config["server"] = base
        self.config["username"] = username
        self.config["access_code"] = code
        self.config["token"] = self.client.token
        self.config["remember"] = bool(remember)
        self.config["password"] = password if remember else ""
        if danmu_url is not None:
            self.config["danmu_url"] = (danmu_url or "").strip()
        self._save_config()
        info = {}
        try:
            info = self.client.user_info()
        except FnError:
            info = {}
        return {"username": info.get("nickname") or info.get("username") or username, "server": base}

    def check_update(self, quiet=False):
        return self.updater.check(quiet)

    def download_update(self, exe_url):
        if not exe_url:
            raise FnError("没有下载地址")
        return self.updater.start_download(exe_url)

    def update_progress(self):
        return self.updater.progress()

    def apply_update(self):
        try:
            return self.updater.apply()
        except RuntimeError as err:
            raise FnError(str(err))

    def logout(self):
        self.stop()
        self.config["token"] = ""
        self.config["password"] = ""
        self.client.token = ""
        self._save_config()

    def update_settings(self, values):
        if "danmu_url" in values:
            self.config["danmu_url"] = (values.get("danmu_url") or "").strip()
        if "danmu_on" in values:
            self.config["danmu_on"] = bool(values.get("danmu_on"))
        if "auto_next" in values:
            self.config["auto_next"] = bool(values.get("auto_next"))
        if "mpv_path" in values:
            self.config["mpv_path"] = (values.get("mpv_path") or "").strip()
        self._save_config()
        return self.status()

    def home(self):
        self._need_login()
        return {
            "libraries": self.client.libraries(),
            "continue": self.client.continue_watching(),
        }

    def library(self, guid, page=1):
        self._need_login()
        if not guid:
            raise FnError("没有媒体库")
        return self.client.browse(guid, page=page)

    def search(self, keyword):
        self._need_login()
        keyword = (keyword or "").strip()
        if not keyword:
            return []
        return self.client.search(keyword)

    def detail(self, guid):
        self._need_login()
        info = self.client.play_info(guid)
        view = self._detail_view(info)
        item_type = view["type"]
        if item_type == "TV":
            view["seasons"] = self.client.seasons(view["guid"])
            if view["seasons"]:
                view["episodes"] = self.client.episodes(view["seasons"][0]["guid"])
            else:
                view["episodes"] = self.client.episodes(view["guid"])
        elif item_type == "Episode" and view.get("parent_guid"):
            view["episodes"] = self.client.episodes(view["parent_guid"])
        return view

    def episodes(self, guid):
        self._need_login()
        return self.client.episodes(guid)

    def play(self, guid, next_guid=""):
        self._need_login()
        mpv_path = find_mpv(self.config.get("mpv_path") or "")
        if not mpv_path:
            raise FnError("没有找到随程序提供的 mpv。请重新下载完整压缩包。")
        info = self.client.play_info(guid)
        media_guid = info.get("media_guid") or ""
        if not media_guid:
            raise FnError("这部内容没有可播放的文件")
        stream = self.client.stream(media_guid)
        url, resolution, bitrate = self._choose_url(media_guid, stream, info)
        item = info.get("item") if isinstance(info.get("item"), dict) else {}
        start = int(info.get("ts") or 0)
        skip = 0
        play_config = info.get("play_config") if isinstance(info.get("play_config"), dict) else {}
        if play_config.get("skip_opening"):
            skip = int(play_config.get("skip_opening") or 0)
        if skip > start:
            start = skip
        title = display_title(item) if item else guid
        episode_number = item.get("episode_number") or 0
        season_number = item.get("season_number") or 0
        match_name = item.get("tv_title") or item.get("title") or title
        if episode_number:
            match_name = "%s S%02dE%02d" % (match_name, season_number or 0, episode_number)
        if not next_guid:
            next_guid = self._find_next(info)
        subtitle = ""
        if self.config.get("danmu_on") and self.config.get("danmu_url"):
            subtitle = self._write_danmu(match_name, episode_number)
        session_id = uuid.uuid4().hex
        cookie = _stream_cookie(stream)
        with self.session_lock:
            self.sessions[session_id] = {"url": url, "cookie": cookie}
        self._save_job_progress()
        job = {
            "guid": info.get("guid") or guid,
            "media_guid": media_guid,
            "video_guid": info.get("video_guid") or "",
            "audio_guid": info.get("audio_guid") or "",
            "subtitle_guid": info.get("subtitle_guid") or "_no_display_",
            "resolution": resolution,
            "bitrate": bitrate,
            "duration": int(item.get("duration") or 0),
            "title": title,
            "next_guid": next_guid or "",
            "session_id": session_id,
        }
        with self.job_lock:
            self.job = job
        local_url = "/stream/%s" % session_id
        self.player.start(mpv_path, "http://127.0.0.1:%s%s" % (self.port, local_url), title, job["guid"], start, subtitle)
        return {"title": title, "guid": job["guid"]}

    def stop(self):
        self._save_job_progress()
        with self.job_lock:
            self.job = None
        self.player.stop()

    def playing(self):
        job = self.job
        if not job:
            return {"running": False}
        duration = self.player.duration or job.get("duration") or 0
        return {
            "running": self.player.running(),
            "title": job.get("title") or "",
            "guid": job.get("guid") or "",
            "position": int(self.player.position or 0),
            "duration": int(duration or 0),
        }

    def open_stream(self, session_id, target=""):
        with self.session_lock:
            session = self.sessions.get(session_id)
        if not session:
            raise FnError("播放已经结束")
        url = target or session.get("url") or ""
        if not url:
            raise FnError("没有播放地址")
        return url, session.get("cookie") or ""

    def _progress_loop(self):
        while True:
            time.sleep(15)
            if self.player.running():
                self._save_job_progress()

    def _on_player_exit(self, eof):
        self._save_job_progress()
        job = self.job
        if eof and job and self.config.get("auto_next") and job.get("next_guid"):
            try:
                self.play(job["next_guid"])
                return
            except FnError:
                pass
        with self.job_lock:
            if self.job is job:
                self.job = None

    def _save_job_progress(self):
        job = self.job
        if not job or not self.client.token:
            return
        position = int(self.player.position or 0)
        if position < 1:
            return
        duration = int(self.player.duration or 0) or int(job.get("duration") or 0)
        body = {
            "item_guid": job["guid"],
            "media_guid": job["media_guid"],
            "video_guid": job.get("video_guid") or "",
            "audio_guid": job.get("audio_guid") or "",
            "subtitle_guid": job.get("subtitle_guid") or "_no_display_",
            "resolution": job.get("resolution") or "",
            "bitrate": int(job.get("bitrate") or 0),
            "ts": position,
            "duration": duration,
        }
        try:
            self.client.record(body)
        except FnError:
            return

    def _choose_url(self, media_guid, stream, info):
        direct = stream.get("direct_link_qualities") or []
        qualities = stream.get("qualities") or []
        file_stream = stream.get("file_stream") if isinstance(stream.get("file_stream"), dict) else {}
        path = file_stream.get("path") or ""
        online = path.startswith("在线/") or path.startswith("在线\\")
        picked = None
        picked_index = 0
        for index, quality in enumerate(direct):
            if not isinstance(quality, dict):
                continue
            if picked is None or (quality.get("bitrate") or 0) > (picked.get("bitrate") or 0):
                picked = quality
                picked_index = index
        resolution = ""
        bitrate = 0
        if isinstance(picked, dict):
            resolution = picked.get("resolution") or ""
            bitrate = picked.get("bitrate") or 0
            link = (picked.get("url") or "").replace("\\u0026", "&")
            if link:
                if link.startswith("/"):
                    link = self.client.base + link
                return link, resolution, bitrate
            return (
                self.client.base + "/v/api/v1/media/range/%s?direct_link_quality_index=%s" % (media_guid, picked_index),
                resolution,
                bitrate,
            )
        if isinstance(qualities, list) and qualities and isinstance(qualities[0], dict):
            resolution = qualities[0].get("resolution") or ""
            bitrate = qualities[0].get("bitrate") or 0
        if online and resolution:
            return self._transcoded_url(stream, info, resolution, bitrate), resolution, bitrate
        return self.client.base + "/v/api/v1/media/range/" + media_guid, resolution, bitrate

    def _transcoded_url(self, stream, info, resolution, bitrate):
        video = stream.get("video_stream") if isinstance(stream.get("video_stream"), dict) else {}
        audios = stream.get("audio_streams") or []
        audio = audios[0] if audios and isinstance(audios[0], dict) else {}
        body = {
            "media_guid": info.get("media_guid") or "",
            "video_guid": video.get("guid") or info.get("video_guid") or "",
            "video_encoder": video.get("codec_name") or "",
            "resolution": resolution,
            "bitrate": bitrate,
            "startTimestamp": int(info.get("ts") or 0),
        }
        if audio:
            body["audio_guid"] = audio.get("guid") or ""
            body["audio_encoder"] = audio.get("codec_name") or ""
            body["channels"] = audio.get("channels") or 0
        linked = self.client.play_link(body)
        play_link = linked.get("play_link") or ""
        if not play_link:
            raise FnError("没有播放地址")
        if play_link.startswith("http://") or play_link.startswith("https://"):
            return play_link
        if not play_link.startswith("/"):
            play_link = "/" + play_link
        return self.client.base + play_link

    def _find_next(self, info):
        parent = info.get("parent_guid") or ""
        current = info.get("guid") or ""
        if not parent:
            return ""
        try:
            episodes = self.client.episodes(parent)
        except FnError:
            return ""
        for index, episode in enumerate(episodes):
            if episode.get("guid") == current and index + 1 < len(episodes):
                return episodes[index + 1].get("guid") or ""
        return ""

    def _write_danmu(self, file_name, episode_number):
        try:
            comments = load_comments(self.config.get("danmu_url") or "", file_name, episode_number)
        except Exception:
            return ""
        if not comments:
            return ""
        path = self.directory / "danmu.ass"
        path.write_text(comments_to_ass(comments), encoding="utf-8")
        return str(path)

    def _detail_view(self, info):
        item = info.get("item") if isinstance(info.get("item"), dict) else {}
        play_config = info.get("play_config") if isinstance(info.get("play_config"), dict) else {}
        poster = item.get("posters") or item.get("poster") or item.get("still_path") or ""
        if isinstance(poster, list):
            poster = poster[0] if poster else ""
        backdrop = item.get("backdrops") or poster
        if isinstance(backdrop, list):
            backdrop = backdrop[0] if backdrop else ""
        duration = item.get("duration") or 0
        if not duration and item.get("runtime"):
            duration = int(item.get("runtime") or 0) * 60
        meta = []
        if item.get("release_date"):
            meta.append(str(item.get("release_date"))[:4])
        elif item.get("air_date"):
            meta.append(str(item.get("air_date"))[:4])
        if item.get("number_of_seasons"):
            meta.append("%s 季" % item.get("number_of_seasons"))
        if item.get("number_of_episodes"):
            meta.append("%s 集" % item.get("number_of_episodes"))
        if duration:
            meta.append("%s 分钟" % max(1, int(duration) // 60))
        return {
            "guid": info.get("guid") or item.get("guid") or "",
            "title": display_title(item) if item else "未命名",
            "overview": item.get("overview") or "",
            "poster": poster or "",
            "backdrop": backdrop or "",
            "type": info.get("type") or item.get("type") or "",
            "meta": " · ".join(meta),
            "ts": info.get("ts") or 0,
            "duration": duration or 0,
            "parent_guid": info.get("parent_guid") or "",
            "media_guid": info.get("media_guid") or "",
            "seasons": [],
            "episodes": [],
            "skip_opening": play_config.get("skip_opening") or 0,
            "skip_ending": play_config.get("skip_ending") or 0,
        }

    def _need_login(self):
        if not self.client.base or not self.client.token:
            raise FnError("请先登录", "auth")


def _stream_cookie(stream):
    header = stream.get("header") if isinstance(stream.get("header"), dict) else {}
    cookie = header.get("Cookie") or header.get("cookie") or ""
    if isinstance(cookie, list):
        cookie = "; ".join(str(part) for part in cookie if part)
    return str(cookie or "")
