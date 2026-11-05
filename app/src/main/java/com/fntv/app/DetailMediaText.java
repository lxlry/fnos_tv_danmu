package com.fntv.app;

import com.fntv.app.api.model.AuthDirList;
import com.fntv.app.api.model.MediaStreamList;
import com.fntv.app.api.model.PlayInfoResponse;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 把流列表格式化成详情页上的文件信息和媒体信息。 */
public final class DetailMediaText {
    private DetailMediaText() {}

    public static final class Picked {
        public MediaStreamList.FileEntry file;
        public MediaStreamList.VideoEntry video;
        public MediaStreamList.AudioEntry audio;
        public MediaStreamList.SubtitleEntry subtitle;
        public String mediaGuid = "";
        public boolean hideStreams;
        public String location = "";
        public String size = "";
        public String created = "";
        public String added = "";
        public String videoLine = "";
        public String audioLine = "";
        public String subtitleLine = "";
    }

    public static Picked pick(MediaStreamList data, PlayInfoResponse info,
                              List<AuthDirList.AuthDir> authDirs,
                              Map<String, String> iso6391, Map<String, String> iso6392) {
        if (data == null) return null;
        boolean hasFile = data.files != null && !data.files.isEmpty();
        boolean hasVideo = data.videoStreams != null && !data.videoStreams.isEmpty();
        boolean hasAudio = data.audioStreams != null && !data.audioStreams.isEmpty();
        boolean hasSub = data.subtitleStreams != null && !data.subtitleStreams.isEmpty();
        if (!hasFile && !hasVideo && !hasAudio && !hasSub) return null;

        Picked picked = new Picked();
        String videoGuid = info != null ? info.videoGuid : null;
        String audioGuid = info != null ? info.audioGuid : null;
        String subtitleGuid = info != null ? info.subtitleGuid : null;
        String mediaGuid = info != null ? info.mediaGuid : null;

        MediaStreamList.VideoEntry video = findVideo(data.videoStreams, videoGuid, mediaGuid);
        if (video != null && video.mediaGuid != null && !video.mediaGuid.isEmpty()) {
            picked.mediaGuid = video.mediaGuid;
        } else if (mediaGuid != null && !mediaGuid.isEmpty()) {
            picked.mediaGuid = mediaGuid;
        } else if (hasFile) {
            picked.mediaGuid = data.files.get(0).guid == null ? "" : data.files.get(0).guid;
        }

        picked.file = findFile(data.files, picked.mediaGuid);
        if (picked.file == null && hasFile) picked.file = data.files.get(0);
        if (video == null) video = firstRealVideo(data.videoStreams, picked.mediaGuid);
        picked.video = video;
        picked.audio = findAudio(data.audioStreams, picked.mediaGuid, audioGuid);
        picked.subtitle = findSubtitle(data.subtitleStreams, picked.mediaGuid, subtitleGuid);

        boolean placeholder = video != null && video.isPlaceholder();
        boolean anyReal = false;
        if (data.videoStreams != null) {
            for (MediaStreamList.VideoEntry v : data.videoStreams) {
                if (v != null && !v.isPlaceholder()) anyReal = true;
            }
        }
        boolean mediaHasAudio = picked.audio != null;
        boolean mediaHasSub = picked.subtitle != null;
        if (video == null) {
            picked.hideStreams = !anyReal && !hasAudio && !hasSub;
        } else if (!placeholder) {
            picked.hideStreams = false;
        } else {
            picked.hideStreams = !mediaHasAudio && !mediaHasSub;
        }

        if (picked.file != null) {
            String path = picked.file.path == null ? "" : picked.file.path;
            picked.location = formatFileLocation(path, authDirs);
            if (picked.location.isEmpty() && picked.file.fileName != null) {
                picked.location = picked.file.fileName;
            }
            picked.size = picked.file.size > 0 ? formatFileSize(picked.file.size) : "";
            long createdTs = picked.file.fileBirthTime > 0
                    ? picked.file.fileBirthTime : picked.file.createTime;
            long addedTs = picked.file.createTime > 0
                    ? picked.file.createTime : picked.file.updateTime;
            picked.created = formatTime(createdTs);
            picked.added = formatTime(addedTs);
        }
        if (!picked.hideStreams) {
            picked.videoLine = videoLine(picked.video);
            picked.audioLine = audioLine(picked.audio, iso6391, iso6392);
            picked.subtitleLine = subtitleLine(picked.subtitle, iso6391, iso6392);
        }
        return picked;
    }

