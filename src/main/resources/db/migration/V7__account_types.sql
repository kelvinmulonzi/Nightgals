-- Viewers and creators are different kinds of account.
--
-- Conflating them is what pushed creator onboarding - create a profile, upload a
-- passport - at people who only ever wanted to watch. Both still sign up with an
-- email; what differs is what the app asks of them next.

ALTER TABLE users ADD COLUMN account_type VARCHAR(10);

-- Backfill from behaviour rather than guessing: anyone who submitted identity
-- documents or posted content was acting as a creator.
UPDATE users u SET account_type = 'CREATOR'
WHERE EXISTS (SELECT 1 FROM kyc_submissions k WHERE k.user_id = u.id)
   OR EXISTS (SELECT 1 FROM media_assets m WHERE m.user_id = u.id)
   OR u.role IN ('ADMIN', 'MODERATOR');

UPDATE users SET account_type = 'VIEWER' WHERE account_type IS NULL;

ALTER TABLE users ALTER COLUMN account_type SET NOT NULL;
ALTER TABLE users ALTER COLUMN account_type SET DEFAULT 'VIEWER';
ALTER TABLE users ADD CONSTRAINT users_account_type_check
    CHECK (account_type IN ('VIEWER', 'CREATOR'));

CREATE INDEX ix_users_account_type ON users (account_type);
