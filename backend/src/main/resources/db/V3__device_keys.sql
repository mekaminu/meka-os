-- MEKA OS v3: hardware-bound device signing keys (ADR-005). Once set, the device's requests must be signed.
ALTER TABLE device ADD COLUMN IF NOT EXISTS public_key TEXT;
