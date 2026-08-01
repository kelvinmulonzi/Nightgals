package com.nightgals.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * One-time codes.
 *
 * <p>The defaults trade a little friction for a lot of account safety: six digits
 * is a million-wide space, five attempts and a ten-minute life mean an attacker
 * gets roughly a one-in-two-hundred-thousand shot per challenge.
 */
@ConfigurationProperties(prefix = "nightgals.otp")
public record OtpProperties(

        /**
         * Whether signing in needs a code from the account's inbox.
         *
         * <p>Switching this off drops back to password-only sign-in. It exists for
         * local development and for a break-glass moment where the mail provider
         * is down and locking every user out is worse than the weaker check.
         */
        boolean loginRequired,

        /** Digits in a code. */
        int length,

        /** How long a code stays usable. */
        Duration ttl,

        /** Wrong guesses before the challenge is burned and a new sign-in is needed. */
        int maxAttempts,

        /** How many times a fresh code may be sent for one challenge. */
        int maxResends,

        /** Minimum gap between sends, so the resend button cannot flood an inbox. */
        Duration resendCooldown,

        /** Challenges one account may open per hour, across all purposes. */
        int maxChallengesPerHour,

        /** Sweep removing consumed and long-expired rows. */
        String purgeCron) {
}
