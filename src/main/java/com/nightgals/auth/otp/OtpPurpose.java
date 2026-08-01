package com.nightgals.auth.otp;

/** What a one-time code is being asked for. */
public enum OtpPurpose {

    /** Second factor on sign-in. Consuming it issues tokens. */
    LOGIN,

    /** Confirms a new account's address. Consuming it sets {@code email_verified}. */
    EMAIL_VERIFICATION
}
