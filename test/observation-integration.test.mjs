import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, fork } from 'node:child_process';
import { once } from 'node:events';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { setupIdentity, intent, roleWrite } from './identity-support.mjs';
import { createObservationService } from '../server/observations.mjs';
import { root } from '../server/config.mjs';
const body = (time, revision = 'absent') => ({ ...intent(time, revision), level: 'moderate', observedJustNow: true });
async function server(path) {
  const p = spawn(process.execPath, ['server/main.mjs', path], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
  const started = new Promise((res, rej) => {
    const timeout = setTimeout(() => { p.kill(); rej(new Error('timeout')); }, 5000); let output = '';
    p.once('exit', () => { clearTimeout(timeout); rej(new Error('start failed')); });
    p.stdout.on('data', chunk => { output += chunk; if (output.includes('\n')) { clearTimeout(timeout); res(JSON.parse(output.split('\n')[0]).port); } });
  });
  return { p, port: await started };
}
async function stop(p) { if (p.exitCode !== null) return; const end = once(p, 'exit'); p.kill(); await end; }

test('O01/D01/C01 HTTP: two independent unsigned clients see same published snapshot through process restart and startup cleanup', async t => {
  const x = setupIdentity(t); const a = await x.service.login(x.fixture()); x.service.changeRole(roleWrite(x.c, a.userId, Date.now()));
  let s = await server(x.path); t.after(() => stop(s.p));
  const client = () => async (path, payload, token) => {
    const response = await fetch(`http://127.0.0.1:${s.port}/v1${path}`, { method: payload ? 'POST' : 'GET', headers: { ...(token ? { authorization: 'Bearer ' + token } : {}), 'content-type': 'application/json' }, body: payload ? JSON.stringify(payload) : undefined });
    return { status: response.status, body: await response.json() };
  };
  const one = client(), two = client(); assert.equal((await one('/venue')).body.data.observation.state, 'never');
  const time = Date.parse((await one('/venue')).body.serverNow), request = body(time);
  assert.equal((await one('/operator/observations', request, a.token)).status, 200);
  const [first, second] = await Promise.all([one('/observations/current'), two('/observations/current')]);
  assert.deepEqual(first.body.data, second.body.data); assert.equal(first.body.data.source, 'manual');
  t.diagnostic('two unsigned HTTP clients: same revision/source/observedAt/validUntil, no actor or counts');
  assert.equal(Object.hasOwn(first.body.data, 'actor_id'), false); assert.equal(Object.hasOwn(first.body.data, 'count'), false);
  assert.equal((await one('/operations/' + request.operationId + '?type=observation.publish', undefined, a.token)).body.data.state, 'committed');
  assert.equal((await one('/operator/observations', request, a.token)).body.operation.replayed, true);
  assert.equal(x.db.prepare('SELECT count(*) n FROM observation_events').get().n, 1);
  await stop(s.p);
  const old = Date.now() - 31 * 86400000;
  x.db.prepare("INSERT INTO observation_events VALUES (999,'quiet',?,?,?,'synthetic-history')").run(old, old, old + 900000);
  s = await server(x.path);
  assert.deepEqual((await two('/observations/current')).body.data, first.body.data);
  assert.equal(x.db.prepare('SELECT 1 FROM observation_events WHERE revision=999').get(), undefined);
  t.diagnostic('full process restart retained current head; startup cleanup removed 31-day history only');
  const withdraw = { ...intent(Date.parse((await one('/venue')).body.serverNow), 1), state: 'withdrawn', reasonCategory: 'correction' };
  assert.equal((await one('/operator/observations/control', withdraw, a.token)).status, 200);
  assert.equal((await two('/venue')).body.data.observation.state, 'withdrawn');
});

test('O04/M04: real competing processes commit only one CAS writer and one idempotent event; revoke barrier rejects pending write', async t => {
  const time = Date.now(); const x = setupIdentity(t, { now: () => time }); const a = await x.service.login(x.fixture());
  x.service.changeRole(roleWrite(x.c, a.userId, time));
  const b = await x.service.login(x.fixture('synthetic-second-operator')); x.service.changeRole(roleWrite(x.c, b.userId, time));
  let sequence = 0;
  function file(request, token = a.token) { const path = resolve(x.c.stateDir, `observation-${++sequence}.json`); writeFileSync(path, JSON.stringify({ time, token, body: request }), { mode: 0o600 }); return path; }
  function run(path) {
    return new Promise((res, rej) => {
      const p = spawn(process.execPath, ['test/observation-worker.mjs', x.path, path], { cwd: root, stdio: ['ignore', 'pipe', 'ignore'] });
      let output = ''; p.stdout.on('data', c => { output += c; }); p.on('error', rej); p.on('exit', code => code === 0 ? res(JSON.parse(output)) : rej(new Error('worker failed')));
    });
  }
  const results = await Promise.all([run(file(body(time))), run(file(body(time), b.token))]);
  assert.deepEqual(results.map(r => r.status).sort(), ['REVISION_CONFLICT', 'committed'].sort());
  const duplicate = file(body(time, 1)); const replayed = await Promise.all([run(duplicate), run(duplicate)]);
  assert.ok(replayed.every(r => r.status === 'committed' && r.revision === 2));
  assert.equal(x.db.prepare('SELECT count(*) n FROM observation_events').get().n, 2);
  const p = fork('test/observation-worker.mjs', [x.path, file(body(time, 2))], { cwd: root, stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
  t.after(() => { if (p.exitCode === null) p.kill(); });
  const end = once(p, 'exit'), queue = [], waiting = [];
  p.on('message', m => waiting.length ? waiting.shift()(m) : queue.push(m));
  const next = () => queue.length ? Promise.resolve(queue.shift()) : new Promise(res => waiting.push(res));
  assert.equal(await next(), 'ready'); x.db.exec('BEGIN IMMEDIATE');
  try {
    p.send('write'); assert.equal(await next(), 'attempting');
    x.db.prepare('UPDATE operator_roles SET granted=0,revision=revision+1 WHERE user_id=?').run(a.userId); x.db.exec('COMMIT');
    assert.equal((await next()).status, 'FORBIDDEN'); await end;
  } finally { if (x.db.isTransaction) x.db.exec('ROLLBACK'); }
  assert.equal(x.db.prepare('SELECT revision FROM observation_head').get().revision, 2);
});
