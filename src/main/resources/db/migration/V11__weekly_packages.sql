-- The subscription packages from the product brief.
--
--   PRO            5 000 XAF / week   15 min live per day,   2 premium videos
--   DIAMOND       10 000 XAF / week   45 min live per day,   5 premium videos
--   BLACK_DIAMOND 15 000 XAF / week    2 h live per day,    10 premium videos
--
-- Three things change from the bronze/silver/gold set they replace:
--
--  * they run weekly rather than monthly;
--  * every tier covers both photos and video, so the tier no longer decides
--    *what* may be posted, only how much of it and how visible it is;
--  * each carries a search-priority rank, which is what "highest priority in
--    search results and homepage listings" means in practice.
--
-- Prices and allowances stay in configuration; this migration only moves the
-- set of codes and the rows already carrying the old ones.

-- ---------------------------------------------------------------- packages
ALTER TABLE creator_packages DROP CONSTRAINT creator_packages_code_check;

-- Mapped by rank, not by what they covered: gold was the top tier and stays the
-- top tier. Nobody loses cover, and nobody is silently downgraded.
UPDATE creator_packages SET package_code = 'BLACK_DIAMOND' WHERE package_code = 'GOLD';
UPDATE creator_packages SET package_code = 'DIAMOND'       WHERE package_code = 'SILVER';
UPDATE creator_packages SET package_code = 'PRO'           WHERE package_code = 'BRONZE';

ALTER TABLE creator_packages ADD CONSTRAINT creator_packages_code_check
    CHECK (package_code IN ('PRO', 'DIAMOND', 'BLACK_DIAMOND'));

-- Same mapping on the purchase history, so a receipt still names a package that
-- exists.
UPDATE purchases SET package_code = 'BLACK_DIAMOND' WHERE package_code = 'GOLD';
UPDATE purchases SET package_code = 'DIAMOND'       WHERE package_code = 'SILVER';
UPDATE purchases SET package_code = 'PRO'           WHERE package_code = 'BRONZE';

-- ---------------------------------------------------------------- live usage
-- How many live minutes a creator has burned on a given day.
--
-- One row per creator per day, incremented as sessions end. A counter rather
-- than a SUM over sessions because the quota is checked before every broadcast
-- and on a busy host that would scan an unbounded history.
CREATE TABLE live_usage_daily (
    id            UUID PRIMARY KEY,
    creator_id    UUID  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- UTC day. The reset is at midnight UTC for everyone rather than in each
    -- creator's local zone: one rule is explainable, twenty-four are not.
    usage_date    DATE  NOT NULL,
    minutes_used  INT   NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT live_usage_minutes_check CHECK (minutes_used >= 0)
);
CREATE UNIQUE INDEX ux_live_usage_day ON live_usage_daily (creator_id, usage_date);
