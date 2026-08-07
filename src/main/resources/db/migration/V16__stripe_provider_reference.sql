-- Room for a Stripe reference.
--
-- provider_reference was sized for MTN's, which is a UUID. A Stripe Checkout
-- Session id (cs_live_...) is materially longer and not fixed-length, so 120
-- characters is a truncation waiting to happen - and truncating this column
-- silently breaks the one thing it is for: finding the purchase a payment
-- belongs to. 255 is comfortably clear of anything Stripe issues.
--
-- The unique index on (provider, provider_reference) is unaffected: widening a
-- VARCHAR in PostgreSQL rewrites no rows and rebuilds no indexes.
ALTER TABLE purchases
    ALTER COLUMN provider_reference TYPE VARCHAR(255);
