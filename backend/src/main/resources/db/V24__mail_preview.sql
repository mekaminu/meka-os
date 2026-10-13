-- MEKA OS v24: Inbox autopilot, slice 6a ("From your mail" cards in Needs you). The provider's own short preview of an
-- email's text (Gmail's snippet, Outlook's bodyPreview: the first ~200 characters, never the whole body), kept so the
-- card for an email that needs Meka can show its first words. Never logged; Disconnect deletes it with the row.
-- Additive: one new nullable column.
ALTER TABLE mail_message ADD COLUMN IF NOT EXISTS preview TEXT;
