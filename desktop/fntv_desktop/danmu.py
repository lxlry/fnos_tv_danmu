"""把弹幕接口的评论转成 mpv 能挂上的 ASS 字幕。"""

import json
import urllib.parse
import urllib.request

from .api import SSL_CTX


def comments_to_ass(comments):
    events = []
    lanes = [0.0] * 14
    for item in comments:
        text, at, mode, color = _parse_comment(item)
        if not text:
            continue
        if mode in (4, 5):
            end = at + 4
            style = "Top" if mode == 5 else "Bottom"
            events.append((at, end, style, 0, color, text))
            continue
        lane = min(range(len(lanes)), key=lambda index: lanes[index])
        if lanes[lane] > at:
            lane = min(range(len(lanes)), key=lambda index: lanes[index])
        lanes[lane] = at + 8
        events.append((at, at + 8, "Scroll", lane, color, text))
    events.sort(key=lambda item: item[0])
    lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        "PlayResX: 1920",
        "PlayResY: 1080",
        "WrapStyle: 2",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
        "Alignment, MarginL, MarginR, MarginV, Encoding",
        "Style: Scroll,Microsoft YaHei,46,&H00FFFFFF,&H00FFFFFF,&H00202020,&H64000000,"
        "0,0,0,0,100,100,0,0,1,2,0,8,40,40,30,1",
        "Style: Top,Microsoft YaHei,46,&H00FFFFFF,&H00FFFFFF,&H00202020,&H64000000,"
        "0,0,0,0,100,100,0,0,1,2,0,8,40,40,40,1",
        "Style: Bottom,Microsoft YaHei,46,&H00FFFFFF,&H00FFFFFF,&H00202020,&H64000000,"
        "0,0,0,0,100,100,0,0,1,2,0,2,40,40,40,1",
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    for at, end, style, lane, color, text in events:
        colour = _ass_colour(color)
        if style == "Scroll":
            y = 48 + lane * 58
            body = "{\\move(1980,%d,-500,%d)\\c%s}%s" % (y, y, colour, _escape(text))
        else:
            body = "{\\c%s}%s" % (colour, _escape(text))
        lines.append("Dialogue: 0,%s,%s,%s,,0,0,0,,%s" % (_ts(at), _ts(end), style, body))
    return "\n".join(lines) + "\n"


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
