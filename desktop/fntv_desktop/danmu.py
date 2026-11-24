"""把弹幕接口的评论转成 mpv 能挂上的 ASS 字幕。"""

import json
import urllib.parse
import urllib.request

from .api import SSL_CTX


def danmaku_options(raw=None):
    src = raw if isinstance(raw, dict) else {}

    def number(key, default, low, high):
        try:
            value = float(src.get(key, default))
        except (TypeError, ValueError):
            value = float(default)
        return max(low, min(high, value))

    def flag(key, default):
        if key not in src:
            return default
        value = src.get(key)
        if isinstance(value, str):
            return value.strip().lower() not in ("0", "false", "off", "no", "")
        return bool(value)

    return {
        "on": flag("on", True),
        "area": int(number("area", 35, 10, 80)),
        "speed": number("speed", 1, 0.3, 3),
        "opacity": number("opacity", 0.85, 0, 1),
        "fontsize": int(number("fontsize", 22, 12, 40)),
        "scroll": flag("scroll", True),
        "top": flag("top", True),
        "bottom": flag("bottom", True),
        "time_scale": flag("time_scale", False),
        "outline": flag("outline", True),
        "density": int(number("density", 100, 50, 100)),
        "max_active": int(number("max_active", 40, 10, 80)),
        "row_spacing": number("row_spacing", 1.8, 1.2, 3),
        "offset": int(number("offset", 0, -120, 120)),
        "max_comments": int(number("max_comments", 50000, 100, 50000)),
    }


def danmaku_options_from_config(config):
    saved = {}
    if isinstance(config, dict):
        raw = config.get("danmaku")
        if isinstance(raw, dict):
            saved.update(raw)
        if "fontsize" not in saved and config.get("danmu_scale"):
            try:
                saved["fontsize"] = int(round(22 * float(config.get("danmu_scale") or 1)))
            except (TypeError, ValueError):
                pass
        if "on" not in saved:
            saved["on"] = bool(config.get("danmu_on", True))
    return danmaku_options(saved)


def comments_to_ass(comments, options=None, duration=0):
    opt = danmaku_options(options)
    size = max(16, int(round(opt["fontsize"] * 46 / 22.0)))
    lane_step = max(size, int(round(size * opt["row_spacing"] * 0.70)))
    max_y = int(1080 * opt["area"] / 100)
    lane_count = max(1, int((max_y - 40) / lane_step))
    lanes = [0.0] * lane_count
    scroll_for = 8.0 / opt["speed"]
    alpha = int(round((1 - opt["opacity"]) * 255))
    alpha = max(0, min(255, alpha))
    prepared = []
    for item in _keep_density(comments, opt["density"]):
        text, at, mode, color = _parse_comment(item)
        if text:
            prepared.append((text, at, mode, color))
    if opt["time_scale"] and duration and prepared:
        last = max(item[1] for item in prepared)
        if last > 0:
            ratio = float(duration) / float(last)
            prepared = [(text, at * ratio, mode, color) for text, at, mode, color in prepared]
    if opt["offset"]:
        prepared = [(text, at + opt["offset"], mode, color) for text, at, mode, color in prepared]
    prepared = [item for item in prepared if item[1] >= 0]
    prepared.sort(key=lambda item: item[1])
    events = []
    active = []
    for text, at, mode, color in prepared:
        style = "Scroll"
        if mode == 5:
            style = "Top"
        elif mode == 4:
            style = "Bottom"
        if style == "Scroll" and not opt["scroll"]:
            continue
        if style == "Top" and not opt["top"]:
            continue
        if style == "Bottom" and not opt["bottom"]:
            continue
        active = [end for end in active if end > at]
        if len(active) >= opt["max_active"]:
            continue
        if style == "Scroll":
            lane = min(range(len(lanes)), key=lambda index: lanes[index])
            lanes[lane] = at + scroll_for
            events.append((at, at + scroll_for, style, lane, color, text))
            active.append(at + scroll_for)
        else:
            events.append((at, at + 4, style, 0, color, text))
            active.append(at + 4)
    outline = 2 if opt["outline"] else 0
    style_row = (
        "Style: %s,Microsoft YaHei,%d,&H00FFFFFF,&H00FFFFFF,&H00202020,&H64000000,"
        "0,0,0,0,100,100,0,0,1,%d,0,%s,40,40,%d,1"
    )
    lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        "PlayResX: 1920",
        "PlayResY: 1080",
        "WrapStyle: 2",
        "ScaledBorderAndShadow: yes",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
        "Alignment, MarginL, MarginR, MarginV, Encoding",
        style_row % ("Scroll", size, outline, 8, 30),
        style_row % ("Top", size, outline, 8, 40),
        style_row % ("Bottom", size, outline, 2, 40),
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    for at, end, style, lane, color, text in events:
        colour = _ass_colour(color)
        fade = "\\alpha&H%02X&" % alpha
        if style == "Scroll":
            y = 40 + lane * lane_step
            body = "{\\an8\\move(1920,%d,-400,%d)%s\\c%s}%s" % (y, y, fade, colour, _escape(text))
        elif style == "Top":
            body = "{\\an8%s\\c%s}%s" % (fade, colour, _escape(text))
        else:
            body = "{\\an2%s\\c%s}%s" % (fade, colour, _escape(text))
        lines.append("Dialogue: 0,%s,%s,%s,,0,0,0,,%s" % (_ts(at), _ts(end), style, body))
    return "\n".join(lines) + "\n"


