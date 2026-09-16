import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const START = Date.parse('2026-09-16T00:00:00.000Z');
function loadView() { const context = { module: { exports: {} }, Intl, Date }; vm.runInNewContext(readFileSync('miniprogram/lib/observation-view.js', 'utf8'), context); return context.module.exports; }
function sample(state = 'moderate') { return { revision: 1, state, level: ['quiet', 'moderate', 'busy'].includes(state) ? state : null, observedAt: new Date(START).toISOString(), publishedAt: new Date(START).toISOString(), validUntil: new Date(START + 900000).toISOString(), environment: 'test', simulation: true }; }
function harness({ operator = false, allowed = true, hook, cached, storageFails = false, modalHook } = {}) {
  let mono = 0, saved = new Map(), page, state = sample(), calls = [], ids = 0;
  if (cached) saved.set('gym.observation-cache.v1', { scope: 'test:http://127.0.0.1:8787::wechat:official', snapshot: { observation: cached, gym: { timeZone: 'Asia/Taipei' } } });
  const timers = [];
  const api = { scope: () => 'test:http://127.0.0.1:8787::wechat:official', config: { environment: 'test', baseUrl: 'http://127.0.0.1:8787' }, load: () => 'synthetic-token', operationId: async () => '00000000-0000-4000-8000-' + String(++ids).padStart(12, '0'),
    request: async (path, method, body) => {
      calls.push({ path, method, body });
      const response = { serverNow: new Date(START + Math.max(0, mono)).toISOString(), data: path === '/operator/role' ? { isOperator: allowed } : path.startsWith('/session?') ? { userId: 'synthetic-user' } : { gym: { name: null, timeZone: 'Asia/Taipei' }, observation: state } };
      return hook ? hook(path, method, body, response) : response;
    } };
  const view = loadView(), context = { module: { exports: {} }, require: name => name === './session' ? api : view,
    wx: { getPerformance: () => ({ now: () => mono }), getStorageSync: key => saved.get(key), setStorageSync: (key, value) => { if (storageFails && key.includes('write')) throw new Error('full'); saved.set(key, value); }, removeStorageSync: key => saved.delete(key), showModal: options => modalHook ? modalHook(options) : options.success({ confirm: true }) },
    setInterval: fn => { timers.push(fn); return timers.length; }, clearInterval() {}, Date };
  vm.runInNewContext(readFileSync('miniprogram/lib/observation-page.js', 'utf8'), context); page = context.module.exports(operator);
  page.setData = data => Object.assign(page.data, data); page.onLoad();
  return { page, calls, saved, timers, mono: value => { mono = value; }, state: value => { state = value; }, flush: () => new Promise(r => setImmediate(r)) };
}

test('O02/O03/O05 native model: exact TTL, controls, future/invalid times and monotonic failure', () => {
  const v = loadView(); let mono = 0; const clock = v.clock(() => mono); assert.equal(clock.now(), null); clock.accept(new Date(START).toISOString(), 0);
  mono = 899000; assert.equal(v.view(sample(), clock.now(), true).live, true);
  mono = 900000; assert.equal(v.view(sample(), clock.now(), true).title, '忙闲信息已过期');
  for (const state of ['never', 'unknown', 'paused', 'withdrawn', 'unavailable']) assert.equal(v.view(sample(state), START, true).live, false);
  assert.equal(v.view({ ...sample(), observedAt: new Date(START + 1).toISOString() }, START, true).live, false);
  mono = 1; assert.equal(clock.now(), null); assert.equal(v.view(sample(), clock.now(), true).live, false);
  assert.equal(v.view(sample(), START, false).live, false);
});

test('O05 native venue: offline restart preserves historical timestamp only; hides/queued late response cannot regain freshness', async () => {
  const pending = []; let active = 0, maximum = 0;
  const h = harness({ cached: sample(), hook: (_p, _m, _b, response) => { active++; maximum = Math.max(maximum, active); return new Promise(res => pending.push(() => { active--; res(response); })); } });
  assert.equal(h.page.data.live, false); assert.match(h.page.data.history, /上次记录/);
  h.page.onShow(); h.page.onHide(); h.page.onShow(); assert.equal(h.calls.length, 1);
  pending.shift()(); await h.flush(); assert.equal(h.calls.length, 2); assert.equal(h.page.data.live, false);
  await h.page.refreshNow(); assert.equal(h.calls.length, 2);
  pending.shift()(); await h.flush(); assert.equal(h.calls.length, 3);
  pending.shift()(); await h.flush(); assert.equal(maximum, 1); assert.equal(h.page.data.live, true);
  h.page.onHide(); const count = h.calls.length; h.timers.forEach(fn => fn()); assert.equal(h.calls.length, count);
});

