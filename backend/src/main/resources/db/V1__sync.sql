-- MEKA OS sync service schema v1 (ADR-003, ADR-004, ADR-005)

CREATE TABLE IF NOT EXISTS household (
    id          TEXT PRIMARY KEY,
    next_seq    BIGINT NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS device (
    id              TEXT NOT NULL,
    household_id    TEXT NOT NULL REFERENCES household(id),
    name            TEXT NOT NULL,
    secret_sha256   TEXT NOT NULL UNIQUE,      -- hex SHA-256 of the bearer secret; the secret itself is never stored
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, id)
);

CREATE TABLE IF NOT EXISTS op_log (
    household_id    TEXT NOT NULL REFERENCES household(id),
    seq             BIGINT NOT NULL,
    op_id           TEXT NOT NULL,
    entity_type     TEXT NOT NULL,
    entity_id       TEXT NOT NULL,
    field_name      TEXT NOT NULL,
    value_type      CHAR(1) NOT NULL CHECK (value_type IN ('s', 'i', 'b', 'n')),
    value_text      TEXT,
    value_int       BIGINT,
    hlc             TEXT NOT NULL,
    base_op_ids     TEXT NOT NULL,
    device_id       TEXT NOT NULL,
    schema_version  INT NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, seq),
    UNIQUE (household_id, op_id)                -- idempotency: an op is stored at most once
);

CREATE INDEX IF NOT EXISTS op_log_entity ON op_log (household_id, entity_type, entity_id);

CREATE TABLE IF NOT EXISTS schema_migration (
    version     INT PRIMARY KEY,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
