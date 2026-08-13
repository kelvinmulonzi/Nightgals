-- One payment for everything a creator currently has locked.
--
-- The set is snapshotted here at checkout rather than recomputed at settlement,
-- because those are two different moments and the buyer must get exactly what
-- they were charged for. A creator who deletes an item between the two would
-- otherwise hand over less than was paid for, and one who uploads would hand
-- over more than was priced.
--
-- It is also what makes "later uploads need a top-up" mean something precise:
-- the bundle is a list of items, not a standing claim on the profile, so
-- anything posted afterwards is simply not in it.
CREATE TABLE purchase_media (
    purchase_id UUID NOT NULL REFERENCES purchases (id) ON DELETE CASCADE,
    media_id    UUID NOT NULL,
    PRIMARY KEY (purchase_id, media_id)
);

-- Deliberately no foreign key to media_assets. This is a receipt: what was
-- bought has to stay readable after the creator deletes the item, and a
-- cascade here would quietly rewrite somebody's purchase history.
CREATE INDEX idx_purchase_media_purchase ON purchase_media (purchase_id);
