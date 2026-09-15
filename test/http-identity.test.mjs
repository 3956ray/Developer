import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { setupIdentity, intent, roleWrite } from './identity-support.mjs';
import { root } from '../server/config.mjs';
async function start(path) {
  const p = spawn(process.execPath, ['server/main.mjs', path], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
  let logs = '';
  p.stderr.on('data', c => { logs += c; });
  const port = await new Promise((res, rej) => {
    const timer = setTimeout(() => { p.kill(); rej(new Error('start timeout')); }, 5000);
    p.once('exit', () => { clearTimeout(timer); rej(new Error('start failed')); });
    p.stdout.on('data', c => { logs += c; if (logs.includes('\n')) { clearTimeout(timer); res(JSON.parse(logs.split('\n')[0]).port); } });
  });
  return { p, port, logs: () => logs };
}
async function stop(p) { if (p.exitCode !== null) return; const end = once(p, 'exit'); p.kill(); await end; }
test('HTTP: strict body, status/Retry-After, authorization, response loss/restart, no secret diagnostics', async t => {
  const x = setupIdentity(t); let server = await start(x.path); t.after(() => stop(server.p));
  const request = async (path, { token, body, raw, method = body || raw ? 'POST' : 'GET' } = {}) => {
    const response = await fetch(`http://127.0.0.1:${server.port}${path}`, { method, headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) }, body: raw ?? (body ? JSON.stringify(body) : undefined) });
    return { status: response.status, headers: response.headers, body: await response.json() };
  };
  assert.equal((await request('/v1/privacy')).status, 200);
  assert.equal((await request('/v1/session')).status, 401);
  assert.equal((await request('/v1/sessions/exchange', { raw: '{"code":"x","code":"y"}' })).status, 400);
  assert.equal((await request('/v1/sessions/exchange', { raw: '{"x":1,}' })).status, 400);
  assert.equal((await request('/v1/sessions/exchange', { body: { ...x.fixture(), gymId: 'forged' } })).status, 422);
  const loginBody = x.fixture(); const login = await request('/v1/sessions/exchange', { body: loginBody });
  assert.equal(login.status, 201); const s = login.body.data; assert.equal(s.simulation, true);
  assert.equal((await request('/v1/operator/audit', { token: s.token })).status, 403);
  assert.equal((await request('/v1/operator/grant', { token: s.token, body: { role: 'operator' } })).status, 404);
  x.service.changeRole(roleWrite(x.c, s.userId, Date.now()));
  assert.equal((await request('/v1/operator/audit', { token: s.token })).status, 200);
  x.service.changeRole(roleWrite(x.c, s.userId, Date.now(), 1, 'revoke'));
  assert.equal((await request('/v1/operator/audit', { token: s.token })).status, 403);
  assert.equal((await request('/v1/session?gymId=forged', { token: s.token })).status, 422);
  await stop(server.p); server = await start(x.path);
  assert.equal((await request('/v1/session', { token: s.token })).status, 200);
  assert.equal((await request('/v1/sessions/exchange', { body: loginBody })).status, 400);
  const w = intent(Date.parse((await request('/v1/session', { token: s.token })).body.serverNow));
  await request('/v1/session/logout', { token: s.token, body: w }); // discard successful response, as if lost
  assert.equal((await request('/v1/operations/' + w.operationId + '?type=session.logout', { token: s.token })).status, 401);
  const minute = Math.floor(Date.now() / 60000) * 60000;
  x.db.prepare("INSERT INTO rate_buckets VALUES ('login',?,?,120) ON CONFLICT(kind,window_start) DO UPDATE SET count=120").run(minute, minute + 60000);
  const limited = await request('/v1/sessions/exchange', { body: x.fixture() });
  assert.equal(limited.status, 429); assert.ok(Number(limited.headers.get('retry-after')) >= 1);
  assert.equal(limited.body.error.retryAfterSeconds, Number(limited.headers.get('retry-after')));
  assert.ok(!server.logs().includes(s.token)); assert.ok(!server.logs().includes(loginBody.code)); assert.ok(!JSON.stringify(s).includes('session_key'));
  x.db.exec('DROP TABLE rate_buckets');
  assert.equal((await request('/v1/sessions/exchange', { body: x.fixture() })).status, 503);
});
