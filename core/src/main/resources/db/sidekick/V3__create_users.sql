CREATE TABLE users (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL,
    idp_id TEXT UNIQUE,
    chat_id TEXT,
    created_at TEXT NOT NULL
);
