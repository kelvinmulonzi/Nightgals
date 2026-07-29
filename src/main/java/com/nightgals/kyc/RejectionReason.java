package com.nightgals.kyc;

public enum RejectionReason {
    DOCUMENT_UNREADABLE,
    DOCUMENT_EXPIRED,
    /** Name or date of birth on the document does not match the profile. */
    DETAILS_MISMATCH,
    /** The selfie is not the person on the document. */
    SELFIE_MISMATCH,
    SUSPECTED_FORGERY,
    UNDERAGE,
    /** This document already verified another account. */
    DUPLICATE_ACCOUNT,
    OTHER
}
