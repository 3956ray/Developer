import { ApiError, fail, parseStrict } from './protocol.mjs';
import { createIdentityService, NOTICE_VERSION } from './identity.mjs';

async function body(req) {
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(req.headers['content-type'] ?? '')) fail(415, 'JSON_REQUIRED');
  if (Number(req.headers['content-length']) > 65536) fail(413, 'REQUEST_TOO_LARGE');
  let size = 0; const parts = [];
  for await (const part of req) { size += part.length; if (size > 65536) fail(413, 'REQUEST_TOO_LARGE'); parts.push(part); }
  let text;
  try { text = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(parts)); } catch { fail(400, 'INVALID_JSON'); }
  return parseStrict(text);
}
function bearer(req) {
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '');
  if (!match) fail(401, 'SESSION_INVALID'); return match[1];
}
function query(url, allowed) {
  const names = [...url.searchParams.keys()];
  if (new Set(names).size !== names.length || names.some(k => !allowed.includes(k))) fail(422, 'INVALID_FIELDS');
}
export function createHandler(db, c, options = {}) {
  const identity = createIdentityService(db, c, options);
  return async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    let status = 200, data, operation;
    try {
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/health') {
        query(url, []);
        const row = db.prepare('SELECT environment,gym_id FROM deployment WHERE singleton=1').get();
        if (!row || row.environment !== c.environment || row.gym_id !== c.gymId) fail(503, 'STORAGE_UNAVAILABLE');
        data = { status: 'ready', checkpoint: 'CP1', environment: c.environment, gymId: c.gymId,
          simulation: c.simulation, identityMode: c.identity.mode, migrations: db.prepare('SELECT count(*) AS count FROM schema_migrations').get().count };
      } else if (req.method === 'GET' && url.pathname === '/v1/privacy') {
        query(url, []); data = { version: NOTICE_VERSION, text: '用于建立本应用身份与维护登录会话。登录不代表会员资格或馆方管理权限；本阶段不索取手机号、姓名、头像、位置或人脸。你可以拒绝并继续浏览场馆。应用用途同意不等于微信平台隐私接口授权。', simulation: c.simulation };
      } else if (req.method === 'POST' && url.pathname === '/v1/sessions/exchange') {
        query(url, []); data = await identity.login(await body(req)); status = 201;
      } else if (req.method === 'GET' && url.pathname === '/v1/session') {
        query(url, ['interaction']); data = identity.session(bearer(req), url.searchParams.get('interaction') ?? 'poll');
      } else if (req.method === 'POST' && url.pathname === '/v1/session/logout') {
        query(url, []); const token = bearer(req); const result = identity.logout(token, await body(req)); data = result.data; operation = result;
      } else if (req.method === 'GET' && url.pathname === '/v1/operator/role') {
        query(url, []); data = identity.role(bearer(req));
      } else if (req.method === 'GET' && url.pathname === '/v1/operator/audit') {
        query(url, ['limit']); data = identity.audits(bearer(req), Number(url.searchParams.get('limit') ?? 100));
      } else if (req.method === 'GET' && /^\/v1\/operations\/[^/]+$/.test(url.pathname)) {
        query(url, ['type']); data = identity.operationResult(bearer(req), url.pathname.split('/').at(-1), url.searchParams.get('type'));
      } else fail(404, 'NOT_FOUND');
      res.writeHead(status); res.end(JSON.stringify({ ok: true, serverNow: new Date(identity.now()).toISOString(), data, ...(operation ? { operation: operation.operation, current: operation.current } : {}) }));
    } catch (error) {
      const safe = error instanceof ApiError ? error : new ApiError(503, 'STORAGE_UNAVAILABLE');
      if (safe.retryAfterSeconds !== undefined) res.setHeader('Retry-After', String(safe.retryAfterSeconds));
      req.resume();
      res.writeHead(safe.status); res.end(JSON.stringify({ ok: false, serverNow: new Date(identity.now()).toISOString(), error: { code: safe.code, message: safe.code, ...(safe.retryAfterSeconds === undefined ? {} : { retryAfterSeconds: safe.retryAfterSeconds }) } }));
    }
  };
}
