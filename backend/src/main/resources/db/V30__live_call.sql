-- MEKA OS v30: Obi on calls, slice 1 (2026-11-07). One row per call Obi talked to live: its id, household, UTC month,
-- when, how long, and what it cost in micro-dollars (the phone service and Gemini Live). Nothing of what was said.
-- Additive only.
CREATE TABLE IF NOT EXISTS live_call (
    id            TEXT PRIMARY KEY,
    household_id  TEXT NOT NULL,
    month         TEXT NOT NULL,
    started_at_ms BIGINT NOT NULL,
    seconds       BIGINT NOT NULL DEFAULT 0,
    micro_usd     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS live_call_month ON live_call (month);
