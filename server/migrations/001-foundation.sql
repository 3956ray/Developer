CREATE TABLE deployment (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  environment TEXT NOT NULL CHECK (environment IN ('test', 'store')),
  gym_id TEXT NOT NULL,
  key_fingerprint TEXT NOT NULL,
  created_at INTEGER NOT NULL
) STRICT;
CREATE TABLE foundation_probe (
  probe_id TEXT PRIMARY KEY,
  value TEXT NOT NULL CHECK (value = 'synthetic-non-member'),
  created_at INTEGER NOT NULL
) STRICT;
