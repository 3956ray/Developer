import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { setupIdentity, intent, roleWrite } from './identity-support.mjs';
import { DAY, createIdentityService } from '../server/identity.mjs';
import { loadConfig, root } from '../server/config.mjs';
import { createWechatAdapter } from '../server/wechat.mjs';
import { parseStrict, canonical, digest } from '../server/protocol.mjs';
import { openDatabase } from '../server/database.mjs';
const START = Date.parse('2026-09-16T00:00:00.000Z');
const rejectsCode = (fn, code) => assert.rejects(fn, e => e.code === code);
const throwsCode = (fn, code) => assert.throws(fn, e => e.code === code);

test('F01: exact idle/absolute boundaries, interactive extension and poll non-extension', async t => {
  let time = START; const x = setupIdentity(t, { now: () => time }); const login = await x.service.login(x.fixture());
  assert.equal(login.accountRevision, 1); assert.equal(login.sessionRevision, 1);
  time = START + DAY - 1; assert.equal(x.service.session(login.token, 'poll').userId, login.userId);
  time++; throwsCode(() => x.service.session(login.token, 'poll'), 'SESSION_INVALID');
  time = START; const live = await x.service.login(x.fixture());
  for (let day = 1; day <= 7; day++) { time = START + day * DAY - day; x.service.session(live.token, 'interactive'); }
  time = START + 7 * DAY - 1; x.service.session(live.token, 'poll');
  time++; throwsCode(() => x.service.session(live.token, 'interactive'), 'SESSION_INVALID');
  assert.equal(x.db.prepare('SELECT expires_at FROM sessions WHERE session_id=?').get(live.sessionId).expires_at, START + 7 * DAY);
});

test('F01/L01: concurrent first exchange has one identity, five active sessions, oldest eviction', async t => {
  let time = START; const x = setupIdentity(t, { now: () => time });
  const first = await x.service.login(x.fixture()); time++;
  const rest = await Promise.all(Array.from({ length: 5 }, () => x.service.login(x.fixture())));
  assert.equal(new Set([first, ...rest].map(s => s.userId)).size, 1);
  assert.equal(x.db.prepare('SELECT count(*) n FROM accounts').get().n, 1);
  assert.equal(x.db.prepare('SELECT count(*) n FROM sessions WHERE revoked_at IS NULL').get().n, 5);
  throwsCode(() => x.service.session(first.token, 'poll'), 'SESSION_INVALID');
  rest.forEach(s => x.service.session(s.token, 'poll'));
  const row = x.db.prepare('SELECT token_digest FROM sessions WHERE session_id=?').get(rest[0].sessionId);
  assert.equal(row.token_digest, digest(rest[0].token)); assert.notEqual(row.token_digest, rest[0].token);
});

test('L01/F01: invalid/replayed/platform code never creates identity; logout current only; deleting lock reserved', async t => {
  const x = setupIdentity(t, { now: () => START });
  await rejectsCode(() => x.service.login(x.fixture('bad', 'invalid')), 'LOGIN_CODE_INVALID');
  await rejectsCode(() => x.service.login(x.fixture('bad', 'platform')), 'PLATFORM_UNAVAILABLE');
  const body = x.fixture(); const a = await x.service.login(body);
  await rejectsCode(() => x.service.login(body), 'LOGIN_CODE_INVALID');
  const b = await x.service.login(x.fixture());
  const request = intent(START); x.service.logout(a.token, request);
  throwsCode(() => x.service.logout(a.token, request), 'SESSION_INVALID');
  x.service.session(b.token, 'poll');
  assert.equal(x.service.operationResult(b.token, request.operationId, 'session.logout').state, 'committed');
  const stranger = await x.service.login(x.fixture('synthetic-other'));
  assert.equal(x.service.operationResult(stranger.token, request.operationId, 'session.logout').state, 'unknown');
  x.db.prepare("UPDATE accounts SET state='deleting' WHERE user_id=?").run(a.userId);
  throwsCode(() => x.service.session(b.token, 'poll'), 'SESSION_INVALID');
  await rejectsCode(() => x.service.login(x.fixture()), 'ACCOUNT_DELETING');
  assert.equal(x.db.prepare('SELECT count(*) n FROM accounts').get().n, 2);
});

test('R01: 120 including platform failures; fixed minute recovery and restart', async t => {
  let time = START + 59001; const x = setupIdentity(t, { now: () => time });
  for (let i = 0; i < 120; i++) await rejectsCode(() => x.service.login(x.fixture('failure', 'platform')), 'PLATFORM_UNAVAILABLE');
  await assert.rejects(() => x.service.login(x.fixture()), e => e.code === 'RATE_LIMITED' && e.retryAfterSeconds === 1);
  const secondDb = openDatabase(x.c); const second = createIdentityService(secondDb, x.c, { now: () => time });
  try { await rejectsCode(() => second.login(x.fixture()), 'RATE_LIMITED'); } finally { secondDb.close(); }
  time = START + 60000; await x.service.login(x.fixture());
  assert.equal(x.db.prepare("SELECT count FROM rate_buckets WHERE kind='login' AND window_start=?").get(time).count, 1);
});

