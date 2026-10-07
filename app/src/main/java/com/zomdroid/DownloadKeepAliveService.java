package com.zomdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Tiny foreground service that runs ONLY to keep the app process at foreground-service priority while a
 * Steam or GOG download is in progress. The actual download runs on its own thread in the same process;
 * this service stops Android from freezing/killing that process (and its connections) when the app is
 * backgrounded.
 *
 * Uses the DATA_SYNC foreground type (declared in the manifest) + ongoing notification with live progress.
 */
public class DownloadKeepAliveService extends Service {

    private static final String CHANNEL_ID = "zd_download";
    private static final int NOTIF_ID = 4242;
    private static final String EXTRA_TEXT = "text";
    private static final String DEFAULT_TEXT = "Downloading from Steam — keep the app open";

    private static volatile DownloadKeepAliveService instance;

    private static volatile String currentTitle = "Project Zomboid";
    private static volatile String currentText = DEFAULT_TEXT;
    private static volatile String currentSubText = null;
    private static volatile int currentProgress = 0;
    private static volatile int currentMax = 0;
    private static volatile boolean currentIndeterminate = true;
    private static volatile long lastNotifTimeMs = 0;

    /**
     * A start() whose onStartCommand has not run yet. A stop() can land in that window - a download
     * that fails the instant it begins (no network, say) calls it within milliseconds of start() -
     * and stopping a service started with startForegroundService() before it has called
     * startForeground() is not a no-op: Android crashes the app ("Context.startForegroundService()
     * did not then call Service.startForeground()"). So an early stop is only recorded, and the
     * service carries it out itself once it has gone foreground.
     */
    private static boolean startPending;
    /** A stop() that arrived while {@link #startPending}. */
    private static boolean stopPending;

    public static void start(Context ctx) { start(ctx, DEFAULT_TEXT); }

    /** @param text what the notification says; the downloads differ in where they come from. */
    public static void start(Context ctx, String text) {
        synchronized (DownloadKeepAliveService.class) {
            startPending = true;
            stopPending = false;
        }
        currentTitle = "Project Zomboid";
        currentText = text != null ? text : DEFAULT_TEXT;
        currentSubText = null;
        currentProgress = 0;
        currentMax = 0;
        currentIndeterminate = true;
        lastNotifTimeMs = 0;

        Intent i = new Intent(ctx.getApplicationContext(), DownloadKeepAliveService.class);
        i.putExtra(EXTRA_TEXT, text);
        try {
            ContextCompat.startForegroundService(ctx.getApplicationContext(), i);
        } catch (RuntimeException e) {
            // Refused (e.g. asked from the background): no onStartCommand is coming to clear it.
            synchronized (DownloadKeepAliveService.class) { startPending = false; }
            throw e;
        }
    }

    public static void stop(Context ctx) {
        synchronized (DownloadKeepAliveService.class) {
            if (startPending) { stopPending = true; return; }
        }
        instance = null;
        ctx.getApplicationContext().stopService(
                new Intent(ctx.getApplicationContext(), DownloadKeepAliveService.class));
    }

    public static void updateProgress(Context ctx, String title, String text, String subText,
                                      int progress, int max, boolean indeterminate) {
        currentTitle = title;
        currentText = text;
        currentSubText = subText;
        currentProgress = progress;
        currentMax = max;
        currentIndeterminate = indeterminate;

        DownloadKeepAliveService s = instance;
        if (s != null) {
            long now = System.currentTimeMillis();
            // Throttle to at most 2-3 updates per second (400ms) to prevent Android IPC spam
            if (now - lastNotifTimeMs < 400) {
                return;
            }
            lastNotifTimeMs = now;
            s.postNotification();
        }
    }

    public static void updateProgressImmediate(Context ctx, String title, String text, String subText,
                                               int progress, int max, boolean indeterminate) {
        currentTitle = title;
        currentText = text;
        currentSubText = subText;
        currentProgress = progress;
        currentMax = max;
        currentIndeterminate = indeterminate;

        DownloadKeepAliveService s = instance;
        if (s != null) {
            lastNotifTimeMs = System.currentTimeMillis();
            s.postNotification();
        }
    }

    private void postNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildCurrentNotification());
            }
        } catch (Exception ignored) {}
    }

    private Notification buildCurrentNotification() {
        Intent notificationIntent = new Intent(this, LauncherActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(currentTitle != null ? currentTitle : "Project Zomboid")
                .setContentText(currentText != null ? currentText : DEFAULT_TEXT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent);

        if (currentSubText != null && !currentSubText.isEmpty()) {
            builder.setSubText(currentSubText);
        }

        if (currentMax > 0 || currentIndeterminate) {
            builder.setProgress(currentMax, currentProgress, currentIndeterminate);
        }

        return builder.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        createChannel();
        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
        if (text != null && (currentText == null || currentText.equals(DEFAULT_TEXT))) {
            currentText = text;
        }

        startForeground(NOTIF_ID, buildCurrentNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        boolean stopNow;
        synchronized (DownloadKeepAliveService.class) {
            startPending = false;
            stopNow = stopPending;
            stopPending = false;
        }
        // Foreground as promised, so a stop that came early is now safe to honour.
        if (stopNow) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
