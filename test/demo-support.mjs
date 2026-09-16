import { randomUUID } from 'node:crypto';
import { initializeDemo } from '../scripts/demo.mjs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService, NOTICE_VERSION } from '../server/identity.mjs';
import { issueDemoTicket } from '../server/demo.mjs';
import { fixtureLifetime } from './process-support.mjs';
export function setupDemo(t, options = {}) {
  const path = initializeDemo('demo-' + randomUUID(), 0), c = loadConfig(path), db = openDatabase(c);
  const lifetime = fixtureLifetime(t, path, () => { if (db.isOpen) db.close(); });
  const service = createIdentityService(db, c, options);
  const ticket = (actor = 'member-a') => ({ code: issueDemoTicket(db, c, actor, (options.now ?? Date.now)()), privacyNoticeVersion: NOTICE_VERSION, consent: true });
  return { path, c, db, service, ticket, beforeRemove: lifetime.beforeRemove };
}
