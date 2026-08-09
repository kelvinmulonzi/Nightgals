-- The profile picture, as its own thing.
--
-- It used to be a media_assets row carrying primary = true, which tangled two
-- unrelated ideas: an avatar is part of who you are, a gallery item is something
-- you publish and may charge for. Sharing the table meant a profile picture had
-- a tier, a price, a moderation status and a position in the grid, none of which
-- mean anything for an avatar - and it forced the picture to be one of the free
-- photos, so setting one published it as gallery content too.
--
-- Stored on the profile, next to the display name and bio it belongs with.
ALTER TABLE profiles
    ADD COLUMN avatar_storage_key  VARCHAR(500),
    ADD COLUMN avatar_content_type VARCHAR(100);

-- Both or neither: a key with no content type cannot be served, and a content
-- type with no key points at nothing.
ALTER TABLE profiles
    ADD CONSTRAINT profiles_avatar_pair_check CHECK (
        (avatar_storage_key IS NULL AND avatar_content_type IS NULL)
        OR (avatar_storage_key IS NOT NULL AND avatar_content_type IS NOT NULL));

COMMENT ON COLUMN profiles.avatar_storage_key IS
    'Profile picture. Independent of media_assets - not published, not priced, not moderated as gallery content.';
