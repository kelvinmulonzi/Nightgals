package com.nightgals.media;

/**
 * Whether a piece of content is the shop window or the thing being sold.
 *
 * <p>Set by the creator, per item - not derived from display order.
 */
public enum ContentTier {

    /**
     * Visible to everyone, including anonymous visitors who have never signed in.
     * This is what makes somebody want to pay.
     */
    FREE,

    /** Behind the paywall: unlock the creator, or subscribe. */
    EXCLUSIVE
}
