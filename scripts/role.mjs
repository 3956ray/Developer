import { readFileSync } from 'node:fs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService } from '../server/identity.mjs';
import { parseStrict, ApiError } from '../server/protocol.mjs';
process.umask(0o077);
let db;
try {
  const [command, path, ...args] = process.argv.slice(2);
  const c = loadConfig(path); db = openDatabase(c); const identity = createIdentityService(db, c);
  let data;
  if (command === 'inspect' && args.length === 2) data = identity.inspectRole(args[0], args[1]);
  else if (command === 'apply' && args.length === 1) data = identity.changeRole(parseStrict(readFileSync(args[0], 'utf8')));
  else if (command === 'result' && args.length === 3) data = identity.maintainerResult(...args);
  else throw new ApiError(400, 'INVALID_COMMAND');
  console.log(JSON.stringify({ ok: true, data }));
} catch (error) { console.error(JSON.stringify({ ok: false, error: error instanceof ApiError ? error.code : 'STORAGE_OR_CONFIG_UNAVAILABLE' })); process.exitCode = 1; }
finally { if (db) db.close(); }
