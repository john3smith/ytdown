package com.local.ytdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadedMediaHistoryTest {
    @Test
    public void normalizesEquivalentYouTubeVideoShareForms() {
        String expected = "https://youtube.com/watch?v=dQw4w9WgXcQ";
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://youtu.be/dQw4w9WgXcQ?si=abc123&t=42"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=shared"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ&pp=ygU"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=xyz"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://www.youtube.com/live/dQw4w9WgXcQ?si=live"));
        assertFalse(DownloadedMediaHistory.isCollectionUrl(expected));
    }

    @Test
    public void recognizesYouTubePlaylistsAsCollections() {
        String playlist = "https://www.youtube.com/playlist?list=PL12345&si=abc";
        assertEquals("https://youtube.com/playlist?list=PL12345",
                DownloadedMediaHistory.normalizeUrl(playlist));
        assertTrue(DownloadedMediaHistory.isCollectionUrl(playlist));

        String watchPlaylist = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL12345&index=2";
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ&list=PL12345",
                DownloadedMediaHistory.normalizeUrl(watchPlaylist));
        assertTrue(DownloadedMediaHistory.isCollectionUrl(watchPlaylist));
    }

    @Test
    public void normalizesEquivalentXStatusFormsRegardlessOfAuthorCaseOrMediaIndex() {
        String expected = "https://x.com/i/status/1890123456789012345";
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://x.com/Alice/status/1890123456789012345?s=20&t=abc"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://twitter.com/alice/status/1890123456789012345/photo/2"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://mobile.x.com/i/web/status/1890123456789012345/video/1"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://x.com/i/status/1890123456789012345/"));
    }

    @Test
    public void normalizesEquivalentInstagramPostAndReelFormsPreservingShortcodeCase() {
        String expected = "https://instagram.com/p/C9aBcDeF123";
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://www.instagram.com/p/C9aBcDeF123/?igsh=xyz"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://www.instagram.com/reel/C9aBcDeF123/"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://instagram.com/reels/C9aBcDeF123/"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://instagr.am/tv/C9aBcDeF123/"));
        assertEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://www.instagram.com/some.user/p/C9aBcDeF123/"));
        assertNotEquals(expected, DownloadedMediaHistory.normalizeUrl(
                "https://www.instagram.com/p/c9abcdef123/"));
    }

    @Test
    public void separatesAudioAndVideoModesWhileKeepingSocialAndYouTubeQualityAgnostic() {
        String best = DownloadFormatSelector.forQualityPosition(0);
        String p720 = DownloadFormatSelector.forQualityPosition(2);
        String audio = DownloadFormatSelector.forMode(0, true);

        assertEquals(
                DownloadedMediaHistory.key("https://youtu.be/dQw4w9WgXcQ?si=1", best, false),
                DownloadedMediaHistory.key("https://www.youtube.com/watch?v=dQw4w9WgXcQ", p720, false));
        assertNotEquals(
                DownloadedMediaHistory.key("https://youtu.be/dQw4w9WgXcQ", best, false),
                DownloadedMediaHistory.key("https://youtu.be/dQw4w9WgXcQ", audio, true));

        assertEquals(
                DownloadedMediaHistory.key("https://www.instagram.com/reel/AbCd123/", best, false),
                DownloadedMediaHistory.key("https://instagram.com/p/AbCd123/", p720, false));

        assertNotEquals(
                DownloadedMediaHistory.key("https://www.pornhub.com/view_video.php?viewkey=ph123", best, false),
                DownloadedMediaHistory.key("https://www.pornhub.com/view_video.php?viewkey=ph123", p720, false));
    }

    @Test
    public void preservesLegacyNormalizationForUpgradingUsers() {
        assertEquals("https://x.com/Alice/status/12345",
                DownloadedMediaHistory.legacyNormalizeUrl("https://twitter.com/Alice/status/12345/photo/1?s=20"));
        assertEquals("https://instagram.com/reel/AbCd123",
                DownloadedMediaHistory.legacyNormalizeUrl("https://www.instagram.com/reel/AbCd123/?igsh=1"));
    }
}
