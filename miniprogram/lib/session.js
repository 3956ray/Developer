const config = require('../config');
const STORAGE = 'gym.business-session.v1';
function configured() {
  return ['test', 'store'].includes(config.environment) &&
    (config.environment === 'test' ? /^http:\/\/127\.0\.0\.1:\d+$/.test(config.baseUrl) : /^https:\/\/[^/]+$/.test(config.baseUrl));
}
function scope() { return config.environment + ':' + config.baseUrl; }
function load() {
  try { const s = wx.getStorageSync(STORAGE); return s && s.scope === scope() && /^[A-Za-z0-9_-]{43}$/.test(s.token) ? s.token : null; }
  catch (_) { return null; }
}
function save(token) { wx.setStorageSync(STORAGE, { scope: scope(), token }); }
function clear() { for (const key of [STORAGE,'gym.member-intent.v1','gym.observation-write.v1','gym.operator-member-operation.v1','gym.schedule-operation.v1']) { try { wx.removeStorageSync(key); } catch (_) {} } }
function request(path, method, data, token, scheme = 'Bearer') {
  if (!configured()) return Promise.reject({ code: 'NOT_CONFIGURED' });
  return new Promise((resolve, reject) => wx.request({
    url: config.baseUrl + '/v1' + path, method, data, timeout: 10000,
    header: { 'content-type': 'application/json', ...(token ? { Authorization: scheme + ' ' + token } : {}) },
    success(response) {
      const b = response.data;
      if (response.statusCode >= 200 && response.statusCode < 300 && b && b.ok === true && Number.isFinite(Date.parse(b.serverNow))) resolve(b);
      else reject({ status: response.statusCode, code: b && b.error ? b.error.code : 'INVALID_RESPONSE' });
    },
    fail() { reject({ code: 'NETWORK_UNCONFIRMED' }); }
  }));
}
function loginCode() {
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
module.exports = { config, configured, load, save, clear, request, loginCode, operationId };
