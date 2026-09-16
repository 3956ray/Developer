import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setupIdentity, intent, roleWrite } from './identity-support.mjs';
import { createObservationService, TTL } from '../server/observations.mjs';
import { createObservationCleanup, startObservationCleanup, HOUR, RETENTION } from '../server/observation-cleanup.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService } from '../server/identity.mjs';
const START = Date.parse('2026-09-16T00:00:00.000Z');
const write = (time, revision = 'absent', level = 'moderate') => ({ ...intent(time, revision), level, observedJustNow: true });
async function setup(t) {
  let time = START;
  const x = setupIdentity(t, { now: () => time }); const a = await x.service.login(x.fixture());
  x.service.changeRole(roleWrite(x.c, a.userId, time));
  return { ...x, a, observations: createObservationService(x.db, x.c, x.service), time: value => { time = value; } };
}
test('B01/O01/O02: public empty -> two consistent reads, 899/900 boundary, unrelated activity cannot renew TTL', async t => {
  const x = await setup(t), o = x.observations;
  assert.equal(o.current().data.state, 'never'); assert.equal(o.venue().data.gym.contact, null);
  const result = o.publish(x.a.token, write(START)); assert.equal(result.data.revision, 1);
  assert.deepEqual(o.current().data, o.venue().data.observation);
  assert.equal(o.current().data.source, 'manual'); assert.equal(o.current().data.simulation, true);
  const head = x.db.prepare('SELECT * FROM observation_head').get();
  x.time(START + 899000); assert.equal(o.current().data.state, 'moderate');
  x.service.session(x.a.token, 'interactive'); o.venue(); o.current();
  assert.deepEqual(x.db.prepare('SELECT * FROM observation_head').get(), head);
  x.time(START + 900000); assert.equal(o.current().data.state, 'expired'); assert.equal(o.current().data.level, null);
  assert.equal(o.current().data.historicalLevel, 'moderate');
});

test('O03: control states and bad timestamps never resurrect prior observations', async t => {
  const x = await setup(t), o = x.observations; o.publish(x.a.token, write(START));
  for (const [i, state] of ['unknown', 'paused', 'withdrawn'].entries()) {
    o.control(x.a.token, { ...intent(START, i + 1), state, reasonCategory: 'correction' });
    assert.equal(o.current().data.state, state); assert.equal(o.current().data.level, null);
  }
  o.publish(x.a.token, write(START, 4, 'quiet'));
  x.db.prepare('UPDATE observation_head SET observed_at=?,published_at=?,valid_until=?').run(START + 1, START + 1, START + 1 + TTL);
  assert.equal(o.current().data.state, 'unavailable');
  x.db.exec('UPDATE observation_head SET observed_at=NULL'); assert.equal(o.current().data.state, 'unavailable');
});

test('O04/I01: CAS, single audit/idempotence, exact 60s intent, role recheck, and atomic failure', async t => {
  const x = await setup(t), o = x.observations;
  const request = write(START); o.publish(x.a.token, request); x.time(START + 61000);
  assert.equal(o.publish(x.a.token, request).operation.replayed, true);
  assert.equal(x.db.prepare('SELECT count(*) n FROM observation_events').get().n, 1);
  assert.equal(x.db.prepare("SELECT count(*) n FROM audit WHERE action='observation.publish'").get().n, 1);
  assert.throws(() => o.publish(x.a.token, { ...request, level: 'busy' }), e => e.code === 'IDEMPOTENCY_CONFLICT');
  assert.throws(() => o.publish(x.a.token, write(START, 1)), e => e.code === 'INTENT_EXPIRED');
  x.time(START + 60000); o.publish(x.a.token, write(START, 1));
  assert.throws(() => o.publish(x.a.token, write(START + 60000, 1)), e => e.code === 'REVISION_CONFLICT');
  const prior = x.db.prepare('SELECT * FROM observation_head').get();
  x.db.exec("CREATE TRIGGER fail_observation_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test'); END");
  assert.throws(() => o.publish(x.a.token, write(START + 60000, 2)));
  assert.deepEqual(x.db.prepare('SELECT * FROM observation_head').get(), prior); x.db.exec('DROP TRIGGER fail_observation_audit');
  x.service.changeRole(roleWrite(x.c, x.a.userId, START + 60000, 1, 'revoke'));
  assert.throws(() => o.publish(x.a.token, request), e => e.code === 'FORBIDDEN');
  assert.throws(() => x.service.operationResult(x.a.token, request.operationId, 'observation.publish'), e => e.code === 'FORBIDDEN');
});

