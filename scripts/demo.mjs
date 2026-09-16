import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { initialize } from './init-local.mjs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { issueDemoTicket, requireDemo } from '../server/demo.mjs';

export function initializeDemo(gymId, port = 8787) {
  if (typeof gymId !== 'string' || !/^demo-[a-z0-9-]+$/.test(gymId)) throw new Error('DEMO_SCOPE_REQUIRED');
  const path = initialize('test', gymId, port);
  const config = JSON.parse(readFileSync(path));
  Object.assign(config, { simulation: true, identity: { mode: 'test', appId: 'test-app' }, demo: { enabled: true } });
  writeFileSync(path, JSON.stringify(config, null, 2) + '\n', { mode: 0o600 });
  const db = openDatabase(loadConfig(path)); db.close();
  return path;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  let db;
  try {
    const [action, path, actor] = process.argv.slice(2);
    if (action === 'init' && process.argv.length === 4) console.log(initializeDemo(path));
    else if (action === 'ticket' && process.argv.length === 5) {
      const c = loadConfig(path); requireDemo(c); db = openDatabase(c);
      process.stdout.write(issueDemoTicket(db, c, actor) + '\n');
    } else throw new Error('INVALID_COMMAND');
  } catch { process.stderr.write('DEMO_REJECTED: check explicit demo scope, actor and existing directory\n'); process.exitCode = 1; }
  finally { if (db?.isOpen) db.close(); }
}
