-- MEKA OS v28: how long the server took over each answer (talking to Ada like a conversation, slice 2, 2026-10-21).
-- One row per answer: the household, when, and the timing as wire JSON (ms per step since the question arrived, open
-- or quick, spoken or typed, the model's name). Times only, nothing of what was asked or answered. The newest 200 per
-- household are kept. Additive only.
CREATE TABLE IF NOT EXISTS talk_timing (
    id            BIGSERIAL PRIMARY KEY,
    household_id  TEXT NOT NULL,
    at_ms         BIGINT NOT NULL,
    timing        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS talk_timing_household ON talk_timing (household_id, id);
