import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { setupDemo } from './demo-support.mjs';
import { intent, roleWrite } from './identity-support.mjs';
import { startService, stop } from './process-support.mjs';
import { root } from '../server/config.mjs';
import { NOTICE_VERSION } from '../server/identity.mjs';

function cli(args) {
  const result = spawnSync(process.execPath, args, { cwd: root, encoding: 'utf8', timeout: 5000 });
  assert.equal(result.status, 0, 'CLI must succeed (credential output deliberately omitted)');
  return result.stdout.trim();
}
function client(server) {
  return async (path, body, token) => {
    const r = await fetch(`http://127.0.0.1:${server().port}${path}`, { method: body ? 'POST' : 'GET',
      headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(3000) });
    return { status: r.status, ...(await r.json()) };
  };
}
function ticket(x, actor) { return { code: cli(['scripts/demo.mjs', 'ticket', x.path, actor]), privacyNoticeVersion: NOTICE_VERSION, consent: true }; }
function role(x, userId, revision, action) {
  const path = x.c.stateDir + '/role-request.json';
  writeFileSync(path, JSON.stringify(roleWrite(x.c, userId, Date.now(), revision, action)), { mode: 0o600 });
  return JSON.parse(cli(['scripts/role.mjs', 'apply', x.path, path]));
}

test('DM01/03/04 actual CLI tickets and role CLI; two HTTP clients retain one observation and revoked role across real process restart', async t => {
  const x = setupDemo(t); let server = await startService(x.path); const one = client(() => server), two = client(() => server);
  t.diagnostic(JSON.stringify({ configSha256: createHash('sha256').update(readFileSync(x.path)).digest('hex'), configuration: { environment: x.c.environment, gymId: x.c.gymId, simulation: x.c.simulation, identity: x.c.identity, demo: x.c.demo, host: x.c.host, configuredPort: x.c.port } }));
  const health = await one('/health');
  assert.equal(health.data.demoEnabled, true); assert.equal(health.data.identityMode, 'test'); assert.equal(health.data.simulation, true);
  const memberBody = ticket(x, 'member-a');
  assert.equal((await one('/v1/sessions/exchange', { ...memberBody, actorAlias: 'operator-a', role: 'operator' })).status, 422);
  const member = await one('/v1/sessions/exchange', memberBody), operator = await two('/v1/sessions/exchange', ticket(x, 'operator-a'));
  assert.equal(member.status, 201); assert.equal(operator.status, 201);
  assert.equal(operator.data.demoEnabled, true); assert.equal(operator.data.gymId, x.c.gymId);
  assert.equal((await two('/v1/operator/role', undefined, operator.data.token)).data.isOperator, false);
  const publicBefore = await one('/v1/observations/current'); assert.equal(publicBefore.data.state, 'never');
  const body = { ...intent(Date.parse(publicBefore.serverNow), 'absent'), level: 'quiet', observedJustNow: true };
  assert.equal((await two('/v1/operator/observations', body, operator.data.token)).status, 403);
  assert.equal(role(x, operator.data.userId, 'absent', 'grant').ok, true);
  assert.equal((await one('/v1/operator/observations', body, member.data.token)).status, 403);
  const write = await two('/v1/operator/observations', body, operator.data.token); assert.equal(write.status, 200);
  const [first, second] = await Promise.all([one('/v1/observations/current'), two('/v1/observations/current')]);
  assert.deepEqual(first.data, second.data);
  assert.equal((await two('/v1/operator/observations', body, operator.data.token)).operation.replayed, true);
  assert.equal((await two('/v1/operator/observations', { ...body, level: 'busy' }, operator.data.token)).status, 409);
  assert.equal(x.db.prepare('SELECT count(*) n FROM observation_events').get().n, 1);
  role(x, operator.data.userId, 1, 'revoke');
  assert.equal((await two('/v1/operator/observations', body, operator.data.token)).status, 403);
  const reissued = ticket(x, 'operator-a');
  await stop(server.p); await assert.rejects(() => one('/health'));
  server = await startService(x.path);
  const after = await one('/v1/observations/current'); assert.deepEqual(after.data, first.data);
  const newSession = await two('/v1/sessions/exchange', reissued); assert.equal(newSession.status, 201);
  assert.equal(newSession.data.userId, operator.data.userId);
  assert.equal((await two('/v1/operator/role', undefined, newSession.data.token)).data.isOperator, false);
  assert.equal((await two('/v1/operator/observations', body, newSession.data.token)).status, 403);
  assert.equal((await one('/v1/session', undefined, member.data.token)).status, 200);
  assert.equal(x.db.prepare("SELECT count(*) n FROM audit WHERE action IN ('role.grant','role.revoke')").get().n, 2);
  assert.equal(x.db.prepare("SELECT count FROM rate_buckets WHERE kind='login' ORDER BY window_start DESC LIMIT 1").get().count >= 1, true);
  t.diagnostic(JSON.stringify({ twoIndependentClients: true, realRestart: true, observation: first.data, exactlyOneEvent: true, duplicateReplay: true, differentBody409: true, ordinary403: true, reissuedRoleStillRevoked: true }));
});

