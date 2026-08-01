-- Creators pay the platform for the right to publish.
--
-- Three packages, and the package decides both what a creator may upload and how
-- much of it:
--
--   BRONZE  photos only, a small allowance     - the cheap way in
--   SILVER  videos only, a small allowance     - for creators who only shoot video
--   GOLD    photos and video, a large allowance
--
-- This is the platform's revenue from the supply side. It is separate from what
-- viewers pay, which goes to the creator (less commission) and is handled by
-- profile_unlocks.
--
-- Limits live in configuration rather than in this table, because they are a
-- commercial decision that changes without a schema migration. The package a
-- creator bought is what is recorded here.

-- ---------------------------------------------------------------- purchases
-- A third thing can now be bought. The type check and the shape check both have
-- to learn about it.
ALTER TABLE purchases ADD COLUMN package_code VARCHAR(30);

ALTER TABLE purchases DROP CONSTRAINT purchases_type_check;
ALTER TABLE purchases ADD CONSTRAINT purchases_type_check
    CHECK (type IN ('PROFILE_UNLOCK', 'SUBSCRIPTION', 'CREATOR_PACKAGE'));

ALTER TABLE purchases DROP CONSTRAINT purchases_target_check;
ALTER TABLE purchases ADD CONSTRAINT purchases_target_check CHECK (
    (type = 'PROFILE_UNLOCK'  AND target_user_id IS NOT NULL AND plan_code IS NULL     AND package_code IS NULL) OR
    (type = 'SUBSCRIPTION'    AND plan_code      IS NOT NULL AND target_user_id IS NULL AND package_code IS NULL) OR
    (type = 'CREATOR_PACKAGE' AND package_code   IS NOT NULL AND target_user_id IS NULL AND plan_code    IS NULL));

-- ---------------------------------------------------------------- packages
CREATE TABLE creator_packages (
    id           UUID PRIMARY KEY,
    creator_id   UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    package_code VARCHAR(20)  NOT NULL,
    starts_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    cancelled_at TIMESTAMPTZ,
    purchase_id  UUID         REFERENCES purchases (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT creator_packages_code_check   CHECK (package_code IN ('BRONZE', 'SILVER', 'GOLD')),
    CONSTRAINT creator_packages_period_check CHECK (expires_at > starts_at)
);

-- Read on every upload, to answer "may this creator post this, and have they
-- room left?". Ordered by expiry so the newest cover is found first.
CREATE INDEX ix_creator_packages_active ON creator_packages (creator_id, expires_at DESC);

-- Existing creators keep publishing.
--
-- Anyone already verified was posting under the old rules, where posting was
-- free. Cutting them off retroactively would be a bait and switch, so they are
-- granted a year of GOLD. New creators buy a package like everyone else.
INSERT INTO creator_packages (id, creator_id, package_code, starts_at, expires_at)
SELECT gen_random_uuid(), u.id, 'GOLD', NOW(), NOW() + INTERVAL '365 days'
FROM users u
WHERE u.account_type = 'CREATOR'
  AND u.verification_status = 'APPROVED';
