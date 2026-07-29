package com.nightgals.earnings;

public enum EarningType {
    /** A viewer paid to unlock this creator specifically. */
    UNLOCK,
    /** This creator's share of a subscriber's payment for a period. */
    SUBSCRIPTION_SHARE,
    /** A manual credit or debit made by staff. May be negative. */
    ADJUSTMENT
}
