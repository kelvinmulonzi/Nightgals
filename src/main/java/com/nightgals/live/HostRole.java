package com.nightgals.live;

public enum HostRole {
    /** Owns the session, its quota and its earnings. Exactly one per session. */
    OWNER,
    /** Invited to appear. Consumes none of their own live allowance. */
    CO_HOST
}
