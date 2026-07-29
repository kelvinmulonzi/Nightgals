-- Creator earnings and payouts.
--
-- Money is kept as an append-only ledger. A creator's balance is always
-- SUM(net_minor) over their entries in a given status - never a stored number
-- that gets incremented, because that is what drifts and cannot be audited.
--
-- Entry lifecycle:
--   PENDING   just earned, inside the hold period (refunds can still reverse it)
--   AVAILABLE hold elapsed, payable
--   RESERVED  attached to an open payout request; cannot be spent twice
--   PAID      the payout completed
--   REVERSED  refunded or charged back

CREATE TABLE earnings (
    id                UUID PRIMARY KEY,
    creator_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type              VARCHAR(25)  NOT NULL,
    -- The purchase this entry derives from. Null for manual adjustments.
    purchase_id       UUID         REFERENCES purchases (id) ON DELETE SET NULL,
    -- For SUBSCRIPTION_SHARE: the attribution period, e.g. 2026-07.
    period            VARCHAR(7),
    gross_minor       BIGINT       NOT NULL,
    commission_minor  BIGINT       NOT NULL,
    net_minor         BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    available_at      TIMESTAMPTZ  NOT NULL,
    payout_id         UUID,
    note              VARCHAR(300),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT earnings_type_check   CHECK (type IN ('UNLOCK', 'SUBSCRIPTION_SHARE', 'ADJUSTMENT')),
    CONSTRAINT earnings_status_check CHECK (status IN ('PENDING', 'AVAILABLE', 'RESERVED', 'PAID', 'REVERSED')),
    -- Adjustments may be negative; real earnings may not.
    CONSTRAINT earnings_amount_check CHECK (
        (type = 'ADJUSTMENT') OR (gross_minor >= 0 AND commission_minor >= 0 AND net_minor >= 0)),
    CONSTRAINT earnings_split_check  CHECK (net_minor = gross_minor - commission_minor)
);
CREATE INDEX ix_earnings_creator ON earnings (creator_id, status);
CREATE INDEX ix_earnings_release ON earnings (status, available_at) WHERE status = 'PENDING';
CREATE INDEX ix_earnings_payout  ON earnings (payout_id) WHERE payout_id IS NOT NULL;
-- One unlock purchase earns its creator exactly once, however many times
-- settlement is replayed.
CREATE UNIQUE INDEX ux_earnings_unlock_purchase ON earnings (purchase_id)
    WHERE type = 'UNLOCK';
-- One subscription purchase pays a given creator at most once per period.
CREATE UNIQUE INDEX ux_earnings_subscription_share ON earnings (purchase_id, creator_id, period)
    WHERE type = 'SUBSCRIPTION_SHARE';

-- Where to send a creator's money.
CREATE TABLE payout_accounts (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    method       VARCHAR(20)  NOT NULL,
    -- M-Pesa number, or bank account number.
    destination  VARCHAR(60)  NOT NULL,
    -- Name the account is held in; the admin checks it before sending money.
    account_name VARCHAR(150) NOT NULL,
    bank_name    VARCHAR(120),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT payout_accounts_method_check CHECK (method IN ('MPESA', 'BANK_TRANSFER'))
);

CREATE TABLE payouts (
    id             UUID PRIMARY KEY,
    creator_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount_minor   BIGINT       NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
    -- Copied from payout_accounts at request time, so changing the account later
    -- does not rewrite the history of where money was actually sent.
    method         VARCHAR(20)  NOT NULL,
    destination    VARCHAR(60)  NOT NULL,
    account_name   VARCHAR(150) NOT NULL,
    -- The admin's proof of payment, e.g. an M-Pesa transaction code.
    reference      VARCHAR(120),
    rejection_reason VARCHAR(300),
    requested_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMPTZ,
    processed_by   UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT payouts_status_check CHECK (status IN ('REQUESTED', 'APPROVED', 'PAID', 'REJECTED')),
    CONSTRAINT payouts_amount_check CHECK (amount_minor > 0),
    CONSTRAINT payouts_method_check CHECK (method IN ('MPESA', 'BANK_TRANSFER'))
);
CREATE INDEX ix_payouts_creator ON payouts (creator_id, created_at DESC);
CREATE INDEX ix_payouts_queue   ON payouts (status, requested_at) WHERE status IN ('REQUESTED', 'APPROVED');
-- A creator may only have one payout in flight. This is what makes reserving
-- their balance race-free without row locks.
CREATE UNIQUE INDEX ux_payouts_one_open ON payouts (creator_id)
    WHERE status IN ('REQUESTED', 'APPROVED');

ALTER TABLE earnings ADD CONSTRAINT fk_earnings_payout
    FOREIGN KEY (payout_id) REFERENCES payouts (id) ON DELETE SET NULL;

-- Which creators a subscriber actually looked at, for user-centric attribution.
-- One row per (subscriber, creator, period), so repeated viewing does not
-- inflate a creator's share.
CREATE TABLE premium_views (
    id          UUID PRIMARY KEY,
    viewer_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    creator_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    period      VARCHAR(7)  NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT premium_views_no_self_check CHECK (viewer_id <> creator_id)
);
CREATE UNIQUE INDEX ux_premium_views ON premium_views (viewer_id, creator_id, period);
CREATE INDEX ix_premium_views_period ON premium_views (period, viewer_id);
