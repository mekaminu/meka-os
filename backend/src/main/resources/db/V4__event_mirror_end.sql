-- MEKA OS v4: removal of mirrored events is judged by overlap with the window, so keep each event's end too.
ALTER TABLE event_mirror ADD COLUMN IF NOT EXISTS end_ms BIGINT;
ALTER TABLE event_mirror ADD COLUMN IF NOT EXISTS all_day BOOLEAN NOT NULL DEFAULT false;
