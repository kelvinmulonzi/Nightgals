-- Each creator names her own price, and gender narrows to two values.

-- ---------------------------------------------------------------- pricing
-- What a viewer pays to see everything one creator has posted.
--
-- One price per creator, not per media type: a viewer picks somebody and buys
-- all of her content. Splitting that into photo and video tiers was considered
-- and dropped - it makes the buy button a menu, and a menu is a decision, and a
-- decision is where people leave.
--
-- NULL means "use the platform default", so a creator who never touches this
-- still has a working price and nothing has to be backfilled.
ALTER TABLE profiles ADD COLUMN unlock_price_minor BIGINT;
ALTER TABLE profiles ADD CONSTRAINT profiles_unlock_price_check
    CHECK (unlock_price_minor IS NULL OR unlock_price_minor >= 0);

-- ---------------------------------------------------------------- gender
-- The product recognises two genders.
--
-- The three retired values have to go somewhere for the tightened constraint to
-- hold. Anyone carrying one is moved to FEMALE, which is lossy: it is a guess,
-- not a fact about that person. If this runs against a database with real rows
-- in those states, prompt the affected members to re-select rather than trusting
-- what lands here.
UPDATE profiles SET gender = 'FEMALE'
WHERE gender IN ('NON_BINARY', 'OTHER', 'PREFER_NOT_TO_SAY');

ALTER TABLE profiles DROP CONSTRAINT profiles_gender_check;
ALTER TABLE profiles ADD CONSTRAINT profiles_gender_check
    CHECK (gender IN ('MALE', 'FEMALE'));
