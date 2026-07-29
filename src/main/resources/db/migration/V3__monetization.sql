-- Paid access.
--
-- Browsing is free: an approved member scrolls a feed of profile cards. Seeing
-- somebody's photos, video or live session is what costs money.
--
-- Two ways to get that access, both resolved by the same entitlement check:
--   * a subscription, which unlocks everybody for its duration
--   * a one-off unlock of a single profile
--
-- No payment provider is integrated yet. Purchases are created PENDING and
-- settled by whatever provider is wired in later (M-Pesa Daraja being the
-- obvious first one); until then an administrator confirms them by hand.

CREATE TABLE purchases (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type                VARCHAR(20)  NOT NULL,
    -- Set only for PROFILE_UNLOCK: whose profile is being unlocked.
    target_user_id      UUID         REFERENCES users (id) ON DELETE CASCADE,
    -- Set only for SUBSCRIPTION: which plan from configuration.
    plan_code           VARCHAR(30),
    amount_minor        BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    provider            VARCHAR(30)  NOT NULL,
    -- The provider's own id for this payment. Unique so a webhook replay cannot
    -- settle the same purchase twice.
    provider_reference  VARCHAR(120),
    failure_reason      VARCHAR(200),
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT purchases_type_check   CHECK (type   IN ('PROFILE_UNLOCK', 'SUBSCRIPTION')),
    CONSTRAINT purchases_status_check CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT purchases_amount_check CHECK (amount_minor >= 0),
    CONSTRAINT purchases_target_check CHECK (
        (type = 'PROFILE_UNLOCK' AND target_user_id IS NOT NULL AND plan_code IS NULL) OR
        (type = 'SUBSCRIPTION'   AND plan_code     IS NOT NULL AND target_user_id IS NULL))
);
CREATE INDEX ix_purchases_user ON purchases (user_id, created_at DESC);
CREATE INDEX ix_purchases_pending ON purchases (status, created_at) WHERE status = 'PENDING';
CREATE UNIQUE INDEX ux_purchases_provider_ref ON purchases (provider, provider_reference)
    WHERE provider_reference IS NOT NULL;

CREATE TABLE subscriptions (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_code     VARCHAR(30)  NOT NULL,
    starts_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    cancelled_at  TIMESTAMPTZ,
    purchase_id   UUID         REFERENCES purchases (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT subscriptions_period_check CHECK (expires_at > starts_at)
);
-- The entitlement check reads this on nearly every profile view.
CREATE INDEX ix_subscriptions_active ON subscriptions (user_id, expires_at DESC);

CREATE TABLE profile_unlocks (
    id           UUID PRIMARY KEY,
    viewer_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source       VARCHAR(20)  NOT NULL DEFAULT 'PURCHASE',
    -- NULL means the unlock never expires.
    expires_at   TIMESTAMPTZ,
    purchase_id  UUID         REFERENCES purchases (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT profile_unlocks_source_check CHECK (source IN ('PURCHASE', 'GRANT')),
    CONSTRAINT profile_unlocks_no_self_check CHECK (viewer_id <> target_id)
);
CREATE UNIQUE INDEX ux_profile_unlocks_pair ON profile_unlocks (viewer_id, target_id);
CREATE INDEX ix_profile_unlocks_viewer ON profile_unlocks (viewer_id);

-- Live sessions hold metadata only. The app does not ingest or transcode video;
-- playback_url points at whatever streaming provider is used.
CREATE TABLE live_sessions (
    id            UUID PRIMARY KEY,
    host_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title         VARCHAR(120) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    playback_url  VARCHAR(1000),
    scheduled_for TIMESTAMPTZ,
    started_at    TIMESTAMPTZ,
    ended_at      TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT live_sessions_status_check CHECK (status IN ('SCHEDULED', 'LIVE', 'ENDED', 'CANCELLED'))
);
CREATE INDEX ix_live_sessions_host ON live_sessions (host_id, created_at DESC);
CREATE INDEX ix_live_sessions_live ON live_sessions (status, started_at DESC) WHERE status = 'LIVE';
