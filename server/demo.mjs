import { randomBytes } from 'node:crypto';
import { transaction } from './database.mjs';
import { fail, hmac, canonical } from './protocol.mjs';
import { codeDigest } from './wechat.mjs';

export function requireDemo(c) {
  if (c.environment !== 'test' || c.simulation !== true || c.identity.mode !== 'test' ||
      c.identity.appId !== 'test-app' || c.demo?.enabled !== true || !c.gymId.startsWith('demo-')) fail(403, 'DEMO_FORBIDDEN');
}
export function issueDemoTicket(db, c, actorAlias, time = Date.now()) {
  requireDemo(c);
  if (!['member-a', 'member-b', 'operator-a'].includes(actorAlias)) fail(422, 'DEMO_ACTOR_INVALID');
  const code = randomBytes(32).toString('base64url');
  const subject = hmac(c.keys.intentHmac, canonical(['demo-subject', c.environment, c.gymId, c.identity.appId, actorAlias]));
  transaction(db, () => {
    const ns = db.prepare('SELECT * FROM identity_namespace WHERE singleton=1').get();
    if (ns && (ns.mode !== 'test' || ns.app_id !== 'test-app')) fail(503, 'IDENTITY_NAMESPACE_MISMATCH');
    db.prepare('INSERT INTO demo_login_tickets VALUES (?,?,?,?)').run(codeDigest(c, code), subject, time, time + 300000);
  });
  return code; // Only the CLI's one-time stdout; never persist the bearer credential.
}
// Called inside the same committed transaction as login_codes insertion. Removing the row
// consumes even an expired ticket; adapter/session failures cannot roll this transaction back.
export function claimDemoTicket(db, c, code, time) {
  requireDemo(c);
  const row = db.prepare('DELETE FROM demo_login_tickets WHERE ticket_hmac=? RETURNING subject_hmac,issued_at,expires_at').get(codeDigest(c, code));
  return row && time >= row.issued_at && time < row.expires_at ? { openId: 'demo_' + row.subject_hmac } : null;
}
