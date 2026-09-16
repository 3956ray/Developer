import test from 'node:test';
import assert from 'node:assert/strict';
import { setupDemo } from './demo-support.mjs';
import { intent, roleWrite } from './identity-support.mjs';
import { createScheduleImport } from '../server/schedule-import.mjs';
import { createScheduleService } from '../server/schedules.mjs';
import { createDemoCleanup } from '../server/demo-cleanup.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService } from '../server/identity.mjs';
const START=Date.UTC(2026,8,17),DAY=86400000;
async function setup(t){let time=START;const x=setupDemo(t,{now:()=>time});const op=await x.service.login(x.ticket('operator-a'));x.service.changeRole(roleWrite(x.c,op.userId,time));const s=createScheduleService(x.db,x.c,x.service),i=createScheduleImport(x.db,x.c,x.service);i.register({sourceId:'synthetic',sourceLabel:'合成排课',gymId:x.c.gymId,timeZone:x.c.timeZone,confirmed:true});return{...x,s,i,op,now:()=>time,time:n=>{time=n;}};}
function input(x,revision=1,courses=[course('one'),course('two','11:00','12:00')]){return{schemaVersion:1,sourceId:'synthetic',gymId:x.c.gymId,sourceRevision:revision,mode:'full',capturedAt:new Date(START).toISOString(),timeZone:x.c.timeZone,coverage:{startDate:'2026-09-17',endDate:'2026-09-18'},courses};}
function course(externalId,start='09:00',end='10:00'){return{externalId,name:'合成课程',localStart:{local:'2026-09-17T'+start,offset:'+08:00'},localEnd:{local:'2026-09-17T'+end,offset:'+08:00'},status:'scheduled'};}
const plan=(x,body)=>x.i.plan(x.op.token,JSON.stringify(body));
const apply=(x,p)=>x.i.apply(x.op.token,{batchId:p.batchId,contentHash:p.contentHash,replaceDraftConfirmed:true});
function publish(x){const d=x.s.getDraft(x.op.token);return x.s.publish(x.op.token,{...intent(x.now(),{draft:d.draftRevision,publication:d.publicationRevision}),replaceCoverageConfirmed:true});}

test('DM07/09 plan is private, confirmed apply atomic, publish copies immutable safe provenance, omissions cancel and stable IDs survive',async t=>{
 const x=await setup(t),p=plan(x,input(x));assert.equal(x.s.getDraft(x.op.token).draftRevision,'absent');assert.equal(x.s.read(null,null,'today').data.publicationState,'unpublished');
 assert.throws(()=>x.i.apply(x.op.token,{batchId:p.batchId,contentHash:p.contentHash,replaceDraftConfirmed:false}),e=>e.code==='REPLACEMENT_CONFIRMATION_REQUIRED');
 const first=apply(x,p);assert.equal(first.draftRevision,1);assert.equal(x.s.read(null,null,'today').data.publicationState,'unpublished');publish(x);
 const snapshot=x.s.read(null,null,'today').data;assert.equal(snapshot.provenance.kind,'import');assert.equal(snapshot.provenance.capturedAt,new Date(START).toISOString());assert.equal('batchId' in snapshot.provenance,false);assert.equal('sourceId' in snapshot.provenance,false);
 const ids=x.s.getDraft(x.op.token).courses.map(c=>c.courseId);const body=input(x,2,[{...course('one','10:00','11:00'),name:'合成改名'}]),next=plan(x,body);assert.deepEqual(next.diff.omittedCancelled,[ids[1]]);apply(x,next);
 const changed=x.s.getDraft(x.op.token);assert.equal(changed.courses[0].courseId,ids[0]);assert.equal(changed.courses[1].status,'cancelled');assert.deepEqual(x.s.read(null,null,'today').data,snapshot);
 const edit={...intent(x.now(),changed.draftRevision),coverage:{startDate:'2026-09-17',endDate:'2026-09-18'},courses:changed.courses};x.s.save(x.op.token,edit);
 assert.equal(x.s.getDraft(x.op.token).provenance.kind,'import-edited');assert.equal(x.s.read(null,null,'today').data.provenance.kind,'import');publish(x);assert.equal(x.s.read(null,null,'today').data.provenance.kind,'import-edited');
 assert.deepEqual(apply(x,next),{...first,batchId:next.batchId,sourceRevision:2,contentHash:next.contentHash,draftRevision:2,publicationRevision:1});
 assert.equal(x.s.getDraft(x.op.token).draftRevision,3);assert.equal(plan(x,body).state,'applied');
 assert.throws(()=>plan(x,input(x)),e=>e.code==='SOURCE_REVISION_OLD');assert.throws(()=>plan(x,{...body,capturedAt:new Date(START+1).toISOString()}),e=>e.code==='SOURCE_HASH_CONFLICT');
 const future=input(x,3,[]);future.coverage={startDate:'2026-09-18',endDate:'2026-09-19'};apply(x,plan(x,future));
 const returned=plan(x,input(x,4,[course('one')]));assert.equal(returned.diff.courses[0].courseId,ids[0]);assert.equal(returned.diff.courses[0].revision,'absent');apply(x,returned);
});

