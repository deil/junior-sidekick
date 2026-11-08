CREATE TABLE projects (
    id INTEGER PRIMARY KEY,
    uid TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL CHECK (type IN ('slack', 'discord')),
    name TEXT NOT NULL,
    external_ref_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (type, external_ref_id)
);
