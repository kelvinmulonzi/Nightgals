-- The number a mobile-money prompt was sent to.
--
-- Nullable: every purchase made before MTN MoMo was wired in has none, and the
-- auto and manual providers never set one. Kept on the purchase rather than the
-- account because the handset that paid is what a dispute needs to see, and it
-- is not necessarily the one the account was opened with.
ALTER TABLE purchases
    ADD COLUMN payer_msisdn VARCHAR(20);
