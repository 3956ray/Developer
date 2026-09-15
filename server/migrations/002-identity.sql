CREATE TABLE identity_namespace (
  singleton INTEGER PRIMARY KEY CHECK (singleton=1),
  mode TEXT NOT NULL CHECK (mode IN ('test','wechat')),
  app_id TEXT NOT NULL
) STRICT;
CREATE TABLE accounts (
  user_id TEXT PRIMARY KEY,
  gym_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('active','deleting')),
  revision INTEGER NOT NULL CHECK (revision>0),
  created_at INTEGER NOT NULL
) STRICT;
CREATE TABLE wechat_identities (
  app_id TEXT NOT NULL,
  open_id TEXT NOT NULL,
  user_id TEXT NOT NULL REFERENCES accounts(user_id),
  PRIMARY KEY (app_id,open_id),
  UNIQUE (user_id)
) STRICT;
CREATE TABLE sessions (
  session_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES accounts(user_id),
  token_digest TEXT NOT NULL UNIQUE,
  auth_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  last_interactive_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER,
  revision INTEGER NOT NULL CHECK (revision>0)
) STRICT;
CREATE INDEX sessions_user ON sessions(user_id,created_at,session_id);
CREATE TABLE privacy_consents (
  user_id TEXT NOT NULL REFERENCES accounts(user_id),
  notice_version TEXT NOT NULL,
  accepted_at INTEGER NOT NULL,
  PRIMARY KEY(user_id,notice_version)
) STRICT;
CREATE TABLE operator_roles (
  user_id TEXT PRIMARY KEY REFERENCES accounts(user_id),
  granted INTEGER NOT NULL CHECK (granted IN (0,1)),
  revision INTEGER NOT NULL CHECK (revision>0),
  updated_at INTEGER NOT NULL
) STRICT;
CREATE TABLE rate_buckets (
  kind TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  window_end INTEGER NOT NULL,
  count INTEGER NOT NULL CHECK (count>=0),
  PRIMARY KEY(kind,window_start)
) STRICT;
CREATE TABLE login_codes (
  code_hmac TEXT PRIMARY KEY,
  attempted_at INTEGER NOT NULL
) STRICT;
CREATE TABLE test_login_fixtures (
  code_hmac TEXT PRIMARY KEY,
  synthetic_subject TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome IN ('success','invalid','platform'))
) STRICT;
CREATE TABLE audit (
  id TEXT PRIMARY KEY,
  actor_id TEXT NOT NULL,
  subject_id TEXT,
  action TEXT NOT NULL,
  result TEXT NOT NULL,
  revision INTEGER NOT NULL,
  time INTEGER NOT NULL,
  reason_category TEXT NOT NULL
) STRICT;
CREATE TABLE operations (
  actor_id TEXT NOT NULL,
  operation_type TEXT NOT NULL,
  operation_key TEXT NOT NULL,
  body_hmac TEXT NOT NULL,
  result_json TEXT NOT NULL,
  applied_revision INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(actor_id,operation_type,operation_key)
) STRICT;
