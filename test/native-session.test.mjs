import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
function harness({ existing = false, confirm = false, failure, requestHook } = {}) {
  let page, prompt, logins = 0, calls = [], saved, cleared = false, idsGenerated = 0;
  const token = 'a'.repeat(43), serverNow = '2026-09-16T00:00:00.000Z';
  const session = { environment: 'test', simulation: true, token, sessionRevision: 1, accountRevision: 1, expiresAt: '2026-09-23T00:00:00.000Z', idleExpiresAt: '2026-09-17T00:00:00.000Z' };
  const api = { config: { environment: 'test' }, configured: () => true, load: () => existing ? token : null,
    save: t => { saved = t; }, clear: () => { cleared = true; }, loginCode: async () => { logins++; return 'synthetic-code'; },
    operationId: async () => { idsGenerated++; return '00000000-0000-4000-8000-' + String(idsGenerated).padStart(12, '0'); },
    request: async (path, method, data) => { calls.push({ path, method, data }); if (requestHook) return requestHook(path, { serverNow, data: session }); if (failure) throw failure; return { serverNow, data: session }; } };
  const timers = []; const context = { require: () => api, Page: p => { page = p; }, wx: { showModal: options => { prompt = options.success({ confirm }); } },
    setInterval: fn => { timers.push(fn); return timers.length; }, clearInterval() {}, setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout() {} };
  vm.runInNewContext(readFileSync('miniprogram/pages/me/index.js', 'utf8'), context);
  page.setData = data => Object.assign(page.data, data); page.onLoad();
  return { page, timers, done: () => prompt, stats: () => ({ logins, calls, saved, cleared, idsGenerated }) };
}
test('P01: public/refusal path never calls wx.login or exchanges identity', async () => {
  const h = harness(); h.page.onShow(); h.page.showPrivacy(); h.page.login(); await h.done();
  assert.equal(h.stats().logins, 0); assert.equal(h.stats().calls.length, 0); assert.equal(h.page.data.authenticated, false);
  assert.match(h.page.data.detail, /拒绝/); assert.equal(h.page.data.showPrivacy, true);
});
test('P01/L01: only explicit consent logs in; simulation label and token outside view data', async () => {
  const h = harness({ confirm: true }); h.page.onShow(); h.page.login(); await h.done();
  assert.equal(h.stats().logins, 1); assert.equal(h.page.data.authenticated, true); assert.match(h.page.data.status, /模拟/);
  assert.ok(h.stats().saved); assert.ok(!JSON.stringify(h.page.data).includes(h.stats().saved));
  await h.page.logout(); assert.equal(h.stats().cleared, true); assert.equal(h.page.data.authenticated, false);
});
test('F01/P01: foreground polls never relogin; failures/401 are honest; hidden timer sends nothing', async () => {
  const h = harness({ existing: true, failure: { status: 401 } }); h.page.onShow(); await new Promise(r => setImmediate(r));
  assert.equal(h.stats().logins, 0); assert.equal(h.stats().cleared, true); assert.equal(h.page.data.authenticated, false);
  const offline = harness({ existing: true, failure: { code: 'NETWORK_UNCONFIRMED' } }); offline.page.onShow(); await new Promise(r => setImmediate(r));
  assert.equal(offline.page.data.authenticated, false); assert.equal(offline.stats().cleared, false); assert.match(offline.page.data.status, /无法确认/);
  const count = offline.stats().calls.length; offline.page.onHide(); await offline.timers[0](); assert.equal(offline.stats().calls.length, count);
});

test('F01: logged in -> hidden past expiry -> foreground pending/failure/401 never shows stale authenticated state', async () => {
  for (const error of [{ code: 'NETWORK_UNCONFIRMED' }, { status: 401, code: 'SESSION_INVALID' }]) {
    let shouldHang = false, pendingReject;
    const h = harness({ confirm: true, requestHook: (_path, response) => shouldHang ? new Promise((_res, rej) => { pendingReject = rej; }) : Promise.resolve(response) });
    h.page.onShow(); h.page.login(); await h.done(); assert.equal(h.page.data.authenticated, true);
    h.page.onHide(); // hidden time is deliberately not trusted; no client wall clock decides session validity
    shouldHang = true; h.page.onShow();
    assert.equal(h.page.data.authenticated, false); assert.match(h.page.data.status, /确认/);
    pendingReject(error); await new Promise(r => setImmediate(r));
    assert.equal(h.page.data.authenticated, false); assert.equal(h.stats().cleared, error.status === 401);
  }
});

