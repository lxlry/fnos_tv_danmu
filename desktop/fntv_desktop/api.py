"""飞牛影视接口。签名规则与 Android 端 AuthInterceptor / FnAuthUtils 一致，单独实现。"""

import base64
import hashlib
import json
import random
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

API_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
API_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237"

SSL_CTX = ssl._create_unverified_context()


class FnError(Exception):
    def __init__(self, message, kind=""):
        super().__init__(message)
        self.kind = kind


def md5_text(text):
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def gen_authx(url_path, payload):
    nonce = str(random.randint(100000, 999999))
    timestamp = str(int(time.time() * 1000))
    data = payload if payload is not None else ""
    sign = md5_text(API_KEY + "_" + url_path + "_" + nonce + "_" + timestamp + "_" + md5_text(data) + "_" + API_SECRET)
    return "nonce=%s&timestamp=%s&sign=%s" % (nonce, timestamp, sign)


def normalize_server(raw):
    """拆出服务器根地址和地址里夹带的 /c/访问码。"""
    value = (raw or "").strip()
    code = ""
    if not value:
        return "", ""
    if "://" not in value:
        value = "http://" + value
    marker = "/c/"
    at = value.find(marker)
    if at >= 0:
        code = value[at + len(marker):]
        for stop in ("/", "?", "#"):
            cut = code.find(stop)
            if cut >= 0:
                code = code[:cut]
        code = urllib.parse.unquote(code).strip()
        value = value[:at]
    value = value.rstrip("/")
    if value.endswith("/v"):
        value = value[:-2]
    return value, code


def encode_access_code(code):
    if not code:
        return ""
    return base64.b64encode(code.encode("utf-8")).decode("ascii")


def _classify_access(status, content_type, with_code):
    if status == 429:
        return "limited"
    html = "html" in (content_type or "").lower()
    if with_code:
        if 200 <= status < 300 and not html:
            return "open"
        if status in (401, 403):
            return "rejected"
        return "unknown"
    if status == 204 or (status == 200 and not html):
        return "open"
    if status in (401, 403):
        return "required"
    return "unknown"


