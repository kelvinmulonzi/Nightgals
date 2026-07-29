package com.nightgals.user;

/**
 * What an account is for. Viewers and creators have different requirements, and
 * conflating them is what pushes passport uploads at people who only want to
 * watch.
 */
public enum AccountType {

    /**
     * Consumes content. Signs up with a username and password only - no email,
     * because an address is the thing most likely to identify somebody, and a
     * viewer needs none of what an email is used for.
     */
    VIEWER,

    /**
     * Posts content and earns from it. Needs an email (payout and verification
     * correspondence) and must pass KYC before publishing anything.
     */
    CREATOR
}
