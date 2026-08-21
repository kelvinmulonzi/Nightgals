package com.nightgals.auth.google;

import com.nightgals.common.ApiException;
import com.nightgals.config.GoogleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the ID token the browser got from Google into an identity we will act on.
 *
 * <p>Verification is local. Google publishes the public half of its signing keys,
 * Nimbus fetches them once and caches them, and every token after that is checked
 * without leaving the process - so signing in does not depend on Google being
 * reachable at that moment, and there is no per-login call to rate-limit.
 *
 * <p>Three things are checked, and all three matter:
 * <ul>
 *   <li><b>Signature</b> - that Google minted it.</li>
 *   <li><b>Expiry</b> - Google's tokens live about an hour, so a leaked one is
 *       not a permanent key to the account.</li>
 *   <li><b>Audience</b> - that it was minted <em>for us</em>. Without this any
 *       site with a Google button could hand us one of their tokens and sign in
 *       as that person here. It is the check that is easiest to leave out and
 *       the one whose absence is a full account takeover.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleTokenVerifier {

    /** Google's published signing keys. Nimbus fetches and caches these itself. */
    private static final String JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /** Google mints both spellings and treats them as equivalent, so we accept both. */
    private static final Set<String> ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");

    private final GoogleProperties properties;

    /**
     * Built on first use, not at startup: constructing it is cheap but it is
     * meaningless without a client id, and an instance without one would be a
     * verifier that accepts everything.
     */
    private volatile JwtDecoder decoder;

    public boolean enabled() {
        return properties.configured();
    }

    /**
     * @throws ApiException 401 when the token is not a currently-valid Google
     *                      token minted for this application, 503 when Google
     *                      sign-in is not configured here.
     */
    public GoogleIdentity verify(String idToken) {
        if (!properties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_unavailable",
                    "Google sign-in is not available. Use an email address and password.");
        }

        Jwt token;
        try {
            token = decoder().decode(idToken);
        } catch (JwtException e) {
            // Deliberately vague to the caller, specific in the log: the reasons
            // this fails (wrong audience, expired, forged) are all things an
            // attacker would like confirmed, and none of them are things a real
            // user can act on beyond trying again.
            log.warn("Rejected a Google ID token: {}", e.getMessage());
            throw ApiException.unauthorized("That Google sign-in could not be verified. Try again.");
        }

        String email = token.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw ApiException.unauthorized(
                    "Google did not share an email address. Grant it and try again.");
        }

        return new GoogleIdentity(
                token.getSubject(),
                email.trim().toLowerCase(Locale.ROOT),
                truthy(token.getClaim("email_verified")),
                token.getClaimAsString("name"));
    }

    /**
     * OpenID Connect says {@code email_verified} is a boolean, and Google sends
     * one - but it has historically sent the string "true" as well, and reading
     * that as "not verified" would turn a good sign-in into a refusal.
     */
    private static boolean truthy(Object claim) {
        return claim instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(claim));
    }

    private JwtDecoder decoder() {
        JwtDecoder existing = decoder;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (decoder == null) {
                NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build();
                built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(),
                        new JwtClaimValidator<String>(JwtClaimNames.ISS, ISSUERS::contains),
                        // The aud claim is a list in the spec even when Google
                        // only ever puts one entry in it.
                        new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                                aud -> aud != null && aud.contains(properties.clientId()))));
                decoder = built;
            }
            return decoder;
        }
    }
}
