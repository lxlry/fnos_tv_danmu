package com.fntv.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import androidx.core.content.ContextCompat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.Map;

/** 电视端扫码输入：手机和电视在同一局域网，扫码后把文字发回输入框。 */
final class QrInput {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Session active;

    private QrInput() {}

    /** 同一页多个输入框共用一个扫码入口。 */
    static final class Field {
        final EditText edit;
        final String label;

        Field(EditText edit, String label) {
            this.edit = edit;
            this.label = label == null || label.isEmpty() ? "输入" : label;
        }
    }

    static boolean isTelevision(Context context) {
        if (context == null) return false;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    /** 单个输入框旁的扫码按钮（设置页、搜索等仍可用）。 */
    static Button scanButton(Activity activity, EditText target, String label) {
        return pageScanButton(activity, new Field(target, label));
    }

    /** 整页一个扫码按钮：手机端一次填写多个输入框。 */
    static Button pageScanButton(Activity activity, Field... fields) {
        Button button = new Button(activity);
        button.setText("扫码");
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        button.setBackgroundResource(R.drawable.bg_input);
        button.setFocusable(true);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> show(activity, fields));
        return button;
    }

    static void show(Activity activity, EditText target, String label) {
        show(activity, new Field(target, label));
    }

    static void show(Activity activity, Field... fields) {
        if (activity == null || activity.isFinishing() || fields == null || fields.length == 0) return;
        for (Field field : fields) {
            if (field == null || field.edit == null) return;
        }
        if (active != null) active.close();
        active = new Session(activity, fields);
        active.open();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Session {
        private final Activity activity;
        private final Field[] fields;
        private final String token;
        private final Handler handler = MAIN;
        private ServerSocket socket;
        private Thread thread;
        private Dialog dialog;
        private ImageView imageView;
        private TextView hintView;
        private TextView urlView;
        private volatile boolean running;
        private volatile boolean stopped;

        Session(Activity activity, Field[] fields) {
            this.activity = activity;
            this.fields = fields;
            byte[] raw = new byte[6];
            new SecureRandom().nextBytes(raw);
            StringBuilder builder = new StringBuilder();
            for (byte value : raw) builder.append(String.format("%02x", value & 0xff));
            token = builder.toString();
        }

        void open() {
            String ip = lanIp();
            dialog = buildDialog(ip == null ? "" : "正在准备…");
            dialog.setOnDismissListener(d -> stopServer());
            dialog.show();
            if (ip == null) {
                setMessage("没有局域网地址，手机连不上这台电视。请用遥控器输入。", null);
                return;
            }
            thread = new Thread(() -> serve(ip), "qr-input");
            thread.setDaemon(true);
            thread.start();
        }

        void close() {
            stopServer();
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }

        private void stopServer() {
            stopped = true;
            running = false;
            ServerSocket current = socket;
            if (current != null) {
                try {
                    current.close();
                } catch (Exception ignored) {}
            }
            if (active == this) active = null;
        }

        private void serve(String ip) {
            try {
                socket = new ServerSocket(0);
                running = true;
                int port = socket.getLocalPort();
                String url = "http://" + ip + ":" + port + "/?k=" + token;
                Bitmap qr = encode(url, 480);
                handler.post(() -> {
                    if (activity.isFinishing() || dialog == null || !dialog.isShowing()) return;
                    String tip = fields.length > 1
                            ? "手机和电视连同一个 Wi-Fi，扫码后一次填入本页全部输入框。"
                            : "手机和电视连同一个 Wi-Fi，扫码后输入，发送后自动填入。";
                    setMessage(tip, url);
                    if (imageView != null && qr != null) {
                        imageView.setImageBitmap(qr);
                        imageView.setVisibility(View.VISIBLE);
                    }
                });
                while (running) {
                    Socket client = socket.accept();
                    handle(client);
                }
            } catch (Exception e) {
                if (!stopped) {
                    handler.post(() -> {
                        if (dialog != null && dialog.isShowing()) {
                            setMessage("扫码服务启动失败，请用遥控器输入。", null);
                        }
                    });
                }
            } finally {
                running = false;
            }
        }

        private void handle(Socket client) {
            try {
                client.setSoTimeout(4000);
                InputStream in = client.getInputStream();
                String head = readHeaders(in);
                if (head.isEmpty()) return;
                int lineEnd = head.indexOf("\r\n");
                String requestLine = lineEnd < 0 ? head : head.substring(0, lineEnd);
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String path = parts[1];
                if (!token.equals(query(path, "k"))) {
                    write(client, 403, "text/plain; charset=utf-8", "forbidden");
                    return;
                }
                if ("POST".equals(method)) {
                    int length = contentLength(head);
                    int maxBody = Math.max(4096, fields.length * 600 + 256);
                    if (length < 0 || length > maxBody) {
                        write(client, 400, "text/plain; charset=utf-8", "bad request");
                        return;
                    }
                    byte[] body = new byte[length];
                    int offset = 0;
                    while (offset < length) {
                        int n = in.read(body, offset, length - offset);
                        if (n < 0) break;
                        offset += n;
                    }
                    String raw = new String(body, "UTF-8");
                    String[] values = new String[fields.length];
                    for (int i = 0; i < fields.length; i++) {
                        String key = fields.length == 1 ? "text" : ("f" + i);
                        String text = formValue(raw, key);
                        // 兼容旧单字段表单
                        if (text.isEmpty() && fields.length == 1) text = formValue(raw, "text");
                        if (text.length() > 500) text = text.substring(0, 500);
                        values[i] = text;
                    }
                    deliver(values);
                    write(client, 200, "text/html; charset=utf-8", successPage());
                } else {
                    write(client, 200, "text/html; charset=utf-8", formPage());
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    client.close();
                } catch (Exception ignored) {}
            }
        }

        private void deliver(String[] values) {
            handler.post(() -> {
                if (activity.isFinishing()) return;
                int filled = 0;
                for (int i = 0; i < fields.length; i++) {
                    String text = values[i] == null ? "" : values[i];
                    EditText target = fields[i].edit;
                    target.setText(text);
                    if (target.getText() != null) target.setSelection(target.getText().length());
                    if (!text.isEmpty()) filled++;
                }
                if (fields.length == 1) {
                    AppToast.show(activity, values[0] == null || values[0].isEmpty() ? "已清空" : "已填入");
                } else {
                    AppToast.show(activity, filled == 0 ? "已提交" : "已填入 " + filled + " 项");
                }
                close();
            });
        }

        private Dialog buildDialog(String message) {
            Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setBackgroundResource(R.drawable.bg_card);
            int pad = dp(activity, 20);
            root.setPadding(pad, dp(activity, 18), pad, dp(activity, 16));

            TextView title = new TextView(activity);
            title.setText(fields.length == 1 ? ("扫码输入 · " + fields[0].label) : "扫码输入");
            title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
            title.setTextSize(18);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            root.addView(title);

            ImageView image = new ImageView(activity);
            imageView = image;
            LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(dp(activity, 220), dp(activity, 220));
            imageLp.topMargin = dp(activity, 16);
            image.setLayoutParams(imageLp);
            image.setBackgroundColor(Color.WHITE);
            image.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setVisibility(View.GONE);
            root.addView(image);

            TextView hint = new TextView(activity);
            hintView = hint;
            hint.setText(message);
            hint.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
            hint.setTextSize(13);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = dp(activity, 12);
            hint.setLayoutParams(hintLp);
            root.addView(hint);

            TextView url = new TextView(activity);
            urlView = url;
            url.setTextColor(ContextCompat.getColor(activity, R.color.text_hint));
            url.setTextSize(12);
            LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            urlLp.topMargin = dp(activity, 6);
            url.setLayoutParams(urlLp);
            root.addView(url);

            Button close = new Button(activity);
            close.setText("关闭");
            close.setAllCaps(false);
            close.setTextSize(15);
            close.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
            close.setBackgroundResource(R.drawable.bg_input);
            close.setFocusable(true);
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44));
            closeLp.topMargin = dp(activity, 16);
            close.setLayoutParams(closeLp);
            close.setOnClickListener(v -> close());
            root.addView(close);

            dialog.setContentView(root);
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.86f);
                int max = dp(activity, 420);
                if (width > max) width = max;
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.setOnShowListener(d -> close.requestFocus());
            return dialog;
        }

        private void setMessage(String message, String url) {
            if (hintView != null) hintView.setText(message);
            if (urlView != null) urlView.setText(url == null ? "" : url);
        }

        private String formPage() {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                    .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                    .append("<title>扫码输入</title><style>")
                    .append("body{margin:0;background:#0b0f14;color:#f4f6f8;font-family:sans-serif}")
                    .append("main{max-width:480px;margin:0 auto;padding:28px 16px}")
                    .append("h1{font-size:20px;margin:0 0 8px}p{color:#a8b0bc;font-size:14px;line-height:1.5}")
                    .append("label{display:block;margin:14px 0 6px;color:#a8b0bc;font-size:13px}")
                    .append("input,button{width:100%;box-sizing:border-box;border-radius:10px;font-size:16px}")
                    .append("input{background:#1c2430;color:#fff;border:1px solid #2a3340;padding:14px}")
                    .append("button{background:#3b6fff;color:#fff;border:0;padding:14px;font-weight:700;margin-top:18px}")
                    .append("</style></head><body><main>");
            if (fields.length == 1) {
                html.append("<h1>").append(escape(fields[0].label)).append("</h1>")
                        .append("<p>输入后点发送，内容会填到电视上的输入框。</p>");
            } else {
                html.append("<h1>扫码输入</h1>")
                        .append("<p>一次填写本页全部内容，发送后会填到电视对应输入框。</p>");
            }
            html.append("<form method=\"post\" action=\"/?k=").append(token).append("\">");
            for (int i = 0; i < fields.length; i++) {
                Field field = fields[i];
                int inputType = field.edit.getInputType();
                boolean password = isPassword(inputType);
                boolean number = (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER;
                String type = password ? "password" : number ? "number" : "text";
                String name = fields.length == 1 ? "text" : ("f" + i);
                String value = password || field.edit.getText() == null
                        ? "" : escape(field.edit.getText().toString());
                if (fields.length > 1) {
                    html.append("<label>").append(escape(field.label)).append("</label>");
                }
                html.append("<input name=\"").append(name).append("\" type=\"").append(type)
                        .append("\" value=\"").append(value)
                        .append("\" maxlength=\"500\" autocomplete=\"off\"");
                if (i == 0) html.append(" autofocus");
                html.append(">");
            }
            html.append("<button type=\"submit\">发送到电视</button></form></main></body></html>");
            return html.toString();
        }

        private String successPage() {
            String tip = fields.length > 1
                    ? "电视上的输入框已填好，可以关闭这个页面。"
                    : "电视上的输入框已填好，可以关闭这个页面。";
            return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>已发送</title><style>"
                    + "body{margin:0;background:#0b0f14;color:#f4f6f8;font-family:sans-serif}"
                    + "main{max-width:480px;margin:0 auto;padding:48px 16px}h1{font-size:22px}"
                    + "p{color:#a8b0bc;font-size:15px}</style></head><body><main>"
                    + "<h1>已发送</h1><p>" + tip + "</p>"
                    + "</main></body></html>";
        }
    }

    private static boolean isPassword(int inputType) {
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private static String lanIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                boolean preferred = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en");
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || !addr.isSiteLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null) continue;
                    if (preferred) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static Bitmap encode(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readHeaders(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4 && buf.size() < 8192) {
            int value = in.read();
            if (value < 0) break;
            buf.write(value);
            if (value == "\r\n\r\n".charAt(matched)) matched++;
            else matched = value == '\r' ? 1 : 0;
        }
        return buf.toString("ISO-8859-1");
    }

    private static int contentLength(String head) {
        String lower = head.toLowerCase();
        int at = lower.indexOf("\r\ncontent-length:");
        if (at < 0) return 0;
        int start = at + "\r\ncontent-length:".length();
        int end = lower.indexOf("\r\n", start);
        if (end < 0) return -1;
        try {
            return Integer.parseInt(lower.substring(start, end).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String query(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        String[] pairs = path.substring(q + 1).split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) return eq < 0 ? "" : pair.substring(eq + 1);
        }
        return "";
    }

    private static String formValue(String body, String key) {
        if (body == null) return "";
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String name = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                if (key.equals(name)) return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static void write(Socket client, int code, String type, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes("UTF-8");
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes("ISO-8859-1"));
        out.write(bytes);
        out.flush();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