test('DM02 two actual server processes exchange same CLI credential at most once; restart retains global rate limit', async t => {
  const x = setupDemo(t); let a = await startService(x.path); const b = await startService(x.path);
  const one = client(() => a), two = client(() => b), body = ticket(x, 'member-b');
  const result = await Promise.all([one('/v1/sessions/exchange', body), two('/v1/sessions/exchange', body)]);
  assert.deepEqual(result.map(r => r.status).sort(), [201, 400]);
  assert.equal(x.db.prepare('SELECT count(*) n FROM sessions').get().n, 1);
  const next = ticket(x, 'member-b'); const start = Math.floor(Date.now() / 60000) * 60000;
  x.db.prepare("INSERT INTO rate_buckets VALUES ('login',?,?,120) ON CONFLICT(kind,window_start) DO UPDATE SET count=120").run(start, start + 60000);
  await stop(a.p); a = await startService(x.path);
  // Only assert fixed-window rate rejection if still in the same real minute.
  if (Math.floor(Date.now() / 60000) * 60000 === start) {
    assert.equal((await one('/v1/sessions/exchange', next)).status, 429);
    assert.equal(x.db.prepare('SELECT count(*) n FROM demo_login_tickets').get().n, 1);
  }
  assert.equal(x.db.prepare("SELECT count FROM rate_buckets WHERE kind='login' AND window_start=?").get(start).count, 120);
  t.diagnostic('two actual processes: one credential, one committed session; persisted login limit unchanged by restart/ticket issuance');
});

test('DM01 regression: native request layer reads real non-demo identity-disabled venue and schedule without locking public browsing', async t => {
  const { readFileSync } = await import('node:fs'); const vm = await import('node:vm');
  const x = setupDemo(t); const config = JSON.parse(readFileSync(x.path));
  config.demo = { enabled: false }; config.identity = { mode: 'disabled' }; config.simulation = false;
  writeFileSync(x.path, JSON.stringify(config)); const server = await startService(x.path);
  const nativeConfig = { environment: 'test', baseUrl: `http://127.0.0.1:${server.port}` }, stores = new Map();
  const context = { module: { exports: {} }, require: () => nativeConfig, wx: {
    getStorageSync: k => stores.get(k), setStorageSync: (k,v) => stores.set(k,v), removeStorageSync: k => stores.delete(k),
    request: options => fetch(options.url, { method: options.method, headers: options.header }).then(async response => options.success({ statusCode: response.status, data: await response.json() })).catch(() => options.fail())
  } };
  vm.runInNewContext(readFileSync('miniprogram/lib/session.js', 'utf8'), context); const api = context.module.exports;
  assert.equal((await api.request('/venue', 'GET')).namespace.identityMode, 'disabled');
  assert.equal((await api.request('/schedule?view=today', 'GET')).ok, true);
  assert.equal((await api.request('/observations/current', 'GET')).data.state, 'never');
  const token = 'a'.repeat(43); api.save(token);
  await assert.rejects(() => api.request('/session', 'GET', undefined, token), e => e.code === 'SCOPE_MISMATCH');
  assert.equal(api.load(), null);
  assert.equal((await api.request('/venue', 'GET')).ok, true);
  t.diagnostic('real disabled identity namespace permits unauthenticated public venue/schedule reads; authenticated mode mismatch still clears token');
});
