package com.local.ytdown;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Private record of media links whose download finished with verified output. Account rescans
 * use it to skip media saved earlier; failed or cancelled links are never recorded and retry.
 * Keys are SHA-256 digests of the normalized link and download mode, values are save times.
 */
final class DownloadedMediaHistory {
    private static final String PREFERENCES = "downloaded_media_history";
    private static final int MAX_ENTRIES = 20000;
    private static final int PRUNE_TO = 18000;

    private DownloadedMediaHistory() {
    }

    static boolean contains(Context context, String url, String format, boolean audioOnly) {
        return preferences(context).contains(key(url, format, audioOnly));
    }

    static void record(Context context, String url, String format, boolean audioOnly) {
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

    /** Same post reached through different share forms must map to one history entry. */
    static String normalizeUrl(String value) {
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
            // A status link downloads the whole post, whichever photo/video index was shared.
            path = path.replaceFirst("(/status/\\d+)/(photo|video)/\\d+/?$", "$1");
        }
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String query = social || uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return "https://" + host + path + query;
    }

    private static String key(String url, String format, boolean audioOnly) {
        String mode = (audioOnly ? "audio" : "media") + "\n" + (format == null ? "" : format);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (normalizeUrl(url) + "\n" + mode).getBytes(StandardCharsets.UTF_8));
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
