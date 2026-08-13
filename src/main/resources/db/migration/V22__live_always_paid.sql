-- Broadcasts are paid to join. There is no free tier for live any more: the API
-- refuses to create or edit a session without a price, and this brings the rows
-- that were already in the table into line with that.
--
-- Only sessions that have not finished are touched. A broadcast that has already
-- ended was free when it ran, people watched it on those terms, and rewriting it
-- would misstate what a past night charged - which matters, because earnings and
-- access records were written against it.
--
-- The fallback price is the platform default (monetization.item-pricing
-- default-price-minor, 2000 XAF). A migration cannot read application config, so
-- it is written out here; a session that already carried its own price keeps it.
UPDATE live_sessions
SET tier = 'EXCLUSIVE',
    access_price_minor = COALESCE(access_price_minor, 2000)
WHERE status IN ('SCHEDULED', 'LIVE')
  AND (tier = 'FREE' OR access_price_minor IS NULL);
