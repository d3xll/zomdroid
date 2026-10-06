package com.zomdroid.steam;

import android.os.Environment;
import android.util.Log;

import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.CDNAuthToken;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
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
import in.dragonbra.javasteam.types.KeyValue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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
 * In-app Steam downloader for the Project Zomboid <b>Linux</b> build, ported & adapted from RimDroid
 * (MIT, same author).
 *
 * <p>Uses an optimized, low-memory SteamPipe pipeline where chunks are pulled and written concurrently
 * across parallel worker threads. The chunk tasks are streamed through a bounded queue and bounded
 * open-file window, preventing both memory OOM and Android file descriptor exhaustion while
 * maximizing CDN throughput even on multi-chunk large files.
 */
public class SteamGameDownloader implements Runnable, Cancellable {

    private static final String TAG = "Zomdroid/SteamDL";

    public static final int PROJECT_ZOMBOID_APP_ID = 108600;

    public interface Listener {
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        void onProgress(String message);
        default void onPercent(int percent) {}
        default void onFileProgress(String fileName, long speedBytesPerSec, long downloadedBytes, long totalBytes, int percent) {}
        void onDone(String message);
        default void onSessionSaved(String accountName, String refreshToken) {}
        default void onSessionExpired() {}
    }

    private final String username, password;
    private final boolean rememberSession;
    private final long manifestId;       // 0 = latest build on the branch; >0 = pin this build
    private final String branch;         // Current branches: public = stable B42, legacy41 = B41.
    private final String buildLabel;     // "41" or "42" — for the output folder name only
    private final int maxConnections;
    private final boolean verifyFiles;
    private final Listener listener;
    private File librariesGameDir;
    private String librariesInstanceName; // macOS pack: whose "Libraries in use" to switch on
    private LibraryPack libraryPack;

    public static SteamGameDownloader macos(String username, String password, String instanceName,
                                            File gameDir, Listener listener) {
        return macos(username, password, null, true, instanceName, gameDir, listener);
    }

    public static SteamGameDownloader macos(String username, String password, String refreshToken, boolean rememberSession,
                                            String instanceName, File gameDir, Listener listener) {
        SteamGameDownloader downloader = libraries(username, password, refreshToken, rememberSession,
                gameDir, LibraryPack.MACOS, listener);
        downloader.librariesInstanceName = instanceName;
        return downloader;
    }

    public static SteamGameDownloader libraries(String username, String password, File gameDir,
                                                LibraryPack pack, Listener listener) {
        return libraries(username, password, null, true, gameDir, pack, listener);
    }

    public static SteamGameDownloader libraries(String username, String password, String refreshToken, boolean rememberSession,
                                                File gameDir, LibraryPack pack, Listener listener) {
        SteamGameDownloader downloader = new SteamGameDownloader(username, password, refreshToken, rememberSession,
                pack.manifest, pack.branch, "42", 4, true, listener);
        downloader.librariesGameDir = gameDir;
        downloader.libraryPack = pack;
        return downloader;
    }

    private String outputRelative(FileData file) {
        return libraryPack == null ? sanitizeRel(file.getFileName())
                : libraryPack.selectedName(file.getFileName());
    }

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private List<License> licenseList;

    private volatile String accountName;
    private volatile String refreshToken;

    private volatile boolean running;
    private volatile boolean started;
    private int connectAttempts = 0;
    private static final int MAX_CONNECT_ATTEMPTS = 10;
    private volatile Thread workerThread;   // the thread running download()
    private volatile boolean finished;      // ensure done() fires once

    public SteamGameDownloader(String username, String password, long manifestId,
                              String branch, String buildLabel, Listener listener) {
        this(username, password, null, true, manifestId, branch, buildLabel, 6, true, listener);
    }

    public SteamGameDownloader(String username, String password, String refreshToken, boolean rememberSession,
                              long manifestId, String branch, String buildLabel, Listener listener) {
        this(username, password, refreshToken, rememberSession, manifestId, branch, buildLabel, 6, true, listener);
    }