class FnClient:
    def __init__(self):
        self.base = ""
        self.token = ""
        self.access_code = ""
        self.username = ""

    def set_session(self, base, token="", access_code="", username=""):
        self.base = (base or "").rstrip("/")
        self.token = token or ""
        self.access_code = (access_code or "").strip()
        self.username = username or ""

    @property
    def host(self):
        parsed = urllib.parse.urlparse(self.base)
        return parsed.hostname or ""

    def probe_access(self, code=None):
        url = self.base + "/access_code_verify"
        headers = {"Cookie": "mode=relay"}
        use = (code if code is not None else "").strip()
        if use:
            headers["x-access-code"] = encode_access_code(use)
            headers["x-access-source"] = "app"
        req = urllib.request.Request(url, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(req, context=SSL_CTX, timeout=10) as resp:
                return _classify_access(resp.status, resp.headers.get("Content-Type", ""), bool(use))
        except urllib.error.HTTPError as err:
            return _classify_access(err.code, err.headers.get("Content-Type", "") if err.headers else "", bool(use))
        except Exception:
            return "offline"

    def request(self, method, path, query=None, body=None, extra_headers=None, timeout=25):
        if not self.base:
            raise FnError("还没有服务器地址")
        pairs = []
        if query:
            for key, value in query.items():
                if value is None:
                    continue
                pairs.append((str(key), str(value)))
        query_text = urllib.parse.urlencode(pairs, quote_via=urllib.parse.quote) if pairs else ""
        # 签名用原始查询串。中文如果按百分号编码计算，服务器会返回 invalid sign。
        sign_query = "&".join("%s=%s" % pair for pair in pairs)
        payload = None
        data = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
            data = payload.encode("utf-8")
        elif sign_query:
            payload = sign_query
        headers = {
            "Content-Type": "application/json",
            "Authx": gen_authx(path, payload),
            "Cookie": "mode=relay",
            "x-trim-client": "web",
            "x-trim-client-version": "608",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        }
        if self.token:
            headers["Authorization"] = self.token
        if self.access_code:
            headers["x-access-code"] = encode_access_code(self.access_code)
            headers["x-access-source"] = "app"
        if extra_headers:
            headers.update(extra_headers)
        url = self.base + path
        if query_text:
            url += "?" + query_text
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, context=SSL_CTX, timeout=timeout) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as err:
            detail = ""
            try:
                detail = err.read().decode("utf-8", "replace")[:180]
            except Exception:
                detail = ""
            if err.code in (401, 403):
                raise FnError("登录已失效或访问码不正确", "auth")
            raise FnError("服务器返回 HTTP %s %s" % (err.code, detail))
        except urllib.error.URLError as err:
            raise FnError("无法连接服务器：%s" % err.reason)
        if not raw:
            return {}
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except Exception:
            raise FnError("服务器返回的不是 JSON")
        if isinstance(parsed, dict) and parsed.get("code") not in (None, 0):
            raise FnError(parsed.get("msg") or "请求失败")
        return parsed

    def data(self, method, path, query=None, body=None, extra_headers=None):
        parsed = self.request(method, path, query=query, body=body, extra_headers=extra_headers)
        if isinstance(parsed, dict) and "data" in parsed:
            return parsed.get("data")
        return parsed

    def login(self, username, password):
        nonce = str(random.randint(100000, 999999))
        result = self.data("POST", "/v/api/v1/login", body={
            "app_name": "trimemedia-web",
            "username": username,
            "password": password,
            "nonce": nonce,
        })
        token = ""
        if isinstance(result, dict):
            token = result.get("token") or ""
        if not token:
            raise FnError("登录失败")
        self.token = token
        self.username = username
        return token

    def user_info(self):
        info = self.data("GET", "/v/api/v1/user/info")
        return info if isinstance(info, dict) else {}

    def libraries(self):
        rows = self.data("GET", "/v/api/v1/mediadb/list") or []
        sums = self.data("GET", "/v/api/v1/mediadb/sum") or {}
        if not isinstance(rows, list):
            rows = []
        if not isinstance(sums, dict):
            sums = {}
        items = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            guid = row.get("guid") or ""
            poster = row.get("poster") or ""
            if not poster:
                posters = row.get("posters") or []
                if isinstance(posters, list) and posters:
                    poster = posters[0]
            items.append({
                "guid": guid,
                "title": row.get("title") or "未命名",
                "poster": poster or "",
                "count": sums.get(guid) or 0,
                "category": row.get("category") or "",
                "type": "Library",
            })
        return items

    def continue_watching(self):
        rows = self.data("GET", "/v/api/v1/play/list") or []
        if not isinstance(rows, list):
            return []
        return [card_from_item(row) for row in rows if isinstance(row, dict)]

    def browse(self, ancestor_guid, page=1, page_size=40):
        body = {
            "ancestor_guid": ancestor_guid,
            "tags": {"type": ["Movie", "TV", "Directory", "Video"]},
            "exclude_grouped_video": 1,
            "sort_type": "DESC",
            "sort_column": "create_time",
            "page_size": page_size,
        }
        if page > 1:
            body["page"] = page
        result = self.data("POST", "/v/api/v1/item/list", body=body) or {}
        rows = result.get("list") if isinstance(result, dict) else []
        total = result.get("total") if isinstance(result, dict) else 0
        if not rows:
            body["tags"] = {"type": ["Movie", "TV", "Directory", "Video", "Episode"]}
            body["exclude_grouped_video"] = 0
            body["sort_column"] = "sort_title"
            body["sort_type"] = "ASC"
            result = self.data("POST", "/v/api/v1/item/list", body=body) or {}
            rows = result.get("list") if isinstance(result, dict) else []
            total = result.get("total") if isinstance(result, dict) else total
        cards = [card_from_item(row) for row in (rows or []) if isinstance(row, dict)]
        return {"items": cards, "total": total or len(cards), "page": page, "page_size": page_size}

    def search(self, keyword):
        rows = self.data("GET", "/v/api/v1/search/list", query={"q": keyword}) or []
        if isinstance(rows, dict):
            rows = rows.get("list") or []
        if not isinstance(rows, list):
            return []
        return [card_from_item(row) for row in rows if isinstance(row, dict)]

    def play_info(self, guid):
        info = self.data("POST", "/v/api/v1/play/info", body={"item_guid": guid})
        if not isinstance(info, dict):
            raise FnError("没有这部影片的信息")
        return info

    def seasons(self, guid):
        rows = self.data("GET", "/v/api/v1/season/list/" + urllib.parse.quote(guid, safe="")) or []
        if not isinstance(rows, list):
            return []
        found = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            number = row.get("season_number") or 0
            title = row.get("title") or ""
            if number == 0 or title.strip() in ("特别篇", "Specials"):
                title = "特别篇"
            elif not title:
                title = "第%s季" % number
            count = row.get("local_number_of_episodes") or row.get("number_of_episodes") or 0
            aired = str(row.get("air_date") or "")
            found.append({
                "guid": row.get("guid") or "",
                "title": title,
                "number": number,
                "poster": cover_path(row),
                "count": count,
                "year": aired[:4] if len(aired) >= 4 else "",
                "overview": row.get("overview") or "",
            })
        return found

    def episodes(self, guid):
        rows = self.data("GET", "/v/api/v1/episode/list/" + urllib.parse.quote(guid, safe="")) or []
        if not isinstance(rows, list):
            return []
        return [card_from_item(row) for row in rows if isinstance(row, dict)]

    def people(self, guid):
        payload = self.data("POST", "/v/api/v1/person/list/" + urllib.parse.quote(guid, safe=""), body={}) or {}
        rows = payload.get("list") if isinstance(payload, dict) else payload
        if not isinstance(rows, list):
            return []
        people = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            name = (row.get("name") or row.get("original_name") or "").strip()
            role = _role_label(row)
            if not name and not role:
                continue
            people.append({
                "name": name,
                "role": role,
                "image": row.get("profile_path") or "",
                "order": row.get("order") or 0,
            })
        people.sort(key=lambda item: item["order"])
        return people

    def file_info(self, guid, info):
        payload = self.data("GET", "/v/api/v1/stream/list/" + urllib.parse.quote(guid, safe="")) or {}
        if not isinstance(payload, dict):
            return {}
        dirs = []
        try:
            listed = self.data("GET", "/v/api/v1/server/getAppAuthorizedDir") or {}
            if isinstance(listed, dict):
                dirs = listed.get("authDirList") or listed.get("auth_dir_list") or []
        except FnError:
            dirs = []
        return _file_summary(payload, info if isinstance(info, dict) else {}, dirs)

    def stream(self, media_guid):
        body = {
            "header": {"User-Agent": ["Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"]},
            "level": 1,
            "media_guid": media_guid,
            "ip": md5_text(self.username or "video"),
            "nonce": str(random.randint(100000, 999999)),
        }
        result = self.data("POST", "/v/api/v1/stream", body=body)
        if not isinstance(result, dict):
            raise FnError("没有可播放的片源")
        return result

    def play_link(self, body):
        result = self.data("POST", "/v/api/v1/play/play", body=body)
        if not isinstance(result, dict):
            raise FnError("没有播放地址")
        return result

    def set_skip(self, guid, opening, ending):
        self.data("POST", "/v/api/v1/play/setConfigByItem", body={
            "guid": guid,
            "skip_opening": int(opening),
            "skip_ending": int(ending),
        })

    def record(self, body):
        item = body.get("item_guid") or ""
        media = body.get("media_guid") or ""
        referer = self.base + "/v/video/" + item + "?media_guid=" + media
        self.request("POST", "/v/api/v1/play/record", body=body, extra_headers={"Referer": referer})

    def image_url(self, path):
        raw = path or ""
        if not raw:
            return ""
        if raw.startswith("http://") or raw.startswith("https://"):
            return raw
        if raw.startswith("/v/"):
            joiner = "&" if "?" in raw else "?"
            return self.base + raw + joiner + "w=400"
        return self.base + "/v/api/v1/sys/img" + raw + "?w=400"

    def signed_headers(self, url, extra_cookie=""):
        """给视频和图片补上和 App 一样的签名头。外链只带必要的 Cookie。"""
        parsed = urllib.parse.urlparse(url)
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        }
        same_host = parsed.hostname and self.host and parsed.hostname.lower() == self.host.lower()
        if same_host:
            payload = parsed.query or None
            headers.update({
                "Content-Type": "application/json",
                "Authx": gen_authx(parsed.path or "/", payload),
                "Cookie": "mode=relay",
                "x-trim-client": "web",
                "x-trim-client-version": "608",
            })
            if self.token:
                headers["Authorization"] = self.token
            if self.access_code:
                headers["x-access-code"] = encode_access_code(self.access_code)
                headers["x-access-source"] = "app"
        elif extra_cookie:
            headers["Cookie"] = extra_cookie
        return headers


