package com.local.ytdown;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.File;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks whether completed output files already exist in Download/YTDown (both direct filesystem
 * and MediaStore Downloads on API 29+). Never treats empty or incomplete (.part/.ytdl/is_pending)
 * files as already downloaded.
 */
final class ExistingDownloadLookup {
    private static final String RELATIVE_PATH_SLASH = "Download/YTDown/";
    private static final String RELATIVE_PATH_PLAIN = "Download/YTDown";
    private static final Pattern X_CANONICAL = Pattern.compile("^https://x\\.com/i/status/(\\d+)$");
    private static final Pattern IG_CANONICAL = Pattern.compile("^https://instagram\\.com/p/([A-Za-z0-9_-]+)$");

    private ExistingDownloadLookup() {
    }

    /** True when yt-dlp output reports that a file was skipped because it already exists. */
    static boolean isAlreadyDownloadedOutput(String line) {
        if (line == null || line.isEmpty()) return false;
        String lower = line.toLowerCase(Locale.US);
        return lower.contains("already been downloaded")
                || lower.contains("already been recorded in the archive");
    }

    /**
     * Extracts a deterministic single-item media ID when the URL maps to one media file
     * produced by yt-dlp ([id] in filename). Returns null for playlists and ambiguous URLs.
     */
    static String mediaIdForUrl(String url, boolean audioOnly, boolean allowSocialVideo) {
        if (url == null || url.isEmpty() || DownloadedMediaHistory.isCollectionUrl(url)) {
            return null;
        }
        String canonical = DownloadedMediaHistory.normalizeUrl(url);
        if (canonical.startsWith("https://youtube.com/watch?v=")) {
            String id = canonical.substring("https://youtube.com/watch?v=".length());
            int amp = id.indexOf('&');
            if (amp >= 0) id = id.substring(0, amp);
            return id.matches("[A-Za-z0-9_-]{6,}") ? id : null;
        }
        if (canonical.startsWith("https://pornhub.com/view_video.php?")) {
            try {
                String viewkey = DownloadedMediaHistory.queryParam(URI.create(canonical).getRawQuery(), "viewkey");
                return viewkey != null && viewkey.matches("[A-Za-z0-9_-]+") ? viewkey : null;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (audioOnly || allowSocialVideo) {
            Matcher x = X_CANONICAL.matcher(canonical);
            if (x.matches()) return x.group(1);
            Matcher ig = IG_CANONICAL.matcher(canonical);
            if (ig.matches()) return ig.group(1);
        }
        return null;
    }

    /** Matches a completed yt-dlp output filename containing [mediaId] for the requested mode. */
    static boolean matchesExistingMediaFile(String fileName, String mediaId, boolean audioOnly) {
        if (fileName == null || fileName.isEmpty() || mediaId == null || mediaId.isEmpty()) {
            return false;
        }
        if (IncompleteFiles.partial(fileName)) return false;
        if (!fileName.contains("[" + mediaId + "]")) return false;
        String lower = fileName.toLowerCase(Locale.US);
        if (audioOnly) {
            return lower.endsWith("-audio.m4a");
        }
        return DownloadArtifactTracker.isVideoFileName(fileName) && !lower.endsWith("-audio.m4a");
    }

    /** Pure filesystem scan for a completed file matching [mediaId] and download mode. */
    static String findInDirectory(File directory, String mediaId, boolean audioOnly) {
        if (directory == null || mediaId == null || !directory.isDirectory()) return null;
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.length() > 0
                    && matchesExistingMediaFile(file.getName(), mediaId, audioOnly)) {
                return file.getName();
            }
        }
        return null;
    }

    /** True when a non-empty, completed file with this exact name already exists in Download/YTDown. */
    static boolean fileExists(Context context, File outputDirectory, String fileName) {
        if (fileName == null || fileName.isEmpty() || IncompleteFiles.partial(fileName)) {
            return false;
        }
        if (outputDirectory != null) {
            File direct = new File(outputDirectory, fileName);
            if (direct.isFile() && direct.length() > 0) {
                return true;
            }
        }
        if (context == null || Build.VERSION.SDK_INT < 29) {
            return false;
        }
        try {
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String[] columns = {MediaStore.MediaColumns._ID};
            String where = MediaStore.MediaColumns.IS_PENDING + "=0 AND "
                    + MediaStore.MediaColumns.SIZE + ">0 AND "
                    + MediaStore.MediaColumns.DISPLAY_NAME + "=? AND ("
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=? OR "
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=?)";
            String[] args = {fileName, RELATIVE_PATH_SLASH, RELATIVE_PATH_PLAIN};
            try (Cursor cursor = context.getContentResolver().query(collection, columns, where, args, null)) {
                return cursor != null && cursor.moveToFirst();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Checks both direct filesystem and MediaStore Downloads for an already-completed file
     * matching this URL and download mode. Returns the existing filename or null.
     */
    static String findExistingMediaForUrl(Context context, File outputDirectory,
                                          String url, boolean audioOnly,
                                          boolean allowSocialVideo) {
        String mediaId = mediaIdForUrl(url, audioOnly, allowSocialVideo);
        if (mediaId == null) return null;
        String directMatch = findInDirectory(outputDirectory, mediaId, audioOnly);
        if (directMatch != null) return directMatch;
        if (context == null || Build.VERSION.SDK_INT < 29) return null;
        try {
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String[] columns = {MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE};
            String where = MediaStore.MediaColumns.IS_PENDING + "=0 AND "
                    + MediaStore.MediaColumns.SIZE + ">0 AND ("
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=? OR "
                    + MediaStore.MediaColumns.RELATIVE_PATH + "=?) AND "
                    + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] args = {RELATIVE_PATH_SLASH, RELATIVE_PATH_PLAIN, "%[" + mediaId + "]%"};
            try (Cursor cursor = context.getContentResolver().query(collection, columns, where, args, null)) {
                if (cursor == null) return null;
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    long size = cursor.getLong(1);
                    if (size > 0 && matchesExistingMediaFile(name, mediaId, audioOnly)) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to normal download if MediaStore query is unavailable.
        }
        return null;
    }
}
