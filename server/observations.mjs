import { transaction } from './database.mjs';
import { fail, validateWrite } from './protocol.mjs';
export const TTL = 900000;
const levels = ['quiet', 'moderate', 'busy'];
const controls = ['unknown', 'paused', 'withdrawn'];
const validTime = n => Number.isSafeInteger(n) && Number.isFinite(new Date(n).getTime());
export function createObservationService(db, c, identity) {
  function unavailable(result) { console.error('OBSERVATION_DATA_INVALID'); return { ...result, state: 'unavailable' }; }
  function snapshot(time) {
    const h = db.prepare('SELECT * FROM observation_head WHERE singleton=1').get();
    const result = { revision: h?.revision ?? 'absent', source: 'manual', state: 'never', level: null,
      historicalLevel: null, observedAt: null, publishedAt: null, validUntil: null, environment: c.environment, simulation: c.environment === 'test' };
    if (!h) return result;
    if (!validTime(time) || !validTime(h.published_at) || h.published_at > time || !Number.isSafeInteger(h.revision) || h.revision < 1) return unavailable(result);
    if (controls.includes(h.state)) return { ...result, state: h.state, publishedAt: new Date(h.published_at).toISOString() };
    if (!levels.includes(h.state) || !validTime(h.observed_at) || !validTime(h.valid_until) || h.observed_at !== h.published_at || h.observed_at > time || h.valid_until - h.observed_at !== TTL) return unavailable(result);
    return { ...result, state: time >= h.valid_until ? 'expired' : h.state, level: time >= h.valid_until ? null : h.state,
      historicalLevel: time >= h.valid_until ? h.state : null, observedAt: new Date(h.observed_at).toISOString(),
      publishedAt: new Date(h.published_at).toISOString(), validUntil: new Date(h.valid_until).toISOString() };
  }
  function write(token, body, control) {
    validateWrite(body, control ? ['state', 'reasonCategory'] : ['level', 'observedJustNow']);
    if (control ? !controls.includes(body.state) || !['cannot-assess', 'updates-paused', 'correction'].includes(body.reasonCategory)
      : !levels.includes(body.level) || body.observedJustNow !== true) fail(422, 'INVALID_OBSERVATION');
    return identity.operatorOperation(token, control ? 'observation.control' : 'observation.publish', body, ({ userId, time, audit }) => {
      const old = db.prepare('SELECT revision FROM observation_head WHERE singleton=1').get();
      if (body.expectedRevision !== (old?.revision ?? 'absent')) fail(409, 'REVISION_CONFLICT');
      const revision = (old?.revision ?? 0) + 1, state = control ? body.state : body.level;
      const observedAt = control ? null : time, validUntil = control ? null : time + TTL;
      db.prepare(`INSERT INTO observation_head VALUES (1,?,?,?,?,?) ON CONFLICT(singleton) DO UPDATE SET
        revision=excluded.revision,state=excluded.state,observed_at=excluded.observed_at,published_at=excluded.published_at,valid_until=excluded.valid_until`).run(revision, state, observedAt, time, validUntil);
      db.prepare('INSERT INTO observation_events VALUES (?,?,?,?,?,?)').run(revision, state, observedAt, time, validUntil, userId);
      audit(userId, null, control ? 'observation.control' : 'observation.publish', revision, time, control ? body.reasonCategory : 'observed-just-now');
      return { revision, data: { revision, state, observedAt: observedAt === null ? null : new Date(observedAt).toISOString(), publishedAt: new Date(time).toISOString(), validUntil: validUntil === null ? null : new Date(validUntil).toISOString() } };
    });
  }
  return {
    current() { return transaction(db, () => { const now = identity.now(); return { serverNow: new Date(now).toISOString(), data: snapshot(now) }; }); },
    venue() { return transaction(db, () => { const now = identity.now(); return { serverNow: new Date(now).toISOString(), data: {
      gym: { name: null, timeZone: c.timeZone, openingHours: null, contact: null }, observation: snapshot(now)
    } }; }); },
    publish: (token, body) => write(token, body, false),
    control: (token, body) => write(token, body, true)
  };
}
