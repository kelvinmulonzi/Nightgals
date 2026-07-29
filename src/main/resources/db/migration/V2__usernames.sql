-- Public pseudonyms.
--
-- Members are identified to each other by a generated handle, never by the legal
-- name on their identity document. Verification proves who someone is to us; it
-- does not expose them to the rest of the app.

ALTER TABLE users ADD COLUMN username VARCHAR(30);

-- Backfill existing rows deterministically from the primary key, so the unique
-- index below cannot collide.
UPDATE users SET username = 'member_' || substr(replace(id::text, '-', ''), 1, 10)
WHERE username IS NULL;

ALTER TABLE users ALTER COLUMN username SET NOT NULL;
CREATE UNIQUE INDEX ux_users_username ON users (LOWER(username));

-- Drives the change cooldown.
ALTER TABLE users ADD COLUMN username_changed_at TIMESTAMPTZ;

-- The display name is now optional and private. Members who want to be known by
-- something other than their handle can set it, but it is never shown publicly.
ALTER TABLE profiles ALTER COLUMN display_name DROP NOT NULL;