test('DM08 strict schema/size/duplicate/time/coverage/overlap rejects whole candidate with safe course-row errors',async t=>{
 const x=await setup(t);const invalid=[{...input(x),phone:'123'}, {...input(x),mode:'delta'}, {...input(x),gymId:'demo-wrong'}, {...input(x),sourceId:'unknown'}, {...input(x),timeZone:'UTC'}, {...input(x),sourceRevision:0}, {...input(x),coverage:{startDate:'2026-09-17',endDate:'2026-09-17'}}, {...input(x),coverage:{startDate:'2026-09-17',endDate:'2026-10-02'}}, input(x,1,[course('same'),course('same')]), input(x,1,[course('one'),course('two')]),input(x,1,[{...course('one'),localStart:{local:'2026-09-17T09:00',offset:'+00:00'}}]),input(x,1,[{...course('one'),email:'x@example.invalid'}]),input(x,1,Array.from({length:201},(_,n)=>course('id'+n)))];
 for(const body of invalid)assert.throws(()=>plan(x,body),e=>e.status===422);
 assert.throws(()=>x.i.plan(x.op.token,' '.repeat(65537)),e=>e.status===422);
 assert.throws(()=>x.i.plan(x.op.token,'{"schemaVersion":1,"schemaVersion":1}'),e=>e.status===422);
 assert.throws(()=>plan(x,input(x,1,[{...course('one'),localEnd:{local:'bad',offset:'+08:00'}}])),e=>e.status===422&&e.row===1);
 assert.equal(x.db.prepare('SELECT count(*) n FROM import_batches').get().n,0);assert.equal(x.db.prepare('SELECT count(*) n FROM import_versions').get().n,0);assert.equal(x.s.getDraft(x.op.token).draftRevision,'absent');
 const ordinary=await x.service.login(x.ticket());assert.throws(()=>x.i.plan(ordinary.token,JSON.stringify(input(x))),e=>e.status===403);
 assert.throws(()=>x.i.register({sourceId:'second',sourceLabel:'另一个合成源',gymId:x.c.gymId,timeZone:x.c.timeZone,confirmed:true}),e=>e.status===409);
});

