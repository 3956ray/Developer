import { createHmac, createHash } from 'node:crypto';

export class ApiError extends Error {
  constructor(status, code, retryAfterSeconds) { super(code); this.status = status; this.code = code; this.retryAfterSeconds = retryAfterSeconds; }
}
export const fail = (status, code, retry) => { throw new ApiError(status, code, retry); };
export const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const digest = value => createHash('sha256').update(value).digest('hex');
export const hmac = (key, value) => createHmac('sha256', Buffer.from(key, 'hex')).update(value).digest('hex');
export function fields(object, allowed, required = allowed) {
  if (!object || Array.isArray(object) || typeof object !== 'object') fail(400, 'INVALID_REQUEST');
  if (Object.keys(object).some(k => !allowed.includes(k)) || required.some(k => !Object.hasOwn(object, k))) fail(422, 'INVALID_FIELDS');
}
export function canonical(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  return '{' + Object.keys(value).sort((a, b) => {
    const x = Array.from(a, c => c.codePointAt(0)), y = Array.from(b, c => c.codePointAt(0));
    for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i] - y[i];
    return x.length - y.length;
  }).map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}';
}
// Recursive descent preserves key boundaries, including escaped duplicate keys.
export function parseStrict(text) {
  if (typeof text !== 'string' || Buffer.byteLength(text) > 65536) fail(400, 'INVALID_JSON');
  let i = 0;
  const ws = () => { while (/[\t\n\r ]/.test(text[i] ?? '\0')) i++; };
  const string = () => {
    const start = i++;
    while (i < text.length) {
      if (text[i] === '\\') { i += 2; continue; }
      if (text[i++] === '"') { try { return JSON.parse(text.slice(start, i)); } catch { fail(400, 'INVALID_JSON'); } }
    }
    fail(400, 'INVALID_JSON');
  };
  const value = depth => {
    if (depth > 32) fail(400, 'INVALID_JSON');
    ws(); const c = text[i];
    if (c === '"') return string();
    if (c === '{' || c === '[') {
      i++; ws(); const object = c === '{'; const result = object ? Object.create(null) : []; const end = object ? '}' : ']';
      if (text[i] === end) { i++; return result; }
      while (true) {
        ws(); let key;
        if (object) {
          if (text[i] !== '"') fail(400, 'INVALID_JSON'); key = string();
          if (Object.hasOwn(result, key)) fail(400, 'DUPLICATE_JSON_KEY');
          ws(); if (text[i++] !== ':') fail(400, 'INVALID_JSON');
        }
        const item = value(depth + 1); if (object) result[key] = item; else result.push(item);
        ws(); if (text[i] === end) { i++; return result; }
        if (text[i++] !== ',') fail(400, 'INVALID_JSON');
      }
    }
    const match = /^(true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(i));
    if (!match) fail(400, 'INVALID_JSON'); i += match[0].length;
    const result = JSON.parse(match[0]);
    if (typeof result === 'number' && (!Number.isFinite(result) || Math.abs(result) > Number.MAX_SAFE_INTEGER)) fail(400, 'INVALID_JSON');
    return result;
  };
  const result = value(0); ws(); if (i !== text.length) fail(400, 'INVALID_JSON'); return result;
}
export function validateWrite(body, extra = [], revisionKeys) {
  fields(body, ['operationId', 'requestCreatedAt', 'expectedRevision', ...extra]);
  const revision = value => value === 'absent' || (Number.isSafeInteger(value) && value > 0);
  if (revisionKeys) { fields(body.expectedRevision, revisionKeys); if (Object.values(body.expectedRevision).some(v => !revision(v))) fail(422, 'INVALID_REQUEST'); }
  if (!uuidPattern.test(body.operationId) || (!revisionKeys && !revision(body.expectedRevision))) fail(422, 'INVALID_REQUEST');
  if (typeof body.requestCreatedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(body.requestCreatedAt) || !Number.isFinite(Date.parse(body.requestCreatedAt)) || new Date(body.requestCreatedAt).toISOString() !== body.requestCreatedAt) fail(422, 'INVALID_REQUEST');
}
export function validateIntent(body, now, maximumAge = 300000) {
  const age = now - Date.parse(body.requestCreatedAt);
  if (age > maximumAge) fail(422, 'INTENT_EXPIRED');
  if (age < -30000) fail(422, 'INTENT_FUTURE');
}
