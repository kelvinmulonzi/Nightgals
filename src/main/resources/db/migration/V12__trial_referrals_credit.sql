-- Free trial, referrals, and the credit those referrals pay out in.

-- ---------------------------------------------------------------- free trial
-- Seven days of full access from the moment the account exists.
--
-- Stored as an expiry rather than a flag, so it needs no job to end it: every
-- check is a comparison against now(), and a trial that has run out simply
-- stops satisfying it.
ALTER TABLE users ADD COLUMN trial_ends_at TIMESTAMPTZ;

-- Existing accounts get the trial measured from when they actually signed up,
-- not from today. Backdating is the honest reading of "7 days from creating an
-- account", and it avoids handing a fresh week to someone who registered months
-- ago.
UPDATE users SET trial_ends_at = created_at + INTERVAL '7 days';

CREATE INDEX ix_users_trial ON users (trial_ends_at) WHERE trial_ends_at IS NOT NULL;

-- ---------------------------------------------------------------- referrals
-- The code is public and appears in a shareable link, so it is generated from a
-- restricted alphabet: no vowels (nothing accidentally spells a word), and no
-- 0/O/1/I/L (nothing is misread off a screenshot).
ALTER TABLE users ADD COLUMN referral_code VARCHAR(12);

-- Backfill deterministically from the primary key so the unique index below
-- cannot collide on existing rows.
UPDATE users SET referral_code = UPPER(SUBSTR(TRANSLATE(REPLACE(id::text, '-', ''),
    'abcdefghijklmnopqrstuvwxyz01il', 'BCDFGHJKMNPQRSTVWXYZ23456789XY'), 1, 8))
WHERE referral_code IS NULL;

ALTER TABLE users ALTER COLUMN referral_code SET NOT NULL;
CREATE UNIQUE INDEX ux_users_referral_code ON users (UPPER(referral_code));

-- Who invited this account. Set once, at registration, and never changed:
-- letting it move later would let two people claim the same bonus.
ALTER TABLE users ADD COLUMN referred_by UUID REFERENCES users (id) ON DELETE SET NULL;
ALTER TABLE users ADD CONSTRAINT users_no_self_referral_check CHECK (referred_by <> id);
CREATE INDEX ix_users_referred_by ON users (referred_by) WHERE referred_by IS NOT NULL;

-- ---------------------------------------------------------------- credit
-- An append-only ledger, for the same reason the earnings ledger is one: a
-- balance that is stored and incremented is a balance that drifts and cannot be
-- audited. A balance here is always SUM(amount_minor) over the user's rows.
--
-- Positive amounts are credit granted, negative amounts are credit spent.
CREATE TABLE credit_entries (
    id               UUID PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount_minor     BIGINT       NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    reason           VARCHAR(30)  NOT NULL,
    -- For REFERRAL_BONUS: whose first purchase earned it. Also the guard that
    -- makes the bonus once-per-referred-account rather than once per purchase.
    referred_user_id UUID         REFERENCES users (id) ON DELETE SET NULL,
    -- For SPEND: what the credit was put towards.
    purchase_id      UUID         REFERENCES purchases (id) ON DELETE SET NULL,
    note             VARCHAR(300),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT credit_reason_check CHECK (reason IN
        ('REFERRAL_BONUS', 'SPEND', 'REFUND', 'ADJUSTMENT')),
    -- A bonus is money in, a spend is money out. Getting the sign wrong is the
    -- one bug in a ledger that is invisible until the numbers are already wrong.
    CONSTRAINT credit_sign_check CHECK (
        (reason = 'REFERRAL_BONUS' AND amount_minor > 0) OR
        (reason = 'SPEND'          AND amount_minor < 0) OR
        (reason IN ('REFUND', 'ADJUSTMENT')))
);
CREATE INDEX ix_credit_user ON credit_entries (user_id, created_at DESC);

-- One referral bonus per referred account, ever. A unique index rather than a
-- check in code, because the check would be a read-then-write race the first
-- time two purchases settled at once.
CREATE UNIQUE INDEX ux_credit_referral_once ON credit_entries (referred_user_id)
    WHERE reason = 'REFERRAL_BONUS';

-- How much of a purchase was paid with credit rather than money. Denormalised
-- from the ledger so a receipt can say "5 000 in credit, 10 000 charged"
-- without joining anything.
ALTER TABLE purchases ADD COLUMN credit_applied_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE purchases ADD CONSTRAINT purchases_credit_check
    CHECK (credit_applied_minor >= 0 AND credit_applied_minor <= amount_minor);
