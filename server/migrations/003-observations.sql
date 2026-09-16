CREATE TABLE observation_head (
  singleton INTEGER PRIMARY KEY CHECK (singleton=1),
  revision INTEGER NOT NULL CHECK (revision>0),
  state TEXT NOT NULL CHECK (state IN ('quiet','moderate','busy','unknown','paused','withdrawn')),
  observed_at INTEGER,
  published_at INTEGER NOT NULL,
  valid_until INTEGER
) STRICT;
CREATE TABLE observation_events (
  revision INTEGER PRIMARY KEY,
  state TEXT NOT NULL,
  observed_at INTEGER,
  published_at INTEGER NOT NULL,
  valid_until INTEGER,
  actor_id TEXT NOT NULL
) STRICT;
CREATE INDEX observation_events_age ON observation_events(published_at,revision);
CREATE TABLE observation_cleanup (
  singleton INTEGER PRIMARY KEY CHECK (singleton=1),
  state TEXT NOT NULL CHECK (state IN ('idle','running','failed')),
  cursor_revision INTEGER NOT NULL,
  cutoff_at INTEGER,
  next_run_at INTEGER NOT NULL,
  last_success_at INTEGER,
  deleted_total INTEGER NOT NULL,
  error_count INTEGER NOT NULL,
  last_error_category TEXT
) STRICT;
INSERT INTO observation_cleanup VALUES (1,'idle',0,NULL,0,NULL,0,0,NULL);
