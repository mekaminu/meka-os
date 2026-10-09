-- MEKA OS v12: Reliability first, item 2 (Google sign-ins that run out). Additive only.
-- When the owner last signed in to an account (the OAuth consent), so the server can warn a day before Google stops
-- honouring a sign-in made while MEKA's Google app is in Testing (7 days). Existing accounts stay NULL: nothing to warn
-- about until their next sign-in.
ALTER TABLE integration_account ADD COLUMN IF NOT EXISTS granted_at TIMESTAMPTZ;
