package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LiveKit, which is where broadcasts go when {@code nightgals.live.provider} is
 * {@code livekit}.
 *
 * <p>WebRTC rather than HLS, deliberately: gifts are reacted to, and a creator
 * thanking somebody thirty seconds after they sent something is not a reaction.
 * It also means creators publish straight from the app, with no stream key to
 * copy and no second app to install.
 *
 * <p>Nothing here is called at broadcast time. Rooms are created implicitly by
 * the first participant to join, so the platform only ever mints tokens - which
 * is local signing, not a network call, and cannot fail halfway.
 */
@ConfigurationProperties(prefix = "nightgals.livekit")
public record LiveKitProperties(

        /**
         * The {@code wss://} host clients connect to, from the LiveKit dashboard.
         *
         * <p>Public: it is handed to every viewer. What protects a room is the
         * token, not the address.
         */
        String url,

        /** Identifies the project. Public, and travels as the token's issuer. */
        String apiKey,

        /**
         * Signs every token. <b>The whole security boundary</b> - anything holding
         * it can mint a publisher token for any room, so it belongs in the
         * environment and never in the repository.
         */
        String apiSecret,

        /**
         * How long a minted token stays usable.
         *
         * <p>Short on purpose. A token is what stands in for the paywall once the
         * client has left this server, so its lifetime is how long a revoked
         * viewer keeps access. Long enough to join and reconnect after a tunnel;
         * not long enough to be worth passing around.
         */
        Duration tokenTtl) {
}
