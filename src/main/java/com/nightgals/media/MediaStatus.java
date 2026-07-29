package com.nightgals.media;

/**
 * Whether a media item is visible to other members.
 *
 * <p>Not a publication gate. Identity verification is the gate: a KYC-approved
 * creator's uploads publish immediately as {@link #APPROVED}. This exists so a
 * moderator can remove something after the fact.
 */
public enum MediaStatus {

    /**
     * Retained for rows created before uploads auto-published, and never set by
     * the application now.
     *
     * @deprecated media no longer queues for review
     */
    @Deprecated
    PENDING_REVIEW,

    /** Live and visible, subject to the paywall. */
    APPROVED,

    /** Taken down by a moderator. Invisible to everyone but its owner and staff. */
    REJECTED
}
