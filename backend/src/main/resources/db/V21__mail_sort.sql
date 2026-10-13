-- MEKA OS v21: mail sorting (Inbox autopilot, slice 2a). Each kept header gets the bucket the rules put it in
-- (a MailSort name: NEEDS_YOU, FYI, NEWSLETTER, RECEIPT, NOTIFICATION, PROMO, JUNK), why (a MailSortReason name) and
-- whether the rules were sure (unsure ones are the AI's to look at). NULL = not sorted yet; a header read again is
-- sorted again. Additive: three new nullable columns and an index.
ALTER TABLE mail_message ADD COLUMN IF NOT EXISTS sort TEXT;
ALTER TABLE mail_message ADD COLUMN IF NOT EXISTS sort_reason TEXT;
ALTER TABLE mail_message ADD COLUMN IF NOT EXISTS sort_sure BOOLEAN;
CREATE INDEX IF NOT EXISTS mail_message_unsorted ON mail_message(account_id) WHERE headers_at IS NOT NULL AND sort IS NULL;
