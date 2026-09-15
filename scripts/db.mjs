import { loadConfig } from '../server/config.mjs';
import { openDatabase, transaction } from '../server/database.mjs';
process.umask(0o077);
let db;
try {
  const [action, path, id] = process.argv.slice(2);
  const c = loadConfig(path);
  if (!['migrate', 'write', 'read'].includes(action)) throw new Error('ACTION_INVALID');
  if (action !== 'migrate' && (c.environment !== 'test' || !/^probe-[a-z0-9-]{1,64}$/.test(id))) throw new Error('PROBE_TEST_ONLY');
  db = openDatabase(c);
  if (action === 'write') transaction(db, () => db.prepare('INSERT INTO foundation_probe VALUES (?, ?, ?)').run(id, 'synthetic-non-member', Date.now()));
  const result = action === 'migrate'
    ? { migrations: db.prepare('SELECT name, sha256 FROM schema_migrations ORDER BY name').all(), integrity: db.prepare('PRAGMA integrity_check').get() }
    : { probe: db.prepare('SELECT * FROM foundation_probe WHERE probe_id=?').get(id) ?? null };
  console.log(JSON.stringify(result));
} catch { process.stderr.write('DB_COMMAND_REJECTED\n'); process.exitCode = 1; }
finally { if (db) db.close(); }
