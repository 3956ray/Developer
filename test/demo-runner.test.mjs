import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { setupDemo } from './demo-support.mjs';
import { roleWrite, intent } from './identity-support.mjs';
import { startService, stop } from './process-support.mjs';
import { createDemoRunner } from '../server/demo-runner.mjs';
import { createMemberCleanup } from '../server/member-cleanup.mjs';
import { createDemoCleanup } from '../server/demo-cleanup.mjs';
async function setup(t,options={}){
 const x=setupDemo(t);let server=await startService(x.path);const credentials={};
 async function request(path,method='GET',body,token,scheme='Bearer'){
  const r=await fetch('http://127.0.0.1:'+server.port+path,{method,headers:{'content-type':'application/json',...(token?{authorization:scheme+' '+token}:{})},body:body?JSON.stringify(body):undefined});return{status:r.status,...await r.json()};
 }
 const users={};for(const alias of ['member-a','member-b','operator-a']){const r=await request('/v1/sessions/exchange','POST',x.ticket(alias));assert.equal(r.status,201);credentials[alias]=r.data.token;users[alias]=r.data.userId;}
 x.service.changeRole(roleWrite(x.c,users['operator-a'],Date.now()));
 const runner=()=>createDemoRunner(x.db,x.c,'http://127.0.0.1:'+server.port,options);
 return{...x,credentials,users,request,runner,server:()=>server,restart:async()=>{await stop(server.p);server=await startService(x.path);}};
}

test('DM05 finite crowd play, one runner during waits, true HTTP persistence, conflict/role pause and explicit controls',async t=>{
 let release;const x=await setup(t,{sleep:()=>new Promise(r=>{release=r;})}),runner=x.runner(),r=await runner.start('crowd-v1','2026-09-17',x.credentials);
 t.diagnostic(JSON.stringify({configSha256:createHash('sha256').update(readFileSync(x.path)).digest('hex'),scope:{environment:x.c.environment,gymId:x.c.gymId,simulation:x.c.simulation,identityMode:x.c.identity.mode,demoEnabled:x.c.demo.enabled},finitePlayWaits:'controlled 60-second waits; no native TTL claim'}));
 const play=runner.play(r.runId,x.credentials,60);while(!release)await new Promise(r=>setImmediate(r));
 await assert.rejects(()=>x.runner().step(r.runId,x.credentials),e=>e.code==='RUNNER_BUSY');release();release=null;while(!release)await new Promise(r=>setImmediate(r));release();assert.equal((await play).state,'completed');
 const before=await x.request('/v1/observations/current');assert.equal(before.data.level,'busy');assert.equal(before.data.revision,3);await x.restart();assert.deepEqual((await x.request('/v1/observations/current')).data,before.data);
 const control=await x.runner().start('observation-pause-v1','2026-09-17',x.credentials,true);await x.runner().step(control.runId,x.credentials);assert.equal((await x.request('/v1/observations/current')).data.state,'paused');
 const withdrawn=await x.runner().start('observation-withdraw-v1','2026-09-17',x.credentials,true);x.service.changeRole(roleWrite(x.c,x.users['operator-a'],Date.now(),1,'revoke'));
 await assert.rejects(()=>x.runner().step(withdrawn.runId,x.credentials),e=>e.status===403);assert.equal(x.runner().status(withdrawn.runId).state,'paused');await x.restart();
 const login=await x.request('/v1/sessions/exchange','POST',x.ticket('operator-a'));x.credentials['operator-a']=login.data.token;
 await assert.rejects(()=>x.runner().resume(withdrawn.runId,x.credentials,'retry'),e=>e.status===403);assert.equal((await x.request('/v1/observations/current')).data.state,'paused');
});

