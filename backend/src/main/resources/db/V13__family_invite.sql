-- MEKA OS v13: family sharing with Jeanette, slice 2 (build plan). Additive only.
-- An invite to Meka's family page. The link's token is never stored, only its SHA-256. The first browser to open the
-- link registers its own P-256 public key; after that only requests signed by that key are let in. Revoking shuts it.
CREATE TABLE IF NOT EXISTS family_invite (
    id              TEXT PRIMARY KEY,
    household_id    TEXT NOT NULL REFERENCES household(id),
    name            TEXT NOT NULL,                 -- the family member's name key ("jeanette"), what her items carry
    token_sha256    TEXT NOT NULL UNIQUE,
    public_key      TEXT,                          -- her browser's key (SPKI, base64), set once on first open
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    last_seen_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS family_invite_household ON family_invite (household_id, created_at);
