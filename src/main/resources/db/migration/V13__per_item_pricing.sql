-- Pricing moves from the creator to the individual item.
--
-- Until now a viewer bought a creator: one payment opened everything she had
-- posted. The brief prices each thing separately - "users can set their own
-- unlock price for every premium video they upload", and "each live stream can
-- also have its own access price". So the unit of sale becomes the video and
-- the broadcast, not the person.
--
-- Nobody loses access in the change: every profile unlock currently in force is
-- expanded into per-item unlocks covering exactly what it covered.

-- ---------------------------------------------------------------- item prices
ALTER TABLE media_assets ADD COLUMN unlock_price_minor BIGINT;
ALTER TABLE media_assets ADD CONSTRAINT media_price_check
    CHECK (unlock_price_minor IS NULL OR unlock_price_minor >= 0);
-- A FREE item is the shop window and is never priced; the price only means
-- anything on an EXCLUSIVE one.
COMMENT ON COLUMN media_assets.unlock_price_minor IS
    'What a viewer pays for this one item. NULL falls back to the platform default.';

ALTER TABLE live_sessions ADD COLUMN access_price_minor BIGINT;
ALTER TABLE live_sessions ADD CONSTRAINT live_price_check
    CHECK (access_price_minor IS NULL OR access_price_minor >= 0);

-- ---------------------------------------------------------------- purchases
-- Three new things can be bought, each pointing at one row.
ALTER TABLE purchases ADD COLUMN media_id        UUID REFERENCES media_assets (id) ON DELETE SET NULL;
ALTER TABLE purchases ADD COLUMN live_session_id UUID REFERENCES live_sessions (id) ON DELETE SET NULL;
ALTER TABLE purchases ADD COLUMN call_id         UUID;

ALTER TABLE purchases DROP CONSTRAINT purchases_type_check;
ALTER TABLE purchases ADD CONSTRAINT purchases_type_check CHECK (type IN (
    'MEDIA_UNLOCK', 'LIVE_ACCESS', 'CALL_BOOKING', 'CREATOR_PACKAGE',
    -- Retired, but historical rows still carry them and receipts still render.
    'PROFILE_UNLOCK', 'SUBSCRIPTION'));

-- The old shape check enumerated every combination and had already been
-- rewritten twice. Replaced with one rule per type, which is the thing anybody
-- reading this actually wants to know.
ALTER TABLE purchases DROP CONSTRAINT purchases_target_check;
ALTER TABLE purchases ADD CONSTRAINT purchases_target_check CHECK (
    CASE type
        WHEN 'MEDIA_UNLOCK'    THEN media_id        IS NOT NULL
        WHEN 'LIVE_ACCESS'     THEN live_session_id IS NOT NULL
        WHEN 'CALL_BOOKING'    THEN call_id         IS NOT NULL
        WHEN 'CREATOR_PACKAGE' THEN package_code    IS NOT NULL
        WHEN 'PROFILE_UNLOCK'  THEN target_user_id  IS NOT NULL
        WHEN 'SUBSCRIPTION'    THEN plan_code       IS NOT NULL
        ELSE FALSE
    END);

-- ---------------------------------------------------------------- unlocks
CREATE TABLE media_unlocks (
    id          UUID PRIMARY KEY,
    viewer_id   UUID        NOT NULL REFERENCES users (id)         ON DELETE CASCADE,
    media_id    UUID        NOT NULL REFERENCES media_assets (id)  ON DELETE CASCADE,
    source      VARCHAR(20) NOT NULL DEFAULT 'PURCHASE',
    purchase_id UUID        REFERENCES purchases (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT media_unlocks_source_check CHECK (source IN ('PURCHASE', 'GRANT', 'MIGRATED'))
);
-- Buying the same item twice is impossible rather than merely discouraged.
CREATE UNIQUE INDEX ux_media_unlocks ON media_unlocks (viewer_id, media_id);
CREATE INDEX ix_media_unlocks_viewer ON media_unlocks (viewer_id, created_at DESC);

CREATE TABLE live_access (
    id          UUID PRIMARY KEY,
    viewer_id   UUID        NOT NULL REFERENCES users (id)          ON DELETE CASCADE,
    session_id  UUID        NOT NULL REFERENCES live_sessions (id)  ON DELETE CASCADE,
    source      VARCHAR(20) NOT NULL DEFAULT 'PURCHASE',
    purchase_id UUID        REFERENCES purchases (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT live_access_source_check CHECK (source IN ('PURCHASE', 'GRANT', 'MIGRATED'))
);
CREATE UNIQUE INDEX ux_live_access ON live_access (viewer_id, session_id);
CREATE INDEX ix_live_access_viewer ON live_access (viewer_id, created_at DESC);

-- ---------------------------------------------------------------- carry over
-- Everything a live profile unlock currently covers becomes an explicit
-- per-item unlock. Marked MIGRATED so it is obvious later that these were not
-- bought one by one.
INSERT INTO media_unlocks (id, viewer_id, media_id, source, created_at)
SELECT gen_random_uuid(), u.viewer_id, m.id, 'MIGRATED', u.created_at
FROM profile_unlocks u
JOIN media_assets m ON m.user_id = u.target_id
WHERE (u.expires_at IS NULL OR u.expires_at > NOW())
ON CONFLICT DO NOTHING;

INSERT INTO live_access (id, viewer_id, session_id, source, created_at)
SELECT gen_random_uuid(), u.viewer_id, s.id, 'MIGRATED', u.created_at
FROM profile_unlocks u
JOIN live_sessions s ON s.host_id = u.target_id
WHERE (u.expires_at IS NULL OR u.expires_at > NOW())
ON CONFLICT DO NOTHING;

-- A whole-creator subscription no longer exists, so the price on the profile has
-- nothing left to price. Item prices replace it.
ALTER TABLE profiles DROP CONSTRAINT profiles_unlock_price_check;
ALTER TABLE profiles DROP COLUMN unlock_price_minor;

-- profile_unlocks itself is kept: the rows are the record of what people paid
-- for under the old model, and the earnings ledger still points at those
-- purchases. Nothing writes to it any more.
