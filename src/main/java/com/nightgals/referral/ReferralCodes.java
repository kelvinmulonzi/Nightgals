package com.nightgals.referral;

import java.security.SecureRandom;

/**
 * How an invite code is made.
 *
 * <p>Shared between {@link ReferralService}, which checks the result is unused,
 * and {@code User}'s lifecycle hook, which cannot reach a repository but must
 * still never write a null into a NOT NULL column.
 */
public final class ReferralCodes {

    /**
     * No vowels, so a code can never accidentally spell a word. No 0/O/1/I/L, so
     * it survives being read off a screenshot.
     */
    private static final String ALPHABET = "BCDFGHJKMNPQRSTVWXYZ23456789";
    public static final int LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ReferralCodes() {
    }

    /**
     * A random code.
     *
     * <p>28^8 is about 3.8e11, so an unchecked collision is vanishingly unlikely -
     * and the unique index catches it if one ever happens.
     */
    public static String random() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
