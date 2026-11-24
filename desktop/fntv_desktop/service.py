"""登录、浏览和播放。进度按 Android 端 play/record 的字段回写。"""

import ctypes
import json
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .api import FnClient, FnError, display_title, normalize_server
from .danmu import comments_to_ass, danmaku_options, danmaku_options_from_config, load_comments
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
        self.playback_source = None
        self._danmu_comments = []
        self._danmu_lock = threading.Lock()
        self._danmu_match = None
        self._danmu_loaded_limit = 0
        self._danmaku_request = ""
        self._window = None
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
        libraries = self.client.libraries()

        def preview(library):
            guid = library.get("guid") or ""
            if not guid:
                library["items"] = []
                return
            try:
                found = self.client.browse(guid, page=1, page_size=14)
                library["items"] = found.get("items") or []
            except FnError:
                library["items"] = []

        with ThreadPoolExecutor(max_workers=6) as pool:
            watching = pool.submit(self.client.continue_watching)
            jobs = [pool.submit(preview, library) for library in libraries]
            for job in jobs:
                job.result()
            continue_items = watching.result()
        return {"libraries": libraries, "continue": continue_items}

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
        with self._danmu_lock:
            self._danmu_comments = []
        mpv_path = find_mpv(self.config.get("mpv_path") or "")
        if not mpv_path:
            raise FnError("没有找到随程序提供的 mpv。请重新下载完整压缩包。")
        info = self.client.play_info(guid)
        media_guid = info.get("media_guid") or ""
        if not media_guid:
            raise FnError("这部内容没有可播放的文件")
        item = info.get("item") if isinstance(info.get("item"), dict) else {}
        play_config = info.get("play_config") if isinstance(info.get("play_config"), dict) else {}
        opening = int(play_config.get("skip_opening") or 0)
        ending = int(play_config.get("skip_ending") or 0)
        start = int(info.get("ts") or 0)
        if opening > start:
            start = opening
        title = display_title(item) if item else guid
        episode_number = item.get("episode_number") or 0
        season_number = item.get("season_number") or 0
        match_name = item.get("tv_title") or item.get("title") or title
        if episode_number:
            match_name = "%s S%02dE%02d" % (match_name, season_number or 0, episode_number)
        self._save_job_progress_later()
        pool = ThreadPoolExecutor(max_workers=2)
        handed = False
        try:
            next_future = None if next_guid else pool.submit(self._find_next, info)
            danmaku_on = bool(self.config.get("danmu_on", True)) and bool(self.config.get("danmu_url"))
            danmu_future = pool.submit(self._write_danmu, match_name, episode_number) if danmaku_on else None
            stream = self.client.stream(media_guid)
            url, resolution, bitrate, quality_index = self._choose_url(media_guid, stream, info)
            session_id = uuid.uuid4().hex
            cookie = _stream_cookie(stream)
            with self.session_lock:
                self.sessions[session_id] = {"url": url, "cookie": cookie}
            self.playback_source = {
                "stream": stream,
                "info": info,
                "media_guid": media_guid,
                "quality_index": quality_index,
                "subtitle": "",
            }
            job = {
                "guid": info.get("guid") or guid,
                "parent_guid": info.get("parent_guid") or "",
                "media_guid": media_guid,
                "video_guid": info.get("video_guid") or "",
                "audio_guid": info.get("audio_guid") or "",
                "subtitle_guid": info.get("subtitle_guid") or "_no_display_",
                "resolution": resolution,
                "bitrate": bitrate,
                "quality_index": quality_index,
                "duration": int(item.get("duration") or 0),
                "title": title,
                "next_guid": next_guid or "",
                "session_id": session_id,
                "skip_opening": opening,
                "skip_ending": ending,
                "outro_done": False,
            }
            with self.job_lock:
                self.job = job
            local_url = "/stream/%s" % session_id
            self.player.start(
                mpv_path,
                "http://127.0.0.1:%s%s" % (self.port, local_url),
                title,
                job["guid"],
                start,
                "",
                danmaku_on,
                self.config.get("danmu_scale") or 1,
            )
            threading.Thread(
                target=self._finish_playback,
                args=(session_id, next_future, danmu_future, pool),
                daemon=True,
            ).start()
            handed = True
            return {"title": title, "guid": job["guid"]}
        finally:
            if not handed:
                pool.shutdown(wait=False)

    def stop(self):
        self._save_job_progress()
        with self.job_lock:
            self.job = None
        self.player.stop()

    def playback(self):
        if not self.player.running() or not self.job:
            raise FnError("播放器没有在运行")
        try:
            tracks = self.player.tracks()
        except OSError as err:
            raise FnError(str(err))
        source = self.playback_source or {}
        job = self.job
        return {
            "title": job.get("title") or "",
            "audio": tracks["audio"],
            "subtitle": tracks["subtitle"],
            "qualities": _quality_choices(source.get("stream") or {}),
            "quality_index": job.get("quality_index") or 0,
            "danmaku_on": tracks["danmaku_on"],
            "danmaku_ready": bool(source.get("subtitle")),
            "danmaku_scale": float(self.config.get("danmu_scale") or 1),
            "skip_opening": int(job.get("skip_opening") or 0),
            "skip_ending": int(job.get("skip_ending") or 0),
            "speed": tracks.get("speed") or 1,
        }

    def set_audio(self, track_id):
        self._player_call(lambda: self.player.set_audio(track_id))

    def set_subtitle(self, track_id):
        self._player_call(lambda: self.player.set_subtitle(track_id))

    def attach_window(self, window):
        self._window = window

    def focus_window(self, enabled=True):
        window = self._window
        if window is None:
            return
        try:
            window.on_top = bool(enabled)
        except Exception:
            pass
        if not enabled:
            return
        try:
            window.restore()
            window.show()
        except Exception:
            pass
        _raise_window(window)

    def danmaku_settings(self):
        with self._danmu_lock:
            ready = bool(self._danmu_comments)
        return {"settings": danmaku_options_from_config(self.config), "ready": ready}

    def update_danmaku_settings(self, payload):
        options = danmaku_options_from_config(self.config)
        if isinstance(payload, dict):
            options.update(payload)
        options = danmaku_options(options)
        self.config["danmaku"] = options
        self.config["danmu_on"] = options["on"]
        self._save_config()
        with self._danmu_lock:
            count = len(self._danmu_comments or [])
        refetch = (
            options["max_comments"] > self._danmu_loaded_limit
            and count >= self._danmu_loaded_limit > 0
            and self._danmu_match
        )
        if refetch:
            path = self._write_danmu(self._danmu_match[0], self._danmu_match[1])
        else:
            path = self._rewrite_danmu()
        if self.player.running():
            if path:
                self._player_call(lambda: self.player.set_danmaku_file(path))
            self._player_call(lambda: self.player.set_danmaku(options["on"]))
        return options

    def take_danmaku_request(self):
        path = self.player.status_path.with_name("status.txt.settings")
        try:
            token = path.read_text(encoding="utf-8").strip()
        except Exception:
            return False
        if not token or token == self._danmaku_request:
            return False
        self._danmaku_request = token
        self.focus_window(True)
        return True

    def set_danmaku(self, enabled, scale=None):
        payload = {"on": bool(enabled)}
        if scale is not None:
            payload["fontsize"] = max(12, min(40, int(round(22 * float(scale)))))
        self.update_danmaku_settings(payload)

    def set_speed(self, speed):
        self._player_call(lambda: self.player.set_speed(speed))

    def set_quality(self, index):
        source = self.playback_source or {}
        stream = source.get("stream")
        info = dict(source.get("info") or {})
        info["ts"] = int(self.player.position or info.get("ts") or 0)
        media_guid = source.get("media_guid") or ""
        job = self.job
        if not stream or not info or not job:
            raise FnError("播放器没有在运行")
        url, resolution, bitrate, picked = self._choose_url(media_guid, stream, info, index)
        with self.session_lock:
            session = self.sessions.get(job.get("session_id") or "")
            if session is not None:
                session["url"] = url
        job["resolution"] = resolution
        job["bitrate"] = bitrate
        job["quality_index"] = picked
        source["quality_index"] = picked
        local = "http://127.0.0.1:%s/stream/%s?v=%s" % (self.port, job["session_id"], int(time.time()))
        self._player_call(lambda: self.player.load(local, self.player.position))

    def set_skip(self, opening, ending):
        job = self.job
        if not job:
            raise FnError("播放器没有在运行")
        opening = max(0, min(int(opening or 0), 20 * 60))
        ending = max(0, min(int(ending or 0), 20 * 60))
        guid = job.get("parent_guid") or job.get("guid") or ""
        if guid:
            self.client.set_skip(guid, opening, ending)
        job["skip_opening"] = opening
        job["skip_ending"] = ending
        job["outro_done"] = False
        if opening > int(self.player.position or 0):
            self._player_call(lambda: self.player.seek(opening))

    def _player_call(self, action):
        try:
            action()
        except OSError as err:
            raise FnError(str(err))

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
        ticks = 0
        while True:
            time.sleep(2)
            ticks += 2
            if not self.player.running():
                continue
            self._maybe_skip_outro()
            if ticks >= 14:
                ticks = 0
                self._save_job_progress()

    def _maybe_skip_outro(self):
        job = self.job
        if not job or job.get("outro_done"):
            return
        ending = int(job.get("skip_ending") or 0)
        duration = float(self.player.duration or 0)
        position = float(self.player.position or 0)
        if ending <= 0 or duration <= ending or position < duration - ending:
            return
        job["outro_done"] = True
        if self.config.get("auto_next") and job.get("next_guid"):
            try:
                self.play(job["next_guid"])
            except FnError:
                return
        else:
            self._player_call(lambda: self.player.seek(max(duration - 1, 0)))

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

    def _finish_playback(self, session_id, next_future, danmu_future, pool):
        try:
            if next_future is not None:
                try:
                    found = next_future.result() or ""
                except Exception:
                    found = ""
                with self.job_lock:
                    job = self.job
                    if job and job.get("session_id") == session_id and found:
                        job["next_guid"] = found
            if danmu_future is None:
                return
            try:
                path = danmu_future.result() or ""
            except Exception:
                path = ""
            if not path:
                return
            source = self.playback_source
            if isinstance(source, dict):
                source["subtitle"] = path
            for _ in range(25):
                with self.job_lock:
                    job = self.job
                if not job or job.get("session_id") != session_id or not self.player.running():
                    return
                try:
                    self.player.set_danmaku_file(path)
                    return
                except OSError:
                    time.sleep(0.12)
        finally:
            pool.shutdown(wait=False)

    def _save_job_progress_later(self):
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
        threading.Thread(target=self._record_body, args=(body,), daemon=True).start()

    def _record_body(self, body):
        try:
            self.client.record(body)
        except FnError:
            return

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

    def _choose_url(self, media_guid, stream, info, quality_index=None):
        direct = [item for item in (stream.get("direct_link_qualities") or []) if isinstance(item, dict)]
        qualities = [item for item in (stream.get("qualities") or []) if isinstance(item, dict)]
        file_stream = stream.get("file_stream") if isinstance(stream.get("file_stream"), dict) else {}
        path = file_stream.get("path") or ""
        online = path.startswith("在线/") or path.startswith("在线\\")
        if direct:
            if quality_index is None:
                picked_index = max(range(len(direct)), key=lambda index: direct[index].get("bitrate") or 0)
            else:
                picked_index = max(0, min(int(quality_index), len(direct) - 1))
            picked = direct[picked_index]
            resolution = picked.get("resolution") or ""
            bitrate = picked.get("bitrate") or 0
            link = (picked.get("url") or "").replace("\\u0026", "&")
            if link:
                if link.startswith("/"):
                    link = self.client.base + link
                return link, resolution, bitrate, picked_index
            return (
                self.client.base + "/v/api/v1/media/range/%s?direct_link_quality_index=%s" % (media_guid, picked_index),
                resolution,
                bitrate,
                picked_index,
            )
        picked_index = 0
        resolution = ""
        bitrate = 0
        if qualities:
            if quality_index is None:
                picked_index = 0
            else:
                picked_index = max(0, min(int(quality_index), len(qualities) - 1))
            resolution = qualities[picked_index].get("resolution") or ""
            bitrate = qualities[picked_index].get("bitrate") or 0
        if online and resolution:
            return self._transcoded_url(stream, info, resolution, bitrate), resolution, bitrate, picked_index
        return self.client.base + "/v/api/v1/media/range/" + media_guid, resolution, bitrate, picked_index

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
        self._danmu_match = (file_name, episode_number)
        limit = danmaku_options_from_config(self.config)["max_comments"]
        try:
            comments = load_comments(self.config.get("danmu_url") or "", file_name, episode_number, limit)
        except Exception:
            return ""
        self._danmu_loaded_limit = limit
        with self._danmu_lock:
            self._danmu_comments = comments
        if not comments:
            return ""
        return self._save_danmu_ass(comments)

    def _rewrite_danmu(self):
        with self._danmu_lock:
            comments = list(self._danmu_comments or [])
        if not comments:
            return ""
        return self._save_danmu_ass(comments)

    def _save_danmu_ass(self, comments):
        options = danmaku_options_from_config(self.config)
        duration = 0
        job = self.job or {}
        try:
            duration = int(job.get("duration") or 0)
        except (TypeError, ValueError):
            duration = 0
        if self.player.duration and self.player.duration > duration:
            duration = int(self.player.duration)
        path = self.directory / "danmu.ass"
        path.write_text(comments_to_ass(comments, options, duration), encoding="utf-8")
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
        vote = item.get("vote_average")
        if vote and str(vote) not in ("0", "0.0"):
            meta.append(str(vote))
        stream = item.get("media_stream") if isinstance(item.get("media_stream"), dict) else {}
        resolutions = stream.get("resolutions") or []
        if resolutions:
            meta.append(str(resolutions[0]))
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


