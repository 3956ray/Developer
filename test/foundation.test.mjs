import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { readFileSync, writeFileSync, rmSync, copyFileSync, mkdirSync, chmodSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { initialize } from '../scripts/init-local.mjs';
import { loadConfig, root } from '../server/config.mjs';
import { openDatabase, transaction } from '../server/database.mjs';

function setup(t, environment = 'test') {
  const path = initialize(environment, `cp0-${randomUUID()}`, 0);
  t.after(() => rmSync(dirname(path), { recursive: true, force: true }));
  return path;
}
function cli(...args) {
  return spawnSync(process.execPath, ['scripts/db.mjs', ...args], { cwd: root, encoding: 'utf8' });
}
async function start(path) {
  const p = spawn(process.execPath, ['server/main.mjs', path], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = ''; let errors = '';
  p.stderr.on('data', chunk => { errors += chunk; });
  const ready = await new Promise((resolveReady, reject) => {
    const timeout = setTimeout(() => { p.kill('SIGKILL'); reject(new Error('Readiness timeout')); }, 5000);
    p.once('exit', code => { clearTimeout(timeout); reject(new Error(`Server exited ${code}: ${errors}`)); });
    p.stdout.on('data', chunk => {
      output += chunk;
      if (output.includes('\n')) { clearTimeout(timeout); resolveReady(JSON.parse(output.split('\n')[0])); }
    });
  });
  return { p, port: ready.port };
}
async function stop(p, signal = 'SIGTERM') {
  if (p.exitCode !== null || p.signalCode !== null) return;
  const ended = once(p, 'exit'); p.kill(signal); await ended;
}

test('durable probe survives full process restart and abrupt termination', async t => {
  const path = setup(t);
  const migration = cli('migrate', path);
  assert.equal(migration.status, 0); t.diagnostic(`migration: ${migration.stdout.trim()}`);
  let s = await start(path); t.after(() => stop(s.p));
  const first = s.p.pid;
  const h = await fetch(`http://127.0.0.1:${s.port}/health`);
  assert.equal(h.status, 200); const health = await h.json(); assert.equal(health.data.environment, 'test');
  assert.equal(health.data.migrations, 5); t.diagnostic(`health: ${JSON.stringify(health)}`);
  assert.equal((await fetch(`http://127.0.0.1:${s.port}/v1/session`)).status, 401);
  assert.equal(cli('write', path, 'probe-restart').status, 0);
  await stop(s.p);
  s = await start(path); assert.notEqual(s.p.pid, first);
  let read = cli('read', path, 'probe-restart'); assert.equal(read.status, 0);
  const probe = JSON.parse(read.stdout).probe; assert.equal(probe.value, 'synthetic-non-member');
  t.diagnostic(`full restart: pid changed; persisted ${JSON.stringify(probe)}`);
  await stop(s.p, 'SIGKILL'); s = await start(path);
  read = cli('read', path, 'probe-restart'); assert.deepEqual(JSON.parse(read.stdout).probe, probe);
  t.diagnostic('abrupt SIGKILL restart: same committed probe retained');
});

test('migration replay is stable; changed migration rejected without modifying ledger', t => {
  const path = setup(t); const c = loadConfig(path);
  let db = openDatabase(c); const before = db.prepare('SELECT * FROM schema_migrations').all(); db.close();
  db = openDatabase(c); assert.deepEqual(db.prepare('SELECT * FROM schema_migrations').all(), before); db.close();
  const folder = resolve(c.stateDir, 'migrations'); mkdirSync(folder);
  for (const name of readdirSync(resolve(root, 'server/migrations'))) copyFileSync(resolve(root, 'server/migrations', name), resolve(folder, name));
  writeFileSync(resolve(folder, '001-foundation.sql'), readFileSync(resolve(root, 'server/migrations/001-foundation.sql'), 'utf8') + '\n-- changed\n');
  assert.throws(() => openDatabase(c, pathToFileURL(folder + '/')), /CHECKSUM/);
  db = openDatabase(c); assert.deepEqual(db.prepare('SELECT * FROM schema_migrations').all(), before); db.close();
});

test('failed new migration rolls back DDL and ledger atomically', t => {
  const path = setup(t); const c = loadConfig(path); let db = openDatabase(c); db.close();
  const folder = resolve(c.stateDir, 'migrations'); mkdirSync(folder);
  for (const name of readdirSync(resolve(root, 'server/migrations'))) copyFileSync(resolve(root, 'server/migrations', name), resolve(folder, name));
  copyFileSync(resolve(root, 'server/migrations/001-foundation.sql'), resolve(folder, '001-foundation.sql'));
  writeFileSync(resolve(folder, '006-broken.sql'), 'CREATE TABLE rollback_marker (id INTEGER); INSERT INTO missing_table VALUES (1);');
  assert.throws(() => openDatabase(c, pathToFileURL(folder + '/')));
  db = openDatabase(c); assert.equal(db.prepare("SELECT name FROM sqlite_master WHERE name='rollback_marker'").get(), undefined);
  assert.equal(db.prepare('SELECT count(*) n FROM schema_migrations').get().n, 5); db.close();
});

test('write rollback preserves original value and no partial row', t => {
  const path = setup(t); const db = openDatabase(loadConfig(path));
  assert.throws(() => transaction(db, () => {
    db.prepare('INSERT INTO foundation_probe VALUES (?, ?, ?)').run('probe-rollback', 'synthetic-non-member', 1);
    throw new Error('injected');
  }), /injected/);
  assert.equal(db.prepare('SELECT count(*) n FROM foundation_probe').get().n, 0); db.close();
});

test('environment/store namespaces, keys and persistent files are isolated', t => {
  const a = setup(t);
  const b = initialize('store', loadConfig(a).gymId, 0);
  t.after(() => rmSync(dirname(b), { recursive: true, force: true }));
  const other = setup(t);
  assert.equal(cli('write', a, 'probe-isolation').status, 0);
  assert.equal(JSON.parse(cli('read', other, 'probe-isolation').stdout).probe, null);
  assert.equal(cli('migrate', b).status, 0); assert.equal(cli('write', b, 'probe-isolation').status, 1);
  const ca = loadConfig(a), cb = loadConfig(b);
  assert.notEqual(ca.keyFingerprint, cb.keyFingerprint); assert.notEqual(ca.databasePath, cb.databasePath);
  let db = openDatabase(cb); assert.equal(db.prepare('SELECT count(*) n FROM foundation_probe').get().n, 0); db.close();
  copyFileSync(ca.databasePath, cb.databasePath);
  assert.throws(() => openDatabase(cb), /DB_SCOPE_OR_KEYS_MISMATCH/);
});

test('configuration, simulation, keys and accidental initialization fail closed', t => {
  const path = setup(t); const original = JSON.parse(readFileSync(path));
  assert.throws(() => loadConfig());
  assert.throws(() => initialize(original.environment, original.gymId));
  for (const change of [{ environment: 'production' }, { simulation: true }, { stateDir: '/tmp' }, { host: '0.0.0.0' }]) {
    writeFileSync(path, JSON.stringify({ ...original, ...change })); assert.throws(() => loadConfig(path));
  }
  writeFileSync(path, JSON.stringify(original));
  const keyPath = resolve(original.stateDir, 'keys.json');
  chmodSync(keyPath, 0o644); assert.throws(() => loadConfig(path), /PERMISSIONS/); chmodSync(keyPath, 0o600);
  const db = openDatabase(loadConfig(path)); db.close();
  const keys = JSON.parse(readFileSync(keyPath)); keys.memberHmac = 'ab'.repeat(32); writeFileSync(keyPath, JSON.stringify(keys));
  assert.throws(() => openDatabase(loadConfig(path)), /DB_SCOPE_OR_KEYS_MISMATCH/);
  rmSync(keyPath); assert.throws(() => loadConfig(path));
  const missing = spawnSync(process.execPath, ['server/main.mjs'], { cwd: root, encoding: 'utf8' });
  assert.equal(missing.status, 1); assert.match(missing.stderr, /STARTUP_REJECTED/);
});

test('cross-process writer lock prevents partial competing write', async t => {
  const path = setup(t); const db = openDatabase(loadConfig(path));
  db.exec('BEGIN IMMEDIATE');
  try {
    const p = spawn(process.execPath, ['scripts/db.mjs', 'write', path, 'probe-contended'], { cwd: root, stdio: 'ignore' });
    const [code] = await once(p, 'exit'); assert.equal(code, 1);
    assert.equal(db.prepare('SELECT count(*) n FROM foundation_probe').get().n, 0);
  } finally { db.exec('ROLLBACK'); db.close(); }
  assert.equal(cli('write', path, 'probe-contended').status, 0);
});
