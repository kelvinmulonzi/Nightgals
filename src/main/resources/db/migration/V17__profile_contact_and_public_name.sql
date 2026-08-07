-- A contact number, and a name that is now public.
--
-- ── whatsapp_number ──────────────────────────────────────────────────────────
-- Kept on the profile rather than the user because it is published: this is the
-- number a member is choosing to hand out, which is not the same as the handset
-- that pays for things (purchases.payer_msisdn) or the address the account was
-- opened with. Nullable, because handing out a number is opt-in.
--
-- Stored as typed, normalised only when a wa.me link is built. Rewriting what
-- someone entered risks turning a number that works into one that does not.
ALTER TABLE profiles
    ADD COLUMN whatsapp_number VARCHAR(20);

-- ── display_name becomes public ──────────────────────────────────────────────
-- No column changes: display_name already exists. What changes is who sees it.
-- It was owner-and-staff only, described in the API as "nothing here is
-- published", and it is now returned on every public view of a profile.
--
-- Worth being explicit that this is a visibility change applied to data already
-- collected: anyone who filled it in did so while it was private. If that is not
-- wanted for existing rows, clearing them is the safe direction -
--
--   UPDATE profiles SET display_name = NULL WHERE display_name IS NOT NULL;
--
-- and is deliberately left as a decision rather than run here, because it
-- destroys what people typed.
COMMENT ON COLUMN profiles.display_name IS
    'Public. Shown under the profile picture. Was private before V17.';

COMMENT ON COLUMN profiles.whatsapp_number IS
    'Optional public contact number, used to build a wa.me link.';
