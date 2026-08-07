package com.nightgals.kyc;

/**
 * Why an identity submission was turned down.
 *
 * <p>Each carries the sentence the applicant is actually shown. The enum name is
 * for the reviewer and the audit trail; nobody should be emailed
 * {@code SELFIE_MISMATCH} and left to work out what to fix.
 */
public enum RejectionReason {

    DOCUMENT_UNREADABLE("the document was too blurred or too dark to read."),
    DOCUMENT_EXPIRED("the document has expired."),

    /** Name or date of birth on the document does not match the profile. */
    DETAILS_MISMATCH("the name or date of birth does not match the profile."),

    /** The selfie is not the person on the document. */
    SELFIE_MISMATCH("the selfie did not match the photo on the document."),

    SUSPECTED_FORGERY("the document could not be accepted as genuine."),
    UNDERAGE("the date of birth is below the minimum age for this platform."),

    /**
     * This document already verified another account.
     *
     * <p>Worded without confirming that another account exists. Someone holding
     * a document that is not theirs should not learn from a rejection that it is
     * already registered here.
     */
    DUPLICATE_ACCOUNT("this document cannot be used to verify a second account."),

    OTHER("the documents were not usable.");

    private final String message;

    RejectionReason(String message) {
        this.message = message;
    }

    /** The applicant-facing sentence, lowercase so it reads on from "the check did not pass: ". */
    public String message() {
        return message;
    }
}
