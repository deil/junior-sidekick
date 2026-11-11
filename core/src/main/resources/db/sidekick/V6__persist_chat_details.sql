ALTER TABLE projects ADD COLUMN channel_kind TEXT;
ALTER TABLE projects ADD COLUMN is_shared INTEGER;
ALTER TABLE projects ADD COLUMN channel_details_updated_at TEXT;

CREATE TABLE users_with_chat_details (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT,
    idp_id TEXT UNIQUE,
    chat_id TEXT,
    created_at TEXT NOT NULL,
    last_seen_at TEXT,
    chat_workspace_id TEXT,
    is_bot INTEGER,
    is_guest INTEGER,
    chat_details_updated_at TEXT
);

INSERT INTO users_with_chat_details (id, name, email, idp_id, chat_id, created_at, last_seen_at)
SELECT id, name, email, idp_id, chat_id, created_at, last_seen_at FROM users;

DROP TABLE users;
ALTER TABLE users_with_chat_details RENAME TO users;
CREATE UNIQUE INDEX users_email_unique ON users (email);
