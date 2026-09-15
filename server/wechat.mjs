import { ApiError, fail, hmac, parseStrict } from './protocol.mjs';

export function codeDigest(c, code) { return hmac(c.keys.intentHmac, `login-code\0${c.environment}\0${c.gymId}\0${c.identity.appId}\0${code}`); }
export function createWechatAdapter(c, transport = fetch) {
  return async code => {
    if (c.identity.mode !== 'wechat' || !c.appSecret) fail(503, 'IDENTITY_NOT_CONFIGURED');
    const url = new URL('https://api.weixin.qq.com/sns/jscode2session');
    url.search = new URLSearchParams({ appid: c.identity.appId, secret: c.appSecret, js_code: code, grant_type: 'authorization_code' }).toString();
    try {
      const response = await transport(url, { redirect: 'error', signal: AbortSignal.timeout(5000) });
      if (!response.ok) fail(503, 'PLATFORM_UNAVAILABLE');
      // Stream limit also bounds hostile/accidental oversized platform responses.
      let size = 0; const chunks = [];
      for await (const chunk of response.body) { size += chunk.length; if (size > 16384) fail(503, 'PLATFORM_UNAVAILABLE'); chunks.push(chunk); }
      const data = parseStrict(Buffer.concat(chunks).toString('utf8'));
      if (!data || Array.isArray(data) || typeof data !== 'object') fail(503, 'PLATFORM_UNAVAILABLE');
      if (data.errcode !== undefined && data.errcode !== 0) {
        if ([40029, 40163, 40226].includes(data.errcode)) fail(400, 'LOGIN_CODE_INVALID');
        fail(503, 'PLATFORM_UNAVAILABLE');
      }
      if (typeof data.openid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(data.openid) ||
          typeof data.session_key !== 'string' || !/^[A-Za-z0-9+/]{22}==$/.test(data.session_key) || Buffer.from(data.session_key, 'base64').length !== 16) fail(503, 'PLATFORM_UNAVAILABLE');
      return { openId: data.openid }; // session_key/unionid never persisted or returned.
    } catch (error) {
      if (error instanceof ApiError && error.code === 'LOGIN_CODE_INVALID') throw error;
      fail(503, 'PLATFORM_UNAVAILABLE'); // Never propagate URL/body/network exception diagnostics.
    }
  };
}
export function createAdapter(db, c, transport) {
  if (c.identity.mode === 'test') {
    if (c.environment !== 'test' || c.simulation !== true) fail(503, 'TEST_IDENTITY_FORBIDDEN');
    return async code => {
      const row = db.prepare('SELECT * FROM test_login_fixtures WHERE code_hmac=?').get(codeDigest(c, code));
      if (!row || row.outcome === 'invalid') fail(400, 'LOGIN_CODE_INVALID');
      if (row.outcome === 'platform') fail(503, 'PLATFORM_UNAVAILABLE');
      return { openId: row.synthetic_subject };
    };
  }
  return createWechatAdapter(c, transport);
}
