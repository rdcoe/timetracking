-- XP Quest Time Tracker schema. Statements are split on ';' and run idempotently
-- on every startup (see Database#applySchema), so this doubles as a migration file.

CREATE TABLE IF NOT EXISTS project (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(64),
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    client_name VARCHAR(255),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS time_entry (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT    NOT NULL REFERENCES project (id),
    start_time TIMESTAMP NOT NULL,
    end_time   TIMESTAMP,
    notes      VARCHAR(2000)
);

CREATE INDEX IF NOT EXISTS idx_time_entry_project ON time_entry (project_id);
