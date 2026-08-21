-- Google sign-in, for viewers.
--
-- Two columns' worth of consequence from one fact: an account created this way
-- has never chosen a password.

-- So the hash has to be allowed to be absent. Storing a random one instead
-- would be a credential nobody can use and everybody who reads this table has
-- to reason about; NULL says the thing plainly, and AuthService.login refuses
-- it before bcrypt is ever asked.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- And the Google subject is kept, not just the address. The subject is the
-- stable identifier - somebody who changes the address on their Google account
-- is still the same person, and matching on email alone would hand them a
-- second account instead of their own.
ALTER TABLE users ADD COLUMN google_subject VARCHAR(64);

-- Partial by nature: Postgres treats NULLs as distinct, so every
-- password-registered account keeps its NULL without colliding.
CREATE UNIQUE INDEX ux_users_google_subject ON users (google_subject);
