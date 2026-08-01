package com.nightgals.profile;

/**
 * The two values the product recognises.
 *
 * <p>Narrowed from a five-value set in V10. Anything persisted under one of the
 * retired values was migrated to {@link #FEMALE}, which was a guess rather than a
 * fact - see the migration.
 */
public enum Gender {
    MALE, FEMALE
}
