package com.fntv.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.StreamResponse;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.gson.Gson;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Response;

/** 云直链管理 — Stream API 请求、质量切换、播放模式管理 */
public class CloudStreamManager {

    public interface Callback {
        String getBaseUrl();
        String getMediaGuid();
        FnApiManager getApiManager();
        Context getContext();
        SharedPreferences getPrefs();
        void onStreamInfoParsed(StreamInfo info);
        void onStreamDataFailed();
        void startPlayback();
        void reloadPlayback();
        void onTrackChanged();
        void probeWithMediaExtractor();
        void onCloudBtnVisibilityChanged(boolean vis);
        void runOnUiThread(Runnable r);
    }

    /** Stream API 解析出的音视频元数据（传给 Activity 更新信息面板） */
    public static class StreamInfo {
        public int bitrate, width, height, bitDepth, duration;
        public long fileSize;
        public String vCodec, vProfile, vPixFmt, vColor, vFps, container;
        public String resolution;
        public boolean vHdr;
        public List<StreamResponse.AudioStreamInfo> audioTracks;
        public List<StreamResponse.SubtitleStreamInfo> subtitleTracks;
    }

    /** 播放配置（getPlaybackConfig 返回） */
    public static class PlaybackConfig {
        public final String url;
        public final boolean hls;
        public final int chunkedModeSize;

        public PlaybackConfig(String url, boolean hls, int chunkedModeSize) {
            this.url = url;
            this.hls = hls;
            this.chunkedModeSize = chunkedModeSize;
        }
    }

    private final Callback cb;
    private final Button btnCloudMode;
    private final SharedPreferences prefs;

    // 云直链状态
    private boolean cloudDirectMode = true;
    private String cloudDirectUrl = "";
    private boolean isStrmFile = false;
    private int qualityIndex = 1;
    private String[] qualityLabels;
    private String[] qualityUrls;
    private int qualityCount = 0;

    // ExoPlayer 引用（用于音轨/字幕切换，由外部注入）
    private SimpleExoPlayer player;

    // Stream API 返回的音轨/字幕信息（供对话框显示标签用）
    private List<StreamResponse.AudioStreamInfo> streamAudioTracks;
    private List<StreamResponse.SubtitleStreamInfo> streamSubtitleTracks;

    // 用户最后一次选择的音轨/字幕标签（供信息面板显示）
    private String lastAudioTrackLabel = "";
    private String lastSubtitleTrackLabel = "";
    private boolean defaultSubtitleApplied;

    private static final String TAG = "Player";

    public CloudStreamManager(Callback cb, Button btnCloudMode, SharedPreferences prefs) {
        this.cb = cb;
        this.btnCloudMode = btnCloudMode;
        this.prefs = prefs;
    }

    // ========== 外部调用 ==========

    /** 从 SharedPreferences 恢复初始状态（onCreate 时调用） */
    public void initFromPrefs() {
        cloudDirectMode = prefs.getBoolean("cloud_direct_mode", true);
        qualityIndex = prefs.getInt("cloud_quality_index", 1);
        updateCloudBtnText();
        btnCloudMode.setOnClickListener(v -> showQualityMenu());
    }

    /** 获取当前播放配置（startPlayback 中调用） */
    public PlaybackConfig getPlaybackConfig(String baseUrl, String mediaGuid) {
        String url;
        // 0 = 不分块，全文件流式加载，适合内网大文件
        int chunkedMode = 0;
        if (!cloudDirectUrl.isEmpty()) {
            url = cloudDirectUrl;
            chunkedMode = isStrmFile ? 0 : 20 * 1024 * 1024;
            Log.d(TAG, "播放模式: 直链 " + url);
        } else if (cloudDirectMode && qualityCount > 0) {
            url = baseUrl + "/v/api/v1/media/range/" + mediaGuid + "?direct_link_quality_index=" + qualityIndex;
            Log.d(TAG, "播放模式: 直链 (NAS代理, index=" + qualityIndex + ") " + url);
        } else {
            url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
            Log.d(TAG, "播放模式: 代理 " + url);
        }
        boolean hls = url.contains(".m3u8");
        if (!hls && !cloudDirectUrl.isEmpty()) {
            hls = isStrmFile;
            Log.d(TAG, "直链模式: isStrm=" + isStrmFile + " useHls=" + hls);
        }
        return new PlaybackConfig(url, hls, chunkedMode);
    }

    /** 获取当前直链 URL（用于 switchMediaSource 等） */
    public String getCloudDirectUrl() { return cloudDirectUrl; }

    /** 是否处于直链播放模式 */
    public boolean hasDirectUrl() { return !cloudDirectUrl.isEmpty(); }

    /** 注入 ExoPlayer 引用（用于音轨/字幕切换） */
    public void setPlayer(SimpleExoPlayer player) { this.player = player; }

    /** 获取用户最后选择的音轨标签（供信息面板展示） */
    public String getLastAudioTrackLabel() { return lastAudioTrackLabel; }
    /** 获取用户最后选择的字幕标签（供信息面板展示） */
    public String getLastSubtitleTrackLabel() { return lastSubtitleTrackLabel; }

    /** 换片时清掉上一集的字幕选择，重新按内嵌/中文默认。 */
    public void resetSubtitleChoice() {
        defaultSubtitleApplied = false;
        lastSubtitleTrackLabel = "";
    }

