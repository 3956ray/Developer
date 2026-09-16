import { randomBytes, randomUUID } from 'node:crypto';
import { claimDemoTicket } from './demo.mjs';
import { transaction } from './database.mjs';
import { createAdapter, codeDigest } from './wechat.mjs';
import { fail, fields, digest, hmac, canonical, validateWrite, validateIntent, uuidPattern } from './protocol.mjs';

export const NOTICE_VERSION = 'cp3-purpose-v1';
export const DAY = 86400000;
export function createIdentityService(db, c, options = {}) {
  if (c.environment !== 'test' && (options.now || options.transport)) fail(503, 'TEST_HOOK_FORBIDDEN');
  const now = options.now ?? Date.now;
  const adapter = createAdapter(db, c, options.transport);
  if (c.identity.mode !== 'disabled') transaction(db, () => {
    const ns = db.prepare('SELECT * FROM identity_namespace WHERE singleton=1').get();
    if (ns && (ns.mode !== c.identity.mode || ns.app_id !== c.identity.appId)) fail(503, 'IDENTITY_NAMESPACE_MISMATCH');
    if (!ns) db.prepare('INSERT INTO identity_namespace VALUES (1,?,?)').run(c.identity.mode, c.identity.appId);
  });
  function audit(actor, subject, action, revision, time, reason) {
    db.prepare('INSERT INTO audit VALUES (?,?,?,?,?,?,?,?)').run(randomUUID(), actor, subject, action, 'committed', revision, time, reason);
  }
  function authenticate(token, time, operator = false) {
    if (c.identity.mode === 'disabled') fail(503, 'IDENTITY_NOT_CONFIGURED');
    if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(token)) fail(401, 'SESSION_INVALID');
    const session = db.prepare(`SELECT s.*, a.state, a.gym_id, a.revision AS account_revision FROM sessions s
      JOIN accounts a ON a.user_id=s.user_id WHERE token_digest=?`).get(digest(token));
    if (!session || session.gym_id !== c.gymId || session.state !== 'active' || session.revoked_at !== null ||
        time >= session.expires_at || time >= session.last_interactive_at + DAY) fail(401, 'SESSION_INVALID');
    if (operator) {
      const role = db.prepare('SELECT granted FROM operator_roles WHERE user_id=?').get(session.user_id);
      if (!role?.granted) fail(403, 'FORBIDDEN');
    }
    return session;
  }
  function attemptLogin() {
    return transaction(db, () => {
      const time = now();
      const start = Math.floor(time / 60000) * 60000;
      const bucket = db.prepare("SELECT count FROM rate_buckets WHERE kind='login' AND window_start=?").get(start);
      if (bucket?.count >= 120) fail(429, 'RATE_LIMITED', Math.ceil((start + 60000 - time) / 1000));
      db.prepare("INSERT INTO rate_buckets VALUES ('login',?,?,1) ON CONFLICT(kind,window_start) DO UPDATE SET count=count+1").run(start, start + 60000);
    });
  }
  function state(s) {
    return { userId: s.user_id, sessionId: s.session_id, authAt: new Date(s.auth_at).toISOString(),
      expiresAt: new Date(s.expires_at).toISOString(), idleExpiresAt: new Date(s.last_interactive_at + DAY).toISOString(),
      sessionRevision: s.revision, accountRevision: s.account_revision,
      environment: c.environment, gymId: c.gymId, simulation: c.simulation, identityMode: c.identity.mode, demoEnabled: c.demo?.enabled === true };
  }
  function operation(actor, type, body, time, apply, maximumAge = 300000, resource = null) {
    const hash = hmac(c.keys.intentHmac, canonical([c.environment, c.gymId, actor, type, body, ...(resource === null ? [] : [resource])]));
    const old = db.prepare('SELECT * FROM operations WHERE actor_id=? AND operation_type=? AND operation_key=?').get(actor, type, body.operationId);
    if (old && time < old.created_at + DAY) {
      if (old.body_hmac !== hash) fail(409, 'IDEMPOTENCY_CONFLICT');
      return { data: JSON.parse(old.result_json), operation: { operationId: body.operationId, type, committedAt: new Date(old.created_at).toISOString(), appliedRevision: old.applied_revision, replayed: true }, current: { refreshRequired: true } };
    }
    validateIntent(body, time, maximumAge);
    const result = apply();
    if (old) db.prepare('DELETE FROM operations WHERE actor_id=? AND operation_type=? AND operation_key=?').run(actor, type, body.operationId);
    db.prepare('INSERT INTO operations (actor_id,operation_type,operation_key,body_hmac,result_json,applied_revision,created_at,subject_id) VALUES (?,?,?,?,?,?,?,?)').run(actor, type, body.operationId, hash, JSON.stringify(result.data), result.revision, time, result.subjectId ?? null);
    return { data: result.data, operation: { operationId: body.operationId, type, committedAt: new Date(time).toISOString(), appliedRevision: result.revision, replayed: false }, current: { refreshRequired: true } };
  }
  return {
    now,
    async login(body) {
      fields(body, ['code', 'privacyNoticeVersion', 'consent']);
      if (body.privacyNoticeVersion !== NOTICE_VERSION || body.consent !== true) fail(422, 'CONSENT_REQUIRED');
      if (typeof body.code !== 'string' || !/^[A-Za-z0-9_-]{1,256}$/.test(body.code)) fail(400, 'LOGIN_CODE_INVALID');
      if (c.identity.mode === 'disabled') fail(503, 'IDENTITY_NOT_CONFIGURED');
      attemptLogin(); // Committed even if code/platform/audit/session creation later fails.
      const ticket = transaction(db, () => {
        const hash = codeDigest(c, body.code);
        if (db.prepare('SELECT 1 FROM login_codes WHERE code_hmac=?').get(hash)) fail(400, 'LOGIN_CODE_INVALID');
        db.prepare('INSERT INTO login_codes VALUES (?,?)').run(hash, now());
        return c.demo?.enabled ? claimDemoTicket(db, c, body.code, now()) : null;
      });
      const identity = await adapter(body.code, ticket);
      return transaction(db, () => {
        const time = now();
        let account = db.prepare(`SELECT a.* FROM wechat_identities w JOIN accounts a ON a.user_id=w.user_id
          WHERE w.app_id=? AND w.open_id=?`).get(c.identity.appId, identity.openId);
        if (account && account.state !== 'active') fail(409, 'ACCOUNT_DELETING');
        if (!account) {
          account = { user_id: randomUUID(), revision: 1 };
          db.prepare("INSERT INTO accounts (user_id,gym_id,state,revision,created_at) VALUES (?,?,'active',1,?)").run(account.user_id, c.gymId, time);
          db.prepare('INSERT INTO wechat_identities VALUES (?,?,?)').run(c.identity.appId, identity.openId, account.user_id);
        }
        const active = db.prepare(`SELECT session_id FROM sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>?
          AND last_interactive_at>? ORDER BY created_at,session_id`).all(account.user_id, time, time - DAY);
        for (const row of active.slice(0, Math.max(0, active.length - 4))) db.prepare('UPDATE sessions SET revoked_at=?,revision=revision+1 WHERE session_id=?').run(time, row.session_id);
        const token = randomBytes(32).toString('base64url'), id = randomUUID();
        db.prepare('INSERT INTO sessions VALUES (?,?,?,?,?,?,?,?,1)').run(id, account.user_id, digest(token), time, time, time, time + 7 * DAY, null);
        db.prepare('INSERT INTO privacy_consents VALUES (?,?,?) ON CONFLICT(user_id,notice_version) DO UPDATE SET accepted_at=excluded.accepted_at').run(account.user_id, NOTICE_VERSION, time);
        audit(account.user_id, account.user_id, 'session.create', 1, time, 'purpose-consent');
        const s = authenticate(token, time);
        return { ...state(s), token };
      });
    },
    session(token, interaction) {
      if (!['poll', 'interactive'].includes(interaction)) fail(422, 'INVALID_INTERACTION');
      return transaction(db, () => {
        const time = now(), s = authenticate(token, time);
        if (interaction === 'interactive') {
          db.prepare('UPDATE sessions SET last_interactive_at=? WHERE session_id=?').run(time, s.session_id);
          s.last_interactive_at = time;
        }
        return state(s);
      });
    },
    logout(token, body) {
      validateWrite(body);
      return transaction(db, () => {
        const time = now(), s = authenticate(token, time);
        return operation(s.user_id, 'session.logout', body, time, () => {
          if (body.expectedRevision !== s.revision) fail(409, 'REVISION_CONFLICT');
          db.prepare('UPDATE sessions SET revoked_at=?,revision=revision+1 WHERE session_id=?').run(time, s.session_id);
          audit(s.user_id, s.user_id, 'session.logout', s.revision + 1, time, 'user-logout');
          return { revision: s.revision + 1, data: { sessionId: s.session_id, revokedAt: new Date(time).toISOString() } };
        });
      });
    },
    role(token) {
      return transaction(db, () => {
        const s = authenticate(token, now());
        const role = db.prepare('SELECT * FROM operator_roles WHERE user_id=?').get(s.user_id);
        return { isOperator: Boolean(role?.granted), roleRevision: role?.revision ?? 'absent' };
      });
    },
    withOperator(token, action) {
      return transaction(db, () => { const time = now(); const s = authenticate(token, time, true); return action({ userId: s.user_id, time, audit }); });
    },
    authorized(token, options, apply) {
      return transaction(db, () => {
        const time = now(), session = authenticate(token, time, Boolean(options.operator));
        if (options.fresh && time - session.auth_at > 300000) fail(422, 'FRESH_AUTH_REQUIRED');
        return apply({ userId: session.user_id, session, time, audit });
      });
    },
    domainOperation(token, type, body, options, apply) {
      const allowed = ['schedule.draft.save','schedule.publish','schedule.withdraw','pairing.create','pairing.cancel','membership.bind','membership.restore','membership.revoke','membership.reverify','membership.unbind','account.delete'];
      if (!allowed.includes(type)) fail(422, 'INVALID_OPERATION');
      return this.authorized(token, options, context => operation(context.userId, type, body, context.time, () => {
        const result = apply(context);
        if (type !== 'account.delete') db.prepare('UPDATE sessions SET last_interactive_at=? WHERE session_id=?').run(context.time, context.session.session_id);
        return result;
      }, 300000, options.resource ?? null));
    },
    operatorOperation(token, type, body, apply) {
      if (!['observation.publish', 'observation.control'].includes(type)) fail(422, 'INVALID_OPERATION');
      return transaction(db, () => {
        const time = now(), session = authenticate(token, time, true);
        return operation(session.user_id, type, body, time, () => {
          const result = apply({ userId: session.user_id, time, audit });
          db.prepare('UPDATE sessions SET last_interactive_at=? WHERE session_id=?').run(time, session.session_id);
          return result;
        }, type === 'observation.publish' ? 60000 : 300000);
      });
    },
    audits(token, limit = 100) {
      if (!Number.isInteger(limit) || limit < 1 || limit > 100) fail(422, 'INVALID_LIMIT');
      return this.withOperator(token, ({ time }) => db.prepare('SELECT actor_id,subject_id,action,result,revision,time,reason_category FROM audit WHERE time>? ORDER BY time DESC,id DESC LIMIT ?').all(time - 90 * DAY, limit));
    },
    inspectRole(userId, gymId) {
      if (!uuidPattern.test(userId) || gymId !== c.gymId) fail(403, 'SCOPE_FORBIDDEN');
      return transaction(db, () => {
        const a = db.prepare('SELECT * FROM accounts WHERE user_id=? AND gym_id=?').get(userId, c.gymId);
        if (!a) fail(404, 'NOT_FOUND');
        const role = db.prepare('SELECT * FROM operator_roles WHERE user_id=?').get(userId);
        return { targetUserId: userId, activeAccount: a.state === 'active', roleState: role?.granted ? 'granted' : 'revoked', roleRevision: role?.revision ?? 'absent' };
      });
    },
    changeRole(body) {
      validateWrite(body, ['action', 'userId', 'gymId', 'maintainerId', 'reasonCategory', 'ownerConfirmed']);
      if (!['grant', 'revoke'].includes(body.action) || !uuidPattern.test(body.userId) || body.gymId !== c.gymId) fail(403, 'SCOPE_FORBIDDEN');
      if (typeof body.maintainerId !== 'string' || !/^[a-z][a-z0-9-]{0,47}$/.test(body.maintainerId) ||
          !['initial-authorization', 'authorization-renewed', 'authorization-ended', 'correction'].includes(body.reasonCategory) || body.ownerConfirmed !== true) fail(422, 'OWNER_CONFIRMATION_REQUIRED');
      return transaction(db, () => {
        const time = now(), actor = `maintainer:${body.maintainerId}`;
        const account = db.prepare('SELECT * FROM accounts WHERE user_id=? AND gym_id=?').get(body.userId, c.gymId);
        if (!account) fail(404, 'NOT_FOUND');
        if (account.state !== 'active') fail(409, 'ACCOUNT_DELETING');
        return operation(actor, `role.${body.action}`, body, time, () => {
          const old = db.prepare('SELECT * FROM operator_roles WHERE user_id=?').get(body.userId);
          if (body.expectedRevision !== (old?.revision ?? 'absent')) fail(409, 'REVISION_CONFLICT');
          const revision = (old?.revision ?? 0) + 1;
          db.prepare('INSERT INTO operator_roles VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET granted=excluded.granted,revision=excluded.revision,updated_at=excluded.updated_at').run(body.userId, Number(body.action === 'grant'), revision, time);
          audit(actor, body.userId, `role.${body.action}`, revision, time, body.reasonCategory);
          return { revision, subjectId: body.userId, data: { userId: body.userId, roleRevision: revision, granted: body.action === 'grant' } };
        });
      });
    },
    operationResult(token, id, type) {
      if (!uuidPattern.test(id) || !['session.logout','observation.publish','observation.control','schedule.draft.save','schedule.publish','schedule.withdraw','pairing.create','pairing.cancel','membership.bind','membership.restore','membership.revoke','membership.reverify','membership.unbind','account.delete'].includes(type)) fail(422, 'INVALID_OPERATION');
      return transaction(db, () => {
        const time = now(), s = authenticate(token, time, type.startsWith('observation.') || type.startsWith('schedule.') || ['membership.bind','membership.restore','membership.revoke','membership.reverify'].includes(type));
        const row = db.prepare('SELECT * FROM operations WHERE actor_id=? AND operation_type=? AND operation_key=?').get(s.user_id, type, id);
        if (!row || time >= row.created_at + DAY) return { state: 'unknown' };
        return { state: 'committed', appliedRevision: row.applied_revision, committedAt: new Date(row.created_at).toISOString(), result: JSON.parse(row.result_json), refreshRequired: true };
      });
    },
    maintainerResult(maintainerId, id, type) {
      if (!/^[a-z][a-z0-9-]{0,47}$/.test(maintainerId) || !uuidPattern.test(id) || !['role.grant', 'role.revoke'].includes(type)) fail(422, 'INVALID_OPERATION');
      const row = db.prepare('SELECT * FROM operations WHERE actor_id=? AND operation_type=? AND operation_key=?').get(`maintainer:${maintainerId}`, type, id);
      if (!row || now() >= row.created_at + DAY) return { state: 'unknown' };
      return { state: 'committed', result: JSON.parse(row.result_json), appliedRevision: row.applied_revision, refreshRequired: true };
    }
  };
}
