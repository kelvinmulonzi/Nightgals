package com.nightgals.mail;

import java.util.Locale;

/** Small helpers for showing an address without publishing it. */
public final class EmailAddresses {

    private EmailAddresses() {
    }

    /**
     * {@code amina@example.com} -> {@code am••••@example.com}.
     *
     * <p>Enough for the owner to recognise which inbox to open, not enough for a
     * stranger who has guessed a challenge id to learn the address behind it.
     */
    public static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return "your email";
        }
        String[] parts = email.toLowerCase(Locale.ROOT).split("@", 2);
        String local = parts[0];
        String masked = local.length() <= 2
                ? local.charAt(0) + "•"
                : local.substring(0, 2) + "•".repeat(Math.min(6, local.length() - 2));
        return masked + "@" + parts[1];
    }
}