def _keep_density(comments, density):
    rows = list(comments or [])
    if density >= 100:
        return rows
    kept = []
    previous = 0
    for index, item in enumerate(rows, start=1):
        current = index * density // 100
        if current > previous:
            kept.append(item)
        previous = current
    return kept


def load_comments(base_url, file_name, episode_number=0, limit=3000):
    root = (base_url or "").strip().rstrip("/")
    if not root or not file_name:
        return []
    episode_id = _match_episode(root, file_name, episode_number)
    if episode_id <= 0:
        return []
    url = root + "/api/v2/comment/%s?format=json" % episode_id
    payload = _get_json(url)
    rows = _comment_rows(payload)
    if len(rows) > limit:
        step = len(rows) / float(limit)
        rows = [rows[int(index * step)] for index in range(limit)]
    return rows


def _match_episode(root, file_name, episode_number):
    try:
        matched = _post_json(root + "/api/v2/match", {"fileName": file_name})
    except Exception:
        matched = {}
    matches = matched.get("matches") if isinstance(matched, dict) else None
    if isinstance(matches, list) and matches:
        first = matches[0] if isinstance(matches[0], dict) else {}
        episode_id = int(first.get("episodeId") or 0)
        if episode_id > 0:
            return episode_id
        anime_id = int(first.get("animeId") or 0)
        if anime_id and episode_number:
            found = _episode_from_bangumi(root, anime_id, episode_number)
            if found:
                return found
    keyword = file_name
    for token in (" S", " s", " 第"):
        cut = keyword.find(token)
        if cut > 0:
            keyword = keyword[:cut]
            break
    if not keyword:
        return 0
    try:
        searched = _get_json(root + "/api/v2/search/anime?keyword=" + urllib.parse.quote(keyword))
    except Exception:
        return 0
    animes = []
    if isinstance(searched, dict):
        animes = searched.get("animes") or searched.get("data") or []
    if isinstance(animes, dict):
        animes = animes.get("animes") or []
    if not isinstance(animes, list) or not animes:
        return 0
    anime = animes[0] if isinstance(animes[0], dict) else {}
    anime_id = int(anime.get("animeId") or anime.get("id") or 0)
    if anime_id and episode_number:
        return _episode_from_bangumi(root, anime_id, episode_number)
    return 0


def _episode_from_bangumi(root, anime_id, episode_number):
    try:
        payload = _get_json(root + "/api/v2/bangumi/" + str(anime_id))
    except Exception:
        return 0
    episodes = []
    if isinstance(payload, dict):
        bangumi = payload.get("bangumi") if isinstance(payload.get("bangumi"), dict) else payload
        episodes = bangumi.get("episodes") or []
        if not episodes and isinstance(payload.get("data"), dict):
            episodes = payload["data"].get("episodes") or []
    for episode in episodes:
        if not isinstance(episode, dict):
            continue
        if int(episode.get("episodeNumber") or 0) == int(episode_number):
            return int(episode.get("episodeId") or 0)
    return 0


def _comment_rows(payload):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("comments", "data"):
            rows = payload.get(key)
            if isinstance(rows, list):
                return rows
    return []


def _parse_comment(item):
    if not isinstance(item, dict):
        return "", 0, 1, 0xFFFFFF
    if item.get("m") is not None or item.get("p") is not None:
        text = str(item.get("m") or "").strip()
        parts = str(item.get("p") or "0").split(",")
        try:
            at = float(parts[0])
        except ValueError:
            at = 0
        mode = 1
        color = 0xFFFFFF
        if len(parts) >= 2:
            try:
                mode = int(float(parts[1]))
            except ValueError:
                mode = 1
        if len(parts) >= 3:
            try:
                color = int(float(parts[2])) & 0xFFFFFF
            except ValueError:
                color = 0xFFFFFF
        return text, max(0, at), mode, color
    text = str(item.get("text") or item.get("content") or "").strip()
    try:
        at = float(item.get("time") or item.get("t") or 0)
    except (TypeError, ValueError):
        at = 0
    try:
        mode = int(item.get("mode") or 1)
    except (TypeError, ValueError):
        mode = 1
    color = item.get("color") if item.get("color") is not None else 0xFFFFFF
    try:
        color = int(color) & 0xFFFFFF
    except (TypeError, ValueError):
        color = 0xFFFFFF
    return text, max(0, at), mode, color


def _ass_colour(rgb):
    red = (rgb >> 16) & 255
    green = (rgb >> 8) & 255
    blue = rgb & 255
    return "&H00%02X%02X%02X&" % (blue, green, red)


def _escape(text):
    return text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\n", " ").replace("\r", "")


def _ts(seconds):
    if seconds < 0:
        seconds = 0
    total = int(seconds * 100)
    cent = total % 100
    total //= 100
    second = total % 60
    total //= 60
    minute = total % 60
    hour = total // 60
    return "%d:%02d:%02d.%02d" % (hour, minute, second, cent)


def _get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "FNTVDesktop"})
    with urllib.request.urlopen(req, context=SSL_CTX, timeout=8) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _post_json(url, body):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json", "User-Agent": "FNTVDesktop"},
        method="POST",
    )
    with urllib.request.urlopen(req, context=SSL_CTX, timeout=8) as resp:
        return json.loads(resp.read().decode("utf-8"))
