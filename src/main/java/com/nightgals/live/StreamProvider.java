package com.nightgals.live;

import com.nightgals.user.User;

/**
 * Where the video actually goes.
 *
 * <p>This application still does not ingest, transcode or serve video. What it
 * does now is <em>provision</em> it: a creator tapping "go live" should not have
 * to paste a stream key from somewhere else, so the platform asks a provider for
 * a place to broadcast and hands back the credentials.
 *
 * <p>Shaped like {@link com.nightgals.billing.PaymentProvider} on purpose, and
 * for the same reason - the vendor is a deployment decision, not an architectural
 * one. Owncast locally, LiveKit in production, something else later, without the
 * session, gift and entitlement code knowing which.
 *
 * <p>The important rule is that <b>viewer credentials are minted per request,
 * after the entitlement check</b>, and expire. A playback URL that never changes
 * is a password anybody can forward; a short-lived token is not. That is the same
 * principle as media never being served straight from S3.
 */
public interface StreamProvider {

    /** Uppercase, e.g. {@code LIVEKIT}. What configuration names. */
    String name();

    /**
     * Where a broadcast will live, decided when the session is created.
     *
     * @param requestedPlaybackUrl what the host supplied, which providers that
     *        provision their own ingest ignore
     */
    Provisioned provision(LiveSession session, String requestedPlaybackUrl);

    /** What the creator's client needs to start publishing. */
    Credentials publishCredentials(LiveSession session, User host);

    /**
     * What a viewer's client needs to watch.
     *
     * <p>Called only once the caller has been found entitled, so this may assume
     * access is already granted and must not be reachable any other way.
     */
    Credentials viewCredentials(LiveSession session, User viewer);

    /** Called when a broadcast ends. Providers with nothing to release skip it. */
    default void teardown(LiveSession session) {
    }

    /**
     * What was set aside for a broadcast.
     *
     * @param playbackUrl stored on the session. For HLS providers this is the
     *        {@code .m3u8}; for a room-based one it is the room's identifier,
     *        which is meaningless without a token and therefore safe to store.
     */
    record Provisioned(String playbackUrl) {
    }

    /**
     * Credentials for one participant, for one broadcast, for a short while.
     *
     * @param mode      how the client should connect - see {@link Mode}
     * @param url       the server to connect to: a {@code wss://} host for
     *                  WebRTC, or the media URL itself for HLS
     * @param token     short-lived, participant-specific. Null when the provider
     *                  has no notion of one, as with a public HLS URL.
     * @param room      which broadcast to join. Null outside room-based providers.
     * @param ingestUrl where a creator points OBS, when the provider takes RTMP.
     *                  Null for publishing straight from the app.
     * @param streamKey pairs with {@code ingestUrl}. Secret; never sent to a viewer.
     */
    record Credentials(Mode mode,
                       String url,
                       String token,
                       String room,
                       String ingestUrl,
                       String streamKey) {

        public static Credentials hls(String playbackUrl) {
            return new Credentials(Mode.HLS, playbackUrl, null, null, null, null);
        }

        public static Credentials webrtc(String url, String token, String room) {
            return new Credentials(Mode.WEBRTC, url, token, room, null, null);
        }
    }

    /** What the client should do with {@link Credentials}. */
    enum Mode {
        /** Play the URL. Simple, cacheable, and ten to thirty seconds behind. */
        HLS,
        /** Join with the token. Sub-second, which is what gifts need to land on. */
        WEBRTC,
        /** Push RTMP to {@code ingestUrl} with {@code streamKey}. Publishing only. */
        RTMP
    }
}
