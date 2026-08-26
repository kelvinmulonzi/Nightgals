-- The verified badge stops meaning "has an account that was approved" and starts
-- meaning "a human checked this person's identity documents".
--
-- Those were the same thing while identity checks were required. They stopped
-- being the same thing when KYC_REQUIRED went to false: approval is now granted
-- automatically the moment a profile is saved, so a badge that reads
-- verification_status has been telling viewers a document was checked when none
-- was ever uploaded.
--
-- verification_status keeps its job. It is the publishing gate and nine checks
-- depend on it, so it is left exactly as it is; this column carries the claim the
-- badge makes, and nothing else reads it.
ALTER TABLE users
    ADD COLUMN identity_verified_at TIMESTAMPTZ;

COMMENT ON COLUMN users.identity_verified_at IS
    'When a reviewer approved this account''s identity documents. NULL means the badge is not shown. Distinct from verification_status, which gates publishing.';

-- Everyone wearing the badge today keeps it.
--
-- The alternative was to grant it only to the accounts with a real approved
-- submission, which would have taken the badge off people who have done nothing
-- wrong - they were approved under the rule that was in force at the time. So the
-- line is drawn here rather than backwards: existing badges stand, and from this
-- migration onward the only thing that grants one is a reviewer approving
-- documents.
--
-- Dated from the actual review where there was one, so the 16 accounts that did
-- pass a real check carry the date it happened rather than the date of this
-- deployment.
UPDATE users u
SET identity_verified_at = COALESCE(
        (SELECT MAX(k.reviewed_at)
         FROM kyc_submissions k
         WHERE k.user_id = u.id AND k.status = 'APPROVED'),
        NOW())
WHERE u.verification_status = 'APPROVED';
