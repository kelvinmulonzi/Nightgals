package com.nightgals.earnings;

public enum EarningType {
    /** A viewer paid to unlock this creator specifically. */
    UNLOCK,
    /**
     * A gift sent during one of this creator's broadcasts.
     *
     * <p>Split and held exactly like an unlock. The credit behind it was bought
     * with a card that can be charged back weeks later, so a gift is not
     * payable the moment it lands on screen.
     */
    GIFT,
    /** This creator's share of a subscriber's payment for a period. */
    SUBSCRIPTION_SHARE,
    /** A manual credit or debit made by staff. May be negative. */
    ADJUSTMENT
}