test('D01/M04: readonly public, no numeric/simulation injection, unauthorized cannot write, idle only extends on new successful publication', async t => {
  const x = await setup(t), o = x.observations; const regular = await x.service.login(x.fixture('synthetic-regular'));
  assert.throws(() => o.publish(regular.token, write(START)), e => e.code === 'FORBIDDEN');
  for (const extra of [{ occupancy: 50 }, { count: 0 }, { simulation: true }, { gymId: 'other' }]) assert.throws(() => o.publish(x.a.token, { ...write(START), ...extra }), e => e.code === 'INVALID_FIELDS');
  x.time(START + 2000); const request = write(START + 2000); o.publish(x.a.token, request);
  assert.equal(x.db.prepare('SELECT last_interactive_at FROM sessions WHERE session_id=?').get(x.a.sessionId).last_interactive_at, START + 2000);
  x.time(START + 4000); o.current(); o.publish(x.a.token, request);
  assert.equal(x.db.prepare('SELECT last_interactive_at FROM sessions WHERE session_id=?').get(x.a.sessionId).last_interactive_at, START + 2000);
  const store = createObservationService(x.db, { ...x.c, environment: 'store', simulation: false }, x.service);
  assert.throws(() => store.publish(x.a.token, { ...write(START + 4000, 1), occupancy: 1 }), e => e.code === 'INVALID_FIELDS');
});

function aged(db, count, time) {
  const insert = db.prepare('INSERT INTO observation_events VALUES (?, ?, ?, ?, ?, ?)');
  for (let i = 1; i <= count; i++) insert.run(i, 'quiet', time, time, time + TTL, 'synthetic-actor');
}
test('C01/D01: bounded cleanup rollback/resume after DB restart preserves current head/TTL and exact 30-day boundary', async t => {
  const x = await setup(t); const now = START + RETENTION;
  aged(x.db, 250, START);
  x.db.prepare("INSERT INTO observation_head VALUES (1,250,'withdrawn',NULL,?,NULL)").run(START);
  x.db.prepare("INSERT INTO observation_events VALUES (251,'quiet',?,?,?,'synthetic-actor')").run(START + 1, START + 1, START + 1 + TTL);
  const head = x.db.prepare('SELECT * FROM observation_head').get(); let batches = 0;
  const cleanup = createObservationCleanup(x.db, x.c, { now: () => now, afterDelete: () => { if (++batches === 2) throw new Error('injected'); } });
  assert.equal(cleanup.batch(true).removed, 100);
  assert.equal(cleanup.batch().failed, true); assert.equal(cleanup.status().cursor_revision, 100); assert.equal(cleanup.status().error_count, 1);
  assert.equal(x.db.prepare('SELECT count(*) n FROM observation_events').get().n, 151);
  x.db.close(); const db = openDatabase(x.c); x.beforeRemove(() => db.close());
  const resumed = createObservationCleanup(db, x.c, { now: () => now + HOUR }); await resumed.run(true);
  assert.equal(resumed.status().deleted_total, 250); // Resumes frozen cutoff; the +1ms newer row waits for next run.
  assert.deepEqual(db.prepare('SELECT * FROM observation_head').get(), head);
  assert.equal(db.prepare('SELECT count(*) n FROM observation_events').get().n, 1);
  await resumed.run(true); assert.equal(db.prepare('SELECT count(*) n FROM observation_events').get().n, 0);
  assert.deepEqual(db.prepare('SELECT * FROM observation_head').get(), head);
  const service = createIdentityService(db, x.c, { now: () => now + HOUR });
  assert.equal(createObservationService(db, x.c, service).current().data.state, 'withdrawn');
});

test('C01: completion-based scheduler does not skip next hour when batches take time', async t => {
  const x = await setup(t); let time = START + RETENTION; aged(x.db, 201, START);
  const cleanup = createObservationCleanup(x.db, x.c, { now: () => time, afterDelete: () => { time += 5000; } });
  const scheduled = []; let notify;
  const nextSchedule = () => scheduled.length ? Promise.resolve(scheduled.shift()) : new Promise(r => { notify = r; });
  const stop = startObservationCleanup(cleanup, () => assert.fail('unexpected failure'), {
    setTimeout(fn, delay) { const item = { fn, delay }; if (notify) { const n = notify; notify = null; n(item); } else scheduled.push(item); return { unref() {} }; }, clearTimeout() {}
  });
  const first = await nextSchedule(); assert.equal(time, START + RETENTION + 15000); assert.equal(first.delay, HOUR);
  const success = cleanup.status().last_success_at; time += first.delay; await first.fn();
  const second = await nextSchedule(); assert.equal(second.delay, HOUR); assert.ok(cleanup.status().last_success_at > success);
  await stop();
});
