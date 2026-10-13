-- MEKA OS v20: mail headers (Inbox autopilot, slice 1c). Per mail account, the last 60 days' message headers (from,
-- subject, received, read/unread, in the inbox, labels and the list headers for unsubscribing): never a body, never
-- logged. A row with headers_at NULL is still to be read (Gmail lists ids first). mail_sync holds where each account's
-- change feed continues (a Gmail history id or a Graph delta link). Additive: two new tables; Disconnect deletes an
-- account's rows.
CREATE TABLE IF NOT EXISTS mail_message (
    account_id              TEXT NOT NULL REFERENCES integration_account(id),
    message_id              TEXT NOT NULL,
    household_id            TEXT NOT NULL REFERENCES household(id),
    thread_id               TEXT,
    from_address            TEXT,
    from_name               TEXT,
    subject                 TEXT,
    received_at_ms          BIGINT,
    unread                  BOOLEAN NOT NULL DEFAULT false,
    in_inbox                BOOLEAN NOT NULL DEFAULT false,
    labels                  TEXT,
    list_unsubscribe        TEXT,
    one_click_unsubscribe   BOOLEAN NOT NULL DEFAULT false,
    list_id                 TEXT,
    headers_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, message_id)
);
CREATE INDEX IF NOT EXISTS mail_message_pending ON mail_message(account_id, created_at) WHERE headers_at IS NULL;
CREATE INDEX IF NOT EXISTS mail_message_received ON mail_message(account_id, received_at_ms);

CREATE TABLE IF NOT EXISTS mail_sync (
    account_id  TEXT PRIMARY KEY REFERENCES integration_account(id),
    cursor      TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
