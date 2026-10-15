-- Training sessions let the dashboard compare audit scores before and after training.
CREATE TABLE training_session (
    id            UUID PRIMARY KEY,
    site_id       UUID NOT NULL REFERENCES site (id),
    session_date  DATE NOT NULL,
    topic         TEXT NOT NULL,
    attendees     INT CHECK (attendees IS NULL OR attendees >= 0),
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX training_session_site_date ON training_session (site_id, session_date);
-- Dashboard and report filter by period across all sites.
CREATE INDEX audit_date_idx ON audit (audit_date);
