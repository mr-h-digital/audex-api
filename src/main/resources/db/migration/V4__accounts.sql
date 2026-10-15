-- Accounts, sign-in sessions and password-reset tokens. Tokens are stored only as SHA-256 hashes, never in plain text.
CREATE TABLE app_user (
    id                  UUID PRIMARY KEY,
    username            TEXT NOT NULL,
    email               TEXT NOT NULL,
    display_name        TEXT NOT NULL,
    phone               TEXT,
    password_hash       TEXT NOT NULL,
    disabled            BOOLEAN NOT NULL DEFAULT FALSE,
    password_changed_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_user_username_lower ON app_user (lower(username));
CREATE UNIQUE INDEX app_user_email_lower ON app_user (lower(email));

CREATE TABLE auth_session (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash          TEXT NOT NULL UNIQUE,
    device              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,          -- slides forward while the app is in use
    absolute_expires_at TIMESTAMPTZ NOT NULL,          -- hard stop, whatever the activity
    revoked_at          TIMESTAMPTZ
);
CREATE INDEX auth_session_user ON auth_session (user_id);

CREATE TABLE password_reset_token (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);
CREATE INDEX password_reset_user ON password_reset_token (user_id);

ALTER TABLE audit ADD COLUMN created_by UUID REFERENCES app_user (id);
