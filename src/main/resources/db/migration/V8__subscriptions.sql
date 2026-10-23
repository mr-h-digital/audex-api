ALTER TABLE app_user ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE app_user ADD COLUMN trial_started_at TIMESTAMPTZ;
ALTER TABLE audit_photo ADD COLUMN size_bytes BIGINT CHECK (size_bytes >= 0);
CREATE TABLE billing_rollout (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    started_at TIMESTAMPTZ NOT NULL
);

-- Freeze eligibility at migration time. A new signup or email change cannot become a complimentary candidate.
CREATE TABLE complimentary_candidate (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    slot TEXT NOT NULL UNIQUE CHECK (slot IN ('trainer', 'administrator')),
    original_email TEXT NOT NULL,
    CHECK ((slot = 'trainer' AND original_email = 'hktrainer@wpc.co.za')
        OR (slot = 'administrator' AND original_email = 'info@mrhdigital.co.za'))
);
INSERT INTO complimentary_candidate(user_id, slot, original_email)
SELECT id, CASE lower(email) WHEN 'info@mrhdigital.co.za' THEN 'administrator' ELSE 'trainer' END, lower(email)
FROM app_user WHERE lower(email) IN ('info@mrhdigital.co.za', 'hktrainer@wpc.co.za');

CREATE TABLE complimentary_grant (
    user_id UUID PRIMARY KEY REFERENCES complimentary_candidate(user_id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by TEXT NOT NULL,
    reason TEXT NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE TABLE billing_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES app_user(id),
    action TEXT NOT NULL,
    actor TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE email_verification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);
CREATE TABLE billing_checkout (
    reference TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    plan TEXT NOT NULL CHECK (plan IN ('SOLO', 'PROFESSIONAL')),
    cadence TEXT NOT NULL CHECK (cadence IN ('monthly', 'annually')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    provider_plan TEXT NOT NULL,
    customer_code TEXT,
    checkout_url TEXT,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX billing_checkout_user ON billing_checkout(user_id);
CREATE TABLE billing_subscription (
    code TEXT PRIMARY KEY,
    customer_code TEXT NOT NULL,
    provider_plan TEXT NOT NULL,
    user_id UUID REFERENCES app_user(id),
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX billing_subscription_user ON billing_subscription(user_id);
CREATE TABLE billing_payment (
    reference TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    subscription_code TEXT REFERENCES billing_subscription(code),
    plan TEXT NOT NULL CHECK (plan IN ('SOLO', 'PROFESSIONAL')),
    cadence TEXT NOT NULL CHECK (cadence IN ('monthly', 'annually')),
    amount BIGINT NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL,
    paid_until TIMESTAMPTZ NOT NULL,
    reversed BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE billing_event (
    id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    reference TEXT,
    subscription_code TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT
);