    public SteamGameDownloader(String username, String password, String refreshToken, boolean rememberSession,
                              long manifestId, String branch, String buildLabel, int maxConnections, boolean verifyFiles,
                              Listener listener) {
        this.username = username;
        this.password = password;
        this.refreshToken = refreshToken;
        this.accountName = (refreshToken != null && !refreshToken.isEmpty()) ? username : null;
        this.rememberSession = rememberSession;
        this.manifestId = manifestId;
        this.branch = (branch != null && !branch.isEmpty()) ? branch : "public";
        this.buildLabel = (buildLabel != null && !buildLabel.isEmpty()) ? buildLabel : "41";
        this.maxConnections = maxConnections > 0 ? maxConnections : 6;
        this.verifyFiles = verifyFiles;
        this.listener = listener;
    }

    /** Public Downloads/zomdroid folder (created on demand). Requires All-files access on Android 11+. */
    public static File getDownloadsDir() {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(dl, "zomdroid");
    }

    @Override
    public void cancel() {
        running = false;
        Thread w = workerThread;   // break the blocking chunk download / sleeps
        if (w != null) w.interrupt();
    }

    private void progress(String m) { Log.i(TAG, m); if (listener != null) listener.onProgress(m); }
    private void done(String m)     {
        if (finished) return;
        finished = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }

