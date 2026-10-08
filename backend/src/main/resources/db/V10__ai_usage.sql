-- MEKA OS v10: the AI layer's token meter (ADR-006 §5). Counts only, per UTC month and feature ("ask",
-- "extract.email", ...): how many calls, tokens in and out, and their list price in micro-dollars, so the monthly
-- budget (soft alert at 70 %, off at 100 %) survives restarts. No prompt, answer or anything of Meka's. Additive only.
CREATE TABLE IF NOT EXISTS ai_usage (
    month          TEXT NOT NULL,
    feature        TEXT NOT NULL,
    calls          BIGINT NOT NULL DEFAULT 0,
    input_tokens   BIGINT NOT NULL DEFAULT 0,
    output_tokens  BIGINT NOT NULL DEFAULT 0,
    micro_usd      BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (month, feature)
);
