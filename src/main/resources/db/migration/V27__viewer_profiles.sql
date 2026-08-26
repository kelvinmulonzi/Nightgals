-- Let a profile exist without the two fields only a creator is asked for.
--
-- The profile picture lives on the profile, and viewers had no profile row - so
-- half the accounts on the platform had nowhere to put a picture and no control
-- to do it with. Giving them a row is the smallest fix; these two columns are
-- what stood in the way, because a viewer is never asked for a date of birth or
-- a gender.
--
-- Creators are still required to supply both: ProfileRequest carries @NotNull on
-- each, so the rule now lives where the form is rather than in a constraint that
-- also governs rows no form ever touches.
ALTER TABLE profiles ALTER COLUMN date_of_birth DROP NOT NULL;
ALTER TABLE profiles ALTER COLUMN gender        DROP NOT NULL;

COMMENT ON COLUMN profiles.date_of_birth IS
    'Required of creators, absent for a viewer whose profile exists only to hold a picture.';
