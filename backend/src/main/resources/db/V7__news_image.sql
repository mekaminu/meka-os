-- MEKA OS v7: news pictures (news ticker, images slice). Public content, not anyone's data: each story's picture as
-- the server fetched it from the feed and shrank it (a JPEG of at most 30 KB), keyed by a hash of its address so
-- every household shares one copy. Pictures no feed has mentioned for three days are deleted. Additive only.
CREATE TABLE IF NOT EXISTS news_image (
    key         TEXT PRIMARY KEY,
    data        BYTEA NOT NULL,
    width       INT NOT NULL,
    height      INT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS news_image_used_at ON news_image (used_at);
