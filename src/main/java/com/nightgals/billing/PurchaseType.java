package com.nightgals.billing;

public enum PurchaseType {
    /** A viewer paying for one creator's photos, video and live sessions. */
    PROFILE_UNLOCK,
    /** A viewer paying for every creator, for the plan's duration. */
    SUBSCRIPTION,
    /**
     * A creator paying the platform for the right to publish. The only type where
     * the money flows towards the platform rather than towards a creator, so it
     * never produces an earnings entry.
     */
    CREATOR_PACKAGE
}
