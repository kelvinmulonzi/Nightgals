-- Nightgals v1 schema: accounts, profiles, KYC verification, gated media.
--
-- Flyway owns this schema. Hibernate runs with ddl-auto=validate, so it will
-- refuse to start if the entities and these tables ever drift apart.

-- ---------------------------------------------------------------- accounts
CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    email               VARCHAR(254) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    role                VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    -- Denormalised from the latest KYC submission so authorisation checks
    -- (can this user post media? appear in discovery?) are a single-row read.
    verification_status VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED',
    email_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT users_role_check         CHECK (role   IN ('USER', 'MODERATOR', 'ADMIN')),
    CONSTRAINT users_status_check       CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT users_verification_check CHECK (verification_status IN ('UNVERIFIED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'))
);
CREATE UNIQUE INDEX ux_users_email ON users (LOWER(email));
CREATE INDEX ix_users_verification ON users (verification_status);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

-- ---------------------------------------------------------------- profiles
CREATE TABLE profiles (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    display_name  VARCHAR(50)  NOT NULL,
    bio           VARCHAR(500),
    date_of_birth DATE         NOT NULL,
    gender        VARCHAR(20)  NOT NULL,
    city          VARCHAR(100),
    country       VARCHAR(100),
    vibe          VARCHAR(20)  NOT NULL DEFAULT 'ANYTHING',
    discoverable  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT profiles_gender_check CHECK (gender IN ('MALE', 'FEMALE', 'NON_BINARY', 'OTHER', 'PREFER_NOT_TO_SAY')),
    CONSTRAINT profiles_vibe_check   CHECK (vibe IN ('CLUBBING', 'BARS', 'LIVE_MUSIC', 'HOUSE_PARTIES', 'FESTIVALS', 'CHILL', 'ANYTHING'))
);

-- ---------------------------------------------------------------- KYC
-- One row per verification attempt. History is kept: a rejected submission is
-- never deleted, the user opens a new one.
CREATE TABLE kyc_submissions (
    id                    UUID PRIMARY KEY,
    user_id               UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    document_type         VARCHAR(20)  NOT NULL,
    -- Legal identity as printed on the document, for the reviewer to compare
    -- against both the document image and the selfie.
    full_name             VARCHAR(150) NOT NULL,
    date_of_birth         DATE         NOT NULL,
    country_of_issue      VARCHAR(2)   NOT NULL,
    -- The raw document number is deliberately NOT stored. The hash detects a
    -- second account opened on the same ID; the last 4 are for admin display.
    document_number_hash  VARCHAR(64)  NOT NULL,
    document_number_last4 VARCHAR(4)   NOT NULL,
    submitted_at          TIMESTAMPTZ,
    reviewed_at           TIMESTAMPTZ,
    reviewed_by           UUID         REFERENCES users (id) ON DELETE SET NULL,
    rejection_reason      VARCHAR(50),
    reviewer_notes        VARCHAR(1000),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT kyc_status_check   CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT kyc_doc_type_check CHECK (document_type IN ('NATIONAL_ID', 'PASSPORT', 'DRIVERS_LICENSE')),
    CONSTRAINT kyc_reason_check   CHECK (rejection_reason IS NULL OR rejection_reason IN
        ('DOCUMENT_UNREADABLE', 'DOCUMENT_EXPIRED', 'DETAILS_MISMATCH', 'SELFIE_MISMATCH',
         'SUSPECTED_FORGERY', 'UNDERAGE', 'DUPLICATE_ACCOUNT', 'OTHER'))
);
CREATE INDEX ix_kyc_user ON kyc_submissions (user_id, created_at DESC);
-- Drives the admin review queue: oldest pending first.
CREATE INDEX ix_kyc_queue ON kyc_submissions (status, submitted_at) WHERE status = 'PENDING_REVIEW';
-- Same document used on an approved account = duplicate.
CREATE INDEX ix_kyc_doc_hash ON kyc_submissions (document_number_hash);

CREATE TABLE kyc_documents (
    id            UUID PRIMARY KEY,
    submission_id UUID          NOT NULL REFERENCES kyc_submissions (id) ON DELETE CASCADE,
    kind          VARCHAR(20)   NOT NULL,
    storage_key   VARCHAR(500)  NOT NULL,
    content_type  VARCHAR(100)  NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    uploaded_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- Set when the file is purged after the retention window; the row stays
    -- as evidence that verification happened.
    purged_at     TIMESTAMPTZ,
    CONSTRAINT kyc_doc_kind_check CHECK (kind IN ('ID_FRONT', 'ID_BACK', 'PASSPORT_PAGE', 'SELFIE'))
);
CREATE UNIQUE INDEX ux_kyc_documents_kind ON kyc_documents (submission_id, kind);

-- Every time an admin opens a KYC document we write a row here. Sensitive PII
-- access must be attributable after the fact.
CREATE TABLE kyc_access_log (
    id           UUID        PRIMARY KEY,
    document_id  UUID        NOT NULL REFERENCES kyc_documents (id) ON DELETE CASCADE,
    accessed_by  UUID        NOT NULL REFERENCES users (id)         ON DELETE CASCADE,
    accessed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ip_address   VARCHAR(45)
);
CREATE INDEX ix_kyc_access_document ON kyc_access_log (document_id, accessed_at DESC);
CREATE INDEX ix_kyc_access_admin    ON kyc_access_log (accessed_by, accessed_at DESC);

-- ---------------------------------------------------------------- media
-- Only reachable once users.verification_status = 'APPROVED'.
CREATE TABLE media_assets (
    id              UUID          PRIMARY KEY,
    user_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type            VARCHAR(10)   NOT NULL,
    storage_key     VARCHAR(500)  NOT NULL,
    content_type    VARCHAR(100)  NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    checksum_sha256 VARCHAR(64)   NOT NULL,
    caption         VARCHAR(300),
    position        INT           NOT NULL DEFAULT 0,
    is_primary      BOOLEAN       NOT NULL DEFAULT FALSE,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING_REVIEW',
    rejection_reason VARCHAR(200),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT media_type_check   CHECK (type   IN ('PHOTO', 'VIDEO')),
    CONSTRAINT media_status_check CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED'))
);
CREATE INDEX ix_media_user  ON media_assets (user_id, position);
CREATE INDEX ix_media_queue ON media_assets (status, created_at) WHERE status = 'PENDING_REVIEW';
-- At most one primary photo per user.
CREATE UNIQUE INDEX ux_media_primary ON media_assets (user_id) WHERE is_primary = TRUE;
