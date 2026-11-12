package com.fntv.app.api;

import android.net.Uri;
import android.util.Base64;

import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 飞牛访问码。未开启时 {@code GET /access_code_verify} 返回 204；
 * 开启后未带码返回 401。校验和后续请求使用
 * {@code x-access-code: base64(访问码)}、{@code x-access-source: app}。
 */
public final class AccessCodes {
    public static final String HEADER = "x-access-code";
    public static final String SOURCE_HEADER = "x-access-source";
    public static final String SOURCE_APP = "app";
    public static final String PREF = "access_code";

    public static final int OPEN = 0;
    public static final int REQUIRED = 1;
    public static final int REJECTED = 2;
    public static final int LIMITED = 3;
    public static final int UNKNOWN = 4;
    public static final int OFFLINE = 5;

    private static final HostnameVerifier TRUST_ALL_HOSTS = (hostname, session) -> true;
    private static final OkHttpClient CLIENT = buildClient();

    private AccessCodes() {}

    public static final class Pasted {
        public final String base;
        public final String code;

        public Pasted(String base, String code) {
            this.base = base == null ? "" : base;
            this.code = code == null ? "" : code;
        }
    }

    public static String encode(String code) {
        if (code == null || code.isEmpty()) return "";
        try {
            return Base64.encodeToString(code.getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** 从地址里拆出 {@code /c/访问码}。主机和端口保留，FN ID 路径也保留。 */
    public static Pasted peel(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return new Pasted("", "");
        int scheme = value.indexOf("://");
        int start = scheme >= 0 ? value.indexOf('/', scheme + 3) : value.indexOf('/');
        if (start < 0) return new Pasted(strip(value), "");
        String path = value.substring(start);
        int marker = path.indexOf("/c/");
        if (marker < 0) return new Pasted(strip(value), "");
        String code = path.substring(marker + 3);
        int cut = indexOfAny(code, '/', '?', '#');
        if (cut >= 0) code = code.substring(0, cut);
        try {
            code = Uri.decode(code);
        } catch (Exception ignored) {}
        return new Pasted(strip(value.substring(0, start + marker)), code.trim());
    }

    /** 登录框里可能粘贴 FN ID，或 {@code https://5ddd.com/id/c/码}。 */
    public static Pasted parseFnId(String raw) {
        Pasted peeled = peel(raw);
        String base = peeled.base;
        if (base.contains("://")) {
            int slash = base.lastIndexOf('/');
            if (slash >= 0 && slash < base.length() - 1) base = base.substring(slash + 1);
        }
        return new Pasted(base.trim(), peeled.code);
    }

    /** 不带访问码探测。204 表示未开启，401/403 表示需要。 */
    public static int probe(String baseUrl) {
        return request(baseUrl, null);
    }

    /** 带访问码校验。2xx 表示通过，401/403 表示码错误。 */
    public static int verify(String baseUrl, String code) {
        if (code == null || code.trim().isEmpty()) return REJECTED;
        return request(baseUrl, code.trim());
    }

    private static int request(String baseUrl, String code) {
        String root = root(baseUrl);
        if (root.isEmpty()) return OFFLINE;
        HttpUrl start = HttpUrl.parse(root + "/access_code_verify");
        if (start == null) return OFFLINE;
        HttpUrl current = start;
        try {
            for (int hop = 0; hop < 4; hop++) {
                Request.Builder builder = new Request.Builder().url(current).get()
                        .header("Cookie", "mode=relay");
                if (code != null && !code.isEmpty()) {
                    builder.header(HEADER, encode(code));
                    builder.header(SOURCE_HEADER, SOURCE_APP);
                }
                Response response = CLIENT.newCall(builder.build()).execute();
                try {
                    int status = response.code();
                    if (status >= 300 && status < 400) {
                        String location = response.header("Location");
                        if (location == null || location.isEmpty()) return UNKNOWN;
                        HttpUrl next = current.resolve(location);
                        if (next == null) return UNKNOWN;
                        if (!next.encodedPath().endsWith("access_code_verify")) {
                            next = next.newBuilder()
                                    .encodedPath("/access_code_verify")
                                    .query(null)
                                    .fragment(null)
                                    .build();
                        }
                        current = next;
                        continue;
                    }
                    return classify(status, response.header("Content-Type", ""), code != null);
                } finally {
                    response.close();
                }
            }
            return UNKNOWN;
        } catch (Exception e) {
            return OFFLINE;
        }
    }

    private static int classify(int status, String contentType, boolean withCode) {
        if (status == 429) return LIMITED;
        boolean html = contentType != null && contentType.toLowerCase().contains("html");
        if (withCode) {
            if (status >= 200 && status < 300 && !html) return OPEN;
            if (status == 401 || status == 403) return REJECTED;
            return UNKNOWN;
        }
        if (status == 204 || (status == 200 && !html)) return OPEN;
        if (status == 401 || status == 403) return REQUIRED;
        return UNKNOWN;
    }

    private static String root(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/v")) value = value.substring(0, value.length() - 2);
        return value;
    }

    private static String strip(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static int indexOfAny(String value, char... chars) {
        int best = -1;
        for (char c : chars) {
            int at = value.indexOf(c);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    private static OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .hostnameVerifier(TRUST_ALL_HOSTS);
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAll}, new java.security.SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustAll);
        } catch (Exception ignored) {}
        return builder.build();
    }
}
