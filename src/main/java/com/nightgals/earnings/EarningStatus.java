package com.nightgals.earnings;

public enum EarningStatus {
    /** Inside the hold period; not yet payable. */
    PENDING,
    /** Payable. */
    AVAILABLE,
    /** Attached to an open payout request, so it cannot be spent twice. */
    RESERVED,
    PAID,
    /** Refunded or charged back. */
    REVERSED
}
