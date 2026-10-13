-- MEKA OS v23: Inbox autopilot, slice 5a (Unsubscribe for him). Per mail account, the senders whose mail was sorted as
-- a newsletter or promo: their name, their latest List-Unsubscribe header (and whether it offers RFC 8058 one-click),
-- when their latest email came and how many the server has seen. Server-side only: the apps get the name, domain and
-- method in the synced `mail_sender` entity, never the header. Disconnect forgets an account's rows. Additive: one new table.
CREATE TABLE IF NOT EXISTS mail_sender (
    account_id       TEXT NOT NULL,
    address          TEXT NOT NULL,
    household_id     TEXT NOT NULL,
    name             TEXT,
    list_unsubscribe TEXT,
    one_click        BOOLEAN NOT NULL DEFAULT false,
    headers_checked  BOOLEAN NOT NULL DEFAULT false,
    last_message_id  TEXT,
    last_at_ms       BIGINT NOT NULL DEFAULT 0,
    seen_count       INTEGER NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, address)
);
