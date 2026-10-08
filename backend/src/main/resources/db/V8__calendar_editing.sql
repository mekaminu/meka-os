-- MEKA OS v8: calendar editing, slice 1 (build plan M1 "Edit your calendars from MEKA"). Additive only.
-- Whether a sign-in asked for the write permission (Allow editing), and whether the account may change events: the
-- provider granted the write scope and the owner hasn't stopped editing. Existing accounts stay read-only.
ALTER TABLE oauth_state ADD COLUMN IF NOT EXISTS editing BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE integration_account ADD COLUMN IF NOT EXISTS can_edit BOOLEAN NOT NULL DEFAULT false;
