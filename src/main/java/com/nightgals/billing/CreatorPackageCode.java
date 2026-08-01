package com.nightgals.billing;

/**
 * The three subscription packages a creator can hold.
 *
 * <p>Declared in ascending order, and the order is load-bearing: {@link #rank()}
 * reads off it and decides who appears first in search and on the homepage.
 *
 * <p>Prices, live allowances and video limits are configuration
 * ({@code nightgals.creator-packages}); this enum only fixes the set of codes,
 * so a typo in a request body is a 400 rather than a row nobody can price.
 */
public enum CreatorPackageCode {

    /** 15 minutes of live per day, 2 premium videos, standard placement. */
    PRO,

    /** 45 minutes of live per day, 5 premium videos, second-level placement. */
    DIAMOND,

    /** 2 hours of live per day, 10 premium videos, top placement. */
    BLACK_DIAMOND;

    /** Higher wins. A creator with no package ranks below every one of these. */
    public int rank() {
        return ordinal() + 1;
    }
}
