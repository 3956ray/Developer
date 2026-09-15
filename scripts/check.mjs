import { readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { root } from '../server/config.mjs';
function visit(dir) {
  for (const name of readdirSync(dir)) {
    if (['.git', '.runtime', 'node_modules'].includes(name)) continue;
    const path = resolve(dir, name);
    if (statSync(path).isDirectory()) visit(path);
    else if (/\.(mjs|js)$/.test(name)) assert.equal(spawnSync(process.execPath, ['--check', path]).status, 0, path);
    else if (name.endsWith('.json')) JSON.parse(readFileSync(path));
  }
}
visit(root);
const sources = JSON.parse(readFileSync(resolve(root, 'doc/baseline/sources.json')));
for (const source of sources) {
  const path = resolve(root, 'doc/baseline', source.path.split('/').at(-1));
  assert.equal(createHash('sha256').update(readFileSync(path)).digest('hex'), source.sha256);
}
const app = JSON.parse(readFileSync(resolve(root, 'miniprogram/app.json')));
for (const page of app.pages) for (const extension of ['js', 'json', 'wxml']) assert.ok(statSync(resolve(root, 'miniprogram', `${page}.${extension}`)).isFile());
assert.equal(JSON.parse(readFileSync(resolve(root, 'project.config.json'))).miniprogramRoot, 'miniprogram/');
console.log('PASS: JavaScript syntax, JSON, baseline hashes, native page references (not WeChat compilation)');
