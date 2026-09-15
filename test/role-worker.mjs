import { readFileSync } from 'node:fs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService } from '../server/identity.mjs';
const c = loadConfig(process.argv[2]);
if (c.environment !== 'test' || !c.simulation) throw new Error('TEST_ONLY');
const input = JSON.parse(readFileSync(process.argv[3]));
const db = openDatabase(c); const service = createIdentityService(db, c);
process.send('ready');
process.once('message', () => {
  process.send('attempting');
  let result;
  try {
    if (input.action === 'write') service.withOperator(input.token, ({ userId, time, audit }) => audit(userId, userId, 'test.competing-write', 1, time, 'test-only'));
    else service.changeRole(input.request);
    result = 'committed';
  } catch (e) { result = e.code ?? 'STORAGE_FAILURE'; }
  db.close(); process.send(result); process.disconnect();
});
