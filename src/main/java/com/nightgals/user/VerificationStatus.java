package com.nightgals.user;

/** Mirrors the state of the user's most recent KYC submission. */
public enum VerificationStatus {
    UNVERIFIED,
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}
