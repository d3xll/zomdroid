package com.zomdroid.steam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.zomdroid.DownloadKeepAliveService;

import java.util.concurrent.CompletableFuture;

/**
 * Process-wide state for an in-progress Steam download. The download itself runs on a background
 * thread that outlives the fragment, so this singleton holds the log buffer / progress / running
 * flag and re-attaches whichever {@link View} (fragment) is currently on screen. That lets the user
 * leave the download screen and come back without losing the log or the "still downloading" state.
 *
 * It is the {@link SteamGameDownloader.Listener} / {@link SteamModDownloader.Listener} passed to the
 * downloaders, so all their callbacks land here first, then get forwarded to the attached view.
 */
public final class SteamDownloadState
        implements SteamGameDownloader.Listener, SteamModDownloader.Listener {

    private static final SteamDownloadState INSTANCE = new SteamDownloadState();
    public static SteamDownloadState get() { return INSTANCE; }
    private SteamDownloadState() {}

    /** The currently-attached UI (fragment). Null when the screen isn't shown. */
    public interface View {
        void onLog(CharSequence fullLog);
        void onPercent(int percent, boolean indeterminate);
        default void onFileProgress(String fileName, long speedBytesPerSec, long downloadedBytes, long totalBytes, int percent) {}
        void onFinished(String message);
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        default void onSessionChanged() {}
    }


    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private volatile boolean downloading;
    private volatile int percent = -1;
    private volatile boolean indeterminate = true;
    private volatile String currentFile = "";
    private volatile long currentSpeed = 0L;
    private volatile long downloadedBytes = 0L;
    private volatile long totalBytes = 0L;
    private View view;
    private Context appCtx;
    private volatile Cancellable active;
    private volatile Thread activeThread;
    private volatile boolean cancelling;

    public boolean isDownloading() { return downloading; }
    public boolean isCancelling() { return cancelling; }
    public String getCurrentFile() { return currentFile; }
    public long getCurrentSpeed() { return currentSpeed; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public long getTotalBytes() { return totalBytes; }

    /** Register the running downloader + its thread so the user can cancel it. */
    public void setActive(Cancellable c, Thread t) { active = c; activeThread = t; cancelling = false; }

    /** Stop the running download: flag the downloader to bail and interrupt its blocking I/O. */
    public void cancel() {
        if (!downloading) return;
        cancelling = true;
        appendLine("Cancelling…");
        if (appCtx != null) {
            DownloadKeepAliveService.updateProgressImmediate(appCtx,
                    "Project Zomboid",
                    "Cancelling download…",
                    null,
                    percent >= 0 ? percent : 0, 100, indeterminate);
        }
        main.post(() -> { if (view != null) view.onLog(getLog()); });
        Cancellable c = active;
        if (c != null) c.cancel();   // downloader sets its running=false + interrupts its worker thread
    }
    public CharSequence getLog() { return log.toString(); }
    public int getPercent() { return percent; }
    public boolean isIndeterminate() { return indeterminate; }

    /** Called on the main thread by the fragment when it (re)appears or goes away. */
    public void setView(View v) { this.view = v; }
    public void clearView(View v) { if (this.view == v) this.view = null; }

    /** Start a new download session: clears the log and raises the keep-alive service. */
    public void begin(Context context) {
        appCtx = context.getApplicationContext();
        synchronized (log) { log.setLength(0); }
        downloading = true;
        indeterminate = true;
        percent = -1;
        currentFile = "";
        currentSpeed = 0L;
        downloadedBytes = 0L;
        totalBytes = 0L;
        DownloadKeepAliveService.start(appCtx);
        DownloadKeepAliveService.updateProgressImmediate(appCtx,
                "Project Zomboid",
                "Connecting to Steam…",
                null, 0, 0, true);
    }

    private void appendLine(String line) {
        synchronized (log) {
            log.append(line).append('\n');
            if (log.length() > 40000) log.delete(0, log.length() - 30000);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    // ---- downloader callbacks (background threads) ----
    @Override
    public void onProgress(String message) {
        appendLine(message);
        if (appCtx != null && indeterminate) {
            DownloadKeepAliveService.updateProgress(appCtx,
                    "Project Zomboid",
                    message,
                    null,
                    0, 0, true);
        }
        main.post(() -> { if (view != null) view.onLog(getLog()); });
    }

    @Override
    public void onPercent(int p) {
        percent = Math.max(0, Math.min(100, p));
        indeterminate = false;
        if (appCtx != null) {
            String title = "Project Zomboid — " + percent + "%";
            String sub = percent + "%";
            String content = currentFile != null && !currentFile.isEmpty() ? currentFile : "Downloading…";
            DownloadKeepAliveService.updateProgress(appCtx,
                    title,
                    content,
                    sub,
                    percent, 100, false);
        }
        main.post(() -> { if (view != null) view.onPercent(percent, false); });
    }

    @Override
    public void onFileProgress(String fileName, long speedBytesPerSec, long downloaded, long total, int p) {
        this.currentFile = fileName != null ? fileName : "";
        this.currentSpeed = speedBytesPerSec;
        this.downloadedBytes = downloaded;
        this.totalBytes = total;
        this.percent = Math.max(0, Math.min(100, p));
        this.indeterminate = false;

        if (appCtx != null) {
            String title = "Project Zomboid — " + this.percent + "%";
            String sub;
            StringBuilder content = new StringBuilder();

            if (speedBytesPerSec > 0) {
                double speedMb = speedBytesPerSec / (1024.0 * 1024.0);
                String speedStr = String.format(java.util.Locale.US, "%.1f MB/s", speedMb);
                sub = this.percent + "% • " + speedStr;
                content.append(speedStr);
                if (total > 0) {
                    content.append(" • ").append(formatBytes(downloaded)).append(" / ").append(formatBytes(total));
                }
            } else {
                sub = this.percent + "%";
                if (total > 0) {
                    content.append(formatBytes(downloaded)).append(" / ").append(formatBytes(total));
                }
            }

            if (this.currentFile != null && !this.currentFile.isEmpty()) {
                if (content.length() > 0) {
                    content.append(" • ");
                }
                content.append(this.currentFile);
            }

            DownloadKeepAliveService.updateProgress(appCtx,
                    title,
                    content.toString(),
                    sub,
                    this.percent, 100, false);
        }

        main.post(() -> {
            if (view != null) {
                view.onFileProgress(this.currentFile, speedBytesPerSec, downloaded, total, this.percent);
            }
        });
    }

    @Override
    public void onDone(String message) {
        appendLine((isError(message) ? "✗ " : "✓ ") + message);
        downloading = false;
        cancelling = false;
        active = null;
        activeThread = null;
        if (appCtx != null) DownloadKeepAliveService.stop(appCtx);
        main.post(() -> {
            if (view != null) { view.onLog(getLog()); view.onFinished(message); }
        });
    }

    @Override
    public void onSessionSaved(String accountName, String refreshToken) {
        if (appCtx != null) {
            SteamSessionManager.saveSession(appCtx, accountName, refreshToken);
        }
        main.post(() -> {
            if (view != null) view.onSessionChanged();
        });
    }

    @Override
    public void onSessionExpired() {
        if (appCtx != null) {
            SteamSessionManager.clearSession(appCtx);
        }
        main.post(() -> {
            if (view != null) view.onSessionChanged();
        });
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email) {
        // JavaSteam calls this on a background coroutine worker. The fragment builds an
        // AlertDialog/EditText, which MUST happen on the main thread (otherwise Android throws
        // "Can't create handler inside thread ... that has not called Looper.prepare()").
        final CompletableFuture<String> result = new CompletableFuture<>();
        main.post(() -> {
            View v = this.view;
            if (v == null) { result.complete(""); return; }
            try {
                CompletableFuture<String> f = v.requestSteamGuardCode(previousWrong, email);
                f.whenComplete((code, err) -> {
                    if (err != null) result.completeExceptionally(err);
                    else result.complete(code != null ? code : "");
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    private static boolean isError(String m) {
        if (m == null) return true;
        String s = m.toLowerCase();
        return s.contains("error") || s.contains("fail") || s.contains("crash")
                || s.contains("lost") || s.contains("cannot") || s.contains("could not");
    }
}
