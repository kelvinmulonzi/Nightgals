package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google sign-in.
 *
 * <p>One value, and the whole feature hangs off whether it is set: the client id
 * is the audience every ID token is checked against, so without it there is
 * nothing to verify against and the endpoint says so rather than accepting
 * tokens minted for somebody else's application.
 */
@ConfigurationProperties(prefix = "nightgals.google")
public record GoogleProperties(

        /**
         * The OAuth <em>Web application</em> client id from the Google Cloud
         * console. Web application specifically - that is the audience baked into
         * the tokens the browser's Google button produces, and a token whose
         * audience is anything else is rejected.
         *
         * <p>The browser needs this same value to render the button, so it is set
         * twice: here for the server, and as {@code VITE_GOOGLE_CLIENT_ID} for the
         * site. They must match.
         */
        String clientId) {

    /** False leaves Google sign-in switched off, which is the default. */
    public boolean configured() {
        return clientId != null && !clientId.isBlank();
    }
}
