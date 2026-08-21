-- Forgotten passwords.
--
-- Recovery reuses the one-time-code machinery already in otp_challenges rather
-- than introducing a second kind of token: same hashing, same expiry, same cap
-- on wrong guesses, same per-account rate limit. Only the purpose is new.
--
-- The check constraint has to be replaced rather than extended - Postgres has no
-- ALTER ... ADD VALUE for a CHECK the way it does for an enum type.

ALTER TABLE otp_challenges DROP CONSTRAINT otp_purpose_check;

ALTER TABLE otp_challenges
    ADD CONSTRAINT otp_purpose_check
        CHECK (purpose IN ('LOGIN', 'EMAIL_VERIFICATION', 'PASSWORD_RESET'));
