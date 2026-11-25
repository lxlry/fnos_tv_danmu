"""本机网页窗口的接口。只监听 127.0.0.1。"""

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .api import FnError, SSL_CTX

WEB_ROOT = Path(__file__).resolve().parent / "web"
FILES = {
    "/": "index.html",
    "/index.html": "index.html",
    "/app.js": "app.js",
    "/app.css": "app.css",
}
TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def _handle(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)
        try:
            if path == "/api/status" and self.command == "GET":
                return self._json(self.server.desktop.status())
            if path == "/api/login" and self.command == "POST":
                body = self._body()
                result = self.server.desktop.login(
                    body.get("server") or "",
                    body.get("username") or "",
                    body.get("password") or "",
                    body.get("access_code") or "",
                    body.get("remember", True),
                    body.get("danmu_url") if "danmu_url" in body else None,
                )
                return self._json(result)
            if path == "/api/logout" and self.command == "POST":
                self.server.desktop.logout()
                return self._json({"ok": True})
            if path == "/api/settings" and self.command == "POST":
                return self._json(self.server.desktop.update_settings(self._body()))
            if path == "/api/home" and self.command == "GET":
                return self._json(self.server.desktop.home())
            if path == "/api/library" and self.command == "GET":
                page = _int(query.get("page", ["1"])[0], 1)
                return self._json(self.server.desktop.library(query.get("guid", [""])[0], page))
            if path == "/api/search" and self.command == "GET":
                return self._json({"items": self.server.desktop.search(query.get("q", [""])[0])})
            if path == "/api/detail" and self.command == "GET":
                return self._json(self.server.desktop.detail(query.get("guid", [""])[0]))
            if path == "/api/episodes" and self.command == "GET":
                return self._json({"items": self.server.desktop.episodes(query.get("guid", [""])[0])})
            if path == "/api/play" and self.command == "POST":
                body = self._body()
                return self._json(self.server.desktop.play(body.get("guid") or "", body.get("next_guid") or ""))
            if path == "/api/stop" and self.command == "POST":
                self.server.desktop.stop()
                return self._json({"ok": True})
            if path == "/api/playing" and self.command == "GET":
                return self._json(self.server.desktop.playing())
            if path == "/api/playback" and self.command == "GET":
                return self._json(self.server.desktop.playback())
            if path == "/api/playback/audio" and self.command == "POST":
                self.server.desktop.set_audio(self._body().get("id"))
                return self._json({"ok": True})
            if path == "/api/playback/subtitle" and self.command == "POST":
                self.server.desktop.set_subtitle(self._body().get("id"))
                return self._json({"ok": True})
            if path == "/api/danmaku/settings" and self.command == "GET":
                return self._json(self.server.desktop.danmaku_settings())
            if path == "/api/danmaku/settings" and self.command == "POST":
                return self._json({"settings": self.server.desktop.update_danmaku_settings(self._body())})
            if path == "/api/danmaku/request" and self.command == "GET":
                return self._json(self.server.desktop.take_danmaku_request())
            if path == "/api/skip" and self.command == "GET":
                return self._json(self.server.desktop.skip_state())
            if path == "/api/window/close" and self.command == "POST":
                return self._json(self.server.desktop.handle_window_close(self._body()))
            if path == "/api/window/focus" and self.command == "POST":
                self.server.desktop.focus_window(bool(self._body().get("on")))
                return self._json({"ok": True})
            if path == "/api/panel/close" and self.command == "POST":
                self.server.desktop.close_panel()
                return self._json({"ok": True})
            if path == "/api/playback/danmaku" and self.command == "POST":
                body = self._body()
                self.server.desktop.set_danmaku(body.get("on"), body.get("scale"))
                return self._json({"ok": True})
            if path == "/api/playback/quality" and self.command == "POST":
                self.server.desktop.set_quality(self._body().get("index"))
                return self._json({"ok": True})
            if path == "/api/playback/speed" and self.command == "POST":
                self.server.desktop.set_speed(self._body().get("speed"))
                return self._json({"ok": True})
            if path == "/api/playback/skip" and self.command == "POST":
                body = self._body()
                self.server.desktop.set_skip(body.get("opening"), body.get("ending"))
                return self._json({"ok": True})
            if path == "/api/update" and self.command == "GET":
                quiet = query.get("quiet", ["0"])[0] == "1"
                return self._json(self.server.desktop.check_update(quiet))
            if path == "/api/update/download" and self.command == "POST":
                body = self._body()
                return self._json(self.server.desktop.download_update(body.get("exeUrl") or ""))
            if path == "/api/update/progress" and self.command == "GET":
                return self._json(self.server.desktop.update_progress())
            if path == "/api/update/apply" and self.command == "POST":
                return self._json(self.server.desktop.apply_update())
            if path == "/img" and self.command == "GET":
                return self._image(query.get("p", [""])[0])
            if path.startswith("/stream/"):
                return self._stream(path, query)
            if path in FILES and self.command == "GET":
                return self._file(FILES[path])
            self._json({"message": "没有这个地址"}, 404)
        except FnError as err:
            self._json({"message": str(err), "kind": err.kind}, 400)
        except Exception as err:
            self._json({"message": "出错了：%s" % err}, 500)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        if not raw:
            return {}
        try:
            data = json.loads(raw.decode("utf-8"))
        except Exception:
            raise FnError("请求格式不正确")
        return data if isinstance(data, dict) else {}

    def _json(self, payload, status=200):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _file(self, name):
        path = WEB_ROOT / name
        if not path.is_file():
            self._json({"message": "页面文件缺失"}, 500)
            return
        raw = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", TYPES.get(path.suffix, "application/octet-stream"))
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _image(self, poster):
        desktop = self.server.desktop
        url = desktop.client.image_url(poster)
        if not url:
            self.send_error(404)
            return
        try:
            resp, _final = open_upstream(desktop.client, url, "")
        except Exception:
            self.send_error(404)
            return
        try:
            data = resp.read()
            ctype = resp.headers.get("Content-Type", "image/jpeg")
        finally:
            resp.close()
        self.send_response(200)
        self.send_header("Content-Type", ctype.split(";")[0])
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "private, max-age=86400")
        self.end_headers()
        self.wfile.write(data)

    def _stream(self, path, query):
        parts = [part for part in path.split("/") if part]
        if len(parts) < 2:
            self.send_error(404)
            return
        target = urllib.parse.unquote(query.get("u", [""])[0]) if len(parts) >= 3 else ""
        desktop = self.server.desktop
        url, cookie = desktop.open_stream(parts[1], target)
        range_header = self.headers.get("Range")
        resp, final = open_upstream(desktop.client, url, cookie, range_header)
        try:
            ctype = resp.headers.get("Content-Type", "application/octet-stream")
            playlist = "mpegurl" in ctype.lower() or ".m3u8" in final.split("?")[0].lower()
            if playlist:
                text = resp.read().decode("utf-8", "replace")
                body = rewrite_playlist(text, final, parts[1]).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.apple.mpegurl")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            status = resp.status if resp.status in (200, 206) else 200
            self.send_response(status)
            for name in ("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"):
                value = resp.headers.get(name)
                if value:
                    self.send_header(name, value)
            self.end_headers()
            while True:
                chunk = resp.read(256 * 1024)
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except OSError:
                    break
        finally:
            resp.close()


