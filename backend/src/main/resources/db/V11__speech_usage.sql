-- MEKA OS v11: MEKA's voice meter (build plan V1, Weather and a voice: Amazon Polly). Counts only, per UTC month:
-- how many clips and how many characters Polly was asked to say, so the monthly cap (1M characters; beyond it the
-- device's own voice speaks) survives restarts. No text, audio or anything of Meka's. Additive only.
CREATE TABLE IF NOT EXISTS speech_usage (
    month       TEXT PRIMARY KEY,
    clips       BIGINT NOT NULL DEFAULT 0,
    chars       BIGINT NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
