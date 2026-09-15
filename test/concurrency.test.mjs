import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { setupIdentity } from './identity-support.mjs';
import { root } from '../server/config.mjs';
const TIME = Date.parse('2026-09-16T00:00:30.000Z');
test('R01/F01: four real processes share 120-count bucket, one identity, five-session cap; restart stays limited', async t => {
  const x = setupIdentity(t, { now: () => TIME });
  const files = Array.from({ length: 4 }, (_, n) => {
    const path = resolve(x.c.stateDir, `worker-${n}.json`);
    writeFileSync(path, JSON.stringify({ time: TIME, bodies: Array.from({ length: 30 }, () => x.fixture()) }), { mode: 0o600 }); return path;
  });
  function run(file) {
    return new Promise((res, rej) => {
      const p = spawn(process.execPath, ['test/identity-worker.mjs', x.path, file], { cwd: root, stdio: ['ignore', 'pipe', 'ignore'] });
      let out = ''; p.stdout.on('data', c => { out += c; }); p.on('error', rej);
      p.on('exit', code => code === 0 ? res(JSON.parse(out)) : rej(new Error('worker failed')));
    });
  }
  const result = (await Promise.all(files.map(run))).flat(); assert.equal(result.length, 120); assert.ok(result.every(s => s === 'success'));
  assert.equal(x.db.prepare('SELECT count(*) n FROM accounts').get().n, 1);
  assert.equal(x.db.prepare('SELECT count(*) n FROM sessions WHERE revoked_at IS NULL').get().n, 5);
  assert.equal(x.db.prepare("SELECT count FROM rate_buckets WHERE kind='login'").get().count, 120);
  const again = resolve(x.c.stateDir, 'after-restart.json'); writeFileSync(again, JSON.stringify({ time: TIME, bodies: [x.fixture()] }));
  assert.deepEqual(await run(again), ['RATE_LIMITED']);
});

test('M04: concurrent management write waits for revoke commit; reverse order preserves committed write', { timeout: 15000 }, async t => {
  const { fork } = await import('node:child_process');
  const { once } = await import('node:events');
  const { roleWrite } = await import('./identity-support.mjs');
  const x = setupIdentity(t); const a = await x.service.login(x.fixture());
  x.service.changeRole(roleWrite(x.c, a.userId, Date.now()));
  async function worker(input) {
    const file = resolve(x.c.stateDir, `role-${input.action}.json`); writeFileSync(file, JSON.stringify(input), { mode: 0o600 });
    const p = fork('test/role-worker.mjs', [x.path, file], { cwd: root, stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
    const ended = once(p, 'exit'); const queue = [], waiting = [];
    p.on('message', message => waiting.length ? waiting.shift()(message) : queue.push(message));
    const next = () => queue.length ? Promise.resolve(queue.shift()) : new Promise(res => waiting.push(res));
    t.after(() => { if (p.exitCode === null && p.signalCode === null) p.kill(); });
    assert.equal(await next(), 'ready'); return { p, next, ended };
  }
  const w = await worker({ action: 'write', token: a.token });
  x.db.exec('BEGIN IMMEDIATE');
  try {
    w.p.send('run'); assert.equal(await w.next(), 'attempting');
    // Hold the same serialization point used by role.change; commit revoke before the blocked writer can authenticate.
    x.db.prepare('UPDATE operator_roles SET granted=0,revision=revision+1 WHERE user_id=?').run(a.userId);
    x.db.exec('COMMIT'); assert.equal(await w.next(), 'FORBIDDEN'); await w.ended;
  } catch (e) { if (x.db.isTransaction) x.db.exec('ROLLBACK'); w.p.kill(); throw e; }
  assert.equal(x.db.prepare("SELECT count(*) n FROM audit WHERE action='test.competing-write'").get().n, 0);
  x.service.changeRole(roleWrite(x.c, a.userId, Date.now(), 2));
  const second = await worker({ action: 'revoke', request: roleWrite(x.c, a.userId, Date.now(), 3, 'revoke') });
  x.db.exec('BEGIN IMMEDIATE');
  try {
    assert.equal(x.db.prepare('SELECT granted FROM operator_roles WHERE user_id=?').get(a.userId).granted, 1);
    x.db.prepare('INSERT INTO audit VALUES (?,?,?,?,?,?,?,?)').run('synthetic-before-revoke', a.userId, a.userId, 'test.before-revoke', 'committed', 1, Date.now(), 'test-only');
    second.p.send('run'); assert.equal(await second.next(), 'attempting');
    x.db.exec('COMMIT');
    assert.equal(await second.next(), 'committed'); await second.ended;
  } catch (e) { if (x.db.isTransaction) x.db.exec('ROLLBACK'); second.p.kill(); throw e; }
  assert.equal(x.db.prepare("SELECT count(*) n FROM audit WHERE action='test.before-revoke'").get().n, 1);
  assert.equal(x.service.role(a.token).isOperator, false);
});
