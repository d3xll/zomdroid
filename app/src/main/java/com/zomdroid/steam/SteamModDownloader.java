/*
 * SPDX-License-Identifier: MIT
 *
 * Anonymous Steam Workshop mod downloader — original mechanism by udarmolota.
 * Copyright (c) 2026 udarmolota
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following condition: the above copyright notice and this
 * permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.zomdroid.steam;

import android.util.Log;

import androidx.annotation.Nullable;

import com.zomdroid.AppStorage;
import com.zomdroid.ZipUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.CDNAuthToken;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.GlobalScope;

/**
 * ANONYMOUS in-app Workshop mod downloader (no Steam login), ported to Zomdroid. An anonymous account
 * CAN obtain the workshop depot key for public items.
 *
 * App-agnostic: reads the mod's owning game from the item (GetPublishedFileDetails → consumer_app_id),
 * so it downloads any public Workshop item. Items requiring ownership return EResult != OK on the depot
 * key and are skipped.
 *
 * High-speed multi-threaded SteamPipe chunk pipeline reusing JavaSteam's CDN {@link Client} + crypto.
 *
 * Output: each item packed into Downloads/zomdroid/&lt;Mod Title&gt;_&lt;id&gt;.zip.
 * Run on a background thread.
 */
public class SteamModDownloader implements Runnable, Cancellable {

    private static final String TAG = "Zomdroid/AnonMod";

    @FunctionalInterface
    public interface QueueProvider {
        @Nullable
        com.zomdroid.steam.workshop.WorkshopDownloadManager.DownloadItem pollNext();
    }

    public interface Listener {
        void onProgress(String message);
        default void onPercent(int percent) {}
        default void onFileProgress(String fileName, long speedBytesPerSec, long downloadedBytes, long totalBytes, int percent) {}
        void onDone(String message);
    }

    private static final class PubInfo {
        int consumerAppId;
        long hcontentFile;
        String title = "(workshop item)";
        boolean ok;
    }

    private final QueueProvider queueProvider;
    private final List<Long> workshopIds;
    private final int maxConnections;
    private final Listener listener;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private volatile boolean running;
    private volatile boolean started;       // anonymous logon succeeded → real work began
    private int connectAttempts = 0;
    private static final int MAX_CONNECT_ATTEMPTS = 10;
    private volatile String lastTitle;
    private volatile Thread workerThread;   // the thread running downloadAll()
    private volatile boolean finished;      // ensure done() fires once

    private volatile boolean cancelCurrentItemRequested;
    private final List<Thread> currentPool = new java.util.concurrent.CopyOnWriteArrayList<>();

    public SteamModDownloader(QueueProvider queueProvider, int maxConnections, Listener listener) {
        this.queueProvider = queueProvider;
        this.workshopIds = null;
        this.maxConnections = maxConnections;
        this.listener = listener;
    }

    public SteamModDownloader(List<Long> workshopIds, int maxConnections, Listener listener) {
        this.workshopIds = workshopIds;
        this.queueProvider = createStaticQueueProvider(workshopIds);
        this.maxConnections = maxConnections;
        this.listener = listener;
    }

    public SteamModDownloader(List<Long> workshopIds, Listener listener) {
        this(workshopIds, 6, listener);
    }

    private static QueueProvider createStaticQueueProvider(List<Long> ids) {
        if (ids == null) return () -> null;
        final java.util.Queue<Long> queue = new java.util.concurrent.ConcurrentLinkedQueue<>(ids);
        return () -> {
            Long next = queue.poll();
            return next != null ? new com.zomdroid.steam.workshop.WorkshopDownloadManager.DownloadItem(next, "Workshop #" + next) : null;
        };
    }

    public void cancelCurrentItem() {
        cancelCurrentItemRequested = true;
        for (Thread t : currentPool) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void cancel() {
        running = false;
        cancelCurrentItemRequested = true;
        for (Thread t : currentPool) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
        Thread w = workerThread;
        if (w != null) w.interrupt();
    }

    private void progress(String m) { Log.i(TAG, m); if (listener != null) listener.onProgress(m); }
    private void done(String m)     {
        if (finished) return;
        finished = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }
    private void percent(int p)     { if (listener != null) listener.onPercent(p); }

    @Override
    public void run() {
        try {
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);
            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            running = true;
            progress("Connecting to Steam (anonymous)...");
            steamClient.connect();
            while (running) manager.runWaitCallbacks(1000L);
            Log.i(TAG, "Anon downloader loop ended");
            if (!finished) {
                if (!running) {
                    done("Download cancelled.");
                } else {
                    done("Stopped before finishing — please try again.");
                }
            }
        } catch (Throwable t) {
            if (running) {
                Log.e(TAG, "SteamModDownloader crashed", t);
                done("crash: " + t);
            } else {
                Log.i(TAG, "Callback loop stopped: " + t);
                if (!finished) done("Download cancelled.");
            }
        }
    }

