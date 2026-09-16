import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createObservationCleanup } from '../server/observation-cleanup.mjs';
process.umask(0o077);
let db;
try {
  const [action, path] = process.argv.slice(2); const c = loadConfig(path); db = openDatabase(c);
  const cleanup = createObservationCleanup(db, c);
  if (!['run', 'status'].includes(action)) throw new Error('INVALID_COMMAND');
  if (action === 'run') { const result = await cleanup.run(true); if (result.failed) throw new Error('CLEANUP_FAILED'); }
  console.log(JSON.stringify({ ok: true, data: cleanup.status() }));
} catch { console.error('OBSERVATION_CLEANUP_UNAVAILABLE'); process.exitCode = 1; }
finally { if (db) db.close(); }