test('R01: time sampled after BEGIN IMMEDIATE, crossing old full window uses new window', async t => {
  let database; let observedOutside = false;
  const x = setupIdentity(t, { now: () => { if (!database.isTransaction) observedOutside = true; return database.isTransaction ? START + 60000 : START + 59999; } });
  database = x.db;
  x.db.prepare("INSERT INTO rate_buckets VALUES ('login',?,?,120)").run(START, START + 60000);
  await x.service.login(x.fixture());
  assert.equal(observedOutside, false);
  assert.equal(x.db.prepare("SELECT count FROM rate_buckets WHERE window_start=?").get(START + 60000).count, 1);
});

test('M04/I01: role inspect, grant/revoke, cached historical result, CAS and audit atomicity', async t => {
  let time = START; const x = setupIdentity(t, { now: () => time }); const a = await x.service.login(x.fixture());
  assert.equal(x.service.inspectRole(a.userId, x.c.gymId).roleRevision, 'absent');
  throwsCode(() => x.service.audits(a.token), 'FORBIDDEN');
  const grant = roleWrite(x.c, a.userId, time); x.service.changeRole(grant); assert.equal(x.service.role(a.token).isOperator, true);
  x.service.withOperator(a.token, ({ userId, time, audit }) => audit(userId, userId, 'test.authorized-write', 1, time, 'test-only'));
  x.service.changeRole(roleWrite(x.c, a.userId, time, 1, 'revoke'));
  throwsCode(() => x.service.audits(a.token), 'FORBIDDEN'); x.service.session(a.token, 'poll');
  assert.equal(x.service.changeRole(grant).operation.replayed, true); assert.equal(x.service.role(a.token).isOperator, false);
  throwsCode(() => x.service.changeRole({ ...grant, reasonCategory: 'correction' }), 'IDEMPOTENCY_CONFLICT');
  throwsCode(() => x.service.changeRole(roleWrite(x.c, a.userId, time, 1)), 'REVISION_CONFLICT');
  assert.equal(x.service.inspectRole(a.userId, x.c.gymId).roleRevision, 2);
  x.db.exec("CREATE TRIGGER fail_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test rollback'); END");
  assert.throws(() => x.service.changeRole(roleWrite(x.c, a.userId, time, 2)));
  assert.equal(x.service.inspectRole(a.userId, x.c.gymId).roleRevision, 2);
  x.db.exec('DROP TRIGGER fail_audit');
  time += DAY; throwsCode(() => x.service.changeRole(grant), 'INTENT_EXPIRED');
  throwsCode(() => x.service.changeRole({ ...roleWrite(x.c, a.userId, time, 2), gymId: 'other' }), 'SCOPE_FORBIDDEN');
});

test('L01/R01: DB failure does not authenticate or bypass count; account creation rolls back with audit', async t => {
  const x = setupIdentity(t, { now: () => START }); const a = await x.service.login(x.fixture());
  x.db.exec("CREATE TRIGGER fail_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test'); END");
  await assert.rejects(() => x.service.login(x.fixture('synthetic-rolled-back')));
  assert.equal(x.db.prepare('SELECT count(*) n FROM accounts').get().n, 1);
  x.db.exec('DROP TRIGGER fail_audit; DROP TABLE rate_buckets');
  await assert.rejects(() => x.service.login(x.fixture()));
  x.db.close(); assert.throws(() => x.service.session(a.token, 'poll'));
});

test('adapter isolation: store refuses fixture mode, namespace cannot silently switch', async t => {
  const x = setupIdentity(t);
  const original = JSON.parse(readFileSync(x.path));
  writeFileSync(x.path, JSON.stringify({ ...original, environment: 'store' })); assert.throws(() => loadConfig(x.path));
  writeFileSync(x.path, JSON.stringify(original));
  assert.throws(() => createIdentityService(x.db, { ...x.c, identity: { mode: 'wechat', appId: 'wx0123456789abcdef' }, simulation: false }), /IDENTITY_NAMESPACE_MISMATCH/);
  const disabled = createIdentityService(x.db, { ...x.c, identity: { mode: 'disabled' } });
  await rejectsCode(() => disabled.login(x.fixture()), 'IDENTITY_NOT_CONFIGURED');
});

