package com.nightgals.earnings;

public enum PayoutStatus {
    /** Creator has asked; waiting on an administrator. */
    REQUESTED,
    /** An administrator has agreed to pay, money not yet sent. */
    APPROVED,
    /** Money sent, with a reference recorded. */
    PAID,
    /** Refused; the reserved earnings return to the creator's balance. */
    REJECTED
}