def cover_path(row):
    poster = row.get("poster") or ""
    if isinstance(poster, str) and poster:
        return poster
    for key in ("posters", "backdrops", "still_path"):
        found = _first_path(row.get(key))
        if found:
            return found
    return ""


def _first_path(value):
    if isinstance(value, str) and value:
        return value
    if isinstance(value, list):
        for one in value:
            if one:
                return str(one)
    return ""


def _year(row):
    for key in ("release_date", "air_date"):
        raw = str(row.get(key) or "")
        if len(raw) >= 4 and raw[0].isdigit():
            return raw[:4]
    return ""


def display_title(row):
    tv = row.get("tv_title") or ""
    episode = row.get("episode_number") or 0
    title = row.get("title") or ""
    if tv:
        if episode:
            return "%s 第%s集" % (tv, episode)
        return tv
    return title or "未命名"


def _role_label(row):
    job = str(row.get("job") or "").strip()
    role = str(row.get("role") or "").strip()
    if job == "Actor":
        return "饰 " + role if role else "演员"
    names = {"Director": "导演", "Screenplay": "编剧", "Writer": "编剧", "Producer": "制片"}
    if job in names:
        return names[job]
    return job or role


def _file_summary(data, info, auth_dirs):
    files = [row for row in (data.get("files") or []) if isinstance(row, dict)]
    videos = [row for row in (data.get("video_streams") or []) if isinstance(row, dict)]
    audios = [row for row in (data.get("audio_streams") or []) if isinstance(row, dict)]
    subtitles = [row for row in (data.get("subtitle_streams") or []) if isinstance(row, dict)]
    if not files and not videos and not audios and not subtitles:
        return {}
    media_guid = info.get("media_guid") or ""
    video = _pick_video(videos, info.get("video_guid") or "", media_guid)
    if video and video.get("media_guid"):
        media_guid = video.get("media_guid") or media_guid
    elif not media_guid and files:
        media_guid = files[0].get("guid") or ""
    file_row = _pick_by_guid(files, media_guid) or (files[0] if files else None)
    if video is None:
        video = _first_real_video(videos, media_guid)
    audio = _pick_track(audios, media_guid, info.get("audio_guid") or "")
    subtitle = _pick_track(subtitles, media_guid, info.get("subtitle_guid") or "")
    placeholder = bool(video) and int(video.get("ext1") or 0) == -1
    any_real = any(int(row.get("ext1") or 0) != -1 for row in videos)
    if video is None:
        hide = not any_real and not audios and not subtitles
    elif not placeholder:
        hide = False
    else:
        hide = audio is None and subtitle is None
    facts = {"location": "", "size": "", "created": "", "added": "", "video": "", "audio": "", "subtitle": "", "report": ""}
    if file_row:
        facts["location"] = _file_location(file_row.get("path") or "", auth_dirs) or (file_row.get("file_name") or "")
        facts["size"] = _file_size(file_row.get("size") or 0)
        created = file_row.get("file_birth_time") or file_row.get("create_time") or 0
        added = file_row.get("create_time") or file_row.get("update_time") or 0
        facts["created"] = _clock(created)
        facts["added"] = _clock(added)
    if not hide:
        facts["video"] = _video_line(video)
        facts["audio"] = _audio_line(audio)
        facts["subtitle"] = _subtitle_line(subtitle)
    facts["report"] = _media_report(files, videos, audios, subtitles)
    if not any(facts.values()):
        return {}
    return facts


