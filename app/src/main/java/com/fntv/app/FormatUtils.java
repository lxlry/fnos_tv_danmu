package com.fntv.app;

/** 播放器格式化工具方法集 */
public class FormatUtils {

    /** 毫秒 → mm:ss / h:mm:ss */
    public static String fmt(long ms) {
        if (ms <= 0) return "00:00";
        int s = (int) (ms / 1000), m = s / 60, h = m / 60;
        s %= 60;
        m %= 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    /** 秒 → #h##m##s */
    public static String fmtTime(int sec) {
        if (sec <= 0) return "?";
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h + "h" + (m < 10 ? "0" : "") + m + "m" + (s < 10 ? "0" : "") + s + "s";
    }

    /** 字节 → KB / MB / GB */
    public static String fmtSize(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes >= 1073741824L) return String.format("%.1f GB", bytes / 1073741824f);
        if (bytes >= 1048576) return String.format("%.0f MB", bytes / 1048576f);
        return bytes / 1024 + " KB";
    }

    /** 音频编码名 → 友好名称 */
    public static String fmtAudioCodec(String name) {
        if (name == null) return "?";
        String n = name.toLowerCase();
        if (n.contains("truehd")) return "Dolby TrueHD";
        if (n.contains("eac3") || n.contains("ec3")) return "Dolby Digital Plus";
        if (n.contains("ac3")) return "AC3";
        if (n.contains("dts")) return n.contains("dts-hd") || n.contains("dtshd") ? "DTS-HD MA" : "DTS";
        if (n.contains("mp4a") || n.contains("aac")) return "AAC";
        if (n.contains("flac")) return "FLAC";
        if (n.contains("opus")) return "OPUS";
        if (n.contains("vorbis")) return "Vorbis";
        if (n.contains("pcm")) return "PCM";
        return name;
    }

    /** 视频编码名 → 友好名称 */
    public static String fmtVideoCodec(String name) {
        if (name == null) return "?";
        String n = name.toLowerCase();
        if (n.contains("hevc") || n.contains("h265") || n.contains("h.265")) return "H.265";
        if (n.contains("avc") || n.contains("h264") || n.contains("h.264")) return "H.264";
        if (n.contains("vp9")) return "VP9";
        if (n.contains("vp8")) return "VP8";
        if (n.contains("av1")) return "AV1";
        if (n.contains("mpeg")) return "MPEG";
        return name.toUpperCase();
    }

    /** bps → 可读码率 */
    public static String formatBitrate(int bps) {
        if (bps <= 0) return "?";
        if (bps >= 1000000) return String.format("%.2f Mbps", bps / 1000000f);
        if (bps >= 1000) return String.format("%.0f Kbps", bps / 1000f);
        return bps + " bps";
    }

    /** 解码器名 → 友好描述 */
    public static String formatDecoder(String decoderName) {
        if (decoderName == null || decoderName.isEmpty()) return "等待中...";
        if (decoderName.startsWith("ffmpeg")) return "FFmpeg软解 (" + decoderName + ")";
        if (decoderName.startsWith("OMX.google") || decoderName.startsWith("c2.android"))
            return "系统软解 (" + decoderName + ")";
        return "硬解 (" + decoderName + ")";
    }
}
