-- Live goes free, and earns through gifts instead.
--
-- A door charge and gifting pull against each other: nobody tips a creator
-- they were never allowed to watch. So the paywall in front of the stream comes
-- off, and the money moves to what happens while it runs.

-- Existing broadcasts included, not just new ones. Leaving scheduled sessions
-- behind a paywall nobody expects any more is the worse surprise.
UPDATE live_sessions SET tier = 'FREE' WHERE tier <> 'FREE';

-- The EXCLUSIVE tier itself stays. Ticketing a single show is still a thing a
-- creator might want, and buy-access still works for anyone who sets it.

-- One gift sent during a broadcast.
--
-- Amount, code, label and icon are copied onto the row rather than referenced,
-- because this is a receipt: re-pricing the catalogue tomorrow must not restate
-- what somebody paid today, nor what a creator was told she had earned.
CREATE TABLE gifts (
    id              UUID PRIMARY KEY,
    sender_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    creator_id      UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    live_session_id UUID NOT NULL REFERENCES live_sessions (id) ON DELETE CASCADE,
    gift_code       VARCHAR(30) NOT NULL,
    gift_label      VARCHAR(60) NOT NULL,
    gift_icon       VARCHAR(16),
    amount_minor    BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    message         VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A gift is a transfer of money; zero or negative is not one.
    CONSTRAINT gifts_amount_check CHECK (amount_minor > 0),
    -- Gifting yourself would recycle bought balance into withdrawable earnings,
    -- which is a way to cash out a card rather than a way to thank somebody.
    CONSTRAINT gifts_not_self_check CHECK (sender_id <> creator_id)
);

-- The polling query: everything for one broadcast since a timestamp, in order.
CREATE INDEX ix_gifts_session_time ON gifts (live_session_id, created_at);

-- "What have I earned from gifts", per creator.
CREATE INDEX ix_gifts_creator_time ON gifts (creator_id, created_at DESC);

-- GIFT joins the allowed earning types. Without this every gift would fail on
-- earnings_type_check after the sender had already been debited.
ALTER TABLE earnings
    DROP CONSTRAINT IF EXISTS earnings_type_check;
ALTER TABLE earnings
    ADD CONSTRAINT earnings_type_check CHECK (type IN
        ('UNLOCK', 'GIFT', 'SUBSCRIPTION_SHARE', 'ADJUSTMENT'));

-- A gift earning has no purchase behind it: the money was taken when the sender
-- topped up, not when the gift was sent. Nothing to relax - purchase_id is
-- already nullable, and ux_earnings_unlock_purchase is partial on type =
-- 'UNLOCK', so a run of gifts with NULL purchase_id does not collide.

-- CREDIT_TOPUP joins the purchase types...
ALTER TABLE purchases
    DROP CONSTRAINT IF EXISTS purchases_type_check;
ALTER TABLE purchases
    ADD CONSTRAINT purchases_type_check CHECK (type IN (
        'MEDIA_UNLOCK', 'LIVE_ACCESS', 'CALL_BOOKING', 'CREATOR_PACKAGE',
        'LIVE_EXTENSION', 'CREDIT_TOPUP', 'PROFILE_UNLOCK', 'SUBSCRIPTION'));

-- ...and needs a branch in the shape rule, whose ELSE false would otherwise
-- reject every top-up. It is the one type that points at nothing: it buys
-- balance, not a thing, so the amount is the whole of it.
ALTER TABLE purchases
    DROP CONSTRAINT IF EXISTS purchases_shape_check;
ALTER TABLE purchases
    ADD CONSTRAINT purchases_shape_check CHECK (
        CASE type
            WHEN 'MEDIA_UNLOCK'   THEN media_id IS NOT NULL
            WHEN 'LIVE_ACCESS'    THEN live_session_id IS NOT NULL
            WHEN 'CALL_BOOKING'   THEN call_id IS NOT NULL
            WHEN 'CREATOR_PACKAGE' THEN package_code IS NOT NULL
            WHEN 'LIVE_EXTENSION' THEN extension_minutes IS NOT NULL AND extension_date IS NOT NULL
            WHEN 'CREDIT_TOPUP'   THEN amount_minor > 0
            WHEN 'PROFILE_UNLOCK' THEN target_user_id IS NOT NULL
            WHEN 'SUBSCRIPTION'   THEN plan_code IS NOT NULL
            ELSE false
        END);

-- TOPUP joins the ledger reasons, and is money in like a referral bonus.
ALTER TABLE credit_entries
    DROP CONSTRAINT IF EXISTS credit_reason_check;
ALTER TABLE credit_entries
    ADD CONSTRAINT credit_reason_check CHECK (reason IN
        ('REFERRAL_BONUS', 'TOPUP', 'SPEND', 'REFUND', 'ADJUSTMENT'));

ALTER TABLE credit_entries
    DROP CONSTRAINT IF EXISTS credit_sign_check;
ALTER TABLE credit_entries
    ADD CONSTRAINT credit_sign_check CHECK (
        (reason = 'REFERRAL_BONUS' AND amount_minor > 0) OR
        (reason = 'TOPUP'          AND amount_minor > 0) OR
        (reason = 'SPEND'          AND amount_minor < 0) OR
        (reason IN ('REFUND', 'ADJUSTMENT')));

-- One top-up credits a balance exactly once, however many times settlement is
-- replayed. A unique index rather than a check in code, because that check is a
-- read-then-write race the first time a webhook and the reconciliation sweep
-- land on the same purchase - and the prize for losing it is free money.
CREATE UNIQUE INDEX ux_credit_topup_once ON credit_entries (purchase_id)
    WHERE reason = 'TOPUP';
