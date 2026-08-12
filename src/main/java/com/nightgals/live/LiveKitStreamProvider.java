package com.nightgals.live;

import com.nightgals.config.LiveKitProperties;
import com.nightgals.user.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * Broadcasts over WebRTC, through LiveKit.
 *
 * <p>Each broadcast is a room; the host publishes into it and viewers subscribe.
 * Rooms are never created explicitly - LiveKit makes one when the first
 * participant arrives - so nothing here calls the network. Provisioning picks a
 * name, and everything else is a signed token.
 *
 * <p>That matters more than it sounds. Going live cannot fail because LiveKit was
 * briefly unreachable, and a viewer's credentials cost a signature rather than a
 * round trip, which is what makes minting them per request affordable.
 *
 * <p><b>Tokens are the entire access-control boundary.</b> Once a client has one
 * it talks to LiveKit directly, and this server sees nothing further. So they are
 * per participant, per room, short-lived, and carry only the permissions that
 * participant should have: a viewer's token cannot publish, which is what stops
 * somebody joining a broadcast as a second performer.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nightgals.live.provider", havingValue = "livekit")
public class LiveKitStreamProvider implements StreamProvider {

    /** LiveKit rejects a token whose lifetime is implausible; this is a sane floor. */
    private static final Duration MIN_TTL = Duration.ofMinutes(1);
    private static final Duration DEFAULT_TTL = Duration.ofHours(4);

    private final LiveKitProperties properties;
    private final SecretKey signingKey;

    public LiveKitStreamProvider(LiveKitProperties properties) {
        this.properties = properties;

        // Failing at startup rather than at the first broadcast: a deployment that
        // names livekit without credentials is misconfigured, and finding that out
        // from a creator who cannot go live is worse.
        if (isBlank(properties.url()) || isBlank(properties.apiKey())
                || isBlank(properties.apiSecret())) {
            throw new IllegalStateException(
                    "nightgals.live.provider is 'livekit' but url, api-key or api-secret "
                    + "is missing. Set all three, or choose another provider.");
        }
        byte[] secret = properties.apiSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256 needs 256 bits. A LiveKit secret is longer than this, so a short
            // one means a truncated paste - worth naming, because the failure
            // otherwise surfaces as an unrelated crypto error.
            throw new IllegalStateException(
                    "nightgals.livekit.api-secret is only " + secret.length
                    + " bytes; it must be at least 32. Check it was copied in full.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
        log.info("LiveKit ready at {}", properties.url());
    }

    @Override
    public String name() {
        return "LIVEKIT";
    }

    /**
     * The room name, which is the session id.
     *
     * <p>Whatever the host supplied is discarded: with LiveKit there is no URL for
     * them to have known, and honouring a client-supplied room would let one
     * broadcast be pointed at another's stream.
     */
    @Override
    public Provisioned provision(LiveSession session, String requestedPlaybackUrl) {
        return new Provisioned(roomOf(session));
    }

    /** The host, who may publish. */
    @Override
    public Credentials publishCredentials(LiveSession session, User host) {
        return Credentials.webrtc(
                properties.url(),
                token(session, host, true),
                roomOf(session));
    }

    /**
     * A viewer, who may not.
     *
     * <p>{@code canPublish} false is doing real work here - without it a token
     * good enough to watch would also be good enough to broadcast into somebody
     * else's room.
     */
    @Override
    public Credentials viewCredentials(LiveSession session, User viewer) {
        return Credentials.webrtc(
                properties.url(),
                token(session, viewer, false),
                roomOf(session));
    }

    /**
     * Mints a LiveKit access token.
     *
     * <p>An ordinary HS256 JWT with LiveKit's {@code video} grant, so no SDK is
     * needed - the claims below are the whole protocol.
     */
    private String token(LiveSession session, User participant, boolean canPublish) {
        Instant now = Instant.now();
        Duration ttl = properties.tokenTtl() == null ? DEFAULT_TTL : properties.tokenTtl();
        if (ttl.compareTo(MIN_TTL) < 0) {
            ttl = MIN_TTL;
        }

        return Jwts.builder()
                // LiveKit identifies the project by the token's issuer.
                .issuer(properties.apiKey())
                // Identity is the account, so a ban or a disconnect can name one
                // participant, and a second tab replaces rather than duplicates them.
                .subject(participant.getId().toString())
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                // Shown to other participants, so the handle rather than the id -
                // and never the email, which viewers must not learn.
                .claim("name", participant.getUsername())
                .claim("video", Map.of(
                        "room", roomOf(session),
                        "roomJoin", true,
                        "canPublish", canPublish,
                        "canSubscribe", true,
                        // Viewers post gifts through this API, not over the media
                        // channel, so nobody needs to send data messages.
                        "canPublishData", false))
                .signWith(signingKey)
                .compact();
    }

    /** One room per broadcast, named for it so the two can never be confused. */
    private static String roomOf(LiveSession session) {
        return "live-" + session.getId();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
