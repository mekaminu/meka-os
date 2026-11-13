-- Navilife v31: Obi's response times from each device (Needs Meka #43 "Yes", 2026-11-11). Each device sends its last
-- day's summary once a day: counts, averages and the slowest time to Obi's voice for hellos and answers, and its label
-- ("phone", "Mac"). Numbers only, nothing said. Only the newest summary per device is kept; the release publisher reads
-- them for the daily build report's speed line.
CREATE TABLE IF NOT EXISTS device_speed_report (
    household_id    TEXT NOT NULL REFERENCES household(id),
    device_id       TEXT NOT NULL,
    report          TEXT NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, device_id)
);
