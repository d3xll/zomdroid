package com.zomdroid.steam.workshop;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.zomdroid.steam.SteamDownloadState;
import com.zomdroid.steam.SteamModDownloader;
import com.zomdroid.steam.SteamSessionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class WorkshopDownloadManager implements SteamModDownloader.Listener {

    private static final String TAG = "Zomdroid/WorkshopMgr";
    private static final WorkshopDownloadManager INSTANCE = new WorkshopDownloadManager();
    public static WorkshopDownloadManager getInstance() { return INSTANCE; }

    public static class DownloadItem {
        public final long id;
        public volatile String title;

        public DownloadItem(long id, String title) {
            this.id = id;
            this.title = (title != null && !title.trim().isEmpty()) ? title.trim() : ("Workshop #" + id);
        }
    }

    public synchronized void updateCurrentTitle(String title) {
        if (currentItem != null && title != null && !title.trim().isEmpty()) {
            currentItem.title = title.trim();
            notifyQueueChanged();
        }
    }

    public interface Listener {
        void onQueueUpdated();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<DownloadItem> queue = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile DownloadItem currentItem = null;
    private volatile boolean isRunning = false;
    private volatile boolean userCancelledAll = false;
    private Context appContext;
    private SteamModDownloader currentDownloader;
    private Thread currentThread;

    private WorkshopDownloadManager() {}

    public void init(Context context) {
        if (appContext == null && context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyQueueChanged() {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                try { l.onQueueUpdated(); } catch (Exception ignored) {}
            }
        });
    }

    public List<DownloadItem> getQueue() {
        return Collections.unmodifiableList(new ArrayList<>(queue));
    }

    public int getQueueCount() {
        return queue.size();
    }

    public int getTotalActiveCount() {
        return (currentItem != null ? 1 : 0) + queue.size();
    }

    @Nullable
    public DownloadItem getCurrentItem() {
        return currentItem;
    }

    public boolean isDownloading() {
        return isRunning;
    }

    @Nullable
    public synchronized DownloadItem pollNext() {
        if (userCancelledAll || queue.isEmpty()) {
            currentItem = null;
            notifyQueueChanged();
            return null;
        }
        currentItem = queue.remove(0);
        notifyQueueChanged();
        return currentItem;
    }

    public synchronized void enqueue(Context context, long id, String title) {
        init(context);
        userCancelledAll = false;
        if (currentItem != null && currentItem.id == id) {
            return;
        }
        for (DownloadItem item : queue) {
            if (item.id == id) return;
        }

        DownloadItem newItem = new DownloadItem(id, title);
        queue.add(newItem);
        notifyQueueChanged();

        if (!isRunning) {
            startDownloadSessionLocked();
        }
    }

    public synchronized void enqueueMultiple(Context context, List<DownloadItem> items) {
        init(context);
        userCancelledAll = false;
        for (DownloadItem item : items) {
            if (currentItem != null && currentItem.id == item.id) continue;
            boolean exists = false;
            for (DownloadItem q : queue) {
                if (q.id == item.id) { exists = true; break; }
            }
            if (!exists) queue.add(item);
        }
        notifyQueueChanged();

        if (!isRunning) {
            startDownloadSessionLocked();
        }
    }

    public synchronized void removeFromQueue(long id) {
        if (currentItem != null && currentItem.id == id) {
            cancelCurrent();
            return;
        }
        queue.removeIf(item -> item.id == id);
        notifyQueueChanged();
    }

    public synchronized void clearQueue() {
        queue.clear();
        notifyQueueChanged();
    }

    public synchronized void cancelCurrent() {
        if (currentDownloader != null) {
            currentDownloader.cancelCurrentItem();
        }
    }

    public synchronized void cancelAll() {
        userCancelledAll = true;
        queue.clear();
        currentItem = null;
        notifyQueueChanged();
        if (currentDownloader != null) {
            currentDownloader.cancel();
        }
        SteamDownloadState.get().cancel();
    }

    private synchronized void startDownloadSessionLocked() {
        if (userCancelledAll || queue.isEmpty()) {
            isRunning = false;
            currentItem = null;
            notifyQueueChanged();
            return;
        }

        isRunning = true;
        userCancelledAll = false;
        notifyQueueChanged();

        int maxConnections = appContext != null ? SteamSessionManager.getMaxConnections(appContext) : 6;
        currentDownloader = new SteamModDownloader(this::pollNext, maxConnections, this);
        currentThread = new Thread(currentDownloader, "zd-workshop-mod-session");

        SteamDownloadState.get().begin(appContext);
        SteamDownloadState.get().setActive(currentDownloader, currentThread);

        currentThread.start();
    }

    // --- SteamModDownloader.Listener implementation ---

    @Override
    public void onProgress(String message) {
        SteamDownloadState.get().onProgress(message);
    }

    @Override
    public void onPercent(int percent) {
        SteamDownloadState.get().onPercent(percent);
    }

    @Override
    public void onFileProgress(String fileName, long speedBytesPerSec, long downloadedBytes, long totalBytes, int percent) {
        SteamDownloadState.get().onFileProgress(fileName, speedBytesPerSec, downloadedBytes, totalBytes, percent);
    }

    @Override
    public void onDone(String message) {
        SteamDownloadState.get().onDone(message);
        mainHandler.post(() -> {
            synchronized (WorkshopDownloadManager.this) {
                currentDownloader = null;
                currentThread = null;
                isRunning = false;
                currentItem = null;
                notifyQueueChanged();
                if (!userCancelledAll && !queue.isEmpty() && (message == null || !message.contains("Could not connect"))) {
                    startDownloadSessionLocked();
                }
            }
        });
    }
}
