-- Burning an account, and the record of who did it.
--
-- `status = 'SUSPENDED'` already existed and already refused sign-in, but it was
-- a dead end: nothing could set it, and it hid nothing. A burned creator kept
-- her profile, her gallery, her reels and her live room in front of the public
-- for as long as anyone had the URL.
--
-- These three columns are the paperwork rather than the switch. The switch is
-- `status`; this is what lets the console say who burned an account, when, and
-- why - which is the difference between a moderation tool and a button that
-- makes people disappear for reasons nobody can reconstruct later.
ALTER TABLE users
    ADD COLUMN suspended_at     TIMESTAMPTZ,
    ADD COLUMN suspended_reason VARCHAR(500),
    -- Nullable and ON DELETE SET NULL: an administrator's own account may be
    -- closed one day, and losing the record of who acted is better than either
    -- refusing to delete them or dropping the suspension along with them.
    ADD COLUMN suspended_by_id  UUID REFERENCES users (id) ON DELETE SET NULL;

-- Every public listing filters on it, so the console's own "show me everyone
-- burned" is the only query that reads it the other way round.
CREATE INDEX idx_users_status ON users (status);
