-- MEKA OS v16: the recovery key (build plan V1 "Recovery key"; ADR-005 amendment 2026-10-12). Additive only.
-- One row per household: the SHA-256 of the key's secret (the words and the secret are never stored or sent when it is
-- made). Making a new key replaces the row; a key that connected a device is spent (used_at set).
CREATE TABLE IF NOT EXISTS recovery_key (
    household_id    TEXT PRIMARY KEY REFERENCES household(id),
    verifier        TEXT NOT NULL UNIQUE,          -- hex SHA-256 of the secret the words make
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      TEXT NOT NULL,                 -- the making device's name ("Fold"), shown in Settings
    used_at         TIMESTAMPTZ,
    used_by         TEXT                           -- the name of the device it connected
);
