package com.fntv.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.LruCache;
import android.util.Log;
import android.widget.ImageView;
import androidx.core.content.ContextCompat;
import com.fntv.app.R;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 简易图片加载器 - 使用 OkHttp（自动经过 AuthInterceptor 签名）
 * 兼容 Android 4.4+
 */
public class SimpleImageLoader {

    private static final String TAG = "ImageLoader";
    private static final int MAX_PARALLEL = 4;
    private static final ArrayDeque<ImageLoadTask> pending = new ArrayDeque<>();
    private static int running;

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(cacheBytes()) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private static int cacheBytes() {
        long heap = Runtime.getRuntime().maxMemory();
        int eighth = (int) (heap / 8);
        int quarter = (int) (heap / 4);
        int want = Math.max(eighth, 16 * 1024 * 1024);
        return Math.min(want, quarter);
    }

    public static boolean isMemoryCached(String url) {
        return url != null && cache.get(url) != null;
    }

    /** 加载图片（使用 OkHttpClient，自动携带认证头） */
    public static void load(String url, ImageView view, OkHttpClient client) {
        if (url == null || url.isEmpty()) {
            view.setImageBitmap(null);
            view.setBackgroundColor(ContextCompat.getColor(view.getContext(), R.color.bg_poster));
            return;
        }

        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        File dir = new File(view.getContext().getCacheDir(), "posters");
        synchronized (pending) {
            pending.add(new ImageLoadTask(view, client, url, dir));
            pump();
        }
    }

    private static void pump() {
        while (running < MAX_PARALLEL && !pending.isEmpty()) {
            running++;
            pending.removeFirst().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    private static void finished() {
        synchronized (pending) {
            running = Math.max(0, running - 1);
            pump();
        }
    }

    public static void clearCache() {
        cache.evictAll();
    }

    private static class ImageLoadTask extends AsyncTask<Void, Void, Bitmap> {
        private final ImageView imageView;
        private final OkHttpClient client;
        private final String url;
        private final File dir;

        ImageLoadTask(ImageView imageView, OkHttpClient client, String url, File dir) {
            this.imageView = imageView;
            this.client = client;
            this.url = url;
            this.dir = dir;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            Bitmap memory = cache.get(url);
            if (memory != null) return memory;
            byte[] disk = readDisk();
            if (disk != null) {
                Bitmap bitmap = tryDecode(disk);
                if (bitmap != null) {
                    cache.put(url, bitmap);
                    return bitmap;
                }
            }
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .build();

                Response response = client.newCall(request).execute();
                int code = response.code();
                String contentType = response.header("Content-Type");

                if (code != 200) return null;

                if (contentType != null && !contentType.contains("image")) {
                    String body = response.body() != null ? response.body().string() : "";
                    String snippet = body.length() > 200 ? body.substring(0, 200) : body;
                    Log.w(TAG, "NOT_IMAGE: " + snippet);
                    return null;
                }

                InputStream input = response.body().byteStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) baos.write(buf, 0, n);
                input.close();
                byte[] imageData = baos.toByteArray();
                writeDisk(imageData);

                Bitmap bitmap = tryDecode(imageData);
                if (bitmap != null) {
                    cache.put(url, bitmap);
                    Log.d(TAG, "OK (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                } else {
                    Log.e(TAG, "DECODE_FAILED");
                }
                return bitmap;
            } catch (Exception e) {
                Log.e(TAG, "FAIL: " + e.getMessage());
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            finished();
            Object tag = imageView.getTag();
            if (tag instanceof String && ((String) tag).startsWith("http") && !url.equals(tag)) return;
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            } else {
                imageView.setBackgroundColor(ContextCompat.getColor(imageView.getContext(), R.color.bg_poster));
            }
        }

        private File file() {
            return new File(dir, digest(url));
        }

        private byte[] readDisk() {
            File file = file();
            if (!file.isFile() || file.length() <= 0) return null;
            try {
                FileInputStream in = new FileInputStream(file);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                in.close();
                return baos.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }

        private void writeDisk(byte[] data) {
            if (data == null || data.length == 0) return;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                File tmp = new File(dir, digest(url) + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                out.write(data);
                out.close();
                File dest = file();
                if (dest.exists() && !dest.delete()) return;
                if (!tmp.renameTo(dest)) tmp.delete();
            } catch (Exception ignored) {}
        }
    }

    private static String digest(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static Bitmap tryDecode(byte[] data) {
        Bitmap bmp = null;
        if (bmp == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inPurgeable = true;
            o.inInputShareable = true;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception ignored) {}
        }
        if (bmp == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inPurgeable = true;
            o.inInputShareable = true;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception ignored) {}
        }
        if (bmp == null) {
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length); } catch (Exception ignored) {}
        }
        return bmp;
    }
}
