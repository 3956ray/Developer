import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
function harness({ demo = true, confirm = true, namespacePatch = {}, exchangeFailure = false, deferHealth = false } = {}) {
  const config = { environment: 'test', baseUrl: 'http://127.0.0.1:8787', gymId: 'demo-native', identityMode: demo ? 'test' : 'wechat', demo: { enabled: demo } };
  const storage = new Map(), calls = [], timers = []; let page, done, logins = 0, release;
  const namespace = { environment: 'test', gymId: config.gymId, simulation: demo, identityMode: config.identityMode, demoEnabled: demo, ...namespacePatch };
  const token = randomBytes(32).toString('base64url'); const serverNow = '2026-09-16T00:00:00.000Z';
  const wx = {
    getStorageSync: k => storage.get(k), setStorageSync: (k,v) => storage.set(k,v), removeStorageSync: k => storage.delete(k),
    showModal: options => { done = options.success({ confirm }); },
    login: options => { logins++; options.success({ code: 'official-code' }); },
    request: options => {
      calls.push({ path: new URL(options.url).pathname, body: options.data });
      const reply = () => {
        if (exchangeFailure && options.url.endsWith('/sessions/exchange')) { options.fail(); return; }
        options.success({ statusCode: 200, data: { ok: true, namespace, serverNow, data: {
          ...namespace, token, expiresAt: '2026-09-23T00:00:00.000Z', idleExpiresAt: '2026-09-17T00:00:00.000Z', authAt: serverNow, sessionRevision: 1
        } } });
      };
      if (deferHealth && options.url.endsWith('/health')) release = reply; else reply();
    }
  };
  const sessionContext = { module: { exports: {} }, require: () => config, wx, ArrayBuffer, Uint8Array };
  vm.runInNewContext(readFileSync('miniprogram/lib/session.js', 'utf8'), sessionContext);
  const api = sessionContext.module.exports;
  vm.runInNewContext(readFileSync('miniprogram/pages/me/index.js', 'utf8'), { require: () => api, wx, Page: p => { page = p; }, setInterval: f => { timers.push(f); return 1; }, clearInterval() {}, setTimeout: f => { timers.push(f); return 1; }, clearTimeout() {} });
  page.setData = d => Object.assign(page.data, d); page.onLoad(); page.onShow();
  return { page, api, config, storage, calls, token, done: () => done, logins: () => logins, release: () => release(), timers };
}
const enter = h => { const code = randomBytes(32).toString('base64url'); h.page.demoCodeInput({ detail: { value: code } }); return code; };

test('DM01/02 native explicit demo consent checks health then exchanges; no wx.login or credential storage; fresh auth requires new input', async () => {
  const h = harness(), code = enter(h); h.page.login(); await h.done();
  assert.equal(h.logins(), 0); assert.equal(h.page.data.authenticated, true);
  assert.deepEqual(h.calls.map(c => c.path), ['/health', '/v1/sessions/exchange']);
  assert.equal(h.calls[1].body.code === code, true); assert.equal(h.api.load() === h.token, true);
  assert.match(h.page.data.status, /演示身份/); assert.equal(h.page._demoCode, '');
  assert.equal(JSON.stringify(h.page.data).includes(code), false); assert.equal(JSON.stringify([...h.storage]).includes(code), false);
  h.page.login(); await h.done(); assert.equal(h.calls.length, 2);
  const fresh = enter(h); h.page.login(); await h.done(); assert.equal(h.calls.at(-1).body.code === fresh, true);
});

test('DM02 native refusal and hidden health response send no exchange or wx.login, discard entered credential', async () => {
  const refused = harness({ confirm: false }); enter(refused); refused.page.login(); await refused.done();
  assert.equal(refused.calls.length, 0); assert.equal(refused.logins(), 0); assert.equal(refused.page._demoCode, '');
  const hidden = harness({ deferHealth: true }); enter(hidden); hidden.page.login(); hidden.page.onHide(); hidden.release(); await hidden.done();
  assert.equal(hidden.calls.length, 1); assert.equal(hidden.logins(), 0); assert.equal(hidden.page.data.authenticated, false);
});

test('DM01 native wrong gym/environment/identity/simulation/demo metadata clears cache and closes demo actions before ticket exchange', async () => {
  for (const namespacePatch of [{ gymId: 'demo-other' }, { environment: 'store' }, { identityMode: 'wechat' }, { simulation: false }, { demoEnabled: false }]) {
    const h = harness({ namespacePatch }); h.api.save(h.token); enter(h); h.page.login(); await h.done();
    assert.equal(h.page.data.authenticated, false); assert.equal(h.page.data.configured, false); assert.equal(h.api.load(), null);
    assert.equal(h.calls.length, 1); h.page.login(); assert.equal(h.calls.length, 1);
    await assert.rejects(() => h.api.request('/operator/role', 'GET', undefined, h.token), e => e.code === 'SCOPE_MISMATCH');
  }
});

test('DM01 native cache includes gym and identity mode; store cannot configure demo; official failure never falls back', async () => {
  const h = harness(); h.api.save(h.token); h.config.gymId = 'demo-other'; assert.equal(h.api.load(), null);
  h.config.gymId = 'demo-native'; h.config.identityMode = 'wechat'; assert.equal(h.api.load(), null); assert.equal(h.api.configured(), false);
  h.config.environment = 'store'; h.config.baseUrl = 'https://example.invalid'; assert.equal(h.api.configured(), false);
  const official = harness({ demo: false, exchangeFailure: true }); official.page.login(); await official.done();
  assert.equal(official.logins(), 1); assert.equal(official.calls.length, 1); assert.equal(official.page.data.authenticated, false);
  assert.equal(official.calls[0].body.code, 'official-code');
});

test('DM02 native uncertain exchange discards old credential, requests new one, and never auto logs in on refresh', async () => {
  const h = harness({ exchangeFailure: true }); enter(h); h.page.login(); await h.done();
  assert.equal(h.page.data.authenticated, false); assert.equal(h.page._demoCode, ''); assert.match(h.page.data.detail, /新的演示凭证/);
  h.page.onHide(); h.page.onShow(); await h.page.refreshNow();
  assert.equal(h.calls.length, 2); assert.equal(h.logins(), 0);
});