test('DM05 committed write before progress failure reconciles original operation after restart; unknown expired intent never rewritten',async t=>{
 let fail=true;const x=await setup(t,{afterWrite:()=>{if(fail){fail=false;throw new Error('injected');}}});const r=await x.runner().start('crowd-v1','2026-09-17',x.credentials);
 await assert.rejects(()=>x.runner().step(r.runId,x.credentials));const step=x.db.prepare('SELECT * FROM demo_steps').get(),id=JSON.parse(step.request_json).operationId;assert.equal((await x.request('/v1/observations/current')).data.revision,1);
 await x.restart();
 x.service.changeRole(roleWrite(x.c,x.users['operator-a'],Date.now(),1,'revoke'));
 await assert.rejects(()=>x.runner().resume(r.runId,x.credentials),e=>e.status===403);
 assert.throws(()=>x.runner().cancel(r.runId,true),e=>e.code==='OUTCOME_REVIEW_REQUIRED');
 x.service.changeRole(roleWrite(x.c,x.users['operator-a'],Date.now(),2,'grant')); // Explicit maintainer action, never performed by resume.
 assert.equal((await x.runner().resume(r.runId,x.credentials)).stepId,1);assert.equal((await x.request('/v1/observations/current')).data.revision,1);assert.equal(JSON.parse(x.db.prepare('SELECT request_json FROM demo_steps').get().request_json).operationId,id);
 const pending={operationId:randomBytes(16).toString('hex'),requestCreatedAt:new Date(Date.now()-90000).toISOString(),expectedRevision:1,level:'moderate',observedJustNow:true};
 // A valid unsent prepared request is aged after preparation; no server clock/TTL bypass.
 pending.operationId=(await import('node:crypto')).randomUUID();
 x.db.prepare("INSERT INTO demo_steps VALUES(?,?,?,?,?,?,?,?,'sent',NULL)").run(r.runId,1,step.actor_alias,step.actor_hmac,step.path,step.method,step.operation_type,JSON.stringify(pending));
 assert.equal((await x.runner().resume(r.runId,x.credentials,'retry')).state,'needs_review');assert.equal((await x.request('/v1/observations/current')).data.revision,1);
 assert.throws(()=>x.runner().cancel(r.runId,true),e=>e.code==='OUTCOME_REVIEW_REQUIRED');
});

test('DM05 manual state change pauses rather than overwrites; restart does not execute remaining scenario',async t=>{
 const x=await setup(t),r=await x.runner().start('crowd-v1','2026-09-17',x.credentials);await x.runner().step(r.runId,x.credentials);
 const read=await x.request('/v1/observations/current');await x.request('/v1/operator/observations','POST',{...intent(Date.parse(read.serverNow),1),level:'busy',observedJustNow:true},x.credentials['operator-a']);
 await assert.rejects(()=>x.runner().step(r.runId,x.credentials),e=>e.code==='SCENARIO_STATE_CHANGED');await x.restart();assert.equal((await x.request('/v1/observations/current')).data.revision,2);assert.equal(x.runner().status(r.runId).state,'paused');
});

test('DM03/06 real member scenario binds/revokes/restores then explicit second-member conflict; unbind/delete do not resurrect registry or role',async t=>{
 const x=await setup(t),r=await x.runner().start('membership-v1','2026-09-17',x.credentials);
 for(let n=0;n<5;n++)await x.runner().step(r.runId,x.credentials);
 assert.equal((await x.request('/v1/me/membership','GET',undefined,x.credentials['member-a'])).data.derivedState,'valid');
 await x.runner().step(r.runId,x.credentials);await assert.rejects(()=>x.runner().step(r.runId,x.credentials),e=>e.code==='EXPLICIT_CONFLICT_CONFIRMATION_REQUIRED');
 await assert.rejects(()=>x.runner().resume(r.runId,x.credentials,'member-conflict'),e=>e.code==='BINDING_CONFLICT');x.runner().cancel(r.runId,true);
 const profile=(await x.request('/v1/me/membership','GET',undefined,x.credentials['member-a'])).data;
 await x.request('/v1/operator/members/'+profile.bindingId+'/revoke','POST',{...intent(Date.now(),{binding:profile.bindingRevision,registry:profile.registryRevision}),reasonCategory:'qualification-withdrawn'},x.credentials['operator-a']);
 const unbind=await x.runner().start('lifecycle-unbind-v1','2026-09-17',x.credentials,true);await assert.rejects(()=>x.runner().step(unbind.runId,x.credentials),e=>e.code==='EXPLICIT_SENSITIVE_CONFIRMATION_REQUIRED');await x.runner().resume(unbind.runId,x.credentials,'sensitive');
 const old=x.users['member-a'];x.service.changeRole(roleWrite(x.c,old,Date.now()));x.credentials.deletionReceipt=randomBytes(32).toString('hex');
 const removal=await x.runner().start('lifecycle-delete-v1','2026-09-17',x.credentials,true);await x.runner().step(removal.runId,x.credentials,'sensitive');
 assert.equal((await x.request('/v1/session','GET',undefined,x.credentials['member-a'])).status,401);
 const blocked=await x.request('/v1/sessions/exchange','POST',x.ticket('member-a'));assert.equal(blocked.status,409);
 await createMemberCleanup(x.db,x.c).run(true);await x.restart();
 const fresh=await x.request('/v1/sessions/exchange','POST',x.ticket('member-a'));assert.notEqual(fresh.data.userId,old);assert.equal((await x.request('/v1/operator/role','GET',undefined,fresh.data.token)).data.isOperator,false);assert.equal((await x.request('/v1/me/membership','GET',undefined,fresh.data.token)).data.derivedState,'unbound');
 assert.equal(x.db.prepare('SELECT state FROM member_registry').get().state,'revoked');assert.equal((await x.request('/v1/deletions/status','POST',undefined,x.credentials.deletionReceipt,'DeletionReceipt')).data.state,'completed');
 const progress=JSON.stringify(x.db.prepare('SELECT * FROM demo_steps').all());for(const secret of Object.values(x.credentials))assert.equal(progress.includes(secret),false);assert.equal(progress.includes('displayCode'),false);assert.equal(progress.includes('memberRef'),false);
});

