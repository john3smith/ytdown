package com.local.ytdown;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class InstagramPostOwnerTest {
    private static final String EMBED = "<a class=\"UsernameText\">embed_owner</a>";

    private static JSONObject post(String fields) throws Exception {
        return new JSONObject("{\"code\":\"ABC\",\"pk\":\"66\"" + (fields.isEmpty() ? "" : "," + fields) + "}");
    }

    private static SocialImageDownloader.InstagramSource<String> embedCounting(AtomicInteger calls) {
        return () -> { calls.incrementAndGet(); return EMBED; };
    }

    @Test public void authenticatedOwnerIsUsedWithoutEmbedRequest() throws Exception {
        AtomicInteger embedCalls = new AtomicInteger();
        String owner = SocialImageDownloader.resolveInstagramPostOwner("ABC",
                () -> post("\"user\":{\"username\":\"real.owner\"}"), embedCounting(embedCalls), () -> false);
        assertEquals("real.owner", owner);
        assertEquals(0, embedCalls.get());
    }

    @Test public void ownerIsOnlyDirectUserNotNestedUsernames() throws Exception {
        JSONObject nested = post("\"usertags\":{\"in\":[{\"user\":{\"username\":\"tagged\"}}]},"
                + "\"coauthor_producers\":[{\"username\":\"coauthor\"}]");
        assertNull(SocialImageDownloader.instagramOwnerFromPost(nested, "ABC"));
        assertNull(SocialImageDownloader.instagramOwnerFromPost(
                post("\"user\":{\"username\":\"bad/name\"}"), "ABC"));
    }

    @Test public void rejectsUnrelatedPostIdentity() throws Exception {
        JSONObject other = post("\"user\":{\"username\":\"someone\"}");
        assertThrows(IOException.class, () -> SocialImageDownloader.instagramOwnerFromPost(other, "ABD"));
        other.put("pk", "67");
        assertThrows(IOException.class, () -> SocialImageDownloader.instagramOwnerFromPost(other, "ABC"));
    }

    @Test public void missingOrMismatchedOwnerFallsBackToEmbed() throws Exception {
        assertEquals("embed_owner", SocialImageDownloader.resolveInstagramPostOwner("ABC",
                () -> post(""), () -> EMBED, () -> false));
        assertEquals("embed_owner", SocialImageDownloader.resolveInstagramPostOwner("ABC",
                () -> new JSONObject("{\"code\":\"XYZ\",\"user\":{\"username\":\"wrong\"}}"),
                () -> EMBED, () -> false));
        assertEquals("embed_owner", SocialImageDownloader.resolveInstagramPostOwner("ABC",
                () -> { throw new IOException("Instagram 전체 미디어 조회 HTTP 500"); },
                () -> EMBED, () -> false));
    }

    @Test public void noSessionUsesEmbedOnly() throws Exception {
        assertEquals("embed_owner", SocialImageDownloader.resolveInstagramPostOwner("ABC",
                null, () -> EMBED, () -> false));
    }

    @Test public void securityChallengeIsNotHiddenByEmbedFallback() {
        AtomicInteger embedCalls = new AtomicInteger();
        assertThrows(SocialImageDownloader.InstagramLoginRequiredException.class,
                () -> SocialImageDownloader.resolveInstagramPostOwner("ABC", () -> {
                    throw new SocialImageDownloader.InstagramLoginRequiredException("Instagram 보안 확인");
                }, embedCounting(embedCalls), () -> false));
        assertEquals(0, embedCalls.get());
    }

    @Test public void cancellationStopsBeforeAndAfterAuthenticatedLookup() {
        AtomicInteger embedCalls = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> SocialImageDownloader.resolveInstagramPostOwner(
                "ABC", () -> { throw new InterruptedException("취소"); }, embedCounting(embedCalls), () -> false));
        assertThrows(InterruptedException.class, () -> SocialImageDownloader.resolveInstagramPostOwner(
                "ABC", () -> post(""), embedCounting(embedCalls), () -> true));
        boolean[] cancelled = {false};
        assertThrows(InterruptedException.class, () -> SocialImageDownloader.resolveInstagramPostOwner(
                "ABC", () -> { cancelled[0] = true; throw new IOException("network"); },
                embedCounting(embedCalls), () -> cancelled[0]));
        assertEquals(0, embedCalls.get());
    }
}