    public static String fullReport(MediaStreamList data, Map<String, String> iso6391,
                                    Map<String, String> iso6392) {
        if (data == null) return "";
        StringBuilder out = new StringBuilder();
        List<String> mediaIds = new ArrayList<>();
        if (data.files != null) {
            for (MediaStreamList.FileEntry file : data.files) {
                if (file != null && file.guid != null && !mediaIds.contains(file.guid)) {
                    mediaIds.add(file.guid);
                }
            }
        }
        addMediaIds(mediaIds, data.videoStreams);
        if (mediaIds.isEmpty()) mediaIds.add("");
        for (int i = 0; i < mediaIds.size(); i++) {
            String mediaGuid = mediaIds.get(i);
            if (i > 0) out.append("\n\n");
            MediaStreamList.FileEntry file = findFile(data.files, mediaGuid);
            if (file != null && file.fileName != null && !file.fileName.isEmpty()) {
                out.append(file.fileName).append("\n");
            }
            appendVideos(out, data.videoStreams, mediaGuid);
            appendAudios(out, data.audioStreams, mediaGuid, iso6391, iso6392);
            appendSubtitles(out, data.subtitleStreams, mediaGuid, iso6391, iso6392);
        }
        return out.toString().trim();
    }

    public static String languageName(String code, Map<String, String> iso6391,
                                      Map<String, String> iso6392) {
        if (code == null) return "未知";
        String language = code.trim();
        if (language.isEmpty() || "und".equals(language) || "zxx".equals(language)
                || "qaa-qtz".equals(language) || "zz-unknow".equals(language)) {
            return "未知";
        }
        if ("_no_display_".equals(language)) return "无";
        if (language.length() == 2 && iso6391 != null) {
            String name = iso6391.get(language);
            if (name != null && !name.isEmpty()) return name;
        }
        if (language.length() == 3 && iso6392 != null) {
            String name = iso6392.get(language);
            if (name != null && !name.isEmpty()) return name;
        }
        String fallback = fallbackLanguage(language);
        return fallback.isEmpty() ? language : fallback;
    }

    private static String fallbackLanguage(String language) {
        String key = language.toLowerCase(Locale.US);
        if ("zh".equals(key) || "zh-cn".equals(key) || "chi".equals(key) || "zho".equals(key)) return "中文";
        if ("zh-tw".equals(key) || "zh-hk".equals(key)) return "中文";
        if ("en".equals(key) || "eng".equals(key)) return "英语";
        if ("ja".equals(key) || "jpn".equals(key)) return "日语";
        if ("ko".equals(key) || "kor".equals(key)) return "韩语";
        if ("fr".equals(key) || "fra".equals(key) || "fre".equals(key)) return "法语";
        if ("de".equals(key) || "deu".equals(key) || "ger".equals(key)) return "德语";
        if ("es".equals(key) || "spa".equals(key)) return "西班牙语";
        if ("ru".equals(key) || "rus".equals(key)) return "俄语";
        if ("th".equals(key) || "tha".equals(key)) return "泰语";
        if ("vi".equals(key) || "vie".equals(key)) return "越南语";
        return "";
    }

