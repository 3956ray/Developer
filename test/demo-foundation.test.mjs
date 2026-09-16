import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, readdirSync, mkdirSync, copyFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { setupDemo } from './demo-support.mjs';
import { setupIdentity, intent, roleWrite } from './identity-support.mjs';
import { initializeDemo } from '../scripts/demo.mjs';
import { loadConfig, root } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService, DAY } from '../server/identity.mjs';
import { issueDemoTicket } from '../server/demo.mjs';
import { codeDigest } from '../server/wechat.mjs';
import { createMembershipService } from '../server/membership.mjs';
import { createMemberCleanup } from '../server/member-cleanup.mjs';
import { digest } from '../server/protocol.mjs';
const START = Date.UTC(2026, 8, 16);
const invalid = e => e.code === 'LOGIN_CODE_INVALID';

test('DM01/12 explicit new demo init, duplicate protection, configuration/store/namespace rejection and no automatic role', async t => {
  const x = setupDemo(t); const original = readFileSync(x.path), keys = readFileSync(x.c.stateDir + '/keys.json');
  const user = await x.service.login(x.ticket('operator-a'));
  assert.equal(x.service.role(user.token).isOperator, false);
  assert.throws(() => initializeDemo(x.c.gymId));
  assert.ok(readFileSync(x.path).equals(original)); assert.ok(readFileSync(x.c.stateDir + '/keys.json').equals(keys));
  assert.equal(x.db.prepare('SELECT count(*) n FROM accounts').get().n, 1);
  for (const patch of [{ environment: 'store' }, { simulation: false }, { identity: { mode: 'wechat', appId: 'wx0000000000000000' } }, { gymId: 'not-demo' }, { demo: { enabled: 'true' } }]) {
    writeFileSync(x.path, JSON.stringify({ ...JSON.parse(original), ...patch })); assert.throws(() => loadConfig(x.path));
  }
  writeFileSync(x.path, original);
  assert.throws(() => issueDemoTicket(x.db, { ...x.c, environment: 'store' }, 'member-a'));
  assert.throws(() => issueDemoTicket(x.db, x.c, 'administrator'));
  x.db.prepare("UPDATE identity_namespace SET mode='wechat',app_id='wx0000000000000000'").run();
  assert.throws(() => issueDemoTicket(x.db, x.c, 'member-a'), e => e.code === 'IDENTITY_NAMESPACE_MISMATCH');
  assert.throws(() => createIdentityService(x.db, x.c), e => e.code === 'IDENTITY_NAMESPACE_MISMATCH');
});

test('DM02 consent/notice/fields/format and prelimit do not consume; stored credentials are HMAC only', async t => {
  let time = START; const x = setupDemo(t, { now: () => time }), body = x.ticket();
  const row = x.db.prepare('SELECT * FROM demo_login_tickets').get();
  assert.equal(body.code.length, 43); assert.equal(row.expires_at - row.issued_at, 300000);
  assert.equal(JSON.stringify(row).includes(body.code), false);
  for (const patch of [{ consent: false }, { privacyNoticeVersion: 'wrong' }, { actorAlias: 'operator-a' }, { role: 'operator' }]) await assert.rejects(() => x.service.login({ ...body, ...patch }));
  await assert.rejects(() => x.service.login({ ...body, code: '*' }));
  assert.equal(x.db.prepare('SELECT count(*) n FROM demo_login_tickets').get().n, 1);
  assert.equal(x.db.prepare('SELECT count(*) n FROM login_codes').get().n, 0);
  x.db.prepare("INSERT INTO rate_buckets VALUES ('login',?,?,120)").run(time, time + 60000);
  await assert.rejects(() => x.service.login(body), e => e.code === 'RATE_LIMITED');
  assert.equal(x.db.prepare('SELECT count(*) n FROM demo_login_tickets').get().n, 1);
  assert.equal(x.db.prepare('SELECT count(*) n FROM login_codes').get().n, 0);
  time += 60000; await x.service.login(body);
  assert.equal(x.db.prepare('SELECT count(*) n FROM demo_login_tickets').get().n, 0);
});

test('DM02 299999/300000 boundary, replay after login ledger removal, unknown code and fixture isolation', async t => {
  let time = START; const x = setupDemo(t, { now: () => time });
  const good = x.ticket(), expired = x.ticket('member-b'); time += 299999;
  await x.service.login(good); time++;
  await assert.rejects(() => x.service.login(expired), invalid);
  x.db.prepare('DELETE FROM login_codes').run();
  await assert.rejects(() => x.service.login(expired), invalid);
  await assert.rejects(() => x.service.login(good), invalid);
  await assert.rejects(() => x.service.login({ ...good, code: randomBytes(32).toString('base64url') }), invalid);
  const fixture = randomBytes(32).toString('base64url');
  x.db.prepare('INSERT INTO test_login_fixtures VALUES (?,?,?)').run(codeDigest(x.c, fixture), 'not-demo', 'success');
  await assert.rejects(() => x.service.login({ ...good, code: fixture }), invalid);
  const other = setupIdentity(t); await assert.rejects(() => other.service.login(x.ticket()), invalid);
});