def _quality_choices(stream):
    direct = [item for item in (stream.get("direct_link_qualities") or []) if isinstance(item, dict)]
    rows = direct or [item for item in (stream.get("qualities") or []) if isinstance(item, dict)]
    if not rows:
        return [{"index": 0, "label": "原画"}]
    choices = []
    for index, quality in enumerate(rows):
        resolution = str(quality.get("resolution") or "").strip()
        bitrate = int(quality.get("bitrate") or 0)
        if bitrate >= 1000000:
            rate = "%s Mbps" % int(round(bitrate / 1000000.0))
        elif bitrate >= 1000:
            rate = "%s kbps" % (bitrate // 1000)
        else:
            rate = ""
        label = resolution or rate or ("画质 %s" % (index + 1))
        if resolution and rate:
            label = "%s · %s" % (resolution, rate)
        choices.append({"index": index, "label": label})
    return choices


def _stream_cookie(stream):
    header = stream.get("header") if isinstance(stream.get("header"), dict) else {}
    cookie = header.get("Cookie") or header.get("cookie") or ""
    if isinstance(cookie, list):
        cookie = "; ".join(str(part) for part in cookie if part)
    return str(cookie or "")


def _raise_window(window):
    native = getattr(window, "native", None)
    hwnd = native if isinstance(native, int) else 0
    if not hwnd:
        handle = getattr(native, "Handle", None) or getattr(native, "hwnd", None)
        if hasattr(handle, "ToInt64"):
            hwnd = int(handle.ToInt64())
        elif isinstance(handle, int):
            hwnd = handle
    if not hwnd:
        return
    try:
        user32 = ctypes.windll.user32
        user32.ShowWindow(hwnd, 9)
        user32.SetForegroundWindow(hwnd)
    except Exception:
        return
