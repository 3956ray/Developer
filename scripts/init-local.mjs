import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { root, keyNames } from '../server/config.mjs';

export function initialize(environment, gymId, port = 8787) {
  if (!['test', 'store'].includes(environment) || !/^[a-z][a-z0-9-]{0,47}$/.test(gymId)) throw new Error('Explicit test/store and gym-id required');
  const dir = resolve(root, '.runtime', environment, gymId);
  if (existsSync(dir)) throw new Error('Existing state is never overwritten');
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const keys = { environment, gymId, ...Object.fromEntries(keyNames.map(n => [n, randomBytes(32).toString('hex')])) };
  writeFileSync(resolve(dir, 'keys.json'), JSON.stringify(keys), { flag: 'wx', mode: 0o600 });
  const c = { environment, gymId, stateDir: dir, simulation: false, timeZone: 'Asia/Taipei', host: '127.0.0.1', port };
  const path = resolve(dir, 'config.json');
  writeFileSync(path, JSON.stringify(c, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  return path;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { console.log(initialize(process.argv[2], process.argv[3])); }
  catch { process.stderr.write('INIT_REJECTED: scope invalid or directory already exists\n'); process.exitCode = 1; }
}
