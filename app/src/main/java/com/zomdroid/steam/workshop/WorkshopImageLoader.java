package com.zomdroid.steam.workshop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkshopImageLoader {
    private static WorkshopImageLoader instance;

    private final LruCache<String, Bitmap> memoryCache;
    private final File diskCacheDir;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public static synchronized WorkshopImageLoader getInstance(Context context) {
        if (instance == null) {
            instance = new WorkshopImageLoader(context.getApplicationContext());
        }
        return instance;
    }

    private WorkshopImageLoader(Context context) {
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8; // 1/8th of available memory in KB
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        diskCacheDir = new File(context.getCacheDir(), "workshop_thumbs");
        if (!diskCacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            diskCacheDir.mkdirs();
        }

        executor = Executors.newFixedThreadPool(4);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void load(String url, ImageView imageView, int placeholderResId) {
        if (imageView == null) return;

        if (url == null || url.trim().isEmpty()) {
            imageView.setTag(null);
            if (placeholderResId != 0) imageView.setImageResource(placeholderResId);
            return;
        }

        imageView.setTag(url);
        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        if (placeholderResId != 0) {
            imageView.setImageResource(placeholderResId);
        }

        executor.execute(() -> {
            Bitmap bitmap = loadFromDisk(url);
            if (bitmap == null) {
                bitmap = downloadAndCache(url);
            }

            if (bitmap != null) {
                memoryCache.put(url, bitmap);
                final Bitmap finalBitmap = bitmap;
                mainHandler.post(() -> {
                    if (url.equals(imageView.getTag())) {
                        imageView.setImageBitmap(finalBitmap);
                    }
                });
            }
        });
    }

    private Bitmap loadFromDisk(String url) {
        try {
            File file = getDiskFile(url);
            if (file.exists() && file.length() > 0) {
                return BitmapFactory.decodeFile(file.getAbsolutePath());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Bitmap downloadAndCache(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 7)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            File tempFile = new File(diskCacheDir, getDiskFile(urlStr).getName() + ".tmp");
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }

            File targetFile = getDiskFile(urlStr);
            //noinspection ResultOfMethodCallIgnored
            tempFile.renameTo(targetFile);

            return BitmapFactory.decodeFile(targetFile.getAbsolutePath());
        } catch (Exception ignored) {
            return null;
        }
    }

    private File getDiskFile(String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
        String hash = new BigInteger(1, digest).toString(16);
        return new File(diskCacheDir, hash + ".img");
    }
}
