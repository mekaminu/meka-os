-- MEKA OS v18: the phone's notification trace (notifications never seen, slice 2). The phone sends whether MEKA may
-- post, its own "This phone" setting, each channel on/off and its last 40 trace entries (counts, channel names,
-- reasons and error class names; never a notification's words) so a build run can read where notifications stop
-- through the release publisher. Only the newest report per household is kept.
CREATE TABLE IF NOT EXISTS notify_report (
    household_id    TEXT PRIMARY KEY REFERENCES household(id),
    device_id       TEXT NOT NULL,
    report          TEXT NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
