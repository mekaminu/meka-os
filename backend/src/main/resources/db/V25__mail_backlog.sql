-- MEKA OS v25: Inbox autopilot, slice 8a (the backlog clean-up). Once per mail account the server looks through the
-- older mail already in the inbox (headers only) and plans each email: archive, Trash (junk) or keep. Kept here: the
-- scan's progress, and each email's id, thread id, sender and when it arrived with its planned kind (so slice 8b can
-- apply exactly what Meka approved and Undo it). No subject, preview or body. Disconnect deletes both.
-- Additive: two new tables.
CREATE TABLE IF NOT EXISTS mail_backlog_scan (
    account_id  TEXT PRIMARY KEY,
    before_ms   BIGINT NOT NULL,
    started_ms  BIGINT NOT NULL,
    next_page   TEXT,
    listed      BOOLEAN NOT NULL DEFAULT false,
    ready_ms    BIGINT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS mail_backlog_item (
    account_id     TEXT NOT NULL,
    message_id     TEXT NOT NULL,
    thread_id      TEXT,
    from_address   TEXT,
    from_name      TEXT,
    received_at_ms BIGINT,
    kind           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, message_id)
);
