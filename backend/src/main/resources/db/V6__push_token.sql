-- MEKA OS v6: push addresses (build plan M1: push via Firebase). One per device; the token is opaque (an FCM
-- registration token). Removed when the device turns push off or FCM says the token is gone; nothing else changes.
CREATE TABLE IF NOT EXISTS push_token (
    household_id    TEXT NOT NULL,
    device_id       TEXT NOT NULL,
    service         TEXT NOT NULL,
    token           TEXT NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (household_id, device_id),
    FOREIGN KEY (household_id, device_id) REFERENCES device(household_id, id) ON DELETE CASCADE
);
