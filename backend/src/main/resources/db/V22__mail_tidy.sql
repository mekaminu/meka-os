-- MEKA OS v22: Inbox autopilot, slice 3b. What each tidy moved (ids only: the email's id before, the id it has now
-- (Outlook's move changes it) and what was done to it), so Undo can put them back; and the emails an Undo put back,
-- never tidied again. No subject, sender or body is kept here. Additive: two new tables.
CREATE TABLE IF NOT EXISTS mail_tidy_item (
    account_id TEXT NOT NULL,
    batch_id   TEXT NOT NULL,
    message_id TEXT NOT NULL,
    moved_id   TEXT NOT NULL,
    tidy       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, batch_id, message_id)
);
CREATE TABLE IF NOT EXISTS mail_never_tidy (
    account_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, message_id)
);
