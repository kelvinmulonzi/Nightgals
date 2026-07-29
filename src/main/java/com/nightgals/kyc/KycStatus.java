package com.nightgals.kyc;

public enum KycStatus {
    /** Created, documents may still be uploading. Not in the review queue. */
    DRAFT,
    /** Submitted by the user, waiting for a human decision. */
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}
