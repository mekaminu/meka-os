-- MEKA OS v2: connected calendar/email accounts and the server-side mirror of their events (ADR-008)

-- Short-lived OAuth handshakes. The PKCE verifier never leaves the server.
CREATE TABLE IF NOT EXISTS oauth_state (
    state           TEXT PRIMARY KEY,
    household_id    TEXT NOT NULL REFERENCES household(id),
    provider        TEXT NOT NULL,
    code_verifier   TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per connected account. The refresh token is encrypted with the MEKA KMS key before it is stored.
CREATE TABLE IF NOT EXISTS integration_account (
    id                  TEXT PRIMARY KEY,
    household_id        TEXT NOT NULL REFERENCES household(id),
    provider            TEXT NOT NULL,
    email               TEXT NOT NULL,
    refresh_token_enc   BYTEA NOT NULL,
    status              TEXT NOT NULL DEFAULT 'ok',      -- ok | needs_reconnect | error
    last_error          TEXT,
    last_sync_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (household_id, provider, email)
);

-- What the server last wrote for each mirrored event, so it only writes ops for real changes and can chain
-- each field's ops (baseOpIds) without ever creating conflicts.
CREATE TABLE IF NOT EXISTS event_mirror (
    household_id    TEXT NOT NULL REFERENCES household(id),
    entity_id       TEXT NOT NULL,
    account_id      TEXT NOT NULL REFERENCES integration_account(id) ON DELETE CASCADE,
    start_ms        BIGINT NOT NULL,
    removed         BOOLEAN NOT NULL DEFAULT false,
    field_ops       TEXT NOT NULL,                       -- JSON: {"field": ["opId", "valueKey"], ...}
    PRIMARY KEY (household_id, entity_id)
);

CREATE INDEX IF NOT EXISTS event_mirror_account ON event_mirror (account_id);