test('DM09 expired same revision/hash replan replaces old batch; CAS and fault rollback preserve every table',async t=>{
 const x=await setup(t),body=input(x),p=plan(x,body);assert.equal(plan(x,body).batchId,p.batchId);
 x.s.save(x.op.token,{...intent(x.now(),'absent'),coverage:body.coverage,courses:[]});assert.throws(()=>apply(x,p),e=>e.status===409);
 x.time(START+DAY);const fresh=await x.service.login(x.ticket('operator-a'));x.op=fresh;const replacement=plan(x,body);assert.notEqual(replacement.batchId,p.batchId);assert.equal(replacement.diff.expectedDraft,1);assert.throws(()=>apply(x,p),e=>e.code==='CANDIDATE_EXPIRED');
 x.db.exec("CREATE TRIGGER fail_import BEFORE UPDATE ON source_bindings BEGIN SELECT RAISE(ABORT,'injected'); END;");assert.throws(()=>apply(x,replacement));assert.equal(x.s.getDraft(x.op.token).draftRevision,1);assert.equal(x.db.prepare('SELECT count(*) n FROM source_entity_map').get().n,0);assert.equal(x.i.status(x.op.token,replacement.batchId).state,'candidate');assert.equal(x.db.prepare("SELECT count(*) n FROM audit WHERE action='schedule.import.apply'").get().n,0);
 x.db.exec('DROP TRIGGER fail_import');apply(x,replacement);const revoked=x.service.inspectRole(x.op.userId,x.c.gymId);x.service.changeRole(roleWrite(x.c,x.op.userId,x.now(),revoked.roleRevision,'revoke'));assert.throws(()=>apply(x,replacement),e=>e.status===403);
});

test('DM09 retention retry and restart preserve mappings/version floor/applied result/current provenance',async t=>{
 const x=await setup(t),p=plan(x,input(x));apply(x,p);publish(x);const map=x.db.prepare('SELECT * FROM source_entity_map').all(),provenance=x.s.read(null,null,'today').data.provenance;
 x.time(START+31*DAY);const cleanup=createDemoCleanup(x.db,x.c,{now:x.now});x.db.exec("CREATE TRIGGER fail_retention BEFORE DELETE ON import_batches BEGIN SELECT RAISE(ABORT,'injected'); END;");assert.equal((await cleanup.run()).failed,true);x.db.exec('DROP TRIGGER fail_retention');x.time(x.now()+3600000);await cleanup.run();assert.equal(x.db.prepare('SELECT count(*) n FROM import_batches').get().n,0);
 x.db.close();const db=openDatabase(x.c);try{const identity=createIdentityService(db,x.c,{now:x.now}),i=createScheduleImport(db,x.c,identity);const {issueDemoTicket}=await import('../server/demo.mjs');const fresh=await identity.login({code:issueDemoTicket(db,x.c,'operator-a',x.now()),privacyNoticeVersion:'cp3-purpose-v1',consent:true});assert.equal(i.status(fresh.token,p.batchId).state,'applied');assert.equal(i.plan(fresh.token,JSON.stringify(input(x))).state,'applied');assert.deepEqual(db.prepare('SELECT * FROM source_entity_map').all(),map);assert.deepEqual(createScheduleService(db,x.c,identity).read(null,null,'today').data.provenance,provenance);}finally{db.close();}
});

