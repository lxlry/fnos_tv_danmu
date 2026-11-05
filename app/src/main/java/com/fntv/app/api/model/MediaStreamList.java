package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * GET /v/api/v1/stream/list/{guid}
 * 文件、视频轨、音轨、字幕轨一起返回。
 */
public class MediaStreamList {
    public List<FileEntry> files;
    @SerializedName("video_streams")
    public List<VideoEntry> videoStreams;
    @SerializedName("audio_streams")
    public List<AudioEntry> audioStreams;
    @SerializedName("subtitle_streams")
    public List<SubtitleEntry> subtitleStreams;

    public static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            if (!Double.isInfinite(n) && n == Math.rint(n)) {
                return String.valueOf(((Number) value).longValue());
            }
        }
        String s = String.valueOf(value).trim();
        return "null".equals(s) ? "" : s;
    }

    public static class FileEntry {
        public String guid;
        public String path;
        @SerializedName("file_name")
        public String fileName;
        public long size;
        @SerializedName("create_time")
        public long createTime;
        @SerializedName("update_time")
        public long updateTime;
        @SerializedName("file_birth_time")
        public long fileBirthTime;
    }

    public static class VideoEntry {
        @SerializedName("media_guid")
        public String mediaGuid;
        public String guid;
        public String title;
        @SerializedName("resolution_type")
        public String resolutionType;
        @SerializedName("color_range_type")
        public String colorRangeType;
        @SerializedName("codec_name")
        public String codecName;
        public Object profile;
        public Object level;
        public int width;
        public int height;
        @SerializedName("coded_width")
        public int codedWidth;
        @SerializedName("coded_height")
        public int codedHeight;
        @SerializedName("display_aspect_ratio")
        public Object displayAspectRatio;
        @SerializedName("pix_fmt")
        public String pixFmt;
        @SerializedName("color_space")
        public String colorSpace;
        @SerializedName("color_transfer")
        public String colorTransfer;
        @SerializedName("color_primaries")
        public String colorPrimaries;
        @SerializedName("r_frame_rate")
        public Object rFrameRate;
        public long bps;
        public int progressive;
        @SerializedName("bit_depth")
        public int bitDepth;
        public int refs;
        /** -1 表示占位流，例如还没解析出真实媒体的 STRM。 */
        public int ext1;

        public boolean isPlaceholder() {
            return ext1 == -1;
        }

        public String resolutionLabel() {
            int w = width > 0 ? width : codedWidth;
            int h = height > 0 ? height : codedHeight;
            if (w <= 0 || h <= 0) return "";
            return w + " x " + h;
        }
    }

    public static class AudioEntry {
        @SerializedName("media_guid")
        public String mediaGuid;
        public String guid;
        public String title;
        @SerializedName("audio_type")
        public String audioType;
        @SerializedName("codec_name")
        public String codecName;
        public String language;
        public int channels;
        public Object profile;
        @SerializedName("sample_rate")
        public Object sampleRate;
        @SerializedName("is_default")
        public int isDefault;
        @SerializedName("channel_layout")
        public String channelLayout;
        public long bps;
    }

    public static class SubtitleEntry {
        @SerializedName("media_guid")
        public String mediaGuid;
        public String guid;
        public String title;
        @SerializedName("codec_name")
        public String codecName;
        @SerializedName("codec_type")
        public String codecType;
        public String language;
        public int forced;
        @SerializedName("is_default")
        public int isDefault;
        @SerializedName("is_external")
        public int isExternal;
    }
}
