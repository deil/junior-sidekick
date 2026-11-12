CREATE TABLE automation_projects_by_id (
    project_id INTEGER NOT NULL PRIMARY KEY REFERENCES projects(id),
    last_allocated_uid INTEGER NOT NULL
);

INSERT INTO automation_projects_by_id (project_id, last_allocated_uid)
SELECT projects.id, automation_projects.last_allocated_uid
FROM automation_projects LEFT JOIN projects ON projects.uid = automation_projects.project_uid;

CREATE TABLE automations_by_project_id (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL REFERENCES projects(id),
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
    UNIQUE (project_id, uid),
    UNIQUE (project_id, name_key)
);

INSERT INTO automations_by_project_id (
    id, project_id, uid, name, name_key, description, instructions, trigger,
    is_enabled, last_run_at, created_at, updated_at
)
SELECT automations.id, projects.id, automations.uid, automations.name, automations.name_key,
    automations.description, automations.instructions, automations.trigger,
    automations.is_enabled, automations.last_run_at, automations.created_at, automations.updated_at
FROM automations LEFT JOIN projects ON projects.uid = automations.project_uid;

UPDATE sqlite_sequence
SET seq = MAX(seq, COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'automations'), 0))
WHERE name = 'automations_by_project_id';

DROP TABLE automations;
DROP TABLE automation_projects;
ALTER TABLE automation_projects_by_id RENAME TO automation_projects;
ALTER TABLE automations_by_project_id RENAME TO automations;
