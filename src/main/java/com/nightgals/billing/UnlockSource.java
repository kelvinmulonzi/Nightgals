package com.nightgals.billing;

public enum UnlockSource {
    PURCHASE,
    /** Given by staff - comps, support gestures, testing. */
    GRANT,
    /**
     * Carried over from the whole-creator unlock this replaced. Kept distinct so
     * it is obvious later that these were not bought one item at a time.
     */
    MIGRATED
}
