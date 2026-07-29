-- Media no longer waits for review.
--
-- Identity verification is the gate. A creator who has passed KYC is trusted to
-- post, so uploads publish immediately instead of queueing for a moderator.
--
-- The status column stays, but its meaning changes: it is now a takedown flag
-- rather than a publication gate. REJECTED means a moderator removed something
-- after the fact, which is the only remedy for illegal content.

-- Nothing should be stranded in a queue that no longer exists.
UPDATE media_assets SET status = 'APPROVED' WHERE status = 'PENDING_REVIEW';

ALTER TABLE media_assets ALTER COLUMN status SET DEFAULT 'APPROVED';

-- The old partial index served the review queue; it is now always empty.
DROP INDEX IF EXISTS ix_media_queue;
-- Replaced by one that supports the moderator's "recently posted" view.
CREATE INDEX ix_media_recent ON media_assets (created_at DESC);
