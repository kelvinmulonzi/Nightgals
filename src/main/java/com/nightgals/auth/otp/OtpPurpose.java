package com.nightgals.auth.otp;

/** What a one-time code is being asked for. */
public enum OtpPurpose {

    /** Proves the inbox on first sign-in. Consuming it issues tokens. */
    LOGIN,

    /** Confirms a new account's address. Consuming it sets {@code email_verified}. */
    EMAIL_VERIFICATION,

    /**
     * Recovers an account whose password has been forgotten. Consuming it is the
     * only thing that authorises a new password, so this code is the whole check -
     * there is no password to fall back on by definition.
     */
    PASSWORD_RESET
}
