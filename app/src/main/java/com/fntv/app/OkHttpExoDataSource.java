package com.fntv.app;

import android.net.Uri;
import android.util.Log;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 基于 OkHttp 的 ExoPlayer DataSource。
 * Range 必须返回正确的分段内容；服务器忽略 Range 时不能假装成功，
 * 否则快进后仍从文件头读，表现为「一快进就从头播放」。
 */
public class OkHttpExoDataSource extends BaseDataSource {

    private static final String TAG = "OkHttpDS";
    private static final Pattern CONTENT_RANGE_SIZE =
            Pattern.compile("bytes (?:(?:\\d+-\\d+)|\\*)/(\\d+)");
    private static final Pattern CONTENT_RANGE_START =
            Pattern.compile("bytes (\\d+)-");

    private static int chunkSize = 0; // 0 = 不分块
    private static String cloudCookie = "";
    public static int lastResponseCode = 0;
    public static String lastContentType = "";

    private final OkHttpClient client;
    private DataSpec dataSpec;
    private Response response;
    private InputStream inputStream;
    private long bytesToRead;
    private long bytesRead;
    private boolean transferStarted;

    public static void setCloudCookie(String cookie) { cloudCookie = cookie; }

    /** 设置分块模式（字节数），用于夸克等云盘直链 */
    public static void setChunkedMode(int bytes) { chunkSize = bytes; }

    public OkHttpExoDataSource(OkHttpClient client) {
        super(/* isNetwork= */ true);
        this.client = client;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        bytesRead = 0;
        bytesToRead = 0;
        transferInitializing(dataSpec);

        long position = dataSpec.position;
        long length = dataSpec.length;

        Request.Builder builder = new Request.Builder().url(dataSpec.uri.toString());

        // 分块模式：每次只请求 chunkSize 字节，避免云盘风控
        long rangeEnd = -1;
        if (chunkSize > 0) {
            long endFromChunk = position + chunkSize - 1;
            if (length != C.LENGTH_UNSET) {
                rangeEnd = Math.min(position + length - 1, endFromChunk);
            } else {
                rangeEnd = endFromChunk;
            }
        } else if (length != C.LENGTH_UNSET) {
            rangeEnd = position + length - 1;
        }

        String range = rangeEnd >= 0
                ? "bytes=" + position + "-" + rangeEnd
                : "bytes=" + position + "-";
        builder.header("Range", range);
        builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        if (!cloudCookie.isEmpty()) {
            builder.header("Cookie", cloudCookie);
        }
        if (dataSpec.httpRequestHeaders != null) {
            for (String key : dataSpec.httpRequestHeaders.keySet()) {
                builder.header(key, dataSpec.httpRequestHeaders.get(key));
            }
        }

        response = client.newCall(builder.build()).execute();
        int code = response.code();
        lastResponseCode = code;
        lastContentType = response.header("Content-Type", "");

        ResponseBody body = response.body();
        if (body == null) {
            closeQuietly();
            throw new IOException("HTTP " + code + " empty body");
        }

        if (code != 200 && code != 206) {
            closeQuietly();
            throw new IOException("HTTP " + code + " " + response.message());
        }

        Log.d(TAG, "open: code=" + code + " type=" + lastContentType
                + " pos=" + position + " range=" + range
                + " len=" + body.contentLength());

        // 请求了非 0 位置却返回 200：服务器忽略了 Range。大文件不能 skip，直接失败。
        if (code == 200 && position != 0) {
            closeQuietly();
            throw new IOException("HTTP 200 for Range at position " + position
                    + " (server ignores Range)");
        }

        // 206 但 Content-Range 起点对不上
        if (code == 206) {
            long rangeStart = parseRangeStart(response.header("Content-Range"));
            if (rangeStart >= 0 && rangeStart != position) {
                closeQuietly();
                throw new IOException("Content-Range start " + rangeStart
                        + " != requested " + position);
            }
        }

        inputStream = new BufferedInputStream(body.byteStream(), 65536);

        if (length != C.LENGTH_UNSET) {
            bytesToRead = length;
        } else if (code == 206) {
            long contentLen = body.contentLength();
            if (contentLen != -1) {
                bytesToRead = contentLen;
            } else {
                long docSize = parseDocumentSize(response.header("Content-Range"));
                bytesToRead = docSize != -1 ? Math.max(0, docSize - position) : C.LENGTH_UNSET;
            }
        } else {
            long contentLen = body.contentLength();
            bytesToRead = contentLen != -1 ? contentLen : C.LENGTH_UNSET;
        }

        // 分块上限（仅在未指定精确 length 时收紧）
        if (chunkSize > 0 && length == C.LENGTH_UNSET) {
            if (bytesToRead == C.LENGTH_UNSET || bytesToRead > chunkSize) {
                bytesToRead = chunkSize;
            }
        }

        transferStarted(dataSpec);
        transferStarted = true;
        return bytesToRead;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) return 0;
        if (bytesToRead != C.LENGTH_UNSET && bytesRead >= bytesToRead) return C.RESULT_END_OF_INPUT;
        if (inputStream == null) return C.RESULT_END_OF_INPUT;

        int toRead = readLength;
        if (bytesToRead != C.LENGTH_UNSET) {
            long remaining = bytesToRead - bytesRead;
            if (remaining == 0) return C.RESULT_END_OF_INPUT;
            toRead = (int) Math.min(toRead, remaining);
        }

        int n = inputStream.read(buffer, offset, toRead);
        if (n == -1) {
            if (bytesToRead != C.LENGTH_UNSET && bytesRead < bytesToRead) {
                throw new EOFException("End of stream before expected length");
            }
            return C.RESULT_END_OF_INPUT;
        }
        bytesRead += n;
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() {
        return response != null && response.request() != null
                ? Uri.parse(response.request().url().toString()) : null;
    }

    @Override
    public void close() throws IOException {
        try {
            closeQuietly();
        } finally {
            if (transferStarted) {
                transferEnded();
                transferStarted = false;
            }
            dataSpec = null;
        }
    }

    private static long parseDocumentSize(String contentRange) {
        if (contentRange == null) return -1;
        Matcher m = CONTENT_RANGE_SIZE.matcher(contentRange);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseRangeStart(String contentRange) {
        if (contentRange == null) return -1;
        Matcher m = CONTENT_RANGE_START.matcher(contentRange);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void closeQuietly() {
        if (inputStream != null) {
            try { inputStream.close(); } catch (IOException ignored) {}
            inputStream = null;
        }
        if (response != null) {
            response.close();
            response = null;
        }
    }
}