def _pick_video(videos, video_guid, media_guid):
    if video_guid:
        for row in videos:
            if row.get("guid") == video_guid and int(row.get("ext1") or 0) != -1:
                return row
    if media_guid:
        for row in videos:
            if row.get("media_guid") == media_guid and int(row.get("ext1") or 0) != -1:
                return row
    return _first_real_video(videos, "")


def _first_real_video(videos, media_guid):
    fallback = None
    for row in videos:
        if int(row.get("ext1") or 0) == -1:
            continue
        if not media_guid or row.get("media_guid") == media_guid:
            return row
        if fallback is None:
            fallback = row
    return fallback


def _pick_by_guid(rows, guid):
    if not guid:
        return None
    for row in rows:
        if row.get("guid") == guid:
            return row
    return None


def _pick_track(rows, media_guid, guid):
    if guid:
        for row in rows:
            if row.get("guid") == guid:
                return row
    fallback = None
    for row in rows:
        if media_guid and row.get("media_guid") != media_guid:
            continue
        if int(row.get("is_default") or 0) == 1:
            return row
        if fallback is None:
            fallback = row
    return fallback


def _file_location(path, auth_dirs):
    if not path:
        return ""
    normalized = path[1:] if path.startswith("/") else path
    parts = [part for part in normalized.split("/") if part]
    if len(parts) < 2:
        return path
    match = re.match(r"^vol(\d+)$", parts[0])
    if not match:
        return path
    volume = "存储空间 " + match.group(1)
    username = ""
    for directory in auth_dirs or []:
        if not isinstance(directory, dict):
            continue
        raw = str(directory.get("path") or "").strip().lstrip("/")
        prefix = [part for part in raw.split("/") if part]
        if len(prefix) >= 2 and prefix[0] == parts[0] and prefix[1] == parts[1] and parts[:len(prefix)] == prefix:
            username = str(directory.get("uname") or "").strip()
            if username:
                break
    user = (username + " 的文件") if username else ("用户 " + parts[1])
    rest = "/".join(parts[2:])
    return volume + "/" + user + ("/" + rest if rest else "")


