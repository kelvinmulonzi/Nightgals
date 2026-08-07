-- Buying more live minutes for today.
--
-- The daily allowance is what a package sells; running out mid-broadcast should
-- not mean being cut off with an audience watching. A creator can buy extra
-- minutes for the current day, and they expire with it - this tops up today, it
-- does not upgrade the package.

-- Minutes bought, held on the same row as the minutes used so "what is my
-- allowance today" stays a single lookup. Separate from minutes_used because
-- they answer different questions: one is consumption, one is entitlement, and
-- adding bought minutes to the used count would make her look busier the more
-- she paid.
ALTER TABLE live_usage_daily
    ADD COLUMN bonus_minutes INT NOT NULL DEFAULT 0;

ALTER TABLE live_usage_daily
    ADD CONSTRAINT live_usage_bonus_check CHECK (bonus_minutes >= 0);

-- How many minutes a purchase bought, and for which day. Null for every other
-- purchase type.
--
-- The day is stored rather than derived from created_at: a purchase started at
-- 23:59 and settled at 00:01 belongs to the day it was bought for, not the day
-- the money landed.
ALTER TABLE purchases
    ADD COLUMN extension_minutes INT,
    ADD COLUMN extension_date DATE;

ALTER TABLE purchases
    ADD CONSTRAINT purchases_extension_minutes_check
        CHECK (extension_minutes IS NULL OR extension_minutes > 0);

-- LIVE_EXTENSION joins the allowed types.
ALTER TABLE purchases
    DROP CONSTRAINT IF EXISTS purchases_type_check;
ALTER TABLE purchases
    ADD CONSTRAINT purchases_type_check CHECK (type IN (
        'MEDIA_UNLOCK', 'LIVE_ACCESS', 'CALL_BOOKING', 'CREATOR_PACKAGE',
        'LIVE_EXTENSION', 'PROFILE_UNLOCK', 'SUBSCRIPTION'));

-- ...and gets a shape rule of its own. The CASE has an ELSE false, so a new type
-- without a branch here is rejected outright rather than allowed through
-- unchecked - which is the behaviour worth keeping, and the reason this has to
-- be rewritten rather than added alongside.
--
-- The existing constraint is named purchases_target_check, from when it only
-- guarded target_user_id. Dropping the name it actually has matters: adding a
-- second constraint and leaving the first in place would reject every
-- LIVE_EXTENSION row on the old ELSE false.
ALTER TABLE purchases
    DROP CONSTRAINT IF EXISTS purchases_target_check;
ALTER TABLE purchases
    DROP CONSTRAINT IF EXISTS purchases_shape_check;
ALTER TABLE purchases
    ADD CONSTRAINT purchases_shape_check CHECK (
        CASE type
            WHEN 'MEDIA_UNLOCK'   THEN media_id IS NOT NULL
            WHEN 'LIVE_ACCESS'    THEN live_session_id IS NOT NULL
            WHEN 'CALL_BOOKING'   THEN call_id IS NOT NULL
            WHEN 'CREATOR_PACKAGE' THEN package_code IS NOT NULL
            WHEN 'LIVE_EXTENSION' THEN extension_minutes IS NOT NULL AND extension_date IS NOT NULL
            WHEN 'PROFILE_UNLOCK' THEN target_user_id IS NOT NULL
            WHEN 'SUBSCRIPTION'   THEN plan_code IS NOT NULL
            ELSE false
        END);

COMMENT ON COLUMN live_usage_daily.bonus_minutes IS
    'Minutes bought for this day on top of the package allowance. Expires with the day.';
