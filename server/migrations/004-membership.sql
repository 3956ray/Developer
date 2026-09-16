ALTER TABLE accounts ADD COLUMN pairing_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE operations ADD COLUMN subject_id TEXT;
UPDATE operations SET subject_id=json_extract(result_json,'$.userId') WHERE operation_type IN ('role.grant','role.revoke');
CREATE TABLE member_registry (
 member_key TEXT PRIMARY KEY, state TEXT NOT NULL CHECK(state IN ('verified','revoked')),
 expiry_mode TEXT NOT NULL CHECK(expiry_mode IN ('fixed_until','no_fixed_expiry','pending_confirmation')),
 valid_until INTEGER, local_end_date TEXT, verified_at INTEGER NOT NULL, revision INTEGER NOT NULL,
 CHECK((expiry_mode='fixed_until' AND valid_until IS NOT NULL) OR (expiry_mode!='fixed_until' AND valid_until IS NULL))
) STRICT;
CREATE TABLE bindings (
 binding_id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES accounts(user_id), member_key TEXT NOT NULL REFERENCES member_registry(member_key),
 current INTEGER NOT NULL CHECK(current IN (0,1)), state TEXT NOT NULL CHECK(state IN ('verified','revoked','unbound')),
 revision INTEGER NOT NULL, closed_at INTEGER
) STRICT;
CREATE UNIQUE INDEX binding_user_current ON bindings(user_id) WHERE current=1;
CREATE UNIQUE INDEX binding_member_current ON bindings(member_key) WHERE current=1;
CREATE TABLE pairings (
 pairing_id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES accounts(user_id), revision INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('active','expired','cancelled','consumed','replaced')),
 lookup_hmac TEXT NOT NULL, ciphertext TEXT, nonce TEXT, tag TEXT, key_id TEXT,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
) STRICT;
CREATE UNIQUE INDEX pairing_user_active ON pairings(user_id) WHERE state='active';
CREATE UNIQUE INDEX pairing_code_active ON pairings(lookup_hmac) WHERE state='active';
CREATE INDEX pairing_user_revision ON pairings(user_id,revision);
CREATE TABLE membership_limits (
 kind TEXT NOT NULL, subject_id TEXT NOT NULL, window_start INTEGER NOT NULL, window_end INTEGER NOT NULL, count INTEGER NOT NULL,
 PRIMARY KEY(kind,subject_id,window_start)
) STRICT;
CREATE TABLE deletion_jobs (
 job_id TEXT PRIMARY KEY, user_id TEXT UNIQUE REFERENCES accounts(user_id), receipt_digest TEXT NOT NULL UNIQUE,
 state TEXT NOT NULL CHECK(state IN ('pending','failed','completed')), accepted_at INTEGER NOT NULL, completed_at INTEGER,
 phase INTEGER NOT NULL DEFAULT 0, next_retry_at INTEGER NOT NULL, error_count INTEGER NOT NULL DEFAULT 0, last_error TEXT
) STRICT;
CREATE TABLE membership_cleanup (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), phase INTEGER NOT NULL, next_run_at INTEGER NOT NULL,
 last_success_at INTEGER, error_count INTEGER NOT NULL, last_error TEXT
) STRICT;
INSERT INTO membership_cleanup VALUES(1,0,0,NULL,0,NULL);

CREATE INDEX pairing_expiry ON pairings(expires_at);
CREATE INDEX binding_closed ON bindings(closed_at) WHERE current=0;
CREATE INDEX operation_subject ON operations(subject_id);
CREATE INDEX membership_limit_expiry ON membership_limits(window_end);
CREATE INDEX deletion_pending ON deletion_jobs(next_retry_at) WHERE state!='completed';