    @Override
    public void run() {
        try {
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);
            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            manager.subscribe(LicenseListCallback.class, this::onLicenseList);

            running = true;
            progress("Connecting to Steam...");
            steamClient.connect();
            while (running) manager.runWaitCallbacks(1000L);
            Log.i(TAG, "Download loop ended");
            // Safety net: never leave the UI stuck in "downloading" if the loop ended without
            // a terminal result.
            // Once started, download() owns completion, including draining cancelled workers.
            if (!finished && !started) done("Stopped before finishing — please try again.");
        } catch (Throwable t) {
            if (running) {   // a real crash, not our cancel/stop
                Log.e(TAG, "SteamGameDownloader crashed", t);
                cancel();
                if (!started) done("crash: " + t);
            } else {
                Log.i(TAG, "Callback loop stopped: " + t);
            }
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            if (refreshToken != null && !refreshToken.isEmpty()) {
                logOnWithToken();
                return;
            }
            progress("Connected. Authenticating '" + username + "'...");
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = rememberSession;
            details.deviceFriendlyName = "Zomdroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress("Approve the sign-in in your Steam Mobile app...");
            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            done("auth error: " + describe(t));
            running = false;
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName != null ? accountName : username);
        lod.setAccessToken(refreshToken);
        lod.setShouldRememberPassword(rememberSession);
        lod.setLoginID(149);
        progress("Logging in...");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        if (!running) return;
        // Before the download starts, a disconnect means the connection/auth dropped — Steam often
        // drops the first connect attempts. Five was not enough: four or five drops in a row happen
        // on an ordinary home connection, and the whole download died on a run of them (2026-09-27).
        if (!started && connectAttempts < MAX_CONNECT_ATTEMPTS) {
            connectAttempts++;
            progress("Connection dropped — retrying (" + connectAttempts + "/" + MAX_CONNECT_ATTEMPTS + ")...");
            // Backing off instead of hammering: a Steam CM that just dropped us rarely takes the
            // next connect two seconds later either. 2, 3, 4 ... seconds, capped at 10.
            long backoffMs = Math.min(10000L, 1000L + connectAttempts * 1000L);
            try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
            if (!running) return;   // cancelled while waiting to reconnect
            steamClient.connect();
            return;
        }
        if (!started) {
            done("Could not connect to Steam after " + connectAttempts
                    + " attempts. Check your connection and try again.");
        }
        running = false;
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            boolean wasTokenLogin = (refreshToken != null && (password == null || password.isEmpty()));
            if (wasTokenLogin && (cb.getResult() == EResult.Expired
                    || cb.getResult() == EResult.InvalidPassword
                    || cb.getResult() == EResult.AccountLogonDenied)) {
                if (listener != null) listener.onSessionExpired();
                done("Session expired. Please log in again.");
            } else {
                done("logOn failed: " + cb.getResult());
            }
            running = false;
            return;
        }
        if (rememberSession && refreshToken != null && !refreshToken.isEmpty()) {
            String acc = accountName != null ? accountName : username;
            if (acc != null && !acc.isEmpty() && listener != null) {
                listener.onSessionSaved(acc, refreshToken);
            }
        }
        progress("Logged on. Waiting for license list...");
    }

    private void onLicenseList(LicenseListCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("license list failed: " + cb.getResult());
            running = false;
            return;
        }
        licenseList = cb.getLicenseList();
        progress("Got " + (licenseList == null ? 0 : licenseList.size()) + " licenses.");
        if (started) return;
        started = true;
        workerThread = new Thread(this::download, "zd-game-dl");
        workerThread.start();
    }

    /** Resolved Linux depot id + manifest GID + build id from PICS. */
    private static final class DepotInfo { int depot = -1; long gid = 0L; long buildId = 0L; }

    private DepotInfo resolveDepot(String targetOs) {
        DepotInfo out = new DepotInfo();
        if (libraryPack != null && libraryPack.manifest != 0) {
            out.depot = libraryPack.depot;
            out.gid = libraryPack.manifest;
            return out;
        }
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            long appToken = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(
                                Collections.singletonList(PROJECT_ZOMBOID_APP_ID), Collections.<Integer>emptyList())
                        .toFuture().get(30, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(PROJECT_ZOMBOID_APP_ID);
                if (t != null) appToken = t;
            } catch (Throwable ignored) {}

            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs = apps.picsGetProductInfo(
                            Collections.singletonList(new PICSRequest(PROJECT_ZOMBOID_APP_ID, appToken)),
                            Collections.<PICSRequest>emptyList())
                    .toFuture().get(60, TimeUnit.SECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                PICSProductInfo app = cb.getApps().get(PROJECT_ZOMBOID_APP_ID);
                if (app == null) continue;
                KeyValue depots = app.getKeyValues().get("depots");

                // Diagnostic only: Valve reshuffles branches without notice (e.g. the 2026-07-29
                // 42.20 release), and a branch we hardcode against can silently stop covering the
                // Linux depot. Logging every branch's buildid here means the next reshuffle shows
                // up in a tester's logcat instead of us guessing from SteamDB history.
                StringBuilder branchList = new StringBuilder();
                for (KeyValue b : depots.get("branches").getChildren()) {
                    if (branchList.length() > 0) branchList.append(", ");
                    branchList.append(b.getName()).append('=').append(b.get("buildid").asLong(0L));
                }
                Log.i(TAG, "Steam branches for Project Zomboid: " + branchList);

                out.buildId = depots.get("branches").get(branch).get("buildid").asLong(0L);
                for (KeyValue depot : depots.getChildren()) {
                    int depotId;
                    try { depotId = Integer.parseInt(depot.getName()); }
                    catch (NumberFormatException e) { continue; }     // skip "branches" etc.
                    String os = depot.get("config").get("oslist").asString();
                    if (os == null || !java.util.Arrays.asList(os.split(",")).contains(targetOs)) continue;

                    // A manually pinned manifest (e.g. an old build id copied from SteamDB, for a
                    // version no branch currently points at — 42.15 and the like) needs only the
                    // depot id, found above by OS match alone. It must NOT require this branch to
                    // have its own manifest override for this depot: that requirement used to make
                    // a pinned manifest unusable whenever the selected branch itself didn't resolve
                    // (branch is still passed to the CDN request-code call further down, for
                    // authorization — just not consulted here to find the manifest gid).
                    if (manifestId > 0) {
                        out.depot = depotId;
                        out.gid = manifestId;
                        return out;
                    }

                    String gid = depot.get("manifests").get(branch).get("gid").asString();
                    if (gid == null || gid.isEmpty()) {
                        Log.i(TAG, targetOs + " depot " + depotId + " has no manifest override for branch '"
                                + branch + "'");
                        continue;
                    }
                    try {
                        out.depot = depotId;
                        out.gid = Long.parseUnsignedLong(gid);
                        return out;
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveDepot(" + targetOs + ") failed", t);
        }
        return out;
    }

    private void download() {
        try {
            String targetOs = libraryPack == null ? "linux" : libraryPack.os;
            progress("Resolving " + targetOs + " depot...");
            DepotInfo info = resolveDepot(targetOs);
            if (info.depot <= 0) { done("Could not find the " + targetOs + " depot for Project Zomboid."); running = false; return; }
            if (libraryPack != null && info.depot != libraryPack.depot)
                throw new java.io.IOException("Unexpected depot for " + libraryPack.id + ": " + info.depot);

            long gid = manifestId > 0 ? manifestId : info.gid;
            if (gid == 0L) { done("Could not resolve a manifest to download."); running = false; return; }
            long buildId = manifestId > 0 ? 0 : info.buildId;

            int appId = PROJECT_ZOMBOID_APP_ID;
            int depot = info.depot;
            progress("Depot " + depot + ", manifest " + gid + (buildId > 0 ? (", build " + buildId) : ""));

            byte[] depotKey = getDepotKey(depot, appId);
            if (depotKey == null) { done("No depot key (is the game owned on this account?)."); running = false; return; }

            File outDir = new File(getDownloadsDir(),
                    "ProjectZomboid_B" + buildLabel + "_" + (manifestId > 0
                            ? Long.toUnsignedString(manifestId) : buildId > 0 ? Long.toString(buildId) : "latest"));
            if (libraryPack != null) {
                if (!librariesGameDir.isDirectory()) throw new java.io.IOException("Instance no longer exists");
                if (libraryPack == LibraryPack.MACOS) MacosLibraries.recover(librariesGameDir);
                else MultiplayerLibraries.recover(librariesGameDir);
                outDir = new File(librariesGameDir, "." + libraryPack.id + "-download-" + depot + "-" + Long.toUnsignedString(gid));
                if (java.nio.file.Files.isSymbolicLink(outDir.toPath())
                        || !outDir.getCanonicalFile().getParentFile().equals(librariesGameDir.getCanonicalFile()))
                    throw new java.io.IOException("Unsafe library staging directory");
                if (outDir.isDirectory()) {
                    File[] children = outDir.listFiles();
                    if (children == null) throw new java.io.IOException("Cannot inspect library staging directory");
                    for (File child : children)
                        if (java.nio.file.Files.isSymbolicLink(child.toPath()))
                            throw new java.io.IOException("Symlink in library staging directory: " + child.getName());
                }
            }
            if (!outDir.exists() && !outDir.mkdirs()) {
                done("Cannot create download folder: " + outDir + " (grant All-files access?)");
                running = false; return;
            }

            SteamContent content = steamClient.getHandler(SteamContent.class);
            Client cdn = new Client(steamClient);

            List<Server> servers = awaitDeferred(
                    content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE), 30000);
            if (servers == null || servers.isEmpty()) { done("No CDN servers available."); running = false; return; }

            long requestCode = awaitDeferred(
                    content.getManifestRequestCode(depot, appId, gid, branch, null, GlobalScope.INSTANCE), 30000);

            Map<String, String> tokenCache = new HashMap<>();
            DepotManifest manifest = null;
            Exception lastErr = null;
            int serverIdx = 0;
            for (int i = 0; i < servers.size(); i++) {
                Server s = servers.get(i);
                try {
                    manifest = cdn.downloadManifestFuture(depot, gid, requestCode, s, depotKey, null,
                            cdnTokenFor(content, appId, depot, s, tokenCache)).get(120, TimeUnit.SECONDS);
                    serverIdx = i;
                    break;
                } catch (Exception e) {
                    lastErr = e;
                    Log.w(TAG, "manifest via " + s.getHost() + " failed: " + describe(e));
                }
            }
            if (manifest == null) { done("Manifest download failed: " + describe(lastErr)); running = false; return; }

            List<FileData> files = manifest.getFiles();
            if (libraryPack != null) {
                Map<String, FileData> selected = new java.util.LinkedHashMap<>();
                for (FileData f : files) {
                    String name = outputRelative(f);
                    if (name == null || f.getFlags().contains(EDepotFileFlag.Directory)) continue;
                    if (selected.put(name, f) != null) throw new java.io.IOException("Ambiguous depot basename: " + name);
                }
                if (!selected.keySet().equals(libraryPack.names))
                    throw new java.io.IOException("Missing " + libraryPack.id + " libraries; found " + selected.keySet());
                files = new java.util.ArrayList<>(selected.values());
            }
            long totalBytes = 0;
            for (FileData f : files) {
                if (!f.getFlags().contains(EDepotFileFlag.Directory)) totalBytes += f.getTotalSize();
            }
            progress("Manifest OK: " + files.size() + " files, " + (totalBytes / (1024 * 1024)) + " MB. Downloading...");

            // Resume: skip files already fully downloaded in a previous run of this same build.
            File doneListFile = new File(outDir, ".zomdroid_complete_" + depot + "_" + Long.toUnsignedString(gid));
            java.util.Set<String> doneSet = loadDoneSet(doneListFile);
            if (!doneSet.isEmpty()) progress("Resuming — " + doneSet.size() + " files already downloaded.");

            final int workers = Math.max(2, Math.min(12, maxConnections));
            final AtomicLong doneBytes = new AtomicLong(0);
            final AtomicInteger serverCursor = new AtomicInteger(serverIdx);
            final AtomicLong lastEmit = new AtomicLong(0);
            final AtomicReference<Throwable> firstError = new AtomicReference<>();
            final AtomicLong lastSpeedBytes = new AtomicLong(0);
            final AtomicLong lastSpeedTime = new AtomicLong(System.currentTimeMillis());
            final AtomicLong currentSpeed = new AtomicLong(0);

            final List<FileData> pending = new java.util.ArrayList<>();
            long verifyEmit = 0;
            for (FileData f : files) {
                if (!running) { done("Download cancelled."); return; }
                String rel = outputRelative(f);
                if (rel == null) continue;
                File outFile = new File(outDir, rel);
                if (f.getFlags().contains(EDepotFileFlag.Directory)) { outFile.mkdirs(); continue; }

                boolean alreadyDone = doneSet.contains(rel) && outFile.isFile() && outFile.length() == f.getTotalSize();
                if (alreadyDone) {
                    if (verifyFiles) {
                        long now = System.currentTimeMillis();
                        if (now - verifyEmit > 300) {
                            verifyEmit = now;
                            int vPct = totalBytes > 0 ? (int) (doneBytes.get() * 100 / totalBytes) : 0;
                            if (listener != null) listener.onFileProgress(rel, 0, doneBytes.get(), totalBytes, vPct);
                        }
                        try {
                            if (!matchesManifestHash(outFile, f)) {
                                pending.add(f);
                                continue;
                            }
                        } catch (Exception ex) {
                            pending.add(f);
                            continue;
                        }
                    }
                    doneBytes.addAndGet(f.getTotalSize());
                    continue;
                }
                pending.add(f);
            }

            final long fTotalBytes = totalBytes;
            final int fDepot = depot;
            final byte[] fDepotKey = depotKey;
            final List<Server> fServers = servers;
            final Client fCdn = cdn;
            final SteamContent fContent = content;
            final Map<String, String> fTokenCache = tokenCache;
            final File fOutDir = outDir;
            final File fDoneListFile = doneListFile;

            final int maxActiveFiles = Math.max(workers * 2, 8);
            final Semaphore fileSlots = new Semaphore(maxActiveFiles);
            final BlockingQueue<ChunkTask> chunkQueue =
                    new LinkedBlockingQueue<>(Math.max(64, workers * 8));
            final CountDownLatch latch = new CountDownLatch(workers);
            final List<Thread> pool = new java.util.ArrayList<>(workers);
            final java.util.Set<ActiveFile> openFiles =
                    Collections.newSetFromMap(new ConcurrentHashMap<>());

            for (int w = 0; w < workers; w++) {
                Thread t = new Thread(() -> {
                    try {
                        while (running && firstError.get() == null) {
                            ChunkTask task;
                            try {
                                task = chunkQueue.poll(200, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ie) {
                                break;
                            }
                            if (task == null) continue;
                            if (task == POISON_PILL) {
                                chunkQueue.offer(POISON_PILL);
                                break;
                            }

                            try {
                                downloadAndWriteChunk(task, fCdn, fContent, fServers, fDepot, fDepotKey,
                                        fTokenCache, serverCursor, doneBytes, fTotalBytes,
                                        lastSpeedBytes, lastSpeedTime, currentSpeed, lastEmit,
                                        fDoneListFile, fileSlots, openFiles);
                            } catch (Throwable err) {
                                firstError.compareAndSet(null, err);
                                break;
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }, "zd-game-dl-" + w);
                t.setDaemon(true);
                pool.add(t);
                t.start();
            }

            // Enqueue chunks from pending files
            for (FileData f : pending) {
                if (!running || firstError.get() != null) break;
                String rel = outputRelative(f);
                if (rel == null) continue;
                File outFile = new File(fOutDir, rel);

                if (f.getChunks().isEmpty() || f.getTotalSize() == 0) {
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    if (!outFile.exists()) {
                        try { outFile.createNewFile(); } catch (Exception ignored) {}
                    }
                    appendDone(fDoneListFile, rel);
                    continue;
                }

                while (running && firstError.get() == null) {
                    if (fileSlots.tryAcquire(200, TimeUnit.MILLISECONDS)) break;
                }
                if (!running || firstError.get() != null) break;

                ActiveFile af;
                try {
                    af = new ActiveFile(f, rel, outFile);
                    openFiles.add(af);
                } catch (Exception ex) {
                    fileSlots.release();
                    firstError.compareAndSet(null, ex);
                    break;
                }

                for (ChunkData chunk : f.getChunks()) {
                    ChunkTask task = new ChunkTask(af, chunk);
                    while (running && firstError.get() == null) {
                        if (chunkQueue.offer(task, 200, TimeUnit.MILLISECONDS)) break;
                    }
                    if (!running || firstError.get() != null) break;
                }
            }

            if (running && firstError.get() == null) {
                chunkQueue.offer(POISON_PILL);
            }

            try {
                latch.await();
            } catch (InterruptedException cancelled) {
                // cancel() interrupts the download thread; the workers see running=false and unwind
                // on their own. Interrupt them too so anyone parked in a backoff sleep leaves now
                // instead of after its remaining 10 seconds.
                running = false;
                for (Thread t : pool) t.interrupt();
                // Do not release the download UI/resume directory while workers still write it.
                boolean drained = false;
                while (!drained) {
                    try { latch.await(); drained = true; }
                    catch (InterruptedException ignored) { /* cancellation is already recorded */ }
                }
                done("Download cancelled.");
                return;
            } finally {
                for (ActiveFile af : openFiles) {
                    af.close();
                }
                openFiles.clear();
            }

            if (!running) { done("Aborted."); return; }
            Throwable workerError = firstError.get();
            if (workerError != null) throw workerError;

            org.json.JSONObject metadata = new org.json.JSONObject();
            // A manually pinned GID is not an app build ID.
            metadata.put("appBuildId", manifestId > 0 ? 0 : info.buildId);
            metadata.put("depotId", depot);
            metadata.put("manifestGid", Long.toUnsignedString(gid));
            metadata.put("branch", branch);
            if (libraryPack != null) {
                org.json.JSONObject entries = new org.json.JSONObject();
                for (FileData f : files) {
                    File file = new File(fOutDir, outputRelative(f));
                    if (file.length() != f.getTotalSize() || !matchesManifestHash(file, f))
                        throw new java.io.IOException("Verification failed: " + file.getName());
                    if (libraryPack == LibraryPack.B41_MULTIPLAYER) MultiplayerLibraries.verifyArm64(file);
                    org.json.JSONObject entry = new org.json.JSONObject();
                    entry.put("size", file.length());
                    entry.put("sha256", MacosLibraries.hash(file, "SHA-256"));
                    entries.put(file.getName(), entry);
                }
                metadata.put("files", entries);
                metadata.put("pack", libraryPack.id);
                java.nio.file.Files.write(new File(fOutDir, libraryPack.metadataName).toPath(), metadata.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!running) { done("Download cancelled."); return; }
                if (libraryPack == LibraryPack.MACOS) {
                    MacosLibraries.publish(librariesGameDir, fOutDir);
                    // A download is the player's request to use what it installed (no switch above
                    // the libraries since 2026-09-16).
                    if (librariesInstanceName != null)
                        new com.zomdroid.game.InstanceSettings(librariesInstanceName)
                                .enableMacosModules(MacosLibraries.installed(librariesGameDir));
                } else {
                    MultiplayerLibraries.publish(librariesGameDir, fOutDir);
                }
                done(libraryPack.id + " libraries installed, manifest " + Long.toUnsignedString(gid));
            } else {
                java.nio.file.Files.write(new File(fOutDir, MacosLibraries.LINUX_METADATA).toPath(), metadata.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                done("Game downloaded to " + fOutDir.getAbsolutePath());
            }
        } catch (Throwable t) {
            if (!running) {
                done("Download cancelled.");
            } else {
                Log.e(TAG, "download crashed", t);
                done("download error: " + describe(t));
            }
        } finally {
            running = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    private byte[] getDepotKey(int depot, int appId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            DepotKeyCallback dk = apps.getDepotDecryptionKey(depot, appId).toFuture().get(30, TimeUnit.SECONDS);
            if (dk.getResult() == EResult.OK && dk.getDepotKey() != null && dk.getDepotKey().length == 32) {
                return dk.getDepotKey();
            }
            Log.w(TAG, "depot key result=" + dk.getResult());
        } catch (Throwable t) {
            Log.e(TAG, "getDepotKey failed", t);
        }
        return null;
    }

    private static final ChunkTask POISON_PILL = new ChunkTask(null, null);

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

    private void downloadAndWriteChunk(ChunkTask task, Client cdn, SteamContent content,
                                       List<Server> servers, int depot, byte[] depotKey,
                                       Map<String, String> tokenCache,
                                       AtomicInteger serverCursor,
                                       AtomicLong doneBytes, long totalBytes,
                                       AtomicLong lastSpeedBytes,
                                       AtomicLong lastSpeedTime,
                                       AtomicLong currentSpeed,
                                       AtomicLong lastEmit,
                                       File doneListFile,
                                       Semaphore fileSlots,
                                       java.util.Set<ActiveFile> openFiles) throws Exception {
        ActiveFile af = task.activeFile;
        ChunkData chunk = task.chunk;
        byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
        int written = -1;
        Exception chunkErr = null;
        final int maxTries = 30;

        for (int t = 0; t < maxTries && written < 0; t++) {
            if (!running) return;
            Server s = servers.get(Math.floorMod(serverCursor.getAndIncrement(), servers.size()));
            try {
                written = cdn.downloadDepotChunkFuture(depot, chunk, s, dest, depotKey, null,
                        cdnTokenFor(content, PROJECT_ZOMBOID_APP_ID, depot, s, tokenCache))
                        .get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                chunkErr = e;
                Log.w(TAG, "chunk " + af.relPath + " via " + s.getHost() + " failed (try " + (t + 1) + "/" + maxTries + "): " + describe(e));
                long now = System.currentTimeMillis();
                long prev = lastEmit.get();
                if (now - prev > 1500 && lastEmit.compareAndSet(prev, now) && listener != null) {
                    listener.onProgress("Steam CDN busy — retrying… ("
                            + (doneBytes.get() / (1024 * 1024)) + " / " + (totalBytes / (1024 * 1024)) + " MB)");
                }
                long backoff = Math.min(10000L, 500L * (1L << Math.min(t, 4))); // 0.5..10s
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) { return; }
            }
        }
        if (written < 0) throw chunkErr != null ? chunkErr : new IOException("chunk download failed for " + af.relPath);

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
        if (diffTime >= 1000L) {
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
            if (running) {
                if (libraryPack != null && !matchesManifestHash(af.outFile, af.fileData))
                    throw new IOException("File hash mismatch: " + af.relPath);
                appendDone(doneListFile, af.relPath);
                if (listener != null) {
                    listener.onFileProgress(af.relPath, currentSpeed.get(), doneBytes.get(), totalBytes, pct);
                }
            }
        }
    }

    private static boolean matchesManifestHash(File file, FileData data) throws Exception {
        StringBuilder expected = new StringBuilder();
        for (byte b : data.getFileHash()) expected.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return expected.toString().equals(MacosLibraries.hash(file, "SHA-1"));
    }

    private String cdnTokenFor(SteamContent content, int appId, int depot, Server s, Map<String, String> cache) {
        String host = s.getHost() != null ? s.getHost() : s.getVHost();
        if (host == null) return null;
        // Guarded rather than a ConcurrentHashMap because a null token is a real cached answer and
        // that map forbids null values. Holding the lock across the fetch is deliberate: the workers
        // all want the same host's token at the same moment, and one request beats six identical ones.
        synchronized (cache) {
            if (cache.containsKey(host)) return cache.get(host);
            String token = null;
            try {
                CDNAuthToken tok = awaitDeferred(content.getCDNAuthToken(appId, depot, host, GlobalScope.INSTANCE), 15000);
                if (tok != null && tok.getResult() == EResult.OK) token = tok.getToken();
            } catch (Throwable ignored) {}
            cache.put(host, token);
            return token;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitDeferred(Deferred<T> d, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!d.isCompleted()) {
            if (System.currentTimeMillis() > deadline) throw new TimeoutException("deferred timed out");
            Thread.sleep(40);
        }
        return (T) d.getCompleted();
    }

    /** Reads the set of already-completed relative file paths from the resume marker. */
    private static java.util.Set<String> loadDoneSet(File f) {
        java.util.Set<String> s = new java.util.HashSet<>();
        if (f == null || !f.isFile()) return s;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) s.add(line);
            }
        } catch (Exception ignored) {}
        return s;
    }

    /** Appends one completed relative path to the resume marker (flushed immediately). */
    // synchronized: several download workers finish files at once and all append to this one list.
    private static synchronized void appendDone(File f, String rel) {
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(rel);
            w.write('\n');
        } catch (Exception ignored) {}
    }

    private static String sanitizeRel(String name) {
        if (name == null) return null;
        String rel = name.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || rel.contains("../")) return null;
        return rel;
    }

    private static String describe(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("  <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        return sb.toString();
    }

    /** Approves via Steam Mobile push when possible; otherwise asks the UI for a Steam Guard code. */
    private class PushAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, null)
                    : CompletableFuture.completedFuture("");
        }
        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, email)
                    : CompletableFuture.completedFuture("");
        }
    }
}
