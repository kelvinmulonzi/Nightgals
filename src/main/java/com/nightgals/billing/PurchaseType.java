package com.nightgals.billing;

/** What a purchase is for. */
public enum PurchaseType {

    /** A viewer buying one photo or video at the price its creator set. */
    MEDIA_UNLOCK,

    /** A viewer buying entry to one live broadcast. */
    LIVE_ACCESS,

    /** A viewer booking a private 1-to-1 call. */
    CALL_BOOKING,

    /**
     * A creator paying the platform for the right to publish. The only type
     * where money flows towards the platform rather than towards a creator, so
     * it never produces an earnings entry - and the only one that pays a
     * referral bonus.
     */
    CREATOR_PACKAGE,

    /**
     * Retired: one payment opened everything a creator had posted. Kept because
     * historical rows still carry it and their receipts still have to render.
     * Nothing creates new ones.
     */
    @Deprecated
    PROFILE_UNLOCK,

    /** Retired: an all-creators subscription. Historical rows only. */
    @Deprecated
    SUBSCRIPTION
}
