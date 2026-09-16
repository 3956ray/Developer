import { readFileSync } from 'node:fs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService } from '../server/identity.mjs';
import { createObservationService } from '../server/observations.mjs';
const c = loadConfig(process.argv[2]);
if (c.environment !== 'test') throw new Error('TEST_ONLY');
const input = JSON.parse(readFileSync(process.argv[3]));
const db = openDatabase(c), identity = createIdentityService(db, c, { now: () => input.time });
const observations = createObservationService(db, c, identity);
function run() {
  let result;
  try { result = { status: 'committed', revision: observations.publish(input.token, input.body).data.revision }; }
  catch (e) { result = { status: e.code ?? 'STORAGE_FAILURE' }; }
  db.close();
  if (process.send) { process.send(result); process.disconnect(); } else console.log(JSON.stringify(result));
}
if (process.send) { process.send('ready'); process.once('message', () => { process.send('attempting'); run(); }); }
else run();
