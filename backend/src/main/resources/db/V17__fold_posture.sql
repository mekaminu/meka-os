-- MEKA OS v17: the Fold's posture changes (bedside clock, slice 2). The Fold sends its last 20 posture changes (when,
-- and what WindowManager, the window and the hinge sensor said; its model name; nothing personal) so a build run can
-- read what the Fold 8 reports through the release publisher. Only the newest report per household is kept.
CREATE TABLE IF NOT EXISTS fold_posture_report (
    household_id    TEXT PRIMARY KEY REFERENCES household(id),
    device_id       TEXT NOT NULL,
    report          TEXT NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