def open_upstream(client, url, cookie, range_header=None):
    current = url
    last_error = None
    for _ in range(6):
        headers = client.signed_headers(current, cookie)
        if range_header and ".m3u8" not in current.split("?")[0].lower():
            headers["Range"] = range_header
        req = urllib.request.Request(current, headers=headers, method="GET")
        try:
            resp = urllib.request.urlopen(req, context=SSL_CTX, timeout=None)
            return resp, current
        except urllib.error.HTTPError as err:
            last_error = err
            if err.code in (301, 302, 303, 307, 308):
                location = err.headers.get("Location") if err.headers else ""
                err.close()
                if not location:
                    break
                current = urllib.parse.urljoin(current, location)
                continue
            detail = ""
            try:
                detail = err.read().decode("utf-8", "replace")[:160]
            except Exception:
                detail = ""
            err.close()
            raise FnError("片源返回 HTTP %s %s" % (err.code, detail))
        except urllib.error.URLError as err:
            raise FnError("片源连不上：%s" % err.reason)
    raise FnError("片源跳转失败：%s" % last_error)


def rewrite_playlist(text, base, session_id):
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            lines.append(line)
            continue
        if stripped.startswith("#"):
            lines.append(_rewrite_uri(line, base, session_id))
            continue
        absolute = urllib.parse.urljoin(base, stripped)
        lines.append("/stream/%s/u?u=%s" % (session_id, urllib.parse.quote(absolute, safe="")))
    return "\n".join(lines) + "\n"


def _rewrite_uri(line, base, session_id):
    def replace(match):
        absolute = urllib.parse.urljoin(base, match.group(1))
        proxied = "/stream/%s/u?u=%s" % (session_id, urllib.parse.quote(absolute, safe=""))
        return 'URI="%s"' % proxied

    return re.sub(r'URI="([^"]+)"', replace, line)


def _int(value, default):
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return number if number > 0 else default


def serve(desktop):
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.desktop = desktop
    desktop.port = httpd.server_address[1]
    return httpd