    private void onConnected(ConnectedCallback cb) {
        progress("Connected. Logging on anonymously (no account)...");
        steamUser.logOnAnonymous();
    }

    private void onDisconnected(DisconnectedCallback cb) {
        if (!running) return;
        if (!started && connectAttempts < MAX_CONNECT_ATTEMPTS) {
            connectAttempts++;
            progress("Connection dropped — retrying (" + connectAttempts + "/" + MAX_CONNECT_ATTEMPTS + ")...");
            long backoffMs = Math.min(10000L, 1000L + connectAttempts * 1000L);
            try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
            if (!running) return;
            steamClient.connect();
            return;
        }
        if (!started) {
            done("Could not connect to Steam after " + connectAttempts
                    + " attempts. Check your connection and try again.");
        } else {
            progress("Disconnected.");
        }
        running = false;
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("Anonymous logon failed: " + cb.getResult());
            running = false;
            return;
        }
        started = true;
        workerThread = new Thread(this::downloadAll, "zd-anon-mod-dl");
        workerThread.start();
    }

    private void downloadAll() {
        File downloadsDir = SteamGameDownloader.getDownloadsDir();
        File tmpRoot = new File(AppStorage.requireSingleton().getCachePath(), "anon_mod_work");
        Client cdn = new Client(steamClient);
        int ok = 0, skipped = 0;
        try {
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                done("Cannot create downloads folder: " + downloadsDir + " (grant All-files access?)");
                return;
            }

            while (running) {
                com.zomdroid.steam.workshop.WorkshopDownloadManager.DownloadItem item =
                        queueProvider != null ? queueProvider.pollNext() : null;
                if (item == null) {
                    break;
                }
                long id = item.id;
                cancelCurrentItemRequested = false;

                if (listener != null) {
                    listener.onPercent(0);
                    listener.onFileProgress("", 0, 0, 0, 0);
                }

                progress("=== Workshop item " + id + " (anonymous) ===");
                File work = new File(tmpRoot, String.valueOf(id));
                deleteRecursive(work);
                if (!work.mkdirs()) {
                    progress("✗ " + id + ": cannot create work dir");
                    skipped++;
                    continue;
                }

                try {
                    boolean success = downloadOne(cdn, id, work);
                    if (!running) {
                        deleteRecursive(work);
                        break;
                    }
                    if (cancelCurrentItemRequested) {
                        cancelCurrentItemRequested = false;
                        deleteRecursive(work);
                        progress("✗ " + id + " — cancelled by user.");
                        skipped++;
                        continue;
                    }
                    if (success && hasContent(work)) {
                        File zip = new File(downloadsDir, sanitizeFileName(lastTitle) + "_" + id + ".zip");
                        try (FileOutputStream fos = new FileOutputStream(zip)) {
                            ZipUtils.zipDirectoryToStream(work, fos);
                        }
                        progress("✓ " + id + " → " + zip.getAbsolutePath());
                        ok++;
                    } else {
                        progress("✗ " + id + " — nothing usable downloaded "
                                + "(items that require game ownership can't be downloaded anonymously).");
                        skipped++;
                    }
                } catch (Throwable t) {
                    if (!running) {
                        deleteRecursive(work);
                        break;
                    }
                    if (cancelCurrentItemRequested) {
                        cancelCurrentItemRequested = false;
                        deleteRecursive(work);
                        progress("✗ " + id + " — cancelled by user.");
                        skipped++;
                        continue;
                    }
                    Log.e(TAG, "item " + id + " failed", t);
                    progress("✗ " + id + " — " + describe(t));
                    skipped++;
                }
                deleteRecursive(work);
            }

            deleteRecursive(tmpRoot);

            if (!running) {
                done("Download cancelled.");
                return;
            }
            done("Mods done: " + ok + " downloaded, " + skipped + " skipped. Saved to " + downloadsDir);
        } catch (Throwable t) {
            if (!running) {
                deleteRecursive(tmpRoot);
                done("Download cancelled.");
            } else {
                Log.e(TAG, "downloadAll crashed", t);
                done("error: " + describe(t));
            }
        } finally {
            running = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    private boolean downloadOne(Client cdn, long id, File work) throws Exception {
        if (!running || cancelCurrentItemRequested) return false;
        PubInfo info = resolvePublishedFile(id);
        if (!running || cancelCurrentItemRequested) return false;
        lastTitle = info.title;
        if (info.ok && info.title != null && !info.title.isEmpty()) {
            com.zomdroid.steam.workshop.WorkshopDownloadManager.getInstance().updateCurrentTitle(info.title);
        }
        if (!info.ok || info.consumerAppId <= 0 || info.hcontentFile == 0) {
            progress("✗ " + id + ": not found / no content manifest.");
            return false;
        }
        int appId = info.consumerAppId;
        progress("item '" + info.title + "' → app " + appId + ", manifest " + info.hcontentFile);

        int depot = resolveWorkshopDepot(appId);
        if (!running || cancelCurrentItemRequested) return false;
        if (depot <= 0) { progress("✗ could not resolve workshop depot for app " + appId); return false; }

        byte[] depotKey = getDepotKey(depot, appId);
        if (!running || cancelCurrentItemRequested) return false;
        if (depotKey == null) {
            progress("✗ no depot key (item likely needs game ownership).");
            return false;
        }

        SteamContent content = steamClient.getHandler(SteamContent.class);
        List<Server> servers = awaitDeferred(
                content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE), 30000);
        if (!running || cancelCurrentItemRequested) return false;
        if (servers == null || servers.isEmpty()) { progress("✗ no CDN servers"); return false; }

        long requestCode = awaitDeferred(
                content.getManifestRequestCode(depot, appId, info.hcontentFile, "public", null,
                        GlobalScope.INSTANCE), 30000);
        if (!running || cancelCurrentItemRequested) return false;

        DepotManifest manifest = null;
        Exception lastErr = null;
        Map<String, String> tokenCache = new ConcurrentHashMap<>();
        int firstGood = -1;
        for (int i = 0; i < servers.size(); i++) {
            if (!running || cancelCurrentItemRequested) return false;
            Server s = servers.get(i);
            try {
                manifest = cdn.downloadManifestFuture(depot, info.hcontentFile, requestCode, s,
                        depotKey, null, cdnTokenFor(content, appId, depot, s, tokenCache))
                        .get(90, TimeUnit.SECONDS);
                firstGood = i;
                break;
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "manifest via " + s.getHost() + " failed: " + describe(e));
            }
        }
        if (manifest == null) {
            progress("✗ manifest download failed: " + describe(lastErr));
            return false;
        }

        List<FileData> files = manifest.getFiles();
        long totalBytes = 0;
        for (FileData f : files) {
            if (!f.getFlags().contains(EDepotFileFlag.Directory)) totalBytes += f.getTotalSize();
        }
        progress("manifest OK: " + files.size() + " entries, "
                + (totalBytes / (1024 * 1024)) + " MB — downloading...");

        // Optimized parallel SteamPipe chunk pipeline matching SteamGameDownloader
        final int workers = Math.max(2, Math.min(12, maxConnections));
        final AtomicInteger serverCursor = new AtomicInteger(Math.max(firstGood, 0));
        final AtomicLong doneBytes = new AtomicLong(0);
        final AtomicLong lastSpeedBytes = new AtomicLong(0);
        final AtomicLong lastSpeedTime = new AtomicLong(System.currentTimeMillis());
        final AtomicLong currentSpeed = new AtomicLong(0);
        final AtomicLong lastEmit = new AtomicLong(0);
        final AtomicReference<Throwable> firstError = new AtomicReference<>();

        final List<FileData> pending = new java.util.ArrayList<>();
        for (FileData f : files) {
            if (!running || cancelCurrentItemRequested) return false;
            String rel = sanitizeRel(f.getFileName());
            if (rel == null) continue;
            File out = new File(work, rel);
            if (f.getFlags().contains(EDepotFileFlag.Directory)) {
                //noinspection ResultOfMethodCallIgnored
                out.mkdirs();
                continue;
            }
            pending.add(f);
        }

        final long fTotalBytes = totalBytes;
        final int fDepot = depot, fAppId = appId;
        final byte[] fDepotKey = depotKey;
        final List<Server> fServers = servers;
        final Client fCdn = cdn;
        final SteamContent fContent = content;
        final Map<String, String> fTokenCache = tokenCache;
        final File fWork = work;

        final int maxActiveFiles = Math.max(workers * 2, 8);
        final Semaphore fileSlots = new Semaphore(maxActiveFiles);
        final BlockingQueue<ChunkTask> chunkQueue =
                new LinkedBlockingQueue<>(Math.max(64, workers * 8));
        final CountDownLatch latch = new CountDownLatch(workers);
        final List<Thread> pool = new java.util.ArrayList<>(workers);
        currentPool.clear();
        final java.util.Set<ActiveFile> openFiles =
                Collections.newSetFromMap(new ConcurrentHashMap<>());

        for (int w = 0; w < workers; w++) {
            Thread t = new Thread(() -> {
                try {
                    while (running && !cancelCurrentItemRequested && firstError.get() == null) {
                        ChunkTask task;
                        try {
                            task = chunkQueue.poll(200, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ie) {
                            break;
                        }
                        if (task == null) continue;
                        if (task == POISON_PILL || cancelCurrentItemRequested) {
                            chunkQueue.offer(POISON_PILL);
                            break;
                        }

                        try {
                            downloadAndWriteChunk(task, fCdn, fContent, fServers, fAppId, fDepot,
                                    fDepotKey, fTokenCache, serverCursor, doneBytes, fTotalBytes,
                                    lastSpeedBytes, lastSpeedTime, currentSpeed, lastEmit,
                                    fileSlots, openFiles);
                        } catch (Throwable err) {
                            if (!cancelCurrentItemRequested) {
                                firstError.compareAndSet(null, err);
                            }
                            break;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }, "zd-mod-dl-" + w);
            t.setDaemon(true);
            pool.add(t);
            t.start();
        }
        currentPool.addAll(pool);

        for (FileData f : pending) {
            if (!running || cancelCurrentItemRequested || firstError.get() != null) break;
            String rel = sanitizeRel(f.getFileName());
            if (rel == null) continue;
            File outFile = new File(fWork, rel);

            if (f.getChunks().isEmpty() || f.getTotalSize() == 0) {
                File parent = outFile.getParentFile();
                if (parent != null) parent.mkdirs();
                if (!outFile.exists()) {
                    try { outFile.createNewFile(); } catch (Exception ignored) {}
                }
                continue;
            }

            while (running && !cancelCurrentItemRequested && firstError.get() == null) {
                if (fileSlots.tryAcquire(200, TimeUnit.MILLISECONDS)) break;
            }
            if (!running || cancelCurrentItemRequested || firstError.get() != null) break;

            ActiveFile af;
            try {
                af = new ActiveFile(f, rel, outFile);
                openFiles.add(af);
            } catch (Exception ex) {
                fileSlots.release();
                if (!cancelCurrentItemRequested) {
                    firstError.compareAndSet(null, ex);
                }
                break;
            }

            for (ChunkData chunk : f.getChunks()) {
                ChunkTask task = new ChunkTask(af, chunk);
                while (running && !cancelCurrentItemRequested && firstError.get() == null) {
                    if (chunkQueue.offer(task, 200, TimeUnit.MILLISECONDS)) break;
                }
                if (!running || cancelCurrentItemRequested || firstError.get() != null) break;
            }
        }

        if (running && !cancelCurrentItemRequested && firstError.get() == null) {
            chunkQueue.offer(POISON_PILL);
        } else {
            chunkQueue.offer(POISON_PILL);
            for (Thread t : pool) {
                try { t.interrupt(); } catch (Throwable ignored) {}
            }
        }

        try {
            latch.await();
        } catch (InterruptedException cancelled) {
            running = false;
            for (Thread t : pool) t.interrupt();
            boolean drained = false;
            while (!drained) {
                try { latch.await(); drained = true; }
                catch (InterruptedException ignored) {}
            }
            return false;
        } finally {
            currentPool.clear();
            for (ActiveFile af : openFiles) {
                af.close();
            }
            openFiles.clear();
        }

        if (!running || cancelCurrentItemRequested) return false;
        Throwable workerError = firstError.get();
        if (workerError != null) {
            if (workerError instanceof Exception) throw (Exception) workerError;
            throw new java.io.IOException(workerError);
        }

        progress("content fetched (" + (doneBytes.get() / (1024 * 1024)) + " MB)");
        return true;
    }

    /** One chunk: downloaded and written at absolute offset to FileChannel. Runs on pool thread. */
    private void downloadAndWriteChunk(ChunkTask task, Client cdn, SteamContent content,
                                       List<Server> servers, int appId, int depot, byte[] depotKey,
                                       Map<String, String> tokenCache,
                                       AtomicInteger serverCursor,
                                       AtomicLong doneBytes, long totalBytes,
                                       AtomicLong lastSpeedBytes,
                                       AtomicLong lastSpeedTime,
                                       AtomicLong currentSpeed,
                                       AtomicLong lastEmit,
                                       Semaphore fileSlots,
                                       java.util.Set<ActiveFile> openFiles) throws Exception {
        if (!running || cancelCurrentItemRequested) return;
        ActiveFile af = task.activeFile;
        ChunkData chunk = task.chunk;
        byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
        int written = -1;
        Exception chunkErr = null;
        int tries = Math.min(Math.max(servers.size(), 4), 8);

        for (int t = 0; t < tries && written < 0; t++) {
            if (!running || cancelCurrentItemRequested) return;
            Server s = servers.get(Math.floorMod(serverCursor.getAndIncrement(), servers.size()));
            try {
                written = cdn.downloadDepotChunkFuture(depot, chunk, s, dest, depotKey, null,
                        cdnTokenFor(content, appId, depot, s, tokenCache)).get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                chunkErr = e;
                Log.w(TAG, "chunk via " + s.getHost() + " failed (try " + (t + 1) + "): " + describe(e));
                Thread.sleep(300);
            }
        }
        if (written < 0) {
            throw chunkErr != null ? chunkErr : new java.io.IOException("chunk download failed for " + af.relPath);
        }

        ByteBuffer buf = ByteBuffer.wrap(dest, 0, written);
        long pos = chunk.getOffset();
        while (buf.hasRemaining()) {
            int n = af.channel.write(buf, pos);
            pos += n;
        }

        long total = doneBytes.addAndGet(written);
        long now = System.currentTimeMillis();
        long prevTime = lastSpeedTime.get();
        long diffTime = now - prevTime;
        if (diffTime >= 800L) {
            long prevBytes = lastSpeedBytes.get();
            long diffBytes = total - prevBytes;
            if (lastSpeedTime.compareAndSet(prevTime, now)) {
                lastSpeedBytes.set(total);
                currentSpeed.set(Math.max(0, (diffBytes * 1000L) / diffTime));
            }
        }

        long spd = currentSpeed.get();
        int pct = totalBytes > 0 ? (int) (total * 100 / totalBytes) : 0;
        long prev = lastEmit.get();
        if (now - prev > 350 && lastEmit.compareAndSet(prev, now)) {
            if (listener != null) {
                listener.onFileProgress(af.relPath, spd, total, totalBytes, pct);
                listener.onPercent(pct);
            }
        }

        if (af.remainingChunks.decrementAndGet() == 0) {
            af.close();
            openFiles.remove(af);
            fileSlots.release();
            if (running && !cancelCurrentItemRequested && listener != null) {
                listener.onFileProgress(af.relPath, currentSpeed.get(), doneBytes.get(), totalBytes, pct);
            }
        }
    }

    private static class ActiveFile {
        final FileData fileData;
        final String relPath;
        final File outFile;
        final RandomAccessFile raf;
        final FileChannel channel;
        final AtomicInteger remainingChunks;
        final AtomicBoolean closed = new AtomicBoolean(false);

        ActiveFile(FileData fileData, String relPath, File outFile) throws Exception {
            this.fileData = fileData;
            this.relPath = relPath;
            this.outFile = outFile;
            this.remainingChunks = new AtomicInteger(fileData.getChunks().size());
            File parent = outFile.getParentFile();
            if (parent != null) parent.mkdirs();
            this.raf = new RandomAccessFile(outFile, "rw");
            if (fileData.getTotalSize() > 0) {
                this.raf.setLength(fileData.getTotalSize());
            }
            this.channel = this.raf.getChannel();
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                try { channel.close(); } catch (Throwable ignored) {}
                try { raf.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static class ChunkTask {
        final ActiveFile activeFile;
        final ChunkData chunk;

        ChunkTask(ActiveFile activeFile, ChunkData chunk) {
            this.activeFile = activeFile;
            this.chunk = chunk;
        }
    }

    private static final ChunkTask POISON_PILL = new ChunkTask(null, null);

    private String cdnTokenFor(SteamContent content, int appId, int depot, Server s, Map<String, String> cache) {
        String host = s.getHost() != null ? s.getHost() : s.getVHost();
        if (host == null) return null;
        String cached = cache.get(host);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String token = null;
        try {
            CDNAuthToken tok = awaitDeferred(
                    content.getCDNAuthToken(appId, depot, host, GlobalScope.INSTANCE), 15000);
            if (tok != null && tok.getResult() == EResult.OK) token = tok.getToken();
        } catch (Throwable ignored) {}
        cache.put(host, token == null ? "" : token);
        return token;
    }

    private byte[] getDepotKey(int depot, int appId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            DepotKeyCallback dk = apps.getDepotDecryptionKey(depot, appId)
                    .toFuture().get(30, TimeUnit.SECONDS);
            if (dk.getResult() == EResult.OK && dk.getDepotKey() != null && dk.getDepotKey().length == 32) {
                return dk.getDepotKey();
            }
            Log.w(TAG, "depot key result=" + dk.getResult());
        } catch (Throwable t) {
            Log.e(TAG, "getDepotKey failed", t);
        }
        return null;
    }

    private int resolveWorkshopDepot(int appId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            long token = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(
                        Collections.singletonList(appId), Collections.<Integer>emptyList())
                        .toFuture().get(30, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(appId);
                if (t != null) token = t;
            } catch (Throwable ignored) {}
            List<PICSRequest> reqs = Collections.singletonList(new PICSRequest(appId, token));
            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    apps.picsGetProductInfo(reqs, Collections.<PICSRequest>emptyList())
                        .toFuture().get(60, TimeUnit.SECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                PICSProductInfo app = cb.getApps().get(appId);
                if (app != null) {
                    return app.getKeyValues().get("depots").get("workshopdepot").asInteger(-1);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "resolveWorkshopDepot failed", t);
        }
        return -1;
    }

    private PubInfo resolvePublishedFile(long id) throws Exception {
        URL url = new URL("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "itemcount=1&publishedfileids%5B0%5D=" + id;
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
            }
            PubInfo pf = new PubInfo();
            JSONObject root = new JSONObject(sb.toString());
            JSONObject resp = root.optJSONObject("response");
            if (resp == null) return pf;
            JSONArray arr = resp.optJSONArray("publishedfiledetails");
            if (arr == null || arr.length() == 0) return pf;
            JSONObject d = arr.getJSONObject(0);
            pf.ok = d.optInt("result", 0) == 1;
            pf.consumerAppId = (int) d.optLong("consumer_app_id", 0L);
            pf.title = d.optString("title", "(workshop item)");
            String h = d.optString("hcontent_file", "");
            if (!h.isEmpty()) {
                try { pf.hcontentFile = Long.parseUnsignedLong(h); } catch (NumberFormatException ignored) {}
            }
            return pf;
        } finally {
            c.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T awaitDeferred(Deferred<T> d, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!d.isCompleted()) {
            if (!running || cancelCurrentItemRequested) {
                throw new InterruptedException("cancelled");
            }
            if (System.currentTimeMillis() > deadline) throw new TimeoutException("deferred timed out");
            Thread.sleep(40);
        }
        return (T) d.getCompleted();
    }

    private static String sanitizeRel(String name) {
        if (name == null) return null;
        String rel = name.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || rel.contains("../")) return null;
        return rel;
    }

    private static String sanitizeFileName(String title) {
        if (title == null) return "workshop";
        String s = title.replaceAll("[\\\\/:*?\"<>|]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s.isEmpty() ? "workshop" : s;
    }

    /** Proof a real item was downloaded: any non-empty regular file present. */
    private static boolean hasContent(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (hasContent(f)) return true;
            } else if (f.length() > 0) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String describe(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m != null ? (": " + m) : "");
    }
}
