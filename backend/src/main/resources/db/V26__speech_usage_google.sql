-- MEKA OS v26: Google's voice meter (natural voice, slice 1: Google Chirp 3 HD). Counts only, per UTC month: how many
-- clips and characters Google was asked to say, so Navilife stops using Google at 900k of its free 1M characters and
-- never pays without Meka's yes. No text, audio or anything of Meka's. Additive only (the same shape as speech_usage).
CREATE TABLE IF NOT EXISTS speech_usage_google (
    month       TEXT PRIMARY KEY,
    clips       BIGINT NOT NULL DEFAULT 0,
    chars       BIGINT NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
