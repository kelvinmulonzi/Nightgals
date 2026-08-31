-- How many people have looked at a profile, a video, a reel.
--
-- Two structures, because a counter and a ledger answer different questions and
-- neither does the other's job:
--
--   · the counters live on the rows themselves, so reading "1 284 views" costs
--     nothing on a page already loading that row. A COUNT(*) over a view table
--     on every card in Discover would be twenty counts per page load.
--
--   · content_views is the ledger that makes the counters mean something. Without
--     it a refresh is a view, and a creator sitting on her own profile is a
--     popular creator. One row per viewer, per item, per UTC day - so the number
--     is people-who-looked rather than times-a-page-rendered.
--
-- The ledger is what keeps the counters honest; the counters are what keep the
-- ledger off the read path.
ALTER TABLE profiles     ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE media_assets ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reels        ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;

CREATE TABLE content_views (
    id           UUID PRIMARY KEY,
    -- PROFILE, MEDIA or REEL. Not a foreign key to three different tables:
    -- one ledger keeps the dedup rule in a single place, and the counter on the
    -- subject row is what any read actually uses.
    subject_type VARCHAR(16)  NOT NULL,
    subject_id   UUID         NOT NULL,

    -- Who looked. The account when there is one; otherwise a hash of address and
    -- user agent, which is enough to tell two people apart for a day without
    -- storing anything that identifies either of them.
    viewer_key   VARCHAR(64)  NOT NULL,
    viewer_id    UUID REFERENCES users (id) ON DELETE SET NULL,

    -- The UTC day this counted for. Part of the key rather than derived from
    -- viewed_at, so the uniqueness rule is enforced by the database instead of
    -- by everybody who ever writes to this table.
    viewed_on    DATE         NOT NULL,
    viewed_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- The dedup rule itself. An insert that violates it is the normal case - the
-- same person looking twice - so writers use ON CONFLICT DO NOTHING and treat
-- "no row inserted" as "already counted", which is also how they know not to
-- bump the counter.
CREATE UNIQUE INDEX uq_content_views_daily
    ON content_views (subject_type, subject_id, viewer_key, viewed_on);

-- For the dashboards: what was looked at over a period, newest first.
CREATE INDEX idx_content_views_recent ON content_views (viewed_on DESC, subject_type);