    private static void addMediaIds(List<String> mediaIds, List<MediaStreamList.VideoEntry> videos) {
        if (videos == null) return;
        for (MediaStreamList.VideoEntry video : videos) {
            if (video != null && video.mediaGuid != null && !video.mediaGuid.isEmpty()
                    && !mediaIds.contains(video.mediaGuid)) {
                mediaIds.add(video.mediaGuid);
            }
        }
    }

    private static void appendVideos(StringBuilder out, List<MediaStreamList.VideoEntry> videos,
                                     String mediaGuid) {
        if (videos == null) return;
        for (MediaStreamList.VideoEntry video : videos) {
            if (video == null || video.isPlaceholder() || !sameMedia(video.mediaGuid, mediaGuid)) continue;
            out.append("\n视频");
            String head = join(" ", video.resolutionType, upper(video.codecName), video.colorRangeType);
            if (!head.isEmpty()) out.append("  ").append(head);
            out.append("\n");
            line(out, "编码器", upper(video.codecName));
            line(out, "配置", MediaStreamList.text(video.profile));
            line(out, "等级", MediaStreamList.text(video.level));
            line(out, "分辨率", video.resolutionLabel());
            line(out, "宽高比", MediaStreamList.text(video.displayAspectRatio));
            line(out, "隔行扫描", video.progressive == 1 ? "否" : "是");
            line(out, "帧率", MediaStreamList.text(video.rFrameRate));
            line(out, "码率", formatBitrate(video.bps));
            line(out, "动态范围", video.colorRangeType);
            line(out, "色彩原色", video.colorPrimaries);
            line(out, "色彩空间", video.colorSpace);
            line(out, "色彩转换", video.colorTransfer);
            line(out, "位深度", video.bitDepth > 0 ? video.bitDepth + " bit" : "");
            line(out, "像素格式", video.pixFmt);
            line(out, "参考帧", video.refs > 0 ? String.valueOf(video.refs) : "");
        }
    }

    private static void appendAudios(StringBuilder out, List<MediaStreamList.AudioEntry> audios,
                                     String mediaGuid, Map<String, String> iso6391,
                                     Map<String, String> iso6392) {
        if (audios == null) return;
        for (MediaStreamList.AudioEntry audio : audios) {
            if (audio == null || !sameMedia(audio.mediaGuid, mediaGuid)) continue;
            String codec = audio.audioType != null && !audio.audioType.isEmpty()
                    ? audio.audioType : upper(audio.codecName);
            out.append("\n音频");
            String head = join(" ", languageName(audio.language, iso6391, iso6392), codec,
                    audio.channelLayout);
            if (!head.isEmpty()) out.append("  ").append(head);
            out.append("\n");
            line(out, "语言", languageName(audio.language, iso6391, iso6392));
            line(out, "编码器", codec);
            line(out, "配置", MediaStreamList.text(audio.profile));
            line(out, "布局", audio.channelLayout);
            line(out, "声道", audio.channels > 0 ? audio.channels + " ch" : "");
            String rate = MediaStreamList.text(audio.sampleRate);
            line(out, "采样率", rate.isEmpty() ? "" : rate + " Hz");
            line(out, "码率", formatBitrate(audio.bps));
            line(out, "默认", audio.isDefault == 1 ? "是" : "否");
        }
    }

    private static void appendSubtitles(StringBuilder out, List<MediaStreamList.SubtitleEntry> subs,
                                        String mediaGuid, Map<String, String> iso6391,
                                        Map<String, String> iso6392) {
        if (subs == null) return;
        for (MediaStreamList.SubtitleEntry sub : subs) {
            if (sub == null || !sameMedia(sub.mediaGuid, mediaGuid)) continue;
            String codec = sub.codecName != null && !sub.codecName.isEmpty()
                    ? sub.codecName : sub.codecType;
            out.append("\n字幕");
            String head = join(" · ", languageName(sub.language, iso6391, iso6392), codec);
            if (!head.isEmpty()) out.append("  ").append(head);
            out.append("\n");
            line(out, "语言", languageName(sub.language, iso6391, iso6392));
            line(out, "编码器", codec);
            line(out, "默认", sub.isDefault == 1 ? "是" : "否");
            line(out, "强制", sub.forced == 1 ? "是" : "否");
            line(out, "外部", sub.isExternal == 1 ? "是" : "否");
        }
    }

