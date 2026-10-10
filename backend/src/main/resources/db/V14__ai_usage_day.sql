-- MEKA OS v14: the AI meter per day (build plan V1, MEKA as the default assistant: "a daily count in Activity").
-- Counts only, per Europe/London day and feature ("ask", "ask.talk", "triage.message", ...): how many calls, tokens in
-- and out, and their list price in micro-dollars, so Activity can say how much Talk and the rest asked today. No
-- prompt, answer or anything of Meka's. The monthly ai_usage table stays the budget's meter. Additive only.
CREATE TABLE IF NOT EXISTS ai_usage_day (
    day            TEXT NOT NULL,
    feature        TEXT NOT NULL,
    calls          BIGINT NOT NULL DEFAULT 0,
    input_tokens   BIGINT NOT NULL DEFAULT 0,
    output_tokens  BIGINT NOT NULL DEFAULT 0,
    micro_usd      BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (day, feature)
);