test('DM06 schedule scenario private draft, stable cancellation/reschedule, separate publication/withdrawal and explicit empty/outside',async t=>{
 const x=await setup(t),r=await x.runner().start('schedule-v1','2026-09-17',x.credentials);await x.runner().step(r.runId,x.credentials);
 assert.equal((await x.request('/v1/schedule?from=2026-09-17&to=2026-09-24')).data.publicationState,'unpublished');await x.runner().step(r.runId,x.credentials);const first=(await x.request('/v1/schedule?from=2026-09-17&to=2026-09-24')).data;
 await x.runner().step(r.runId,x.credentials);assert.deepEqual((await x.request('/v1/schedule?from=2026-09-17&to=2026-09-24')).data,first);await x.runner().step(r.runId,x.credentials);const second=(await x.request('/v1/schedule?from=2026-09-17&to=2026-09-24')).data;
 assert.deepEqual(second.courses.map(c=>c.courseId),first.courses.map(c=>c.courseId));assert.equal(second.courses[0].status,'cancelled');assert.notEqual(second.courses[1].startAt,first.courses[1].startAt);await x.runner().step(r.runId,x.credentials);assert.equal((await x.request('/v1/schedule?from=2026-09-17&to=2026-09-24')).data.state,'withdrawn');
 const empty=await x.runner().start('schedule-empty-v1','2026-10-01',x.credentials,true);await x.runner().step(empty.runId,x.credentials);await x.runner().step(empty.runId,x.credentials);await x.restart();
 assert.equal((await x.request('/v1/schedule?from=2026-10-01&to=2026-10-02')).data.days[0].state,'empty');assert.equal((await x.request('/v1/schedule?from=2026-09-17&to=2026-09-18')).data.days[0].covered,false);
});

test('DM12 demo cleanup retains active/review runs and removes only 30-day terminal progress atomically',async t=>{
 const x=await setup(t),r=await x.runner().start('crowd-v1','2026-09-17',x.credentials);x.runner().cancel(r.runId,true);const active=await x.runner().start('crowd-v1','2026-09-17',x.credentials);
 const time=Date.now()+31*86400000,cleanup=createDemoCleanup(x.db,x.c,{now:()=>time});await cleanup.run();assert.equal(x.db.prepare('SELECT 1 FROM demo_runs WHERE run_id=?').get(r.runId),undefined);assert.ok(x.db.prepare('SELECT 1 FROM demo_runs WHERE run_id=?').get(active.runId));
});

