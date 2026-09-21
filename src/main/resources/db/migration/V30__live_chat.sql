-- Chat during a broadcast: one row per message, never deleted while the
-- session exists. Same shape as V21__live_gifts.sql's feed table - a
-- broadcast-scoped, append-only log that a client polls with `since`.
CREATE TABLE live_chat_messages (
    id              UUID PRIMARY KEY,
    live_session_id UUID NOT NULL REFERENCES live_sessions (id) ON DELETE CASCADE,
    sender_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body            VARCHAR(300) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT live_chat_messages_body_not_blank_check CHECK (btrim(body) <> '')
);

-- The polling query: everything for one broadcast since a timestamp, in order.
CREATE INDEX ix_live_chat_messages_session_time ON live_chat_messages (live_session_id, created_at);
