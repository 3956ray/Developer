import { readFileSync, realpathSync, statSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const keyNames = ['memberHmac', 'pairLookupHmac', 'pairEncryption', 'intentHmac'];
export function loadConfig(path) {
  if (!path) throw new Error('CONFIG_REQUIRED');
  const c = JSON.parse(readFileSync(path, 'utf8'));
  if (!['test', 'store'].includes(c.environment) || !/^[a-z][a-z0-9-]{0,47}$/.test(c.gymId)) throw new Error('CONFIG_SCOPE_INVALID');
  const identity = c.identity ?? { mode: 'disabled' };
  if (!['disabled', 'test', 'wechat'].includes(identity.mode)) throw new Error('IDENTITY_MODE_INVALID');
  if (identity.mode === 'test') {
    if (c.environment !== 'test' || c.simulation !== true || identity.appId !== 'test-app') throw new Error('TEST_IDENTITY_FORBIDDEN');
  } else if (c.simulation !== false) throw new Error('SIMULATION_NOT_SUPPORTED');
  if (identity.mode === 'wechat' && !/^wx[0-9a-f]{16}$/.test(identity.appId)) throw new Error('APPID_INVALID');
  if (c.demo !== undefined && (!c.demo || typeof c.demo !== 'object' || Array.isArray(c.demo) || Object.keys(c.demo).some(k => k !== 'enabled') || typeof c.demo.enabled !== 'boolean')) throw new Error('DEMO_CONFIG_INVALID');
  if (c.demo?.enabled && (c.environment !== 'test' || c.simulation !== true || identity.mode !== 'test' || identity.appId !== 'test-app' || !c.gymId.startsWith('demo-'))) throw new Error('DEMO_FORBIDDEN');
  if (c.host !== '127.0.0.1' || !Number.isInteger(c.port) || c.port < 0 || c.port > 65535) throw new Error('CONFIG_LISTENER_INVALID');
  if (typeof c.timeZone !== 'string' || !c.timeZone) throw new Error('TIMEZONE_INVALID');
  try { new Intl.DateTimeFormat('en',{timeZone:c.timeZone}).format(0); } catch { throw new Error('TIMEZONE_INVALID'); }
  const dir = resolve(root, '.runtime', c.environment, c.gymId);
  if (c.stateDir !== dir || realpathSync(dir) !== dir) throw new Error('CONFIG_STORAGE_SCOPE_INVALID');
  const keyFile = resolve(dir, 'keys.json');
  if (realpathSync(keyFile) !== keyFile || (statSync(keyFile).mode & 0o077)) throw new Error('KEY_FILE_PERMISSIONS');
  const k = JSON.parse(readFileSync(keyFile, 'utf8'));
  if (k.environment !== c.environment || k.gymId !== c.gymId) throw new Error('KEY_SCOPE_MISMATCH');
  const values = keyNames.map(n => k[n]);
  if (values.some(v => typeof v !== 'string' || !/^[a-f0-9]{64}$/.test(v)) || new Set(values).size !== values.length) throw new Error('KEY_MATERIAL_INVALID');
  let appSecret;
  if (identity.mode === 'wechat') {
    const path = resolve(dir, 'wechat-secret.txt');
    if (realpathSync(path) !== path || (statSync(path).mode & 0o077)) throw new Error('APP_SECRET_PERMISSIONS');
    appSecret = readFileSync(path, 'utf8').trim();
    if (!/^[a-zA-Z0-9]{32,128}$/.test(appSecret)) throw new Error('APP_SECRET_INVALID');
  }
  return { ...c, identity, appSecret, databasePath: resolve(dir, 'state.sqlite'), keys: k,
    keyFingerprint: createHash('sha256').update(JSON.stringify(values)).digest('hex') };
}