test('DM05 actual killed runner after HTTP commit leaves interrupted progress; resume queries original operation once',async t=>{
 const {writeFileSync}=await import('node:fs'),{fork,bounded}=await import('./process-support.mjs');
 const x=await setup(t),r=await x.runner().start('crowd-v1','2026-09-17',x.credentials),path=x.c.stateDir+'/runner-input.json';
 writeFileSync(path,JSON.stringify({action:'crash-runner',baseUrl:'http://127.0.0.1:'+x.server().port,runId:r.runId,credentials:x.credentials}),{mode:0o600});
 const worker=fork('test/demo-process-worker.mjs',[x.path,path],{stdio:['ignore','ignore','ignore','ipc']});const queue=[],waiting=[];
 worker.on('message',m=>waiting.length?waiting.shift()(m):queue.push(m));const next=()=>queue.length?Promise.resolve(queue.shift()):bounded(new Promise(resolve=>waiting.push(resolve)));
 assert.equal(await next(),'ready');worker.send('go');assert.equal(await next(),'business-committed');
 await assert.rejects(()=>x.runner().step(r.runId,x.credentials),e=>e.code==='RUNNER_BUSY');await stop(worker,'SIGKILL');
 assert.equal(x.runner().status(r.runId).state,'interrupted');await x.restart();
 const recovered=await x.runner().resume(r.runId,x.credentials);assert.equal(recovered.stepId,1);assert.equal(x.db.prepare('SELECT count(*) n FROM observation_events').get().n,1);
 t.diagnostic('SIGKILL after actual HTTP commit: durable original operation reconciled after service restart, no second observation');
});

test('DM05 actor substitution, 401 and 429 pause without issuing sessions or resetting persistent limits',async t=>{
 const x=await setup(t);await assert.rejects(()=>x.runner().start('membership-v1','2026-09-17',{...x.credentials,'member-a':x.credentials['operator-a']}),e=>e.code==='ACTOR_MISMATCH');
 const r=await x.runner().start('crowd-v1','2026-09-17',x.credentials);const s=x.service.session(x.credentials['operator-a'],'poll');x.service.logout(x.credentials['operator-a'],intent(Date.now(),s.sessionRevision));
 await assert.rejects(()=>x.runner().step(r.runId,x.credentials),e=>e.status===401);assert.equal(x.runner().status(r.runId).state,'paused');x.runner().cancel(r.runId,true);
 const reauth=await x.request('/v1/sessions/exchange','POST',x.ticket('operator-a'));x.credentials['operator-a']=reauth.data.token;
 const member=await x.runner().start('membership-v1','2026-09-17',x.credentials),start=Math.floor(Date.now()/600000)*600000;
 x.db.prepare("INSERT INTO membership_limits VALUES('generation',?,?,?,3)").run(x.users['member-a'],start,start+600000);
 const count=x.db.prepare('SELECT count(*) n FROM sessions').get().n;
 await assert.rejects(()=>x.runner().step(member.runId,x.credentials),e=>e.status===429);assert.equal(x.runner().status(member.runId).error,'RATE_LIMITED');assert.equal(x.db.prepare('SELECT count(*) n FROM sessions').get().n,count);
 await x.restart();assert.equal(x.db.prepare("SELECT count FROM membership_limits WHERE kind='generation' AND subject_id=? AND window_start=?").get(x.users['member-a'],start).count,3);
});

test('DM05 actual demo run CLI start/step/status uses private session file and no credential output',async t=>{
 const {spawnSync}=await import('node:child_process'),{writeFileSync}=await import('node:fs');const x=await setup(t),file=x.c.stateDir+'/cli-sessions.json';writeFileSync(file,JSON.stringify(x.credentials),{mode:0o600});
 const cli=args=>{const p=spawnSync(process.execPath,['scripts/demo.mjs','run',x.path,...args,'--port',String(x.server().port),'--sessions-file',file],{encoding:'utf8',timeout:5000});assert.equal(p.status,0);return JSON.parse(p.stdout);};
 const start=cli(['start','crowd-v1','--anchor-date','2026-09-17']);const step=cli(['step',start.data.runId]);assert.equal(step.data.stepId,1);assert.equal(cli(['status',start.data.runId]).data.lastRevision.observation,1);
 const output=JSON.stringify([start,step]);for(const token of Object.values(x.credentials))assert.equal(output.includes(token),false);
 const observed=(await x.request('/v1/observations/current')).data,{createObservationService}=await import('../server/observations.mjs'),{createIdentityService}=await import('../server/identity.mjs');
 const identity=createIdentityService(x.db,x.c,{now:()=>Date.parse(observed.validUntil)});assert.equal(createObservationService(x.db,x.c,identity).current().data.state,'expired');assert.equal(x.db.prepare('SELECT revision FROM observation_head').get().revision,1);
});
