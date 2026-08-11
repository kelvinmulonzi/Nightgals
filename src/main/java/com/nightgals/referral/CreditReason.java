package com.nightgals.referral;

/** Why a credit entry exists. */
public enum CreditReason {
    /** Somebody they invited bought their first package. Always positive. */
    REFERRAL_BONUS,
    /**
     * Balance bought with real money, through a settled CREDIT_TOPUP purchase.
     * Always positive.
     */
    TOPUP,
    /** Put towards a purchase, or spent on a gift. Always negative. */
    SPEND,
    /** A spend reversed because the purchase it paid for did not stand. */
    REFUND,
    /** A manual correction by staff. Either sign. */
    ADJUSTMENT
}
