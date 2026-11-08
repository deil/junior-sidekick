ALTER TABLE projects RENAME COLUMN type TO adapter;

ALTER TABLE projects ADD COLUMN type TEXT NOT NULL DEFAULT 'private'
    CHECK (type IN ('public', 'private', 'dm'));