    /**
     * 有内嵌字幕时只选内嵌（简体优先，其次中文、繁体、中英，再没有就用默认或第一条内嵌）。
     * 没有内嵌时，只选已挂上的中文外部字幕。
     */
    public void applyDefaultChineseSubtitle() {
        if (defaultSubtitleApplied || player == null) return;
        if (lastSubtitleTrackLabel != null && !lastSubtitleTrackLabel.isEmpty()) {
            defaultSubtitleApplied = true;
            return;
        }
        DefaultTrackSelector selector = (DefaultTrackSelector) player.getTrackSelector();
        if (selector == null) return;
        MappingTrackSelector.MappedTrackInfo trackInfo = selector.getCurrentMappedTrackInfo();
        if (trackInfo == null) return;
        TrackGroupArray groups = trackInfo.getTrackGroups(C.TRACK_TYPE_TEXT);
        if (groups == null || groups.length == 0) return;

        boolean embeddedOnly = hasEmbeddedSubtitle(streamSubtitleTracks);
        int trackCount = 0;
        for (int g = 0; g < groups.length; g++) trackCount += groups.get(g).length;
        boolean aligned = streamSubtitleTracks != null && streamSubtitleTracks.size() == trackCount;
        int bestGroup = -1;
        int bestTrack = -1;
        int bestRank = 0;
        String bestLabel = null;
        int seen = 0;
        for (int g = 0; g < groups.length; g++) {
            TrackGroup group = groups.get(g);
            for (int t = 0; t < group.length; t++) {
                Format fmt = group.getFormat(t);
                StreamResponse.SubtitleStreamInfo ssi = null;
                if (aligned && seen < streamSubtitleTracks.size()) ssi = streamSubtitleTracks.get(seen);
                else ssi = matchSubtitleInfo(fmt.label, fmt.language);
                if (embeddedOnly && aligned && ssi != null && ssi.isExternal != 0) {
                    seen++;
                    continue;
                }
                String title = ssi != null && ssi.title != null && !ssi.title.isEmpty() ? ssi.title : fmt.label;
                String language = fmt.language;
                if ((language == null || language.isEmpty()) && ssi != null) language = ssi.language;
                int score = chineseSubtitleScore(title, language);
                int rank;
                if (embeddedOnly) {
                    rank = score * 10 + 1;
                    if (ssi != null && ssi.isDefault != 0) rank += 2;
                } else if (score > 0) {
                    rank = score;
                } else {
                    seen++;
                    continue;
                }
                if (rank > bestRank) {
                    bestRank = rank;
                    bestGroup = g;
                    bestTrack = t;
                    String codecStr = fmt.codecs != null ? fmt.codecs
                            : (fmt.sampleMimeType != null ? fmt.sampleMimeType.replace("text/", "") : "");
                    if ((codecStr == null || codecStr.isEmpty()) && ssi != null && ssi.codecName != null) {
                        codecStr = ssi.codecName;
                    }
                    bestLabel = subtitleChoiceText(title, language, codecStr, seen + 1);
                }
                seen++;
            }
        }
        defaultSubtitleApplied = true;
        if (bestGroup < 0) return;
        selector.setParameters(selector.buildUponParameters()
                .clearSelectionOverrides(C.TRACK_TYPE_TEXT)
                .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
                .setSelectionOverride(C.TRACK_TYPE_TEXT, groups,
                        new DefaultTrackSelector.SelectionOverride(bestGroup, bestTrack))
                .build());
        lastSubtitleTrackLabel = bestLabel != null ? bestLabel : "";
    }

    /** 没有内嵌字幕时，返回应挂到播放器上的中文外部字幕。有内嵌则返回 null。 */
    public ExternalSubtitle defaultExternalSubtitle(String baseUrl) {
        if (hasEmbeddedSubtitle(streamSubtitleTracks) || baseUrl == null) return null;
        StreamResponse.SubtitleStreamInfo chosen = chooseChineseExternal(streamSubtitleTracks);
        if (chosen == null || chosen.guid == null || chosen.guid.isEmpty()) return null;
        String mime = textSubtitleMime(chosen.codecName);
        if (mime == null) return null;
        String language = chosen.language != null && !chosen.language.isEmpty() ? chosen.language : "zh";
        String label = subtitleChoiceText(chosen.title, chosen.language,
                chosen.codecName != null ? chosen.codecName : "", 1);
        return new ExternalSubtitle(baseUrl + "/v/api/v1/subtitle/dl/" + chosen.guid, mime, language, label);
    }

    /** 转码时传给服务端的字幕：有内嵌用内嵌，否则用中文外部。 */
    public static String chooseSubtitleGuid(List<StreamResponse.SubtitleStreamInfo> tracks) {
        if (hasEmbeddedSubtitle(tracks)) {
            StreamResponse.SubtitleStreamInfo embedded = chooseEmbedded(tracks);
            if (embedded != null && embedded.guid != null) return embedded.guid;
        }
        StreamResponse.SubtitleStreamInfo external = chooseChineseExternal(tracks);
        if (external != null && external.guid != null) return external.guid;
        return "";
    }

    public static class ExternalSubtitle {
        public final String url;
        public final String mimeType;
        public final String language;
        public final String label;

        public ExternalSubtitle(String url, String mimeType, String language, String label) {
            this.url = url;
            this.mimeType = mimeType;
            this.language = language;
            this.label = label;
        }
    }

    private static boolean hasEmbeddedSubtitle(List<StreamResponse.SubtitleStreamInfo> tracks) {
        if (tracks == null) return false;
        for (StreamResponse.SubtitleStreamInfo s : tracks) {
            if (s != null && s.isExternal == 0) return true;
        }
        return false;
    }

