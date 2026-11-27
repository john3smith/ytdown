package com.local.ytdown;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Private record of media links whose download finished with verified output. Single links,
 * queued links, and account rescans use it to skip media saved earlier; failed or cancelled
 * links are never recorded and remain retryable. Keys are SHA-256 digests of the canonical
 * link and download mode, values are save times.
 */
final class DownloadedMediaHistory {
    private static final String PREFERENCES = "downloaded_media_history";
    private static final int MAX_ENTRIES = 20000;
    private static final int PRUNE_TO = 18000;
    private static final Pattern X_STATUS_PATH = Pattern.compile(
            "^/(?:[^/]+|i/web)/status/(\\d+)(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTAGRAM_MEDIA_PATH = Pattern.compile(
            "^/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)(?:/.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YOUTUBE_PATH_ID = Pattern.compile(
            "^/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{6,})(?:/.*)?$",
            Pattern.CASE_INSENSITIVE);

    private DownloadedMediaHistory() {
    }

    static boolean contains(Context context, String url, String format, boolean audioOnly) {
        if (isCollectionUrl(url)) return false;
        SharedPreferences preferences = preferences(context);
        if (preferences.contains(key(url, format, audioOnly))) return true;
        // Preserve history entries written by v1.9.44–v1.9.45 under the legacy key layout.
        return preferences.contains(legacyKey(url, format, audioOnly));
    }

    static void record(Context context, String url, String format, boolean audioOnly) {
        if (isCollectionUrl(url)) return;
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(key(url, format, audioOnly), System.currentTimeMillis());
        Map<String, ?> all = preferences.getAll();
        if (all.size() >= MAX_ENTRIES) {
            // Forget the oldest links so the private file stays bounded.
            List<Map.Entry<String, ?>> entries = new ArrayList<>(all.entrySet());
            entries.sort((left, right) -> Long.compare(time(left.getValue()), time(right.getValue())));
            for (int index = 0; index < entries.size() - PRUNE_TO; index++) {
                editor.remove(entries.get(index).getKey());
            }
        }
        editor.apply();
    }

    /**
     * Multi-item playlist links can grow over time; never block the whole collection link
     * in history so individual items are deduplicated via yt-dlp's download archive instead.
     */
    static boolean isCollectionUrl(String value) {
        String normalized = normalizeUrl(value);
        return normalized.startsWith("https://youtube.com/playlist?list=")
                || (normalized.startsWith("https://youtube.com/watch?")
                && normalized.contains("&list="));
    }

    /** Same post or video reached through different share forms must map to one history entry. */
    static String normalizeUrl(String value) {
        String normalized = DownloadUrlPolicy.normalize(value);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException error) {
            return normalized;
        }
        if (uri.getHost() == null) return normalized;
        String host = stripHostPrefix(uri.getHost().toLowerCase(Locale.US));
        if (host.equals("twitter.com")) host = "x.com";
        if (host.equals("instagr.am")) host = "instagram.com";
        if (host.equals("youtube-nocookie.com")) host = "youtube.com";
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        if (host.equals("youtube.com") || host.equals("youtu.be")) {
            String youtubeCanonical = canonicalYouTubeUrl(host, path, uri.getRawQuery());
            if (youtubeCanonical != null) return youtubeCanonical;
        }
        if (host.equals("x.com")) {
            Matcher status = X_STATUS_PATH.matcher(path);
            if (status.matches()) {
                return "https://x.com/i/status/" + status.group(1);
            }
            return "https://x.com" + path.toLowerCase(Locale.US);
        }
        if (host.equals("instagram.com")) {
            Matcher post = INSTAGRAM_MEDIA_PATH.matcher(path);
            if (post.matches()) {
                return "https://instagram.com/p/" + post.group(1);
            }
            return "https://instagram.com" + path.toLowerCase(Locale.US);
        }
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return "https://" + host + path + query;
    }

    /** Exact v1.9.44–v1.9.45 normalization kept for read-only backward compatibility. */
    static String legacyNormalizeUrl(String value) {
        String normalized = DownloadUrlPolicy.normalize(value);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException error) {
            return normalized;
        }
        if (uri.getHost() == null) return normalized;
        String host = uri.getHost().toLowerCase(Locale.US);
        if (host.startsWith("www.")) host = host.substring(4);
        if (host.startsWith("mobile.")) host = host.substring(7);
        if (host.startsWith("m.")) host = host.substring(2);
        if (host.equals("twitter.com")) host = "x.com";
        if (host.equals("instagr.am")) host = "instagram.com";
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        boolean social = host.equals("x.com") || host.equals("instagram.com");
        if (host.equals("x.com")) {
            path = path.replaceFirst("(/status/\\d+)/(photo|video)/\\d+/?$", "$1");
        }
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String query = social || uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return "https://" + host + path + query;
    }

    static String key(String url, String format, boolean audioOnly) {
        String canonical = normalizeUrl(url);
        String mode;
        if (audioOnly) {
            mode = "audio";
        } else if (canonical.startsWith("https://pornhub.com/")) {
            // Stream downloads include format_id in the saved filename so quality choices stay distinct.
            mode = "media\n" + (format == null ? "" : format);
        } else {
            // Social posts and YouTube outputs do not embed quality into filenames.
            mode = "media";
        }
        return sha256(canonical + "\n" + mode);
    }

    static String legacyKey(String url, String format, boolean audioOnly) {
        String mode = (audioOnly ? "audio" : "media") + "\n" + (format == null ? "" : format);
        return sha256(legacyNormalizeUrl(url) + "\n" + mode);
    }

    private static String stripHostPrefix(String host) {
        String current = host;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : new String[]{"www.", "mobile.", "m.", "music."}) {
                if (current.startsWith(prefix)) {
                    current = current.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return current;
    }

    private static String canonicalYouTubeUrl(String host, String path, String rawQuery) {
        String videoId = null;
        if (host.equals("youtu.be")) {
            String candidate = path.startsWith("/") ? path.substring(1) : path;
            int slash = candidate.indexOf('/');
            if (slash >= 0) candidate = candidate.substring(0, slash);
            if (candidate.matches("[A-Za-z0-9_-]{6,}")) videoId = candidate;
        } else {
            Matcher matcher = YOUTUBE_PATH_ID.matcher(path);
            if (matcher.matches()) {
                videoId = matcher.group(1);
            } else if ("/watch".equalsIgnoreCase(path)) {
                videoId = queryParam(rawQuery, "v");
            }
        }
        String listId = queryParam(rawQuery, "list");
        if (videoId != null && !videoId.matches("[A-Za-z0-9_-]{6,}")) videoId = null;
        if (listId != null && !listId.matches("[A-Za-z0-9_-]{2,}")) listId = null;
        if (videoId != null && listId != null) {
            return "https://youtube.com/watch?v=" + videoId + "&list=" + listId;
        }
        if (videoId != null) {
            return "https://youtube.com/watch?v=" + videoId;
        }
        if (listId != null) {
            return "https://youtube.com/playlist?list=" + listId;
        }
        return null;
    }

    static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            if (!name.equals(key)) continue;
            String rawValue = pair.substring(eq + 1);
            if (rawValue.isEmpty()) return null;
            try {
                return URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
                return rawValue;
            }
        }
        return null;
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) hex.append(String.format(Locale.US, "%02x", part));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static long time(Object value) {
        return value instanceof Long ? (Long) value : 0L;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
