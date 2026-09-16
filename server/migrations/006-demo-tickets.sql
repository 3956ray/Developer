CREATE TABLE demo_login_tickets (
  ticket_hmac TEXT PRIMARY KEY,
  subject_hmac TEXT NOT NULL,
  issued_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL CHECK (expires_at = issued_at + 300000)
) STRICT;
CREATE INDEX demo_ticket_expiry ON demo_login_tickets(expires_at);
