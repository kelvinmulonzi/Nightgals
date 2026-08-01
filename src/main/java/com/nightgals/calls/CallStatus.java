package com.nightgals.calls;

/** Where a booked call is in its life. */
public enum CallStatus {
    /** Booked, not paid. Holds the slot, but hands out no room. */
    PENDING_PAYMENT,
    /** Paid. Both sides get the room when the time comes. */
    CONFIRMED,
    LIVE,
    COMPLETED,
    /** Called off by either side. Frees the slot. */
    CANCELLED,
    /** The creator refused it. Frees the slot. */
    DECLINED
}
