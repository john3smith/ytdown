package com.local.ytdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ExistingDownloadLookupTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void extractsMediaIdOnlyForDeterministicSingleItems() {
        assertEquals("dQw4w9WgXcQ", ExistingDownloadLookup.mediaIdForUrl(
                "https://youtu.be/dQw4w9WgXcQ?si=xyz", false, false));
        assertEquals("dQw4w9WgXcQ", ExistingDownloadLookup.mediaIdForUrl(
                "https://www.youtube.com/shorts/dQw4w9WgXcQ", true, false));
        assertNull(ExistingDownloadLookup.mediaIdForUrl(
                "https://www.youtube.com/playlist?list=PL12345", false, false));
        assertNull(ExistingDownloadLookup.mediaIdForUrl(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL12345", false, false));

        assertEquals("ph123abc", ExistingDownloadLookup.mediaIdForUrl(
                "https://m.pornhub.com/embed/ph123abc/", false, false));

        // Social media posts may contain multiple items; skip pre-check for video mode unless allowed.
        assertNull(ExistingDownloadLookup.mediaIdForUrl(
                "https://x.com/alice/status/1234567890", false, false));
        assertEquals("1234567890", ExistingDownloadLookup.mediaIdForUrl(
                "https://x.com/alice/status/1234567890", true, false));
        assertEquals("1234567890", ExistingDownloadLookup.mediaIdForUrl(
                "https://x.com/alice/status/1234567890", false, true));
        assertEquals("C9aBcDeF123", ExistingDownloadLookup.mediaIdForUrl(
                "https://www.instagram.com/reel/C9aBcDeF123/", true, false));
    }

    @Test
    public void matchesCompletedMediaFilesAndIgnoresPartialsAndWrongModes() throws Exception {
        File dir = temporary.newFolder("YTDown");
        File partial = new File(dir, "Video [dQw4w9WgXcQ]-001.mp4.part");
        Files.write(partial.toPath(), new byte[]{1, 2, 3});
        File empty = new File(dir, "Empty [dQw4w9WgXcQ]-001.mp4");
        assertTrue(empty.createNewFile());
        assertNull(ExistingDownloadLookup.findInDirectory(dir, "dQw4w9WgXcQ", false));

        File video = new File(dir, "My Video [dQw4w9WgXcQ]-001.mp4");
        Files.write(video.toPath(), new byte[]{1, 2, 3, 4});
        assertEquals(video.getName(), ExistingDownloadLookup.findInDirectory(dir, "dQw4w9WgXcQ", false));
        assertNull(ExistingDownloadLookup.findInDirectory(dir, "dQw4w9WgXcQ", true));

        File audio = new File(dir, "My Video [dQw4w9WgXcQ]-001-audio.m4a");
        Files.write(audio.toPath(), new byte[]{5, 6, 7});
        assertEquals(audio.getName(), ExistingDownloadLookup.findInDirectory(dir, "dQw4w9WgXcQ", true));
    }

    @Test
    public void recognizesBothYtDlpAlreadyDownloadedAndArchiveLines() {
        assertTrue(ExistingDownloadLookup.isAlreadyDownloadedOutput(
                "[download] /storage/emulated/0/Download/YTDown/test [abc]-001.mp4 has already been downloaded"));
        assertTrue(ExistingDownloadLookup.isAlreadyDownloadedOutput(
                "[download] Example Video: has already been recorded in the archive"));
        assertFalse(ExistingDownloadLookup.isAlreadyDownloadedOutput(
                "[download]  42.0% of 10.00MiB at 1.00MiB/s ETA 00:05"));
        assertFalse(ExistingDownloadLookup.isAlreadyDownloadedOutput(null));
    }

    @Test
    public void detectsImageExtensionFromPathOrQueryFormat() {
        assertEquals(".jpg", SocialImageDownloader.imageExtensionFromUrl(
                "https://pbs.twimg.com/media/abc?format=jpg&name=orig"));
        assertEquals(".png", SocialImageDownloader.imageExtensionFromUrl(
                "https://pbs.twimg.com/media/abc.PNG?name=orig"));
        assertEquals(".webp", SocialImageDownloader.imageExtensionFromUrl(
                "https://cdn.example.com/photo/item.webp"));
        assertNull(SocialImageDownloader.imageExtensionFromUrl(
                "https://cdn.example.com/photo/no-extension"));
    }

    @Test
    public void socialResultAllSkippedReflectsSkippedCounts() {
        assertFalse(new SocialImageDownloader.Result(2, 0, false, false, 1, 0).allSkipped());
        assertTrue(new SocialImageDownloader.Result(2, 1, true, false, 2, 1).allSkipped());
        assertFalse(new SocialImageDownloader.Result(0, 0, false, false, 0, 0).allSkipped());
    }
}
