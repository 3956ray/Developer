const config = require('../config');
const STORAGE = 'gym.business-session.v1';
let namespaceRejected = false;
function demoMode() { return config.demo && config.demo.enabled === true && config.environment === 'test' && config.identityMode === 'test' && /^demo-[a-z0-9-]+$/.test(config.gymId || ''); }
function namespaceMatches(n, publicRead = false) {
  if (!n) return !demoMode() && !config.gymId && !config.identityMode;
  return n.environment === config.environment && (!config.gymId || n.gymId === config.gymId) &&
    (n.identityMode === (config.identityMode || 'wechat') || (!demoMode() && !config.identityMode && publicRead && ['disabled', 'test', 'wechat'].includes(n.identityMode))) && n.demoEnabled === Boolean(demoMode()) &&
    (demoMode() ? n.simulation === true : !(n.simulation && config.environment !== 'test'));
}
function configured() {
  if (config.demo && config.demo.enabled !== false && !demoMode()) return false;
  return ['test', 'store'].includes(config.environment) &&
    (config.environment === 'test' ? /^http:\/\/127\.0\.0\.1:\d+$/.test(config.baseUrl) : /^https:\/\/[^/]+$/.test(config.baseUrl));
}
function scope() { return [config.environment, config.baseUrl, config.gymId || '', config.identityMode || 'wechat', demoMode() ? 'demo' : 'official'].join(':'); }
function load() {
  try { const s = wx.getStorageSync(STORAGE); return s && s.scope === scope() && /^[A-Za-z0-9_-]{43}$/.test(s.token) ? s.token : null; }
  catch (_) { return null; }
}
function save(token) { wx.setStorageSync(STORAGE, { scope: scope(), token }); }
function clear() { for (const key of [STORAGE,'gym.member-intent.v1','gym.observation-write.v1','gym.operator-member-operation.v1','gym.schedule-operation.v1']) { try { wx.removeStorageSync(key); } catch (_) {} } }
function request(path, method, data, token, scheme = 'Bearer') {
  if (!configured()) return Promise.reject({ code: 'NOT_CONFIGURED' });
  // Public browsing has no identity prerequisite; authenticated/demo requests stay strict.
  const publicRead = !token && method === 'GET' && /^(\/health|\/venue|\/observations\/current|\/schedule|\/privacy)(\?|$)/.test(path);
  if (namespaceRejected && path !== '/health' && (demoMode() || !publicRead)) return Promise.reject({ code: 'SCOPE_MISMATCH' });
  return new Promise((resolve, reject) => wx.request({
    url: config.baseUrl + (path === '/health' ? path : '/v1' + path), method, data, timeout: 10000,
    header: { 'content-type': 'application/json', ...(token ? { Authorization: scheme + ' ' + token } : {}) },
    success(response) {
      const b = response.data;
      if (b && !namespaceMatches(b.namespace, publicRead)) { namespaceRejected = true; clear(); reject({ code: 'SCOPE_MISMATCH' }); return; }
      if (path === '/health' && b && b.ok === true && namespaceMatches(b.namespace, publicRead)) namespaceRejected = false;
      if (response.statusCode >= 200 && response.statusCode < 300 && b && b.ok === true && Number.isFinite(Date.parse(b.serverNow))) resolve(b);
      else reject({ status: response.statusCode, code: b && b.error ? b.error.code : 'INVALID_RESPONSE' });
    },
    fail() { reject({ code: 'NETWORK_UNCONFIRMED' }); }
  }));
}
function loginCode() {
  if (demoMode()) return Promise.reject({ code: 'DEMO_CREDENTIAL_REQUIRED' });
  return new Promise((resolve, reject) => wx.login({ timeout: 10000,
    success(r) { r.code ? resolve(r.code) : reject({ code: 'LOGIN_CODE_UNAVAILABLE' }); },
    fail() { reject({ code: 'LOGIN_CODE_UNAVAILABLE' }); }
  }));
}
function operationId() {
  return new Promise((resolve, reject) => {
    if (typeof wx.getRandomValues !== 'function') { reject({ code: 'SECURE_RANDOM_UNAVAILABLE' }); return; }
    wx.getRandomValues({ length: 16, success(r) {
      if (!(r.randomValues instanceof ArrayBuffer) || r.randomValues.byteLength !== 16) { reject({ code: 'SECURE_RANDOM_UNAVAILABLE' }); return; }
      const bytes = new Uint8Array(r.randomValues); bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
      const h = Array.from(bytes, x => x.toString(16).padStart(2, '0')).join(''); resolve(h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20));
    }, fail() { reject({ code: 'SECURE_RANDOM_UNAVAILABLE' }); } });
  });
}
module.exports = { config, configured, scope, demoMode, namespaceMatches, load, save, clear, request, loginCode, operationId };