    private static StreamResponse.SubtitleStreamInfo chooseEmbedded(List<StreamResponse.SubtitleStreamInfo> tracks) {
        StreamResponse.SubtitleStreamInfo best = null;
        int bestRank = 0;
        if (tracks == null) return null;
        for (StreamResponse.SubtitleStreamInfo s : tracks) {
            if (s == null || s.isExternal != 0) continue;
            int rank = chineseSubtitleScore(s.title, s.language) * 10 + 1;
            if (s.isDefault != 0) rank += 2;
            if (rank > bestRank) {
                bestRank = rank;
                best = s;
            }
        }
        return best;
    }

    private static StreamResponse.SubtitleStreamInfo chooseChineseExternal(List<StreamResponse.SubtitleStreamInfo> tracks) {
        StreamResponse.SubtitleStreamInfo best = null;
        int bestScore = 0;
        if (tracks == null) return null;
        for (StreamResponse.SubtitleStreamInfo s : tracks) {
            if (s == null || s.isExternal == 0) continue;
            if (textSubtitleMime(s.codecName) == null) continue;
            int score = chineseSubtitleScore(s.title, s.language);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /** 文字外挂字幕的 MIME。图形字幕无法外挂，返回 null。 */
    private static String textSubtitleMime(String codec) {
        if (codec == null) return com.google.android.exoplayer2.util.MimeTypes.APPLICATION_SUBRIP;
        String n = codec.toLowerCase(java.util.Locale.US);
        if (n.contains("pgs") || n.contains("hdmv") || n.contains("dvd") || n.contains("vobsub")
                || n.contains("bitmap") || n.equals("sub")) return null;
        if (n.contains("ass") || n.contains("ssa")) return com.google.android.exoplayer2.util.MimeTypes.TEXT_SSA;
        if (n.contains("vtt")) return com.google.android.exoplayer2.util.MimeTypes.TEXT_VTT;
        if (n.contains("ttml")) return com.google.android.exoplayer2.util.MimeTypes.APPLICATION_TTML;
        return com.google.android.exoplayer2.util.MimeTypes.APPLICATION_SUBRIP;
    }

    /** 简体 4，中文 3，繁体 2，中英 1，其余 0。 */
    private static int chineseSubtitleScore(String title, String language) {
        int score = rankChineseLabel(languageToChinese(language));
        score = Math.max(score, rankChineseLabel(languageToChinese(cleanSubtitleTitle(title))));
        String blob = ((title == null ? "" : title) + " " + (language == null ? "" : language))
                .toLowerCase(java.util.Locale.US);
        int hint = 0;
        if (blob.contains("简体") || blob.contains("简中") || blob.contains("chs")
                || blob.contains("zh-cn") || blob.contains("zh-hans") || blob.contains("simplified")) hint = 4;
        else if (blob.contains("繁体") || blob.contains("繁中") || blob.contains("cht")
                || blob.contains("zh-tw") || blob.contains("zh-hk") || blob.contains("zh-hant")
                || blob.contains("traditional")) hint = 2;
        else if (blob.contains("中英") || blob.contains("双语")) hint = 1;
        else if (blob.contains("中文") || blob.contains("chinese") || blob.contains("国语")
                || blob.contains("chi") || blob.contains("zho") || blob.contains("cmn")) hint = 3;
        return Math.max(score, hint);
    }

    private static int rankChineseLabel(String name) {
        if ("简体中文".equals(name)) return 4;
        if ("中文".equals(name)) return 3;
        if ("繁体中文".equals(name)) return 2;
        if ("中英".equals(name)) return 1;
        return 0;
    }

    // ========== Stream API ==========

    /** 调用 stream API 获取直链并解析（从 loadPlayInfo 回调中调用） */
    public void fetchDirectLink(final String itemGuid, final String mediaGuid) {
        if (mediaGuid == null) {
            cb.onStreamDataFailed();
            return;
        }
        Map<String, Object> streamReq = new HashMap<>();
        Map<String, Object> header = new HashMap<>();
        header.put("User-Agent", new String[]{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"});
        streamReq.put("header", header);
        int qualityLevel = prefs.getInt("stream_quality_level", 1);
        streamReq.put("level", qualityLevel);
        streamReq.put("media_guid", mediaGuid);
        // ip = 账号的 MD5 哈希
        String account = prefs.getString("user", "video");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(account.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            streamReq.put("ip", sb.toString());
        } catch (Exception e) {
            streamReq.put("ip", "");
        }
        streamReq.put("nonce", String.valueOf(100000 + (int) (Math.random() * 900000)));
        String reqJson = new Gson().toJson(streamReq);
        Log.d(TAG, "getStream 请求体: " + reqJson);
        Log.d(TAG, "getStream 请求URL: " + cb.getBaseUrl() + "/v/api/v1/stream");

        cb.getApiManager().getApi().getStream(streamReq)
                .enqueue(new retrofit2.Callback<ApiResponse<StreamResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<StreamResponse>> call,
                                           Response<ApiResponse<StreamResponse>> r) {
                        try {
                            Log.d(TAG, "getStream resp code=" + r.code());
                            if (r.isSuccessful() && r.body() != null && r.body().code == 0
                                    && r.body().data != null) {
                                StreamResponse sd = r.body().data;
                                StreamInfo info = new StreamInfo();

                                // 视频流
                                if (sd.videoStream != null) {
                                    info.bitrate = sd.videoStream.bps;
                                    info.vCodec = sd.videoStream.codecName != null ? sd.videoStream.codecName : "";
                                    info.vProfile = sd.videoStream.profile != null ? sd.videoStream.profile : "";
                                    info.width = sd.videoStream.width;
                                    info.height = sd.videoStream.height;
                                    info.bitDepth = sd.videoStream.bitDepth;
                                    info.vHdr = sd.videoStream.dvProfile > 0;
                                    info.vPixFmt = sd.videoStream.pixFmt != null ? sd.videoStream.pixFmt : "";
                                    info.vColor = sd.videoStream.colorPrimaries != null ? sd.videoStream.colorPrimaries : "";
                                    String cs = sd.videoStream.colorSpace != null ? sd.videoStream.colorSpace : "";
                                    if (!info.vColor.isEmpty() && !cs.isEmpty()) info.vColor += " " + cs;
                                    info.vFps = sd.videoStream.rFrameRate != null ? sd.videoStream.rFrameRate : "";
                                    info.duration = sd.videoStream.duration;
                                }

                                // 文件信息
                                boolean isStrm = false;
                                if (sd.fileStream != null) {
                                    info.fileSize = sd.fileStream.size;
                                    String fn = sd.fileStream.fileName != null ? sd.fileStream.fileName : "";
                                    String fp = sd.fileStream.path != null ? sd.fileStream.path : "";
                                    if (fn.contains("."))
                                        info.container = fn.substring(fn.lastIndexOf('.') + 1).toLowerCase();
                                    if (info.duration <= 0) info.duration = sd.fileStream.duration;
                                    isStrm = fp.toLowerCase().endsWith(".strm") || fn.toLowerCase().endsWith(".strm");
                                }

                                // STRM 处理
                                if (isStrm && sd.directLinkQualities != null && !sd.directLinkQualities.isEmpty()) {
                                    isStrmFile = true;
                                    String directUrl = sd.directLinkQualities.get(0).url;
                                    directUrl = directUrl.replace("\\u0026", "&");
                                    cloudDirectUrl = directUrl;
                                    cloudDirectMode = true;
                                    qualityCount = sd.directLinkQualities.size();
                                    qualityLabels = new String[qualityCount];
                                    qualityUrls = new String[qualityCount];
                                    for (int qi = 0; qi < qualityCount; qi++) {
                                        StreamResponse.DirectLinkQuality dlq = sd.directLinkQualities.get(qi);
                                        qualityLabels[qi] = dlq.resolution != null && !dlq.resolution.isEmpty()
                                                ? dlq.resolution : ("画质" + qi);
                                        String u = dlq.url != null ? dlq.url.replace("\\u0026", "&") : "";
                                        qualityUrls[qi] = u;
                                    }
                                    if (qualityIndex >= qualityCount) qualityIndex = 0;
                                    cloudDirectUrl = qualityUrls[qualityIndex];
                                    Log.d(TAG, "STRM 文件，使用直链: " + cloudDirectUrl);
                                    cb.runOnUiThread(() -> {
                                        setCloudBtnVisible(true);
                                        updateCloudBtnText();
                                    });
                                }

                                // 音频/字幕流（保存本地副本供音轨切换用）
                                streamAudioTracks = sd.audioStreams;
                                streamSubtitleTracks = sd.subtitleStreams;
                                info.audioTracks = sd.audioStreams;
                                info.subtitleTracks = sd.subtitleStreams;

                                // 非 STRM 的画质信息
                                qualityCount = sd.directLinkQualities != null ? sd.directLinkQualities.size() : 0;
                                if (qualityCount > 0) {
                                    qualityLabels = new String[qualityCount];
                                    for (int qi = 0; qi < qualityCount; qi++) {
                                        StreamResponse.DirectLinkQuality q = sd.directLinkQualities.get(qi);
                                        qualityLabels[qi] = q.resolution != null && !q.resolution.isEmpty()
                                                ? q.resolution : ("画质" + qi);
                                    }
                                    if (qualityIndex >= qualityCount) qualityIndex = 0;
                                    cb.runOnUiThread(() -> {
                                        setCloudBtnVisible(true);
                                        updateCloudBtnText();
                                    });
                                }

                                // 取 resolution：直链优先，代理取 qualities[0]
                                if (sd.directLinkQualities != null && qualityIndex < sd.directLinkQualities.size()) {
                                    info.resolution = sd.directLinkQualities.get(qualityIndex).resolution;
                                    if (sd.directLinkQualities.get(qualityIndex).bitrate > 0)
                                        info.bitrate = sd.directLinkQualities.get(qualityIndex).bitrate;
                                } else if (sd.qualities != null && !sd.qualities.isEmpty()) {
                                    info.resolution = sd.qualities.get(0).resolution;
                                    info.bitrate = sd.qualities.get(0).bitrate;
                                }
                                // 回调 Activity 更新显示信息
                                cb.onStreamInfoParsed(info);
                            } else {
                                cb.onStreamDataFailed();
                                return;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "getStream parse error", e);
                            cb.onStreamDataFailed();
                            return;
                        }
                        cb.startPlayback(); // 无论成功/失败都尝试播放
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<StreamResponse>> call, Throwable t) {
                        Log.e(TAG, "getStream onFailure: " + t.getMessage());
                        cb.onStreamDataFailed();
                    }
                });
    }

    /** 重新加载（切换清晰度时调用，重置云直链状态） */
    public void resetForQualitySwitch() {
        cloudDirectUrl = "";
        isStrmFile = false;
        qualityCount = 0;
        qualityLabels = null;
        qualityUrls = null;
    }

    // ========== 画质菜单 ==========

    /** 显示画质/模式切换菜单 */
    public void showQualityMenu() {
        final SharedPreferences sp = cb.getPrefs();
        if (isStrmFile && qualityCount > 0 && qualityLabels != null) {
            // STRM 文件：只显示画质
            final String[] items = new String[qualityCount];
            for (int i = 0; i < qualityCount; i++) {
                items[i] = (i == qualityIndex ? "✓ " : "  ") + qualityLabels[i];
            }
            SideSheet.showGrid(cb.getContext(), "视频质量", qualityChoices(items), checkedIndex(items), (dialog, which) -> {
                        if (which < qualityCount) {
                            qualityIndex = which;
                            sp.edit().putInt("cloud_quality_index", qualityIndex).apply();
                            updateCloudBtnText();
                            dialog.dismiss();
                            AppToast.show(cb.getContext(), "切换画质：" + qualityLabels[qualityIndex]);
                            cb.runOnUiThread(() -> reloadPlayback());
                        }
                    });
        } else if (cloudDirectMode && qualityCount > 0 && qualityLabels != null) {
            // 直链模式：画质列表 + 切换代理
            final String[] items = new String[qualityCount + 1];
            for (int i = 0; i < qualityCount; i++) {
                items[i] = (i == qualityIndex ? "✓ " : "  ") + qualityLabels[i];
            }
            items[qualityCount] = "切换到代理模式";
            SideSheet.showList(cb.getContext(), "播放设置", items, (dialog, which) -> {
                        if (which < qualityCount) {
                            qualityIndex = which;
                            sp.edit().putInt("cloud_quality_index", qualityIndex)
                                    .putBoolean("cloud_direct_mode", true).apply();
                            updateCloudBtnText();
                            dialog.dismiss();
                            AppToast.show(cb.getContext(), "切换画质：" + qualityLabels[qualityIndex]);
                            cb.runOnUiThread(() -> reloadPlayback());
                        } else {
                            cloudDirectMode = false;
                            sp.edit().putBoolean("cloud_direct_mode", false).apply();
                            updateCloudBtnText();
                            dialog.dismiss();
                            AppToast.show(cb.getContext(), "已切换为代理模式");
                            cb.runOnUiThread(() -> reloadPlayback());
                        }
                    });
        } else {
            // 代理模式：切换到直链
            SideSheet.showList(cb.getContext(), "播放设置", new String[]{"切换到直链模式"}, (dialog, which) -> {
                        cloudDirectMode = true;
                        sp.edit().putBoolean("cloud_direct_mode", true).apply();
                        updateCloudBtnText();
                        dialog.dismiss();
                        AppToast.show(cb.getContext(), "已切换为直链模式");
                        cb.runOnUiThread(() -> reloadPlayback());
                    });
        }
    }

    // ========== UI 按钮 ==========

    public void setCloudBtnVisible(boolean vis) {
        btnCloudMode.setVisibility(vis ? View.VISIBLE : View.GONE);
        cb.onCloudBtnVisibilityChanged(vis);
    }

    public void updateCloudBtnText() {
        String mode = isStrmFile ? "STRM" : (cloudDirectMode ? "直链" : "代理");
        String ql = qualityCount > 0 && qualityIndex < qualityCount && qualityLabels != null
                ? qualityLabels[qualityIndex] : "";
        btnCloudMode.setText(ql.isEmpty() ? mode : mode + "/" + ql);
        btnCloudMode.setTextColor(isStrmFile || cloudDirectMode ? 0xFF81C784 : 0xFFFFB74D);
    }

    // ========== 音轨/字幕选择 ==========

    /** 显示音轨选择弹窗（匹配弹幕设置的美化风格） */
    public void showAudioTrackDialog(Activity activity) {
        if (player == null) return;
        DefaultTrackSelector selector = (DefaultTrackSelector) player.getTrackSelector();
        MappingTrackSelector.MappedTrackInfo trackInfo = selector.getCurrentMappedTrackInfo();
        TrackGroupArray groups = (trackInfo != null) ? trackInfo.getTrackGroups(C.TRACK_TYPE_AUDIO) : null;

        // 优先用 ExoPlayer 的 TrackGroup（支持切换），FFmpeg 软解时 groups 为空，降级到 stream API
        if (groups != null && groups.length > 0) {
            showAudioTracksFromPlayer(activity, selector, groups);
        } else if (streamAudioTracks != null && !streamAudioTracks.isEmpty()) {
            showAudioTracksFromStreamApi(activity);
        } else {
            AppToast.show(activity, trackInfo == null ? "音轨信息尚未就绪" : "无可用音轨");
        }
    }

    /** ExoPlayer 有 TrackGroup → 可切换音轨 */
    private void showAudioTracksFromPlayer(Activity activity, DefaultTrackSelector selector, TrackGroupArray groups) {
        final java.util.ArrayList<String> itemLabels = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> itemGroupIdx = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> itemTrackIdx = new java.util.ArrayList<>();

        itemLabels.add("默认");
        itemGroupIdx.add(-1);
        itemTrackIdx.add(-1);

        for (int g = 0; g < groups.length; g++) {
            TrackGroup group = groups.get(g);
            for (int t = 0; t < group.length; t++) {
                Format fmt = group.getFormat(t);
                String label = "音轨" + (itemLabels.size());
                if (fmt.language != null && !fmt.language.isEmpty()) {
                    label += "  " + fmt.language;
                } else if (streamAudioTracks != null && t < streamAudioTracks.size()) {
                    StreamResponse.AudioStreamInfo asi = streamAudioTracks.get(t);
                    if (asi.language != null && !asi.language.isEmpty()) label += "  " + asi.language;
                }
                String codecStr = fmt.codecs != null ? fmt.codecs
                        : (fmt.sampleMimeType != null ? fmt.sampleMimeType.replace("audio/", "") : "");
                if (!codecStr.isEmpty()) label += "  " + codecStr;
                if (fmt.channelCount > 0) label += "  " + fmt.channelCount + "ch";
                if (fmt.sampleRate > 0) label += "  " + (fmt.sampleRate / 1000) + "kHz";

                itemLabels.add(label);
                itemGroupIdx.add(g);
                itemTrackIdx.add(t);
            }
        }

        final String[] items = itemLabels.toArray(new String[0]);
        SideSheet.showCards(activity, "音频", null, trackChoices(items), trackSelected(items, lastAudioTrackLabel), "", (dialog, which) -> {
            if (which < 0 || which >= itemLabels.size()) return;
                    int selGroup = itemGroupIdx.get(which);
                    int selTrack = itemTrackIdx.get(which);
                    if (selGroup >= 0 && selTrack >= 0) {
                        selector.setParameters(
                            selector.buildUponParameters()
                                .clearSelectionOverrides(C.TRACK_TYPE_AUDIO)
                                .setRendererDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setSelectionOverride(C.TRACK_TYPE_AUDIO, groups,
                                    new DefaultTrackSelector.SelectionOverride(selGroup, selTrack))
                                .build()
                        );
                    } else {
                        selector.setParameters(
                            selector.buildUponParameters()
                                .clearSelectionOverrides(C.TRACK_TYPE_AUDIO)
                                .setRendererDisabled(C.TRACK_TYPE_AUDIO, false)
                                .build()
                        );
                    }
                    lastAudioTrackLabel = items[which];
                    lastSubtitleTrackLabel = items[which];
                    cb.onTrackChanged();
                    AppToast.show(activity, "已切换: " + items[which]);
                    dialog.dismiss();
                });
    }

    /** ExoPlayer 无 TrackGroup（FFmpeg 软解）→ 用 stream API 试切换（语言匹配 + 重载） */
    private void showAudioTracksFromStreamApi(Activity activity) {
        final String[] items = new String[streamAudioTracks.size()];
        for (int i = 0; i < streamAudioTracks.size(); i++) {
            StreamResponse.AudioStreamInfo asi = streamAudioTracks.get(i);
            String lang = asi.language != null && !asi.language.isEmpty() ? asi.language : "未知";
            String codec = FormatUtils.fmtAudioCodec(asi.codecName);
            String ch = asi.channels > 0 ? (asi.channels == 8 ? "7.1" : asi.channels == 6 ? "5.1" : asi.channels + "ch") : "";
            String br = asi.bps > 0 ? " " + FormatUtils.formatBitrate(asi.bps) : "";
            items[i] = "音轨" + (i + 1) + "  " + lang + "  " + codec + (ch.isEmpty() ? "" : "  " + ch) + br;
        }
        SideSheet.showCards(activity, "音频", null, trackChoices(items), trackSelected(items, lastAudioTrackLabel), "", (dialog, which) -> {
            if (which < 0 || which >= streamAudioTracks.size()) return;
                    StreamResponse.AudioStreamInfo sel = streamAudioTracks.get(which);
                    String lang = sel.language;
                    if (lang != null && !lang.isEmpty()) {
                        DefaultTrackSelector selector = (DefaultTrackSelector) player.getTrackSelector();
                        selector.setParameters(
                            selector.buildUponParameters()
                                .clearSelectionOverrides(C.TRACK_TYPE_AUDIO)
                                .setRendererDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setPreferredAudioLanguage(lang)
                                .build()
                        );
                        lastAudioTrackLabel = items[which];
                    cb.onTrackChanged();
                    AppToast.show(activity, "已切换: " + items[which]);
                    } else {
                        AppToast.show(activity, "该音轨无语言标记，无法自动切换");
                    }
                    dialog.dismiss();
                });
    }

    /** 显示字幕选择弹窗（匹配弹幕设置的美化风格） */
    public void showSubtitleTrackDialog(Activity activity) {
        if (player == null) return;
        DefaultTrackSelector selector = (DefaultTrackSelector) player.getTrackSelector();
        MappingTrackSelector.MappedTrackInfo trackInfo = selector.getCurrentMappedTrackInfo();
        TrackGroupArray groups = (trackInfo != null) ? trackInfo.getTrackGroups(C.TRACK_TYPE_TEXT) : null;

        if (groups != null && groups.length > 0) {
            showSubtitleTracksFromPlayer(activity, selector, groups);
        } else if (streamSubtitleTracks != null && !streamSubtitleTracks.isEmpty()) {
            showSubtitleTracksFromStreamApi(activity);
        } else if (trackInfo == null) {
            AppToast.show(activity, "字幕信息尚未就绪");
        } else {
            SideSheet.showCards(activity, "字幕", "字幕列表",
                    java.util.Collections.<SideSheet.Choice>emptyList(), -1, "当前没有字幕可选择", null);
        }
    }

    /** ExoPlayer 有 TrackGroup → 可切换字幕 */
    private void showSubtitleTracksFromPlayer(Activity activity, DefaultTrackSelector selector, TrackGroupArray groups) {
        final java.util.ArrayList<String> itemLabels = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> itemGroupIdx = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> itemTrackIdx = new java.util.ArrayList<>();

        itemLabels.add("关闭字幕");
        itemGroupIdx.add(-2);
        itemTrackIdx.add(-2);
        itemLabels.add("默认");
        itemGroupIdx.add(-1);
        itemTrackIdx.add(-1);

        int trackCount = 0;
        for (int g = 0; g < groups.length; g++) trackCount += groups.get(g).length;
        boolean aligned = streamSubtitleTracks != null && streamSubtitleTracks.size() == trackCount;
        int seen = 0;
        for (int g = 0; g < groups.length; g++) {
            TrackGroup group = groups.get(g);
            for (int t = 0; t < group.length; t++) {
                Format fmt = group.getFormat(t);
                StreamResponse.SubtitleStreamInfo ssi = null;
                if (aligned) ssi = streamSubtitleTracks.get(seen);
                else ssi = matchSubtitleInfo(fmt.label, fmt.language);
                String title = ssi != null && ssi.title != null && !ssi.title.isEmpty()
                        ? ssi.title : fmt.label;
                String language = fmt.language;
                if ((language == null || language.isEmpty()) && ssi != null) language = ssi.language;
                String codecStr = fmt.codecs != null ? fmt.codecs
                        : (fmt.sampleMimeType != null ? fmt.sampleMimeType.replace("text/", "") : "");
                if (codecStr.isEmpty() && ssi != null && ssi.codecName != null) codecStr = ssi.codecName;
                itemLabels.add(subtitleChoiceText(title, language, codecStr, seen + 1));
                itemGroupIdx.add(g);
                itemTrackIdx.add(t);
                seen++;
            }
        }

        final String[] items = itemLabels.toArray(new String[0]);
        SideSheet.showCards(activity, "字幕", "字幕列表", trackChoices(items), trackSelected(items, lastSubtitleTrackLabel), "当前没有字幕可选择", (dialog, which) -> {
                    if (which < 0 || which >= itemLabels.size()) return;
                    int selGroup = itemGroupIdx.get(which);
                    int selTrack = itemTrackIdx.get(which);
                    if (selGroup == -2) {
                        selector.setParameters(
                            selector.buildUponParameters()
                                .clearSelectionOverrides(C.TRACK_TYPE_TEXT)
                                .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        );
                    } else if (selGroup >= 0 && selTrack >= 0) {
                        selector.setParameters(
                            selector.buildUponParameters()
                                .clearSelectionOverrides(C.TRACK_TYPE_TEXT)
                                .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
                                .setSelectionOverride(C.TRACK_TYPE_TEXT, groups,
                                    new DefaultTrackSelector.SelectionOverride(selGroup, selTrack))
                                .build()
                        );
                    } else {
                        selector.setParameters(
                            selector.buildUponParameters()
                                .clearSelectionOverrides(C.TRACK_TYPE_TEXT)
                                .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
                                .build()
                        );
                    }
                    lastAudioTrackLabel = items[which];
                    lastSubtitleTrackLabel = items[which];
                    cb.onTrackChanged();
                    AppToast.show(activity, "已切换: " + items[which]);
                    dialog.dismiss();
                });
    }

    /** ExoPlayer 无 TrackGroup（FFmpeg 软解）→ 用 stream API 试切换（语言匹配） */
    private void showSubtitleTracksFromStreamApi(Activity activity) {
        final String[] items = new String[streamSubtitleTracks.size() + 1];
        items[0] = "关闭字幕";
        for (int i = 0; i < streamSubtitleTracks.size(); i++) {
            StreamResponse.SubtitleStreamInfo ssi = streamSubtitleTracks.get(i);
            String codec = ssi.codecName != null ? ssi.codecName : "";
            String text = subtitleChoiceText(ssi.title, ssi.language, codec, i + 1);
            if (ssi.isDefault != 0) text = text + "  默认";
            items[i + 1] = text;
        }
        SideSheet.showCards(activity, "字幕", "字幕列表", trackChoices(items), trackSelected(items, lastSubtitleTrackLabel), "当前没有字幕可选择", (dialog, which) -> {
                    if (which < 0) return;
                    DefaultTrackSelector selector = (DefaultTrackSelector) player.getTrackSelector();
                    if (which == 0) {
                        // 关闭字幕
                        selector.setParameters(
                            selector.buildUponParameters()
                                .clearSelectionOverrides(C.TRACK_TYPE_TEXT)
                                .setRendererDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        );
                    } else {
                        int idx = which - 1;
                        if (idx < streamSubtitleTracks.size()) {
                            StreamResponse.SubtitleStreamInfo sel = streamSubtitleTracks.get(idx);
                            String lang = sel.language;
                            if (lang != null && !lang.isEmpty()) {
                                selector.setParameters(
                                    selector.buildUponParameters()
                                        .clearSelectionOverrides(C.TRACK_TYPE_TEXT)
                                        .setRendererDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setPreferredTextLanguage(lang)
                                        .build()
                                );
                            }
                        }
                    }
                    lastAudioTrackLabel = items[which];
                    lastSubtitleTrackLabel = items[which];
                    cb.onTrackChanged();
                    AppToast.show(activity, "已切换: " + items[which]);
                    dialog.dismiss();
                });
    }

    /** 卡片标题用中文名（有标题或能识别的语言才用），编码放在副标题。 */
    private String subtitleChoiceText(String title, String language, String codec, int index) {
        String name = subtitleChineseName(title, language);
        if (name == null || name.isEmpty()) name = "字幕" + index;
        String extra = codec == null ? "" : codec.trim();
        if (!extra.isEmpty()) extra = extra.toUpperCase();
        return extra.isEmpty() ? name : name + "  " + extra;
    }

    private StreamResponse.SubtitleStreamInfo matchSubtitleInfo(String label, String language) {
        if (streamSubtitleTracks == null) return null;
        if (label != null && !label.isEmpty()) {
            for (StreamResponse.SubtitleStreamInfo s : streamSubtitleTracks) {
                if (label.equals(s.title)) return s;
            }
        }
        if (language != null && !language.isEmpty()) {
            for (StreamResponse.SubtitleStreamInfo s : streamSubtitleTracks) {
                if (language.equalsIgnoreCase(s.language)) return s;
            }
        }
        return null;
    }

    private static String subtitleChineseName(String title, String language) {
        String titled = cleanSubtitleTitle(title);
        if (titled != null && containsCjk(titled)) return titled;
        String fromLang = languageToChinese(language);
        if (fromLang != null) return fromLang;
        String fromTitle = languageToChinese(titled);
        if (fromTitle != null) return fromTitle;
        if (titled != null && !looksLikeFileName(titled)) return titled;
        return null;
    }

    private static String cleanSubtitleTitle(String title) {
        if (title == null) return null;
        String s = title.trim().replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        String lower = s.toLowerCase(java.util.Locale.US);
        for (String ext : new String[]{".utf-8.srt", ".utf8.srt", ".ass", ".ssa", ".srt", ".vtt", ".sub", ".sup", ".idx"}) {
            if (lower.endsWith(ext)) {
                s = s.substring(0, s.length() - ext.length()).trim();
                break;
            }
        }
        return s.isEmpty() ? null : s;
    }

    private static boolean looksLikeFileName(String s) {
        return s.indexOf('.') >= 0 || s.length() > 32;
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static String languageToChinese(String raw) {
        if (raw == null) return null;
        String n = raw.trim().toLowerCase(java.util.Locale.US).replace('_', '-');
        if (n.isEmpty() || "und".equals(n) || "unk".equals(n) || "unknown".equals(n)) return null;
        if (n.contains(",") || n.contains("+") || n.contains("&") || n.contains("/")) {
            boolean zh = false;
            boolean en = false;
            for (String part : n.split("[,+&/]")) {
                String one = languageToChinese(part.trim());
                if ("中文".equals(one) || "简体中文".equals(one) || "繁体中文".equals(one)) zh = true;
                if ("英语".equals(one)) en = true;
            }
            if (zh && en) return "中英";
            if (zh) return "中文";
            if (en) return "英语";
            return null;
        }
        if (n.equals("zh-hans") || n.equals("zh-cn") || n.equals("chs") || n.equals("sc")
                || n.equals("gb") || n.equals("simplified") || n.contains("simplified")) return "简体中文";
        if (n.equals("zh-hant") || n.equals("zh-tw") || n.equals("zh-hk") || n.equals("cht")
                || n.equals("tc") || n.equals("big5") || n.equals("traditional")
                || n.contains("traditional")) return "繁体中文";
        if (n.equals("zh") || n.equals("chi") || n.equals("zho") || n.equals("cn")
                || n.equals("chinese") || n.equals("cmn") || n.equals("mandarin")) return "中文";
        if (n.equals("en") || n.equals("eng") || n.equals("english")) return "英语";
        if (n.equals("ja") || n.equals("jpn") || n.equals("jp") || n.equals("japanese")) return "日语";
        if (n.equals("ko") || n.equals("kor") || n.equals("korean")) return "韩语";
        if (n.equals("fr") || n.equals("fre") || n.equals("fra") || n.equals("french")) return "法语";
        if (n.equals("de") || n.equals("ger") || n.equals("deu") || n.equals("german")) return "德语";
        if (n.equals("es") || n.equals("spa") || n.equals("spanish")) return "西班牙语";
        if (n.equals("ru") || n.equals("rus") || n.equals("russian")) return "俄语";
        if (n.equals("pt") || n.equals("por") || n.equals("portuguese")) return "葡萄牙语";
        if (n.equals("it") || n.equals("ita") || n.equals("italian")) return "意大利语";
        if (n.equals("th") || n.equals("tha") || n.equals("thai")) return "泰语";
        if (n.equals("vi") || n.equals("vie") || n.equals("vietnamese")) return "越南语";
        if (n.equals("ar") || n.equals("ara") || n.equals("arabic")) return "阿拉伯语";
        if (n.equals("hi") || n.equals("hin") || n.equals("hindi")) return "印地语";
        return null;
    }

    // ========== 内部 ==========

    private java.util.ArrayList<SideSheet.Choice> qualityChoices(String[] items) {
        java.util.ArrayList<SideSheet.Choice> list = new java.util.ArrayList<>();
        if (items == null) return list;
        for (String raw : items) {
            String text = raw.startsWith("✓") ? raw.substring(1).trim() : raw.trim();
            list.add(new SideSheet.Choice(text, "", null));
        }
        return list;
    }

    private int checkedIndex(String[] items) {
        if (items == null) return 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i].startsWith("✓")) return i;
        }
        return 0;
    }

    private java.util.ArrayList<SideSheet.Choice> trackChoices(String[] items) {
        java.util.ArrayList<SideSheet.Choice> list = new java.util.ArrayList<>();
        if (items == null) return list;
        for (String raw : items) {
            String text = raw == null ? "" : raw.trim();
            int cut = text.indexOf("  ");
            String title = cut < 0 ? text : text.substring(0, cut).trim();
            String detail = cut < 0 ? "" : text.substring(cut).trim();
            list.add(new SideSheet.Choice(title, detail, null));
        }
        return list;
    }

    private int trackSelected(String[] items, String current) {
        if (items == null || items.length == 0) return -1;
        if (current == null || current.isEmpty()) return 0;
        for (int i = 0; i < items.length; i++) {
            if (current.equals(items[i])) return i;
        }
        return 0;
    }

    public void reloadPlayback() {
        resetForQualitySwitch();
        cb.reloadPlayback(); // triggers loadPlayInfo again
    }
}




