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
  if (c.simulation !== false) throw new Error('SIMULATION_NOT_SUPPORTED');
  if (c.host !== '127.0.0.1' || !Number.isInteger(c.port) || c.port < 0 || c.port > 65535) throw new Error('CONFIG_LISTENER_INVALID');
  if (c.timeZone !== 'Asia/Taipei') throw new Error('CP0_TIMEZONE_INVALID');
  const dir = resolve(root, '.runtime', c.environment, c.gymId);
  if (c.stateDir !== dir || realpathSync(dir) !== dir) throw new Error('CONFIG_STORAGE_SCOPE_INVALID');
  const keyFile = resolve(dir, 'keys.json');
  if (realpathSync(keyFile) !== keyFile || (statSync(keyFile).mode & 0o077)) throw new Error('KEY_FILE_PERMISSIONS');
  const k = JSON.parse(readFileSync(keyFile, 'utf8'));
  if (k.environment !== c.environment || k.gymId !== c.gymId) throw new Error('KEY_SCOPE_MISMATCH');
  const values = keyNames.map(n => k[n]);
  if (values.some(v => typeof v !== 'string' || !/^[a-f0-9]{64}$/.test(v)) || new Set(values).size !== values.length) throw new Error('KEY_MATERIAL_INVALID');
  return { ...c, databasePath: resolve(dir, 'state.sqlite'), keys: k,
    keyFingerprint: createHash('sha256').update(JSON.stringify(values)).digest('hex') };
}
