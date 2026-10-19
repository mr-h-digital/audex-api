-- Only infer ownership where an existing audit identifies one unambiguous account.
-- Unassigned/ambiguous legacy records stay inaccessible until an administrator resolves them.
ALTER TABLE site ADD COLUMN owner_id UUID REFERENCES app_user (id);
ALTER TABLE site ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0);
ALTER TABLE site ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE site s SET owner_id = owners.owner_id
FROM (
    SELECT site_id, (array_agg(DISTINCT created_by))[1] AS owner_id
    FROM audit WHERE created_by IS NOT NULL
    GROUP BY site_id HAVING count(DISTINCT created_by) = 1
) owners WHERE s.id = owners.site_id;
CREATE INDEX site_owner_id ON site (owner_id, id);

ALTER TABLE training_session ADD COLUMN owner_id UUID REFERENCES app_user (id);
ALTER TABLE training_session ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0);
ALTER TABLE training_session ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE training_session t SET owner_id = s.owner_id FROM site s WHERE t.site_id = s.id;
CREATE INDEX training_session_owner_id ON training_session (owner_id, id);

-- Preserve the site name used by a finalised audit, independently of later site edits.
ALTER TABLE audit ADD COLUMN site_name TEXT;
UPDATE audit a SET site_name = s.name FROM site s WHERE a.site_id = s.id;
ALTER TABLE audit ALTER COLUMN site_name SET NOT NULL;
CREATE INDEX audit_owner_id ON audit (created_by, id);
ALTER TABLE audit ADD COLUMN score_snapshot JSONB;
ALTER TABLE audit_photo ADD COLUMN content_hash TEXT;