test('F01: response from before hiding cannot restore authenticated state or an expiry timer', async () => {
  let pendingResolve, count = 0;
  const h = harness({ existing: true, requestHook: (_path, response) => new Promise(res => { pendingResolve = () => res(response); count++; }) });
  h.page.onShow(); h.page.onHide(); const timerCount = h.timers.length;
  pendingResolve(); await new Promise(r => setImmediate(r));
  assert.equal(h.page.data.authenticated, false); assert.equal(h.timers.length, timerCount);
  h.page.onShow(); assert.equal(count, 2); assert.equal(h.page.data.authenticated, false);
  pendingResolve(); await new Promise(r => setImmediate(r)); assert.equal(h.page.data.authenticated, true);
});

test('F01: hide/show and late A response serialize queued B/manual refresh with one in-flight session read', async () => {
  const pending = []; let active = 0, maximum = 0;
  const h = harness({ existing: true, requestHook: (_path, response) => {
    active++; maximum = Math.max(active, maximum);
    return new Promise(res => pending.push(() => { active--; res(response); }));
  } });
  h.page.onShow(); assert.equal(h.stats().calls.length, 1);
  h.page.onHide(); h.page.onShow(); await h.page.refreshNow();
  assert.equal(h.stats().calls.length, 1); assert.equal(h.page.data.authenticated, false);
  pending.shift()(); await new Promise(r => setImmediate(r)); assert.equal(h.stats().calls.length, 2);
  await h.page.refreshNow(); assert.equal(h.stats().calls.length, 2); assert.equal(h.page._refreshing, true);
  pending.shift()(); await new Promise(r => setImmediate(r)); assert.equal(h.stats().calls.length, 3);
  pending.shift()(); await new Promise(r => setImmediate(r)); assert.equal(maximum, 1); assert.equal(h.page._refreshing, false);
});

test('native request boundary: non-2xx/business failure rejected, cross-environment cached token ignored', async () => {
  let reply = { statusCode: 200, data: { ok: false, error: { code: 'DENIED' } } };
  const context = { module: { exports: {} }, require: () => ({ environment: 'test', baseUrl: 'http://127.0.0.1:8787' }),
    wx: { getStorageSync: () => ({ scope: 'store:https://example.invalid', token: 'a'.repeat(43) }), request: args => args.success(reply) }, ArrayBuffer, Uint8Array };
  vm.runInNewContext(readFileSync('miniprogram/lib/session.js', 'utf8'), context); const api = context.module.exports;
  assert.equal(api.load(), null);
  await assert.rejects(() => api.request('/session', 'GET'), e => e.code === 'DENIED');
  reply = { statusCode: 503, data: { ok: true, serverNow: new Date().toISOString(), data: {} } };
  await assert.rejects(() => api.request('/session', 'GET'));
  await assert.rejects(() => api.operationId(), e => e.code === 'SECURE_RANDOM_UNAVAILABLE');
});

test('I01: unconfirmed logout retains original operation key until explicit retry resolves', async () => {
  let failed = false;
  const h = harness({ confirm: true, requestHook: (path, response) => {
    if (path === '/session/logout' && !failed) { failed = true; return Promise.reject({ code: 'NETWORK_UNCONFIRMED' }); }
    if (path.startsWith('/operations/')) return Promise.resolve({ data: { state: 'unknown' } });
    return Promise.resolve(response);
  } });
  h.page.onShow(); h.page.login(); await h.done(); await h.page.logout();
  assert.equal(h.stats().cleared, false); await h.page.refreshNow(); await h.page.logout();
  const writes = h.stats().calls.filter(c => c.path === '/session/logout');
  assert.equal(writes.length, 2); assert.equal(writes[0].data.operationId, writes[1].data.operationId);
  assert.equal(h.stats().idsGenerated, 1); assert.equal(h.stats().cleared, true);
});
