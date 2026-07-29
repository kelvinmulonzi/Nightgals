package com.nightgals.user;

public enum UserStatus {
    ACTIVE,
    /** Blocked by a moderator; login is refused. */
    SUSPENDED,
    /** Closed by the user themselves. */
    DEACTIVATED
}
