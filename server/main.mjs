import { createServer } from 'node:http';
import { loadConfig } from './config.mjs';
import { openDatabase } from './database.mjs';

process.umask(0o077);
let db;
try {
  const c = loadConfig(process.argv[2]);
  db = openDatabase(c);
  const server = createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    let status = 404;
    let body = { ok: false, error: { code: 'NOT_FOUND' }, serverNow: new Date().toISOString() };
    if (req.method === 'GET' && req.url === '/health') {
      try {
        const migrations = db.prepare('SELECT count(*) AS count FROM schema_migrations').get().count;
        const identity = db.prepare('SELECT environment, gym_id FROM deployment WHERE singleton=1').get();
        if (!identity || identity.environment !== c.environment || identity.gym_id !== c.gymId) throw new Error('IDENTITY_CHANGED');
        status = 200;
        body = { ok: true, serverNow: new Date().toISOString(), data: { status: 'ready', checkpoint: 'CP0', environment: c.environment, gymId: c.gymId, migrations } };
      } catch { status = 503; body.error.code = 'STORAGE_UNAVAILABLE'; }
    }
    res.writeHead(status); res.end(JSON.stringify(body));
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  server.on('error', error => { db.close(); process.stderr.write(`SERVER_START_FAILED: ${error.code || 'UNKNOWN'}\n`); process.exitCode = 1; });
  server.listen(c.port, c.host, () => console.log(JSON.stringify({ event: 'ready', port: server.address().port, environment: c.environment, gymId: c.gymId })));
  const stop = () => server.close(() => { db.close(); process.exitCode = 0; });
  process.once('SIGTERM', stop); process.once('SIGINT', stop);
} catch { if (db) db.close(); process.stderr.write('STARTUP_REJECTED: check explicit configuration, keys and database\n'); process.exitCode = 1; }