test('DM09-RP1–RP5 explicit stale candidate replacement is atomic, version-bound, single successor and never applies',async t=>{
 const x=await setup(t),body=input(x),p=plan(x,body),text=JSON.stringify(body);
 assert.throws(()=>x.i.plan(x.op.token,text,{batchId:p.batchId,expectedDraft:'absent',expectedPublication:'absent'}),e=>e.code==='CANDIDATE_STILL_CURRENT');
 x.s.save(x.op.token,{...intent(x.now(),'absent'),coverage:body.coverage,courses:[]});assert.equal(plan(x,body).batchId,p.batchId);
 const replacement={batchId:p.batchId,expectedDraft:1,expectedPublication:'absent'};
 x.s.save(x.op.token,{...intent(x.now(),1),coverage:body.coverage,courses:[]});assert.throws(()=>x.i.plan(x.op.token,text,replacement),e=>e.code==='REVISION_CONFLICT');assert.equal(x.i.status(x.op.token,p.batchId).state,'candidate');replacement.expectedDraft=2;
 x.db.exec("CREATE TRIGGER fail_new_candidate BEFORE INSERT ON import_batches BEGIN SELECT RAISE(ABORT,'injected'); END;");assert.throws(()=>x.i.plan(x.op.token,text,replacement));assert.equal(x.i.status(x.op.token,p.batchId).state,'candidate');x.db.exec('DROP TRIGGER fail_new_candidate');
 const before=x.s.getDraft(x.op.token),newPlan=x.i.plan(x.op.token,text,replacement);assert.deepEqual(x.s.getDraft(x.op.token),before);assert.equal(x.db.prepare('SELECT applied_revision FROM source_bindings').get().applied_revision,0);assert.equal(x.db.prepare('SELECT count(*) n FROM source_entity_map').get().n,0);
 assert.throws(()=>apply(x,p),e=>e.code==='CANDIDATE_EXPIRED');assert.throws(()=>x.i.plan(x.op.token,text,replacement),e=>e.code==='CANDIDATE_REPLACED');
 x.s.save(x.op.token,{...intent(x.now(),2),coverage:body.coverage,courses:[]});assert.throws(()=>apply(x,newPlan),e=>e.code==='REVISION_CONFLICT');
 const last=x.i.plan(x.op.token,text,{batchId:newPlan.batchId,expectedDraft:3,expectedPublication:'absent'});apply(x,last);
 assert.equal(x.i.plan(x.op.token,text,{batchId:last.batchId,expectedDraft:3,expectedPublication:'absent'}).state,'applied');assert.throws(()=>apply(x,p),e=>e.code==='CANDIDATE_EXPIRED');
 assert.throws(()=>x.i.plan(x.op.token,JSON.stringify({...body,capturedAt:new Date(START+1).toISOString()}),{batchId:last.batchId,expectedDraft:3,expectedPublication:'absent'}),e=>e.status===409);
 apply(x,plan(x,input(x,2)));assert.throws(()=>x.i.plan(x.op.token,text,{batchId:last.batchId,expectedDraft:3,expectedPublication:'absent'}),e=>e.code==='SOURCE_REVISION_OLD');
});

test('DM08 cancellation union over 200 rejects without discarding old rows; 14-day edge uses existing domain validation',async t=>{
 const x=await setup(t),many=[];for(let n=0;n<200;n++)many.push({...course('id'+n),status:'cancelled'});
 apply(x,plan(x,input(x,1,many)));const before=x.s.getDraft(x.op.token);assert.throws(()=>plan(x,input(x,2,[course('new')])),e=>e.status===422);assert.deepEqual(x.s.getDraft(x.op.token),before);
 const full=input(x,2,many);full.coverage.endDate='2026-10-01';assert.ok(plan(x,full));
});

test('DM08 import honors DST nonexistent/repeated local time with explicit offsets',async t=>{
 const x=setupDemo(t,{now:()=>START});x.c.timeZone='America/New_York';const op=await x.service.login(x.ticket('operator-a'));x.service.changeRole(roleWrite(x.c,op.userId,START));const i=createScheduleImport(x.db,x.c,x.service);i.register({sourceId:'synthetic',sourceLabel:'合成排课',gymId:x.c.gymId,timeZone:x.c.timeZone,confirmed:true});
 const base=input(x,1,[]);base.coverage={startDate:'2026-03-08',endDate:'2026-03-09'};base.courses=[{...course('missing'),localStart:{local:'2026-03-08T02:30',offset:'-05:00'},localEnd:{local:'2026-03-08T03:30',offset:'-04:00'}}];assert.throws(()=>i.plan(op.token,JSON.stringify(base)),e=>e.status===422);
 base.coverage={startDate:'2026-11-01',endDate:'2026-11-02'};base.courses=['-04:00','-05:00'].map((offset,n)=>({...course('repeated'+n),localStart:{local:'2026-11-01T01:30',offset},localEnd:{local:'2026-11-01T01:45',offset}}));const planned=i.plan(op.token,JSON.stringify(base));assert.notEqual(planned.diff.courses[0].startAt,planned.diff.courses[1].startAt);
});
