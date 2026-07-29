package com.nightgals.billing;

public enum PurchaseStatus {
    /** Created, awaiting settlement by the payment provider. */
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED
}
