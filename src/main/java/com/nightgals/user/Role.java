package com.nightgals.user;

public enum Role {
    USER,
    /** Can work the KYC and media review queues. */
    MODERATOR,
    /** Everything a moderator can do, plus account administration. */
    ADMIN
}