    private static boolean sameMedia(String itemMedia, String mediaGuid) {
        if (mediaGuid == null || mediaGuid.isEmpty()) return true;
        return mediaGuid.equals(itemMedia);
    }

    private static void line(StringBuilder out, String label, String value) {
        String shown = value == null || value.trim().isEmpty() ? "--" : value.trim();
        out.append(label).append("  ").append(shown).append("\n");
    }

    private static String videoLine(MediaStreamList.VideoEntry video) {
        if (video == null || video.isPlaceholder()) return "";
        String codec = upper(video.codecName);
        String resolution = video.resolutionType == null ? "" : video.resolutionType;
        return join(" ", resolution, codec, formatBitrate(video.bps),
                video.bitDepth > 0 ? "· " + video.bitDepth + " bit" : "");
    }

    private static String audioLine(MediaStreamList.AudioEntry audio, Map<String, String> iso6391,
                                    Map<String, String> iso6392) {
        if (audio == null) return "";
        String codec = audio.audioType != null && !audio.audioType.isEmpty()
                ? audio.audioType : upper(audio.codecName);
        String rate = MediaStreamList.text(audio.sampleRate);
        return join(" ", languageName(audio.language, iso6391, iso6392), codec,
                audio.channelLayout, rate.isEmpty() ? "" : "· " + rate + " Hz");
    }

    private static String subtitleLine(MediaStreamList.SubtitleEntry sub, Map<String, String> iso6391,
                                       Map<String, String> iso6392) {
        if (sub == null) return "";
        String codec = sub.codecName != null && !sub.codecName.isEmpty()
                ? sub.codecName : sub.codecType;
        return join(" ", languageName(sub.language, iso6391, iso6392), upper(codec));
    }

    private static MediaStreamList.VideoEntry findVideo(List<MediaStreamList.VideoEntry> videos,
                                                        String videoGuid, String mediaGuid) {
        if (videos == null) return null;
        if (videoGuid != null && !videoGuid.isEmpty()) {
            for (MediaStreamList.VideoEntry video : videos) {
                if (video != null && videoGuid.equals(video.guid) && !video.isPlaceholder()) return video;
            }
        }
        if (mediaGuid != null && !mediaGuid.isEmpty()) {
            for (MediaStreamList.VideoEntry video : videos) {
                if (video != null && mediaGuid.equals(video.mediaGuid) && !video.isPlaceholder()) return video;
            }
        }
        return firstRealVideo(videos, null);
    }

    private static MediaStreamList.VideoEntry firstRealVideo(List<MediaStreamList.VideoEntry> videos,
                                                             String mediaGuid) {
        if (videos == null) return null;
        MediaStreamList.VideoEntry fallback = null;
        for (MediaStreamList.VideoEntry video : videos) {
            if (video == null || video.isPlaceholder()) continue;
            if (mediaGuid == null || mediaGuid.isEmpty() || mediaGuid.equals(video.mediaGuid)) return video;
            if (fallback == null) fallback = video;
        }
        return fallback;
    }

    private static MediaStreamList.FileEntry findFile(List<MediaStreamList.FileEntry> files, String guid) {
        if (files == null || guid == null || guid.isEmpty()) return null;
        for (MediaStreamList.FileEntry file : files) {
            if (file != null && guid.equals(file.guid)) return file;
        }
        return null;
    }

