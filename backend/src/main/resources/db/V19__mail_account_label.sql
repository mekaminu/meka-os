-- MEKA OS v19: mail accounts (Inbox autopilot, slice 1). A mail account is an integration_account row of its own
-- (provider gmail or outlook_mail, its own encrypted refresh token); this adds the label Meka gives it
-- (PERSONAL, BUSINESS or OUTLOOK) so the business mail can get its own section. Additive; null on every other row.
ALTER TABLE integration_account ADD COLUMN IF NOT EXISTS label TEXT;
