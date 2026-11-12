CREATE TABLE automation_projects (
    project_uid TEXT NOT NULL PRIMARY KEY,
    last_allocated_uid INTEGER NOT NULL
);

CREATE TABLE automations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_uid TEXT NOT NULL,
    uid INTEGER NOT NULL,
    name TEXT NOT NULL,
    name_key TEXT NOT NULL,
    description TEXT,
    instructions TEXT NOT NULL,
    trigger JSON NOT NULL CHECK (
        json_valid(trigger)
        AND json_type(trigger) = 'object'
        AND COALESCE(json_type(trigger, '$.type') = 'text', 0)
    ),
    is_enabled INTEGER NOT NULL,
    last_run_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (project_uid, uid),
    UNIQUE (project_uid, name_key)
);
