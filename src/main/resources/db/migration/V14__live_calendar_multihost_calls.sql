-- Scheduling, co-hosting, followers and paid 1-to-1 calls.
--
-- None of this ships a media server. The platform owns who may join, what it
-- costs and when it happens; the audio and video themselves ride on whatever
-- provider is plugged into the URL columns. Everything below is the half that
-- has to be right whichever provider that turns out to be.

-- ---------------------------------------------------------------- calendar
-- scheduled_for already exists and holds the start instant. What a creator
-- actually fills in is date, start time and duration, so the duration is what
-- is missing.
ALTER TABLE live_sessions ADD COLUMN duration_minutes INT;
ALTER TABLE live_sessions ADD CONSTRAINT live_duration_check
    CHECK (duration_minutes IS NULL OR (duration_minutes > 0 AND duration_minutes <= 720));

-- Set when followers have been told, so a restart or a second sweep cannot mail
-- the same people twice.
ALTER TABLE live_sessions ADD COLUMN reminder_sent_at TIMESTAMPTZ;

-- Drives the reminder sweep: upcoming sessions nobody has been told about yet.
CREATE INDEX ix_live_reminders ON live_sessions (scheduled_for)
    WHERE status = 'SCHEDULED' AND reminder_sent_at IS NULL;

-- ---------------------------------------------------------------- followers
-- "Scheduled live events will appear on their profile so followers can receive
-- reminders" - which needs followers, and there were none.
--
-- Deliberately not mutual and not approved: following is a subscription to
-- somebody's schedule, not a relationship.
CREATE TABLE follows (
    id           UUID PRIMARY KEY,
    follower_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    creator_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- A follower who does not want mail about every broadcast can keep the
    -- follow and drop the reminder.
    remind       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT follows_no_self_check CHECK (follower_id <> creator_id)
);
CREATE UNIQUE INDEX ux_follows ON follows (follower_id, creator_id);
-- The reminder sweep reads "who follows this creator and wants telling".
CREATE INDEX ix_follows_creator ON follows (creator_id) WHERE remind = TRUE;

-- ---------------------------------------------------------------- multi-host
-- A session can be broadcast by several people together.
--
-- The owner is still live_sessions.host_id - somebody has to own the row, the
-- quota and the money. Co-hosts are invitations to appear in it.
CREATE TABLE live_hosts (
    id           UUID PRIMARY KEY,
    session_id   UUID        NOT NULL REFERENCES live_sessions (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id)         ON DELETE CASCADE,
    role         VARCHAR(20) NOT NULL DEFAULT 'CO_HOST',
    status       VARCHAR(20) NOT NULL DEFAULT 'INVITED',
    responded_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT live_hosts_role_check   CHECK (role   IN ('OWNER', 'CO_HOST')),
    CONSTRAINT live_hosts_status_check CHECK (status IN ('INVITED', 'ACCEPTED', 'DECLINED', 'REMOVED'))
);
CREATE UNIQUE INDEX ux_live_hosts ON live_hosts (session_id, user_id);
-- "What have I been invited to?"
CREATE INDEX ix_live_hosts_user ON live_hosts (user_id, status);

-- Every existing session gets its host recorded as the owner, so the roster is
-- complete from the start rather than empty until somebody invites a co-host.
INSERT INTO live_hosts (id, session_id, user_id, role, status, responded_at)
SELECT gen_random_uuid(), s.id, s.host_id, 'OWNER', 'ACCEPTED', s.created_at
FROM live_sessions s;

-- ---------------------------------------------------------------- calls
-- What a creator charges for a private call, by length.
--
-- One row per creator per duration. The six durations in the brief are
-- suggestions, so the length is stored rather than enumerated - a creator who
-- only wants to offer 15 and 60 simply has two rows.
CREATE TABLE call_rates (
    id               UUID PRIMARY KEY,
    creator_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    duration_minutes INT         NOT NULL,
    price_minor      BIGINT      NOT NULL,
    currency         VARCHAR(3)  NOT NULL,
    -- Kept rather than deleted when withdrawn, so a booking that references it
    -- still explains what was charged.
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT call_rates_duration_check CHECK (duration_minutes > 0 AND duration_minutes <= 240),
    CONSTRAINT call_rates_price_check    CHECK (price_minor >= 0)
);
CREATE UNIQUE INDEX ux_call_rates ON call_rates (creator_id, duration_minutes);
CREATE INDEX ix_call_rates_creator ON call_rates (creator_id) WHERE active = TRUE;

CREATE TABLE video_calls (
    id               UUID PRIMARY KEY,
    creator_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    viewer_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Copied from the rate at booking time: changing the price list later must
    -- not rewrite what somebody already agreed to pay.
    duration_minutes INT         NOT NULL,
    price_minor      BIGINT      NOT NULL,
    currency         VARCHAR(3)  NOT NULL,
    scheduled_for    TIMESTAMPTZ NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT',
    -- The provider's room, handed out only once the call is paid for and only
    -- to its two participants.
    room_url         VARCHAR(1000),
    started_at       TIMESTAMPTZ,
    ended_at         TIMESTAMPTZ,
    cancelled_reason VARCHAR(300),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT video_calls_status_check CHECK (status IN
        ('PENDING_PAYMENT', 'CONFIRMED', 'LIVE', 'COMPLETED', 'CANCELLED', 'DECLINED')),
    CONSTRAINT video_calls_no_self_check  CHECK (creator_id <> viewer_id),
    CONSTRAINT video_calls_duration_check CHECK (duration_minutes > 0),
    CONSTRAINT video_calls_price_check    CHECK (price_minor >= 0)
);
CREATE INDEX ix_video_calls_creator ON video_calls (creator_id, scheduled_for DESC);
CREATE INDEX ix_video_calls_viewer  ON video_calls (viewer_id, scheduled_for DESC);
-- A creator cannot be in two calls at once. Partial, so a cancelled booking
-- frees the slot again.
CREATE UNIQUE INDEX ux_video_calls_slot ON video_calls (creator_id, scheduled_for)
    WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED', 'LIVE');

-- Deferred to here because video_calls did not exist when the column was added.
ALTER TABLE purchases ADD CONSTRAINT fk_purchases_call
    FOREIGN KEY (call_id) REFERENCES video_calls (id) ON DELETE SET NULL;