def _file_size(size):
    try:
        value = float(size or 0)
    except (TypeError, ValueError):
        return ""
    if value <= 0:
        return ""
    units = ("B", "KB", "MB", "GB", "TB")
    unit = 0
    while value >= 1024 and unit < len(units) - 1:
        value /= 1024
        unit += 1
    return "%.2f %s" % (value, units[unit])


def _bitrate(bps):
    try:
        value = float(bps or 0)
    except (TypeError, ValueError):
        return ""
    if value <= 0:
        return ""
    units = ("bps", "Kbps", "Mbps", "Gbps")
    unit = 0
    while value >= 1000 and unit < len(units) - 1:
        value /= 1000
        unit += 1
    return "%.2f %s" % (value, units[unit])


def _clock(timestamp):
    try:
        value = int(timestamp or 0)
    except (TypeError, ValueError):
        return ""
    if value <= 0:
        return ""
    if value < 10000000000:
        value *= 1000
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(value / 1000.0))


def _language_name(code):
    key = str(code or "").strip().lower()
    if key in ("", "und", "zxx", "qaa-qtz", "zz-unknow"):
        return "未知"
    if key == "_no_display_":
        return "无"
    names = {
        "zh": "中文", "zh-cn": "中文", "chi": "中文", "zho": "中文", "zh-tw": "中文", "zh-hk": "中文",
        "en": "英语", "eng": "英语", "ja": "日语", "jpn": "日语", "ko": "韩语", "kor": "韩语",
        "fr": "法语", "fra": "法语", "fre": "法语", "de": "德语", "deu": "德语", "ger": "德语",
        "es": "西班牙语", "spa": "西班牙语", "ru": "俄语", "rus": "俄语",
        "th": "泰语", "tha": "泰语", "vi": "越南语", "vie": "越南语",
    }
    return names.get(key) or str(code).strip()


