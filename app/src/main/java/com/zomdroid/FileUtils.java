package com.zomdroid;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.zip.CRC32;

public class FileUtils {

    static void extractTarXzToDisk(@NonNull InputStream inStream, @NonNull String destPath,
                                   TaskProgressListener taskProgressListener, long tarXzSize) throws IOException {
        XZCompressorInputStream xzCompressorInStream = new XZCompressorInputStream(inStream);
        extractTarToDisk(xzCompressorInStream, destPath, taskProgressListener, tarXzSize);
/*        TarArchiveInputStream tarArchiveInStream = new TarArchiveInputStream(xzCompressorInStream);
        TarArchiveEntry entry;
        while ((entry = tarArchiveInStream.getNextEntry()) != null) {
            extractArchiveEntry(tarArchiveInStream, entry, destPath);
        }*/
    }

    static void extractTarToDisk(@NonNull InputStream inStream, @NonNull String destPath,
                                 TaskProgressListener taskProgressListener, long tarSize) throws IOException {
        TarArchiveInputStream tarArchiveInStream = new TarArchiveInputStream(new BufferedInputStream(inStream, 1024 * 1024));
        TarArchiveEntry entry;
        while ((entry = tarArchiveInStream.getNextEntry()) != null) {
            extractArchiveEntry(tarArchiveInStream, entry, destPath);
            if (taskProgressListener != null) {
                int progress = -1;
                if (tarSize > 0)
                    progress = (int) ((tarArchiveInStream.getBytesRead() / (float) tarSize) * 100);
                taskProgressListener.onProgressUpdate(null, progress, 100);
            }
        }
    }

    static void extractZipToDisk(@NonNull InputStream inStream, @NonNull String destPath,
                                 TaskProgressListener taskProgressListener, long zipSize) throws IOException {
        // allowStoredEntriesWithDataDescriptor: some mod sites (ggntw.com among them) pack files
        // uncompressed but with the "size follows the data" flag. That is legal ZIP, yet the
        // default refuses it and java.util.zip.ZipInputStream cannot read it at all ("only
        // DEFLATED entries can have EXT descriptor"): such a mod unpacked to nothing and was
        // reported as "no mod.info found" (2026-09-29).
        ZipArchiveInputStream zipArchiveInStream = new ZipArchiveInputStream(
                new BufferedInputStream(inStream, 1024 * 1024), "UTF-8", true, true);
        ZipArchiveEntry entry;
        while ((entry = zipArchiveInStream.getNextEntry()) != null) {
            extractArchiveEntry(zipArchiveInStream, entry, destPath);
            if (taskProgressListener != null) {
                int progress = -1;
                if (zipSize > 0)
                    progress = (int) ((zipArchiveInStream.getBytesRead() / (float) zipSize) * 100);
                taskProgressListener.onProgressUpdate(null, progress, 100);
            }
        }
    }

