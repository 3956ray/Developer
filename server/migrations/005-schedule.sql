CREATE TABLE schedule_draft (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), revision INTEGER NOT NULL, time_zone TEXT NOT NULL,
 coverage_json TEXT NOT NULL, courses_json TEXT NOT NULL
) STRICT;
CREATE TABLE schedule_snapshots (
 id TEXT PRIMARY KEY, publication_revision INTEGER NOT NULL UNIQUE, time_zone TEXT NOT NULL,
 coverage_json TEXT NOT NULL, coverage_end_at INTEGER NOT NULL, courses_json TEXT NOT NULL,
 published_at INTEGER NOT NULL, retired_at INTEGER
) STRICT;
CREATE TABLE schedule_head (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), revision INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('published','withdrawn')), current_snapshot_id TEXT REFERENCES schedule_snapshots(id),
 time_zone TEXT NOT NULL, changed_at INTEGER NOT NULL,
 CHECK((state='published' AND current_snapshot_id IS NOT NULL) OR (state='withdrawn' AND current_snapshot_id IS NULL))
) STRICT;
CREATE INDEX schedule_retention ON schedule_snapshots(coverage_end_at) WHERE retired_at IS NOT NULL;
CREATE TABLE schedule_cleanup (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), next_run_at INTEGER NOT NULL, last_success_at INTEGER,
 error_count INTEGER NOT NULL, deleted_total INTEGER NOT NULL, last_error TEXT
) STRICT;
INSERT INTO schedule_cleanup VALUES(1,0,NULL,0,0,NULL);
