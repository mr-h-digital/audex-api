-- AudEx initial schema. Audit ids are client-generated UUIDs so audits created offline can sync idempotently.

CREATE TABLE site (
    id            UUID PRIMARY KEY,
    name          TEXT NOT NULL,
    address       TEXT,
    facility_type TEXT,
    unit_manager  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE checklist_template (
    id      UUID PRIMARY KEY,
    name    TEXT NOT NULL,
    version INT  NOT NULL,
    UNIQUE (name, version)
);

CREATE TABLE template_section (
    id          UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES checklist_template (id) ON DELETE CASCADE,
    code        TEXT NOT NULL,
    name        TEXT NOT NULL,
    high_risk   BOOLEAN NOT NULL DEFAULT FALSE,
    position    INT NOT NULL,
    UNIQUE (template_id, code)
);

CREATE TABLE template_item (
    id         UUID PRIMARY KEY,
    section_id UUID NOT NULL REFERENCES template_section (id) ON DELETE CASCADE,
    item_key   TEXT NOT NULL,
    text       TEXT NOT NULL,
    severity   TEXT NOT NULL CHECK (severity IN ('MINOR', 'MAJOR', 'CRITICAL')),
    weight     INT  NOT NULL CHECK (weight > 0),
    position   INT  NOT NULL,
    UNIQUE (section_id, item_key)
);

-- Rule sets are versioned and immutable once an audit has used them; changes create a new row.
CREATE TABLE rule_set (
    code       TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    config     JSONB NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_active_rule_set ON rule_set (is_active) WHERE is_active;

CREATE TABLE audit (
    id                UUID PRIMARY KEY,
    site_id           UUID NOT NULL REFERENCES site (id),
    template_id       UUID NOT NULL REFERENCES checklist_template (id),
    rule_set_code     TEXT NOT NULL REFERENCES rule_set (code),
    rule_set_snapshot JSONB NOT NULL,            -- the exact rules the audit was scored under
    audit_date        DATE NOT NULL,
    auditor           TEXT NOT NULL,
    unit_manager      TEXT,
    status            TEXT NOT NULL CHECK (status IN ('COMPLIANT', 'NOT_YET_COMPLIANT', 'NON_COMPLIANT', 'INCOMPLETE')),
    earned_half       INT,
    possible_half     INT,
    triggers          JSONB NOT NULL DEFAULT '[]',
    flags             JSONB NOT NULL DEFAULT '[]',
    finalised_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_site_date ON audit (site_id, audit_date);

CREATE TABLE audit_response (
    audit_id          UUID NOT NULL REFERENCES audit (id) ON DELETE CASCADE,
    item_key          TEXT NOT NULL,
    result            TEXT CHECK (result IN ('COMPLIANT', 'PARTIALLY_COMPLIANT', 'NON_COMPLIANT', 'NA')),
    evidence          TEXT,
    na_reason         TEXT,
    corrective_action TEXT,
    action_owner      TEXT,
    action_due        DATE,
    action_closed_at  TIMESTAMPTZ,
    answered_at       TIMESTAMPTZ,
    PRIMARY KEY (audit_id, item_key),
    CHECK (result <> 'NA' OR na_reason IS NOT NULL)   -- N/A always needs a reason
);

CREATE TABLE audit_photo (
    id          UUID PRIMARY KEY,
    audit_id    UUID NOT NULL REFERENCES audit (id) ON DELETE CASCADE,
    item_key    TEXT,
    storage_key TEXT NOT NULL,                   -- object key in the Railway storage bucket
    caption     TEXT,
    taken_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (audit_id, item_key) REFERENCES audit_response (audit_id, item_key)
);
