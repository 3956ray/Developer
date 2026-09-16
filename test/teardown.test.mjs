import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {dirname} from 'node:path';
import {setupIdentity} from './identity-support.mjs';
import {startService,spawn,stop} from './process-support.mjs';
import {root} from '../server/config.mjs';
test('fixture teardown waits for every owned service and worker close before DB/directory cleanup',async t=>{
 const x=setupIdentity(t),one=await startService(x.path),two=await startService(x.path),worker=spawn(process.execPath,['test/teardown-child.mjs',x.path,'idle'],{cwd:root,stdio:'ignore'});
 let checked=false;x.beforeRemove(()=>{assert.ok([one.p,two.p,worker].every(p=>p.exitCode!==null||p.signalCode!==null));assert.equal(existsSync(dirname(x.path)),true);checked=true;});
 t.after(()=>{assert.equal(checked,true);assert.equal(existsSync(dirname(x.path)),false);});
});
test('startup timeout, malformed readiness and early exit stop owned children before returning failure',async t=>{
 const x=setupIdentity(t);for(const mode of ['idle','bad-ready','early-exit'])await assert.rejects(startService(x.path,{args:['test/teardown-child.mjs',x.path,mode],timeout:200}));
 t.after(()=>assert.equal(existsSync(dirname(x.path)),false));
});
test('assertion failure subprocess exits nonzero without hanging; later hook verifies stopped child and removed fixture',()=>{
 const env={...process.env};delete env.NODE_TEST_CONTEXT;
 const result=spawnSync(process.execPath,['--test','test/teardown-driver.mjs'],{cwd:root,env,encoding:'utf8',timeout:40000});assert.equal(result.error,undefined);assert.equal(result.status,1);assert.match(result.stdout,/INJECTED_ASSERTION_FAILURE/);assert.match(result.stdout,/TEARDOWN_COMPLETE/);assert.doesNotMatch(result.stdout,/ENOTEMPTY/);
});
