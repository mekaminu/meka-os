-- MEKA OS v5: self-updating phone app. Builds published from the Mac, stored in 1 MiB chunks so every request stays
-- under the API's body limit. The server keeps the newest two complete builds per platform; nothing else changes.
CREATE TABLE IF NOT EXISTS app_release (
    household_id    TEXT NOT NULL REFERENCES household(id),
    platform        TEXT NOT NULL,
    version_code    BIGINT NOT NULL,
    version_name    TEXT NOT NULL,
    sha256          TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL,
    chunk_count     INT NOT NULL,
    published_by    TEXT NOT NULL,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, platform, version_code)
);

CREATE TABLE IF NOT EXISTS app_release_chunk (
    household_id    TEXT NOT NULL,
    platform        TEXT NOT NULL,
    version_code    BIGINT NOT NULL,
    idx             INT NOT NULL,
    data            BYTEA NOT NULL,
    PRIMARY KEY (household_id, platform, version_code, idx),
    FOREIGN KEY (household_id, platform, version_code) REFERENCES app_release(household_id, platform, version_code) ON DELETE CASCADE
);
