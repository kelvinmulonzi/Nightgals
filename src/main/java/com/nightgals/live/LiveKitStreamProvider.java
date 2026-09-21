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
import java.util.UUID;

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

        // LiveKit treats identity as unique: when a second token claims one that
        // is already in the room, the server disconnects the participant holding
        // it. That is the behaviour we want for a publisher and the wrong one for
        // a viewer, so the two are identified differently.
        //
        // A host keeps her account id. Going live again from a second tab should
        // replace the first broadcast rather than put two of her on air.
        //
        // A viewer gets a fresh identity on every mint. Two connections from one
        // account are ordinary - a second tab, a page that remounts, a reconnect
        // overlapping the connection it is replacing - and with a shared identity
        // each one kicks the last, which the kicked tab can only show as a stream
        // that died. Nothing keys off a viewer's identity; the handle other
        // participants see is the `name` claim below.
        boolean anonymous = participant == null;
        String displayName = anonymous ? "Guest" : participant.getUsername();
        String identity;
        if (anonymous) {
            identity = "guest-" + UUID.randomUUID();
        } else if (canPublish) {
            identity = participant.getId().toString();
        } else {
            identity = participant.getId() + "#" + UUID.randomUUID();
        }

        return Jwts.builder()
                // LiveKit identifies the project by the token's issuer.
                .issuer(properties.apiKey())
                .subject(identity)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                // Shown to other participants, so the handle rather than the id -
                // and never the email, which viewers must not learn.
                .claim("name", displayName)
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

    /**
     * Closes the room, which disconnects everybody in it.
     *
     * <p>This is what actually stops the provider billing. Marking a session
     * ENDED in our database changes nothing at LiveKit: the host's browser keeps
     * publishing and every viewer stays subscribed, and those minutes are charged
     * whether anybody is watching or not. Before this existed, pressing "End" and
     * walking away looked identical to LiveKit.
     *
     * <p>Plain HTTP against LiveKit's Twirp endpoint rather than an SDK, the same
     * way tokens are plain JWTs - one POST is the whole protocol.
     *
     * <p>Never throws. A room that is already gone is the outcome we wanted, and a
     * provider that is briefly unreachable must not stop a session being marked
     * over; the host's own client disconnects when it notices, and LiveKit closes
     * an empty room on its own shortly after.
     */
    @Override
    public void teardown(LiveSession session) {
        String room = roomOf(session);
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(httpBase() + "/twirp/livekit.RoomService/DeleteRoom"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + adminToken())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"room\":\"" + room + "\"}"))
                    .build();
            java.net.http.HttpResponse<String> response =
                    HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            int code = response.statusCode();
            if (code / 100 == 2) {
                log.info("LiveKit room {} closed", room);
            } else if (code == 404 || response.body().contains("not_found")) {
                // Nobody was ever in it, or LiveKit already reaped it. Either way it
                // is closed, which is all this was for.
                log.debug("LiveKit room {} was already gone", room);
            } else {
                log.warn("LiveKit refused to close room {}: {} {}", room, code, response.body());
            }
        } catch (java.io.IOException e) {
            log.warn("Could not reach LiveKit to close room {}: {}", room, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted closing LiveKit room {}", room);
        }
    }

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * The API's address for server calls.
     *
     * <p>Configured as the {@code wss://} address clients connect to; the same host
     * answers HTTPS for administration, so only the scheme changes.
     */
    private String httpBase() {
        String url = properties.url().trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("wss://")) {
            return "https://" + url.substring("wss://".length());
        }
        if (url.startsWith("ws://")) {
            return "http://" + url.substring("ws://".length());
        }
        return url;
    }

    /**
     * A token for managing rooms, not for joining one.
     *
     * <p>Only {@code roomCreate}, which is the grant DeleteRoom checks. Short-lived
     * because it is used once, immediately, from this process.
     */
    private String adminToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.apiKey())
                .subject("nightgals-server")
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(2))))
                .claim("video", Map.of("roomCreate", true))
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
