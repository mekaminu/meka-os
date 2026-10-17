-- MEKA OS v27: the spam-call lookup's cache (2026-10-17). One row per household and number, keyed by a SHA-256 of the
-- number (never the number itself): what the web's report sites said (scam · nuisance · legit · unknown, or failed),
-- the report count, whether sure enough to block, a few words of why, result counts for the deploy report, and when it
-- was checked, so a number is searched at most once a day and a verdict is kept 30 days. Additive only.
CREATE TABLE IF NOT EXISTS number_lookup (
    household_id   TEXT NOT NULL,
    number_hash    TEXT NOT NULL,
    verdict        TEXT NOT NULL,
    reports        INT NOT NULL DEFAULT 0,
    sure           BOOLEAN NOT NULL DEFAULT FALSE,
    why            TEXT,
    results        INT NOT NULL DEFAULT 0,
    report_hits    INT NOT NULL DEFAULT 0,
    reason         TEXT,
    checked_at_ms  BIGINT NOT NULL,
    PRIMARY KEY (household_id, number_hash)
);
