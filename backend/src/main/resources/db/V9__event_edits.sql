-- MEKA OS v9: calendar editing, slice 2a (build plan M1 "Edit your calendars from MEKA"). Additive only.
-- The provider's own id for each mirrored event ("<calendar>/<event>"), so an edit Meka makes in MEKA can find the real
-- event. Filled in by the next calendar poll; rows from before stay NULL until then (an edit waits for it).
ALTER TABLE event_mirror ADD COLUMN IF NOT EXISTS remote_id TEXT;