test('DM02 concurrent same ticket once, distinct tickets one identity, response loss/new ticket and five session limit', async t => {
  const x = setupDemo(t, { now: () => START }), body = x.ticket();
  const results = await Promise.allSettled([x.service.login(body), x.service.login(body)]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  const first = results.find(r => r.status === 'fulfilled').value;
  const sessions = await Promise.all(Array.from({ length: 6 }, () => x.service.login(x.ticket())));
  assert.ok(sessions.every(s => s.userId === first.userId));
  assert.equal(x.db.prepare('SELECT count(*) n FROM accounts').get().n, 1);
  assert.equal(x.db.prepare('SELECT count(*) n FROM sessions WHERE revoked_at IS NULL').get().n, 5);
  assert.equal(x.db.prepare("SELECT count FROM rate_buckets WHERE kind='login'").get().count, 8);
});

test('DM02 committed attempt consumes ticket even when session creation fails; failed ledger commit preserves ticket', async t => {
  const x = setupDemo(t); const body = x.ticket();
  x.db.exec("CREATE TRIGGER fail_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT,'injected'); END;");
  await assert.rejects(() => x.service.login(body));
  assert.equal(x.db.prepare('SELECT count(*) n FROM demo_login_tickets').get().n, 0);
  assert.equal(x.db.prepare('SELECT count(*) n FROM login_codes').get().n, 1);
  assert.equal(x.db.prepare('SELECT count(*) n FROM accounts').get().n, 0);
  x.db.exec('DROP TRIGGER fail_session'); await assert.rejects(() => x.service.login(body), invalid);
  const next = x.ticket(); x.db.exec("CREATE TRIGGER fail_attempt BEFORE INSERT ON login_codes BEGIN SELECT RAISE(ABORT,'injected'); END;");
  await assert.rejects(() => x.service.login(next));
  assert.equal(x.db.prepare('SELECT count(*) n FROM demo_login_tickets').get().n, 1);
  x.db.exec('DROP TRIGGER fail_attempt'); await x.service.login(next);
});

test('DM02 hourly cleanup removes expired tickets without resurrection and retries failed retention', async t => {
  let time = START; const x = setupDemo(t, { now: () => time }); const old = x.ticket();
  const cleanup = createMemberCleanup(x.db, x.c, { now: () => time }); await cleanup.run();
  time += 300000; await assert.rejects(() => x.service.login(old), invalid);
  const second = x.ticket(); time = START + 3600000;
  x.db.exec("CREATE TRIGGER fail_ticket_cleanup BEFORE DELETE ON demo_login_tickets BEGIN SELECT RAISE(ABORT,'injected'); END;");
  assert.equal((await cleanup.run()).failed, true); assert.equal(cleanup.status().error_count, 1);
  x.db.exec('DROP TRIGGER fail_ticket_cleanup'); time += 3600000; await cleanup.run();
  assert.equal(x.db.prepare('SELECT count(*) n FROM demo_login_tickets').get().n, 0);
  time += DAY; await cleanup.run(); await assert.rejects(() => x.service.login(second), invalid);
});

test('DM02/03 new ticket provides fresh auth; deleting blocks reconstruction; completed deletion restores neither role nor membership', async t => {
  let time = START; const x = setupDemo(t, { now: () => time });
  const first = await x.service.login(x.ticket('operator-a')); x.service.changeRole(roleWrite(x.c, first.userId, time));
  const m = createMembershipService(x.db, x.c, x.service); time += 300001;
  const receipt = randomBytes(32).toString('hex');
  const request = () => ({ ...intent(time, 1), confirmed: true, receiptDigest: digest(receipt) });
  assert.throws(() => m.deleteAccount(first.token, request()), e => e.code === 'FRESH_AUTH_REQUIRED');
  const fresh = await x.service.login(x.ticket('operator-a')); assert.equal(fresh.userId, first.userId);
  m.deleteAccount(fresh.token, request());
  assert.throws(() => x.service.session(first.token, 'poll'), e => e.code === 'SESSION_INVALID');
  await assert.rejects(() => x.service.login(x.ticket('operator-a')), e => e.code === 'ACCOUNT_DELETING');
  await createMemberCleanup(x.db, x.c, { now: () => time }).run(true);
  const reborn = await x.service.login(x.ticket('operator-a'));
  assert.notEqual(reborn.userId, first.userId); assert.equal(x.service.role(reborn.token).isOperator, false);
  assert.equal(x.db.prepare('SELECT count(*) n FROM bindings').get().n, 0);
  assert.equal(m.deletionStatus(receipt).state, 'completed');
});

test('DM12 migration of existing five-migration database preserves data and checksums', t => {
  const x = setupDemo(t); x.db.close();
  const dir = x.c.stateDir + '/old-migrations'; mkdirSync(dir);
  for (const file of readdirSync(root + '/server/migrations').filter(f => !f.startsWith('006-'))) copyFileSync(root + '/server/migrations/' + file, dir + '/' + file);
  const c = { ...x.c, databasePath: x.c.stateDir + '/legacy.sqlite' };
  let db = openDatabase(c, pathToFileURL(dir + '/')); const before = db.prepare('SELECT * FROM schema_migrations').all();
  db.prepare("INSERT INTO foundation_probe (probe_id,value,created_at) VALUES ('migration-check','synthetic-non-member',1)").run(); db.close();
  db = openDatabase(c); try {
    assert.equal(db.prepare('SELECT count(*) n FROM schema_migrations').get().n, 6);
    assert.deepEqual(db.prepare("SELECT * FROM schema_migrations WHERE name NOT LIKE '006-%'").all(), before);
    assert.equal(db.prepare("SELECT value FROM foundation_probe WHERE probe_id='migration-check'").get().value, 'synthetic-non-member');
  } finally { db.close(); }
});
