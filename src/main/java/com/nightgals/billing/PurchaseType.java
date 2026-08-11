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
     * A creator buying extra live minutes for one day.
     *
     * <p>Money towards the platform, like a package - it tops up the daily
     * allowance a package sells, so it produces no earnings entry. It expires
     * with the day it was bought for: this is not an upgrade.
     */
    LIVE_EXTENSION,

    /**
     * A viewer loading their credit balance.
     *
     * <p>The only type that buys no content. It exists because gifts have to be
     * instant: a card redirect per gift would send the payer out of the
     * broadcast and back for every one, which is no way to tip somebody who is
     * live. So the money is taken once, up front, and spent from the ledger
     * afterwards at no cost.
     *
     * <p>Produces no earnings entry - nobody has earned anything yet. The
     * creator's share is recorded when a gift is actually sent, against whoever
     * received it.
     */
    CREDIT_TOPUP,

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