test('official adapter transport contract: strict platform errors, missing fields, redirect and secret suppression', async t => {
  const x = setupIdentity(t); const c = { ...x.c, identity: { mode: 'wechat', appId: 'wx0123456789abcdef' }, appSecret: 's'.repeat(32) };
  const good = { openid: 'synthetic-platform-id', session_key: Buffer.alloc(16, 1).toString('base64'), errcode: 0 };
  let captured;
  const adapter = createWechatAdapter(c, async (url, options) => { captured = { url, options }; return new Response(JSON.stringify(good)); });
  assert.deepEqual(await adapter('synthetic-code'), { openId: good.openid });
  assert.equal(captured.url.origin, 'https://api.weixin.qq.com'); assert.equal(captured.url.pathname, '/sns/jscode2session');
  assert.equal(captured.url.searchParams.get('grant_type'), 'authorization_code'); assert.equal(captured.options.redirect, 'error');
  for (const response of [ { errcode: 40029 }, { errcode: 40163 }, { errcode: 40226 } ]) {
    await rejectsCode(() => createWechatAdapter(c, async () => new Response(JSON.stringify(response)))('x'), 'LOGIN_CODE_INVALID');
  }
  for (const response of [{}, { ...good, errcode: '0' }, { ...good, session_key: '' }, { ...good, errcode: -1 }]) {
    await rejectsCode(() => createWechatAdapter(c, async () => new Response(JSON.stringify(response)))('x'), 'PLATFORM_UNAVAILABLE');
  }
  await rejectsCode(() => createWechatAdapter(c, async () => { throw new Error('secret URL and code must be suppressed'); })('x'), 'PLATFORM_UNAVAILABLE');
  await rejectsCode(() => createWechatAdapter(c, async () => new Response('invalid JSON'))('x'), 'PLATFORM_UNAVAILABLE');
  await rejectsCode(() => createWechatAdapter(c, async () => new Response('failure', { status: 500 }))('x'), 'PLATFORM_UNAVAILABLE');
});

test('strict input: escaped/nested duplicate keys, prototype keys, malformed numbers and extra fields', async t => {
  for (const text of ['{"a":1,"\\u0061":2}', '{"a":{"x":1,"x":2}}', '{"a":1,}', '[1,]', 'NaN', '1e999', '{"a":01}', 'true false']) assert.throws(() => parseStrict(text));
  assert.equal(Object.getPrototypeOf(parseStrict('{"__proto__":{}}')), null);
  assert.equal(canonical(parseStrict('{"b":2,"a":1}')), '{"a":1,"b":2}');
  const x = setupIdentity(t); const body = x.fixture();
  await rejectsCode(() => x.service.login({ ...body, role: 'operator' }), 'INVALID_FIELDS');
  await rejectsCode(() => x.service.login({ ...body, openid: 'injected' }), 'INVALID_FIELDS');
  await rejectsCode(() => x.service.login({ ...body, consent: false }), 'CONSENT_REQUIRED');
  assert.equal(x.db.prepare('SELECT count(*) n FROM rate_buckets').get().n, 0);
});

test('real role CLI: inspect/apply/result is local, versioned and audited', async t => {
  const x = setupIdentity(t); const a = await x.service.login(x.fixture());
  const request = roleWrite(x.c, a.userId, Date.now()); const requestPath = resolve(x.c.stateDir, 'role-request.json');
  writeFileSync(requestPath, JSON.stringify(request));
  const cli = (...args) => spawnSync(process.execPath, ['scripts/role.mjs', ...args], { cwd: root, encoding: 'utf8' });
  assert.equal(cli('inspect', x.path, a.userId, x.c.gymId).status, 0);
  assert.equal(cli('apply', x.path, requestPath).status, 0);
  assert.equal(cli('result', x.path, request.maintainerId, request.operationId, 'role.grant').status, 0);
  assert.equal(x.service.role(a.token).isOperator, true);
});

test('M04/I01: foreign-store token denied, logout audit failure rolls back, intent window boundaries enforced', async t => {
  const x = setupIdentity(t, { now: () => START }); const y = setupIdentity(t, { now: () => START });
  const a = await x.service.login(x.fixture());
  throwsCode(() => y.service.session(a.token, 'poll'), 'SESSION_INVALID');
  x.db.exec("CREATE TRIGGER fail_logout BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test'); END");
  assert.throws(() => x.service.logout(a.token, intent(START)));
  x.service.session(a.token, 'poll'); assert.equal(x.db.prepare('SELECT count(*) n FROM operations').get().n, 0);
  x.db.exec('DROP TRIGGER fail_logout');
  throwsCode(() => x.service.changeRole(roleWrite(x.c, a.userId, START - 300001)), 'INTENT_EXPIRED');
  throwsCode(() => x.service.changeRole(roleWrite(x.c, a.userId, START + 30001)), 'INTENT_FUTURE');
  x.service.changeRole(roleWrite(x.c, a.userId, START - 300000));
  x.service.changeRole(roleWrite(x.c, a.userId, START + 30000, 1, 'revoke'));
  assert.equal(x.service.role(a.token).isOperator, false);
});
