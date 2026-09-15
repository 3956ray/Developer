import { readFileSync, writeFileSync, rmSync } from 'node:fs';
import { dirname } from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import { initialize } from '../scripts/init-local.mjs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService, NOTICE_VERSION } from '../server/identity.mjs';
import { codeDigest } from '../server/wechat.mjs';
export function setupIdentity(t, options = {}) {
  const path = initialize('test', `cp1-${randomUUID()}`, 0);
  const config = JSON.parse(readFileSync(path)); config.identity = { mode: 'test', appId: 'test-app' }; config.simulation = true;
  writeFileSync(path, JSON.stringify(config)); const c = loadConfig(path); const db = openDatabase(c);
  const service = createIdentityService(db, c, options);
  t.after(() => { try { db.close(); } catch {} rmSync(dirname(path), { recursive: true, force: true }); });
  function fixture(subject = 'synthetic-one', outcome = 'success') {
    const code = randomBytes(24).toString('base64url');
    db.prepare('INSERT INTO test_login_fixtures VALUES (?,?,?)').run(codeDigest(c, code), subject, outcome);
    return { code, privacyNoticeVersion: NOTICE_VERSION, consent: true };
  }
  return { path, c, db, service, fixture };
}
export function intent(time, revision = 1) { return { operationId: randomUUID(), requestCreatedAt: new Date(time).toISOString(), expectedRevision: revision }; }
export function roleWrite(c, userId, time, revision = 'absent', action = 'grant') {
  return { ...intent(time, revision), userId, action, gymId: c.gymId, maintainerId: 'synthetic-maintainer', reasonCategory: action === 'grant' ? 'initial-authorization' : 'authorization-ended', ownerConfirmed: true };
}