test('O01/O03 native maintenance: publish and all controls are confirmable, role denial hides write actions', async () => {
  const h = harness({ operator: true }); h.page.onShow(); await h.flush(); assert.equal(h.page.data.authorized, true);
  await h.page.submitNew('quiet', true); await h.flush();
  const first = h.calls.find(c => c.method === 'POST'); assert.equal(first.body.observedJustNow, true); assert.equal(first.body.expectedRevision, 1);
  for (const state of ['unknown', 'paused', 'withdrawn']) { await h.page.submitNew(state, false); await h.flush(); }
  assert.equal(h.calls.filter(c => c.method === 'POST').length, 4); assert.equal(h.page.data.pending, false);
  const denied = harness({ operator: true, allowed: false }); denied.page.onShow(); await denied.flush(); await denied.page.submitNew('quiet', true);
  assert.equal(denied.page.data.authorized, false); assert.equal(denied.calls.filter(c => c.method === 'POST').length, 0);
});

test('O04 native unknown result: preserve key, query before retry, old intent cannot be reissued', async () => {
  let failWrite = true;
  const h = harness({ operator: true, hook: (path, method, _body, response) => {
    if (method === 'POST' && failWrite) return Promise.reject({ code: 'NETWORK_UNCONFIRMED' });
    if (path.startsWith('/operations/')) return { ...response, data: { state: 'unknown' } };
    return response;
  } });
  h.page.onShow(); await h.flush(); await h.page.submitNew('moderate', true); await h.flush();
  assert.equal(h.page.data.pending, true); const original = h.calls.find(c => c.method === 'POST').body;
  await h.page.queryPending(); await h.flush(); assert.equal(h.page.data.canReplace, true);
  failWrite = false; await h.page.retryPending(); await h.flush();
  const writes = h.calls.filter(c => c.method === 'POST'); assert.equal(writes.length, 2); assert.equal(writes[1].body.operationId, original.operationId);
  failWrite = true; await h.page.submitNew('busy', true); await h.flush(); await h.page.queryPending(); await h.flush();
  h.mono(60001); const count = h.calls.filter(c => c.method === 'POST').length; await h.page.retryPending();
  assert.equal(h.calls.filter(c => c.method === 'POST').length, count); assert.match(h.page.data.message, /重新观察/);
});

test('O04 native committed history always refreshes current state; failed intent persistence prevents submission', async () => {
  let lost = true;
  const h = harness({ operator: true, hook: (path, method, _b, response) => {
    if (method === 'POST' && lost) return Promise.reject({ code: 'NETWORK_UNCONFIRMED' });
    if (path.startsWith('/operations/')) return { ...response, data: { state: 'committed', result: { state: 'quiet' } } };
    return response;
  } });
  h.page.onShow(); await h.flush(); await h.page.submitNew('quiet', true); await h.flush();
  h.state(sample('withdrawn')); lost = false; await h.page.queryPending(); await h.flush();
  assert.equal(h.page.data.title, '记录已撤回'); assert.equal(h.page.data.live, false); assert.equal(h.page.data.pending, false);
  const noStorage = harness({ operator: true, storageFails: true }); noStorage.page.onShow(); await noStorage.flush(); await noStorage.page.submitNew('busy', true); await noStorage.flush();
  assert.equal(noStorage.calls.filter(c => c.method === 'POST').length, 0); assert.equal(Boolean(noStorage.page._pending), false);
});

test('O04 native confirmation freezes visible revision even when polling changes current state', async () => {
  let modal;
  const h = harness({ operator: true, modalHook: options => { modal = options; } });
  h.page.onShow(); await h.flush(); h.page.choose({ currentTarget: { dataset: { state: 'quiet' } } });
  h.state({ ...sample('busy'), revision: 2 }); await h.page.refreshNow();
  modal.success({ confirm: true }); await h.flush();
  assert.equal(h.calls.find(c => c.method === 'POST').body.expectedRevision, 1);
});

test('O02/O05 native page expiry and network failure retain original timestamp without freshness', async () => {
  let failed = false;
  const h = harness({ hook: (_p, _m, _b, response) => failed ? Promise.reject({ code: 'NETWORK_UNCONFIRMED' }) : response });
  h.page.onShow(); await h.flush(); assert.equal(h.page.data.live, true);
  const timestamp = h.page.data.observedText; h.mono(900000); h.page.render(true);
  assert.equal(h.page.data.title, '忙闲信息已过期'); assert.equal(h.page.data.observedText, timestamp);
  failed = true; await h.page.refreshNow(); assert.equal(h.page.data.live, false); assert.equal(h.page.data.observedText, timestamp); assert.match(h.page.data.history, /上次记录/);
});
