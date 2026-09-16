import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {dirname} from 'node:path';
import {setupIdentity} from './identity-support.mjs';
import {startService} from './process-support.mjs';
test('deliberate assertion failure still cleans owned writer first',async t=>{
 const x=setupIdentity(t),child=await startService(x.path,{args:['test/teardown-child.mjs',x.path,'writer']});
 t.after(()=>{assert.ok(child.p.exitCode!==null||child.p.signalCode!==null);assert.equal(existsSync(dirname(x.path)),false);console.log('TEARDOWN_COMPLETE');});
 assert.fail('INJECTED_ASSERTION_FAILURE');
});
