"""飞牛影视接口。签名规则与 Android 端 AuthInterceptor / FnAuthUtils 一致，单独实现。"""

import base64
import hashlib
import json
import random
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
                pairs.append((key, value))
        query_text = urllib.parse.urlencode(pairs, quote_via=urllib.parse.quote) if pairs else ""
        payload = None
        data = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
            data = payload.encode("utf-8")
        elif query_text:
            payload = query_text
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
            title = row.get("title") or ("特别篇" if number == 0 else "第%s季" % number)
            found.append({
                "guid": row.get("guid") or "",
                "title": title,
                "number": number,
                "poster": cover_path(row),
            })
        return found

    def episodes(self, guid):
        rows = self.data("GET", "/v/api/v1/episode/list/" + urllib.parse.quote(guid, safe="")) or []
        if not isinstance(rows, list):
            return []
        return [card_from_item(row) for row in rows if isinstance(row, dict)]

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


def display_title(row):
    tv = row.get("tv_title") or ""
    episode = row.get("episode_number") or 0
    title = row.get("title") or ""
    if tv:
        if episode:
            return "%s 第%s集" % (tv, episode)
        return tv
    return title or "未命名"


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