    static void extractArchiveEntry(ArchiveInputStream<?> archiveInStream, ArchiveEntry archiveEntry, String destPath) throws IOException {
        if (!archiveInStream.canReadEntryData(archiveEntry)) {
            throw new RuntimeException("Failed to read archive entry");
        }
        File file = new File(destPath + "/" + archiveEntry.getName());
        // An entry named "../x" or with an absolute path would land outside destPath.
        String root = new File(destPath).getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(root) && !file.getCanonicalPath().equals(root.substring(0, root.length() - 1))) {
            throw new IOException("Archive entry outside the destination: " + archiveEntry.getName());
        }
        if (archiveEntry.isDirectory()) {
            if (!file.isDirectory() && !file.mkdirs()) {
                throw new IOException("Failed to create directory " + file);
            }
        } else {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Failed to create directory " + parent);
            }
            long written;
            try (OutputStream fileOutStream = new BufferedOutputStream(new FileOutputStream(file), 1024 * 1024)) {
                // copyLarge, not copy: copy() returns an int and reports -1 once the count passes
                // Integer.MAX_VALUE, which would read as a truncation on a multi-GB entry.
                written = IOUtils.copyLarge(archiveInStream, fileOutStream);
            }
            // An extraction cut short - storage filled up, the process killed - used to leave a
            // shorter file behind and the instance still looked installed. The damage only
            // surfaces much later and far from its cause: a truncated native library fails the
            // Android linker's own bounds check at load time ("invalid shdr offset/size"), which
            // is how one player's game stopped starting on 1.4.8. Fail here instead, where the
            // cause is still visible. Some streamed archives do not state an entry size, so only
            // check when the archive gives one.
            long expectedSize = archiveEntry.getSize();
            if (expectedSize >= 0 && written != expectedSize) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                throw new IOException("Truncated extraction of " + archiveEntry.getName()
                        + ": wrote " + written + " of " + expectedSize + " bytes");
            }
        }
    }

    public static long queryFileSize(ContentResolver contentResolver, Uri uri) {
        try (Cursor cursor = contentResolver.query(uri, null, null, null, null)) {
            if (cursor == null)
                throw new RuntimeException("Cursor from content resolver query is null");
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (sizeIndex == -1)
                throw new RuntimeException("Size column doesn't exist in cursor from content resolver query");
            cursor.moveToFirst();
            return cursor.getLong(sizeIndex);
        }
    }

    public static void copyAssetsToDisk(@NotNull Context context, @NotNull String assetPath, @NotNull String destinationPath) throws IOException {
        String[] assets = context.getAssets().list(assetPath);
        if (assets == null || assets.length == 0) {
            try (InputStream inputStream = context.getAssets().open(assetPath)) {
                File outFile = new File(destinationPath);
                try (FileOutputStream outStream = new FileOutputStream(outFile)) {
                    IOUtils.copy(inputStream, outStream);
                }
            }
            return;
        }

        File destinationDir = new File(destinationPath);
        if (!destinationDir.exists() && !destinationDir.mkdirs())
            throw new IOException("Failed to create directory " + destinationDir.getAbsolutePath());

        for (String asset : assets) {
            copyAssetsToDisk(context, assetPath + "/" + asset, destinationPath + "/" + asset);
        }
    }

    /**
     * True for a directory we may recurse into. A symbolic link to a directory answers
     * {@code isDirectory()} with true, so every recursive walk has to ask this instead - following
     * one means leaving the tree we were asked to work on, and a link that points at an ancestor
     * turns the walk into an endless loop.
     */
    public static boolean isWalkableDirectory(File file) {
        return file.isDirectory() && !Files.isSymbolicLink(file.toPath());
    }

    public static boolean deleteDirectory(File directory) {
        // The argument itself can be a link - listFiles() would then hand us the contents of
        // whatever it points at. Drop the link and leave the target alone.
        if (Files.isSymbolicLink(directory.toPath())) return directory.delete();
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    // A symlink is deleted as a plain entry: recursing would wipe whatever it
                    // points at, which lives outside the directory we were told to remove.
                    if (isWalkableDirectory(file)) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        return directory.delete();
    }

    public static long generateCRC32ForAsset(@NonNull Context context, @NonNull String assetPath) throws IOException {
        CRC32 crc32 = new CRC32();
        try (InputStream inputStream = new BufferedInputStream(context.getAssets().open(assetPath))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                crc32.update(buffer, 0, bytesRead);
            }
        }
        return crc32.getValue();
    }

    public static boolean isValidFilenameStrict(String filename) {
        if (filename == null || filename.trim().isEmpty()) return false;

        // disallow / \ ? % * : | " < >
        String pattern = "^[^\\\\/:*?\"<>|%]+$";

        if (!filename.matches(pattern)) return false;

        if (filename.startsWith(".")) return false;

        return filename.length() <= 40;
    }

    // Files/dirs that mark the real Project Zomboid install root, across all builds:
    // desktop launcher script + its manifest, the 42.12+ fat jar, and the classes dir.
    public static final String[] GAME_ROOT_MARKERS = {
            "ProjectZomboid64.json", "ProjectZomboid64", "projectzomboid.jar", "zombie"
    };

    /** True if dir DIRECTLY contains any PZ root marker. */
    public static boolean isGameRoot(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            for (String marker : GAME_ROOT_MARKERS) {
                if (f.getName().equalsIgnoreCase(marker)) return true;
            }
        }
        return false;
    }

    /** Recursively locate the game root (this dir or a descendant). null if none found. */
    public static File findGameRoot(File dir) {
        if (isGameRoot(dir)) return dir;
        File[] children = dir.listFiles(FileUtils::isWalkableDirectory);
        if (children == null) return null;
        for (File child : children) {
            File found = findGameRoot(child);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Resolves a Uri (tree, document, or file) to a local File if accessible.
     */
    @Nullable
    public static File getFileFromUri(Context context, Uri uri) {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            return path != null ? new File(path) : null;
        }
        try {
            if (DocumentsContract.isTreeUri(uri)) {
                String docId = DocumentsContract.getTreeDocumentId(uri);
                return getFileFromDocumentId(context, docId);
            } else if (DocumentsContract.isDocumentUri(context, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                return getFileFromDocumentId(context, docId);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private static File getFileFromDocumentId(Context context, String docId) {
        if (docId == null) return null;
        if (docId.startsWith("raw:")) {
            return new File(docId.substring(4));
        }
        String[] parts = docId.split(":", 2);
        String type = parts[0];
        String relPath = parts.length > 1 ? parts[1] : "";

        if ("primary".equalsIgnoreCase(type)) {
            return new File(Environment.getExternalStorageDirectory(), relPath);
        }
        File candidate = new File("/storage/" + type, relPath);
        if (candidate.exists()) return candidate;

        if (context != null) {
            File[] dirs = context.getExternalFilesDirs(null);
            if (dirs != null) {
                for (File d : dirs) {
                    if (d != null) {
                        String p = d.getAbsolutePath();
                        int idx = p.indexOf("/Android/");
                        if (idx > 0) {
                            File root = new File(p.substring(0, idx));
                            if (root.getName().equalsIgnoreCase(type)) {
                                File f = new File(root, relPath);
                                if (f.exists()) return f;
                            }
                        }
                    }
                }
            }
        }
        return candidate;
    }
}