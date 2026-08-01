-- One-time codes emailed to the account's address.
--
-- A password alone is no longer enough to sign in. Signing in creates a
-- challenge, a six-digit code goes to the address on the account, and tokens are
-- only issued when that code comes back. Somebody who has phished or reused a
-- password still cannot get in without the mailbox.
--
-- The code itself is never stored - only its SHA-256, the same treatment refresh
-- tokens get. A stolen database dump therefore contains no usable codes, and the
-- rows expire within minutes anyway.

CREATE TABLE otp_challenges (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     VARCHAR(25)  NOT NULL,
    code_hash   VARCHAR(64)  NOT NULL,
    -- Wrong guesses so far. The challenge dies at the configured ceiling, which
    -- is what stops a six-digit code being brute-forced in a few thousand tries.
    attempts    INT          NOT NULL DEFAULT 0,
    -- How many times a fresh code has been sent for this challenge. Bounded so
    -- the endpoint cannot be turned into a mail bomb aimed at someone's inbox.
    resends     INT          NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    -- Recorded for abuse investigation, not for authorisation: a challenge is
    -- not tied to the address that created it, because mobile networks change
    -- it between the login and the code being entered.
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT otp_purpose_check CHECK (purpose IN ('LOGIN', 'EMAIL_VERIFICATION'))
);

-- Rate limiting reads "challenges this user opened recently".
CREATE INDEX ix_otp_user ON otp_challenges (user_id, created_at DESC);
-- The sweep that deletes spent and expired rows.
CREATE INDEX ix_otp_expiry ON otp_challenges (expires_at) WHERE consumed_at IS NULL;
