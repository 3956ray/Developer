ALTER TABLE schedule_draft ADD COLUMN provenance_json TEXT NOT NULL DEFAULT '{"kind":"manual"}';
ALTER TABLE schedule_snapshots ADD COLUMN provenance_json TEXT NOT NULL DEFAULT '{"kind":"manual"}';
CREATE TABLE source_bindings (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), source_id TEXT NOT NULL UNIQUE, gym_id TEXT NOT NULL,
 source_label TEXT NOT NULL, schema_version INTEGER NOT NULL, mapping_version INTEGER NOT NULL, time_zone TEXT NOT NULL,
 applied_revision INTEGER NOT NULL DEFAULT 0, applied_hash TEXT, applied_result_json TEXT
) STRICT;
CREATE TABLE source_entity_map (
 source_id TEXT NOT NULL REFERENCES source_bindings(source_id), external_id TEXT NOT NULL, course_id TEXT NOT NULL UNIQUE,
 PRIMARY KEY(source_id,external_id)
) STRICT;
CREATE TABLE import_versions (
 source_id TEXT NOT NULL REFERENCES source_bindings(source_id), source_revision INTEGER NOT NULL, content_hash TEXT NOT NULL,
 PRIMARY KEY(source_id,source_revision)
) STRICT;
CREATE TABLE import_batches (
 batch_id TEXT PRIMARY KEY, source_id TEXT NOT NULL REFERENCES source_bindings(source_id), source_revision INTEGER NOT NULL,
 content_hash TEXT NOT NULL, captured_at TEXT NOT NULL, prepared_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('candidate','replaced','applied')), candidate_json TEXT NOT NULL, diff_json TEXT NOT NULL,
 result_json TEXT, applied_at INTEGER
) STRICT;
CREATE INDEX import_retention ON import_batches(state,expires_at,applied_at);
CREATE TABLE demo_runs (
 run_id TEXT PRIMARY KEY, scenario TEXT NOT NULL, anchor_date TEXT NOT NULL, step_index INTEGER NOT NULL DEFAULT 0,
 state TEXT NOT NULL CHECK(state IN ('ready','running','paused','interrupted','needs_review','completed','cancelled')),
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, terminal_at INTEGER, last_revision_json TEXT, error_code TEXT
) STRICT;
CREATE UNIQUE INDEX one_active_demo ON demo_runs((1)) WHERE terminal_at IS NULL;
CREATE TABLE demo_steps (
 run_id TEXT NOT NULL REFERENCES demo_runs(run_id) ON DELETE CASCADE, step_index INTEGER NOT NULL,
 actor_alias TEXT NOT NULL, actor_hmac TEXT NOT NULL, path TEXT NOT NULL, method TEXT NOT NULL,
 operation_type TEXT NOT NULL, request_json TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('prepared','sent','committed')),
 result_json TEXT, PRIMARY KEY(run_id,step_index)
) STRICT;
CREATE TABLE demo_cleanup (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), next_run_at INTEGER NOT NULL, last_success_at INTEGER,
 error_count INTEGER NOT NULL, deleted_total INTEGER NOT NULL, last_error TEXT
) STRICT;
INSERT INTO demo_cleanup VALUES(1,0,NULL,0,0,NULL);
CREATE TABLE demo_runner_lock (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), owner TEXT NOT NULL, pid INTEGER NOT NULL, run_id TEXT NOT NULL
) STRICT;
