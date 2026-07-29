-- Free vs exclusive content.
--
-- Until now the split was positional: the first N photos by display order were
-- the free preview and everything after was paid. That gave creators no say in
-- which item is the teaser. Now every piece of content carries its own tier.
--
--   FREE       anyone can see it, including anonymous visitors. The shop window.
--   EXCLUSIVE  behind the paywall - unlock the creator or subscribe.

ALTER TABLE media_assets ADD COLUMN tier VARCHAR(10) NOT NULL DEFAULT 'EXCLUSIVE';
ALTER TABLE media_assets ADD CONSTRAINT media_tier_check CHECK (tier IN ('FREE', 'EXCLUSIVE'));

-- Preserve what people can currently see: the profile picture was the free
-- preview under the positional rule, so it stays free. Everything else was
-- already locked.
UPDATE media_assets SET tier = 'FREE' WHERE is_primary = TRUE;

-- The feed reads free photos for every creator on a page.
CREATE INDEX ix_media_tier ON media_assets (user_id, tier, status);

-- Live sessions get the same choice: a creator can run an open broadcast to
-- attract people, or keep it for paying viewers.
ALTER TABLE live_sessions ADD COLUMN tier VARCHAR(10) NOT NULL DEFAULT 'EXCLUSIVE';
ALTER TABLE live_sessions ADD CONSTRAINT live_tier_check CHECK (tier IN ('FREE', 'EXCLUSIVE'));
