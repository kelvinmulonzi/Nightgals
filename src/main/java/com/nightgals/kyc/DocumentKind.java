package com.nightgals.kyc;

public enum DocumentKind {
    ID_FRONT, ID_BACK, PASSPORT_PAGE,
    /** Live photo of the holder, compared against the document by the reviewer. */
    SELFIE
}