def _join(parts):
    return " ".join(part for part in parts if part)


def _video_line(video):
    if not video or int(video.get("ext1") or 0) == -1:
        return ""
    depth = int(video.get("bit_depth") or 0)
    return _join([
        str(video.get("resolution_type") or ""),
        str(video.get("codec_name") or "").upper(),
        _bitrate(video.get("bps") or 0),
        ("%s bit" % depth) if depth else "",
    ])


def _audio_line(audio):
    if not audio:
        return ""
    codec = audio.get("audio_type") or str(audio.get("codec_name") or "").upper()
    rate = audio.get("sample_rate")
    rate_text = ""
    if rate not in (None, "", 0, "0"):
        rate_text = str(rate) + " Hz"
    return _join([_language_name(audio.get("language")), str(codec or ""), audio.get("channel_layout") or "", rate_text])


def _subtitle_line(subtitle):
    if not subtitle:
        return ""
    codec = subtitle.get("codec_name") or subtitle.get("codec_type") or ""
    return _join([_language_name(subtitle.get("language")), str(codec).upper()])


def _media_report(files, videos, audios, subtitles):
    media_ids = []
    for row in files:
        guid = row.get("guid") or ""
        if guid and guid not in media_ids:
            media_ids.append(guid)
    for row in videos:
        guid = row.get("media_guid") or ""
        if guid and guid not in media_ids:
            media_ids.append(guid)
    if not media_ids:
        media_ids.append("")
    blocks = []
    for media_guid in media_ids:
        lines = []
        file_row = _pick_by_guid(files, media_guid)
        if file_row and file_row.get("file_name"):
            lines.append(file_row.get("file_name"))
        for video in videos:
            if int(video.get("ext1") or 0) == -1:
                continue
            if media_guid and video.get("media_guid") != media_guid:
                continue
            lines.append("视频  " + (_video_line(video) or "—"))
        for audio in audios:
            if media_guid and audio.get("media_guid") != media_guid:
                continue
            lines.append("音频  " + (_audio_line(audio) or "—"))
        for subtitle in subtitles:
            if media_guid and subtitle.get("media_guid") != media_guid:
                continue
            lines.append("字幕  " + (_subtitle_line(subtitle) or "—"))
        if lines:
            blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def card_from_item(row):
    episode = row.get("episode_number") or 0
    season = row.get("season_number") or 0
    subtitle = row.get("ancestor_name") or row.get("parent_title") or ""
    if episode:
        label = "第%s集" % episode
        if season:
            label = "第%s季 %s" % (season, label)
        subtitle = label
    duration = row.get("duration") or 0
    if not duration and row.get("runtime"):
        duration = int(row.get("runtime") or 0) * 60
    year = _year(row)
    if year:
        subtitle = "%s · %s" % (year, subtitle) if subtitle else year
    return {
        "guid": row.get("guid") or "",
        "title": display_title(row),
        "raw_title": row.get("title") or "",
        "type": row.get("type") or "",
        "poster": cover_path(row),
        "subtitle": subtitle,
        "ts": row.get("ts") or 0,
        "duration": duration or 0,
        "watched": row.get("watched") or 0,
        "overview": row.get("overview") or "",
        "parent_guid": row.get("parent_guid") or "",
        "episode_number": episode,
        "season_number": season,
    }
