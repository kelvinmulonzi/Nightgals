-- Short clips posted by staff to the public site, gone after a day.
--
-- Not media_assets: those belong to a creator, sit behind a paywall, count
-- against a package allowance and are permanent. A reel is none of those - it is
-- promotional, free to everyone including signed-out visitors, owned by the
-- platform, and it deletes itself. Sharing a table would mean every query about
-- creator content growing an "and not a reel" clause.
CREATE TABLE reels (
    id            UUID PRIMARY KEY,

    -- Who posted it. Kept for the audit trail; a reel is the platform's, not
    -- theirs, so it is not shown to viewers.
    posted_by     UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,

    storage_key   VARCHAR(500) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    caption       VARCHAR(300),

    -- When it stops being shown. Stored rather than computed from created_at so
    -- the lifetime can be changed later without silently resurrecting or killing
    -- reels that already exist.
    expires_at    TIMESTAMPTZ NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT reels_size_check   CHECK (size_bytes > 0),
    CONSTRAINT reels_expiry_check CHECK (expires_at > created_at)
);

-- The public listing is "still live, newest first", which is this index exactly.
CREATE INDEX ix_reels_live ON reels (expires_at DESC, created_at DESC);