    private static MediaStreamList.AudioEntry findAudio(List<MediaStreamList.AudioEntry> audios,
                                                        String mediaGuid, String audioGuid) {
        if (audios == null) return null;
        if (audioGuid != null && !audioGuid.isEmpty()) {
            for (MediaStreamList.AudioEntry audio : audios) {
                if (audio != null && audioGuid.equals(audio.guid)) return audio;
            }
        }
        MediaStreamList.AudioEntry fallback = null;
        for (MediaStreamList.AudioEntry audio : audios) {
            if (audio == null) continue;
            if (mediaGuid != null && !mediaGuid.isEmpty() && !mediaGuid.equals(audio.mediaGuid)) continue;
            if (audio.isDefault == 1) return audio;
            if (fallback == null) fallback = audio;
        }
        return fallback;
    }

    private static MediaStreamList.SubtitleEntry findSubtitle(List<MediaStreamList.SubtitleEntry> subs,
                                                              String mediaGuid, String subtitleGuid) {
        if (subs == null) return null;
        if (subtitleGuid != null && !subtitleGuid.isEmpty()) {
            for (MediaStreamList.SubtitleEntry sub : subs) {
                if (sub != null && subtitleGuid.equals(sub.guid)) return sub;
            }
        }
        MediaStreamList.SubtitleEntry fallback = null;
        for (MediaStreamList.SubtitleEntry sub : subs) {
            if (sub == null) continue;
            if (mediaGuid != null && !mediaGuid.isEmpty() && !mediaGuid.equals(sub.mediaGuid)) continue;
            if (sub.isDefault == 1) return sub;
            if (fallback == null) fallback = sub;
        }
        return fallback;
    }

    static String formatFileLocation(String path, List<AuthDirList.AuthDir> authDirs) {
        if (path == null || path.isEmpty()) return "";
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String[] segments = normalized.split("/");
        if (segments.length < 2) return path;
        java.util.regex.Matcher vol = java.util.regex.Pattern.compile("^vol(\\d+)$").matcher(segments[0]);
        if (!vol.matches()) return path;
        String volumeLabel = "存储空间 " + vol.group(1);
        String uid = segments[1];
        String username = "";
        if (authDirs != null) {
            for (AuthDirList.AuthDir dir : authDirs) {
                if (dir == null || dir.path == null || dir.path.trim().isEmpty()) continue;
                String dirPath = dir.path.trim();
                if (dirPath.startsWith("/")) dirPath = dirPath.substring(1);
                String[] dirSegments = dirPath.split("/");
                if (dirSegments.length >= 2 && dirSegments[0].equals(segments[0])
                        && dirSegments[1].equals(uid) && startsWith(dirSegments, segments)) {
                    if (dir.uname != null && !dir.uname.trim().isEmpty()) {
                        username = dir.uname.trim();
                        break;
                    }
                }
            }
        }
        String userLabel = username.isEmpty() ? "用户 " + uid : username + " 的文件";
        StringBuilder rest = new StringBuilder();
        for (int i = 2; i < segments.length; i++) {
            if (rest.length() > 0) rest.append("/");
            rest.append(segments[i]);
        }
        if (rest.length() == 0) return volumeLabel + "/" + userLabel;
        return volumeLabel + "/" + userLabel + "/" + rest;
    }

    private static boolean startsWith(String[] prefix, String[] full) {
        if (prefix.length > full.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (!prefix[i].equals(full[i])) return false;
        }
        return true;
    }

    static String formatFileSize(long size) {
        if (size <= 0) return "";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        double value = size;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, "%.2f %s", value, units[unit]);
    }

    static String formatBitrate(long bps) {
        if (bps <= 0) return "";
        String[] units = {"bps", "Kbps", "Mbps", "Gbps"};
        int unit = 0;
        double value = bps;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return String.format(Locale.US, "%.2f %s", value, units[unit]);
    }

    static String formatTime(long timestamp) {
        if (timestamp <= 0) return "";
        long millis = timestamp < 10_000_000_000L ? timestamp * 1000L : timestamp;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.US);
    }

    private static String join(String gap, String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(gap);
            out.append(part.trim());
        }
        return out.toString();
    }
}
