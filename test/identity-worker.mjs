// Test-only subprocess driver. Input is an ephemeral private fixture file, never an HTTP route.
import { readFileSync } from 'node:fs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService } from '../server/identity.mjs';
const c = loadConfig(process.argv[2]);
if (c.environment !== 'test' || !c.simulation) throw new Error('TEST_ONLY');
const input = JSON.parse(readFileSync(process.argv[3]));
const db = openDatabase(c); const service = createIdentityService(db, c, { now: () => input.time });
const outcomes = [];
for (const body of input.bodies) {
  try { await service.login(body); outcomes.push('success'); }
  catch (e) { outcomes.push(e.code ?? 'STORAGE_FAILURE'); }
}
console.log(JSON.stringify(outcomes)); db.close();
