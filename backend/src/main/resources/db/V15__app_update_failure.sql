-- MEKA OS v15: phone update failures (build plan: phone updates sometimes fail to install). The Fold reports why an
-- update didn't go in (stage, Android's status and message, build numbers, a free-storage band, network type; nothing
-- personal) so a build run can read it through the release publisher. The newest 20 per household are kept.
CREATE TABLE IF NOT EXISTS app_update_failure (
    id              BIGSERIAL PRIMARY KEY,
    household_id    TEXT NOT NULL REFERENCES household(id),
    device_id       TEXT NOT NULL,
    stage           TEXT NOT NULL,
    status          INT,
    message         TEXT,
    from_code       BIGINT NOT NULL,
    to_code         BIGINT NOT NULL,
    failed_at_ms    BIGINT NOT NULL,
    storage         TEXT,
    network         TEXT,
    attempts        INT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS app_update_failure_household ON app_update_failure (household_id, id DESC);
