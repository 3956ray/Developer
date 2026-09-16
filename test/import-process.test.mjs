import test from 'node:test';
import assert from 'node:assert/strict';
import { writeFileSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { setupDemo } from './demo-support.mjs';
import { roleWrite, intent } from './identity-support.mjs';
import { fork, bounded } from './process-support.mjs';
import { createScheduleImport } from '../server/schedule-import.mjs';
import { createScheduleService } from '../server/schedules.mjs';
async function setup(t){const x=setupDemo(t),op=await x.service.login(x.ticket('operator-a'));x.service.changeRole(roleWrite(x.c,op.userId,Date.now()));const i=createScheduleImport(x.db,x.c,x.service);i.register({sourceId:'synthetic',sourceLabel:'合成外部源',gymId:x.c.gymId,timeZone:x.c.timeZone,confirmed:true});const body={schemaVersion:1,sourceId:'synthetic',sourceRevision:1,gymId:x.c.gymId,timeZone:x.c.timeZone,capturedAt:new Date().toISOString(),mode:'full',coverage:{startDate:'2026-09-17',endDate:'2026-09-18'},courses:[]};return{...x,op,i,body,text:JSON.stringify(body),s:createScheduleService(x.db,x.c,x.service)};}
let sequence=0;
function child(x,fixture){const path=x.c.stateDir+'/import-worker-'+(++sequence)+'.json';writeFileSync(path,JSON.stringify(fixture),{mode:0o600});const p=fork('test/demo-process-worker.mjs',[x.path,path],{stdio:['ignore','ignore','ignore','ipc']});const queue=[],waiting=[];p.on('message',m=>waiting.length?waiting.shift()(m):queue.push(m));return{p,next:()=>queue.length?Promise.resolve(queue.shift()):bounded(new Promise(resolve=>waiting.push(resolve)))};}

test('DM09/RP3 two actual apply/replan processes serialize one atomic result/successor and replay safely',async t=>{
 const x=await setup(t),p=x.i.plan(x.op.token,x.text),input={batchId:p.batchId,contentHash:p.contentHash,replaceDraftConfirmed:true};
 const workers=[child(x,{action:'apply',token:x.op.token,input}),child(x,{action:'apply',token:x.op.token,input})];for(const w of workers)assert.equal(await w.next(),'ready');for(const w of workers)w.p.send('go');const results=await Promise.all(workers.map(w=>w.next()));assert.ok(results.every(r=>r.ok));assert.deepEqual(results[0].result,results[1].result);assert.equal(x.s.getDraft(x.op.token).draftRevision,1);assert.equal(x.db.prepare("SELECT count(*) n FROM audit WHERE action='schedule.import.apply'").get().n,1);
 const text=JSON.stringify({...x.body,sourceRevision:2}),old=x.i.plan(x.op.token,text);x.s.save(x.op.token,{...intent(Date.now(),1),coverage:x.body.coverage,courses:[]});
 const replacement={batchId:old.batchId,expectedDraft:2,expectedPublication:'absent'},race=[child(x,{action:'replan',token:x.op.token,text,replacement}),child(x,{action:'replan',token:x.op.token,text,replacement})];for(const w of race)assert.equal(await w.next(),'ready');for(const w of race)w.p.send('go');const replanned=await Promise.all(race.map(w=>w.next()));assert.equal(replanned.filter(r=>r.ok).length,1);assert.equal(replanned.find(r=>!r.ok).code,'CANDIDATE_REPLACED');assert.equal(x.db.prepare("SELECT count(*) n FROM import_batches WHERE source_revision=2 AND state='candidate'").get().n,1);
 t.diagnostic('two real processes: apply one audit/revision; explicit replan one usable successor');
});

test('DM09 actual CLI register/plan/apply/status, private-token permissions and SQLite lock fail closed',async t=>{
 const x=await setup(t),sourceFile=x.c.stateDir+'/source.json',sessionFile=x.c.stateDir+'/session.json';writeFileSync(sourceFile,x.text,{mode:0o600});writeFileSync(sessionFile,JSON.stringify({token:x.op.token}),{mode:0o600});
 const cli=args=>{const r=spawnSync(process.execPath,['scripts/import-schedule.mjs',...args,'--session-file',sessionFile],{encoding:'utf8',timeout:5000});return{status:r.status,result:JSON.parse((r.stdout||r.stderr).trim())};};
 const registration=x.c.stateDir+'/register.json';writeFileSync(registration,JSON.stringify({sourceId:'synthetic',sourceLabel:'合成外部源',gymId:x.c.gymId,timeZone:x.c.timeZone}),{mode:0o600});
 const registered=spawnSync(process.execPath,['scripts/import-schedule.mjs','register',x.path,registration,'--confirm'],{encoding:'utf8',timeout:5000});assert.equal(registered.status,0);
 const p=cli(['plan',x.path,sourceFile]);assert.equal(p.status,0);assert.equal(x.s.getDraft(x.op.token).draftRevision,'absent');
 x.db.exec('BEGIN IMMEDIATE');try{const blocked=cli(['apply',x.path,p.result.data.batchId,p.result.data.contentHash,'--confirm-replace']);assert.equal(blocked.status,1);assert.equal(blocked.result.status,503);}finally{x.db.exec('ROLLBACK');}
 assert.equal(x.i.status(x.op.token,p.result.data.batchId).state,'candidate');const applied=cli(['apply',x.path,p.result.data.batchId,p.result.data.contentHash,'--confirm-replace']);assert.equal(applied.status,0);assert.equal(cli(['status',x.path,p.result.data.batchId]).result.data.state,'applied');
 const {chmodSync}=await import('node:fs');chmodSync(sessionFile,0o644);const rejected=cli(['status',x.path,p.result.data.batchId]);assert.equal(rejected.status,1);assert.equal(rejected.result.error,'PRIVATE_INPUT_REQUIRED');
 assert.equal(JSON.stringify([p,applied,rejected]).includes(x.op.token),false);
});

test('DM09 actual process killed after atomic apply before result delivery recovers by batch status without second write',async t=>{
 const {stop}=await import('./process-support.mjs'),{openDatabase}=await import('../server/database.mjs'),{createIdentityService}=await import('../server/identity.mjs');
 const x=await setup(t),p=x.i.plan(x.op.token,x.text),input={batchId:p.batchId,contentHash:p.contentHash,replaceDraftConfirmed:true};
 const worker=child(x,{action:'apply-drop',token:x.op.token,input});assert.equal(await worker.next(),'ready');worker.p.send('go');assert.equal(await worker.next(),'applied-no-result');await stop(worker.p,'SIGKILL');
 x.db.close();const db=openDatabase(x.c);try{const importer=createScheduleImport(db,x.c,createIdentityService(db,x.c));assert.equal(importer.status(x.op.token,p.batchId).state,'applied');assert.equal(importer.apply(x.op.token,input).draftRevision,1);assert.equal(db.prepare("SELECT count(*) n FROM audit WHERE action='schedule.import.apply'").get().n,1);}finally{db.close();}
});
