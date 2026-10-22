-- MEKA OS v29: Ada's Live mode meter (slice 1, 2026-10-22). One row per Gemini Live session the server minted a token
-- for: its id, household, UTC month, when, and what it cost in micro-dollars (the whole window until the device
-- reports, then the seconds it heard and spoke). Nothing of what was said. Additive only.
CREATE TABLE IF NOT EXISTS live_session (
    id            TEXT PRIMARY KEY,
    household_id  TEXT NOT NULL,
    month         TEXT NOT NULL,
    minted_at_ms  BIGINT NOT NULL,
    in_seconds    BIGINT NOT NULL DEFAULT 0,
    out_seconds   BIGINT NOT NULL DEFAULT 0,
    micro_usd     BIGINT NOT NULL DEFAULT 0,
    reported      BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS live_session_month ON live_session (month);
