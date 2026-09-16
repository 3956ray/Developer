import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setupIdentity,intent,roleWrite} from './identity-support.mjs';
import {createObservationService} from '../server/observations.mjs';
import {createMembershipService} from '../server/membership.mjs';
import {createScheduleService} from '../server/schedules.mjs';
import {startCleanups} from '../server/cleanup-runners.mjs';
import {digest} from '../server/protocol.mjs';
const START=Date.parse('2026-09-16T01:00:00Z'),HOUR=3600000,DAY=24*HOUR;
async function fixture(t){let time=START;const x=setupIdentity(t,{now:()=>time}),op=await x.service.login(x.fixture('synthetic-integrated-op')),user=await x.service.login(x.fixture('synthetic-integrated-member'));x.service.changeRole(roleWrite(x.c,op.userId,time));return{...x,op,user,now:()=>time,time:value=>{time=value;},o:createObservationService(x.db,x.c,x.service),m:createMembershipService(x.db,x.c,x.service),s:createScheduleService(x.db,x.c,x.service)};}
async function settle(check){for(let i=0;i<100;i++){if(check())return;await new Promise(r=>setImmediate(r));}assert.fail('CLEANUP_DID_NOT_SETTLE');}
function scheduler(x){const pending=new Map(),errors=[];let id=0;const stop=startCleanups(x.db,x.c,name=>errors.push(name),{now:x.now,timers:{setTimeout(fn,delay){const key=++id;pending.set(key,{fn,at:x.now()+delay});return key;},clearTimeout:key=>pending.delete(key)}});x.beforeRemove(stop);return{pending,errors,stop,async due(){for(const [key,job] of [...pending])if(job.at<=x.now()){pending.delete(key);job.fn();}await settle(()=>pending.size===3);}};}

test('CP5 cross-domain writes, cleanup and deletion never renew observation TTL or restore revoked qualification',async t=>{
 const x=await fixture(t);x.o.publish(x.op.token,{...intent(START,'absent'),level:'moderate',observedJustNow:true});const before=x.o.current().data;
 const pair=x.m.createPair(x.user.token,intent(START,'absent')).data,b=x.m.bind(x.op.token,{...intent(START),expectedRevision:{pairing:pair.revision,binding:'absent',registry:'absent'},pairingId:pair.pairingId,code:pair.displayCode,memberRef:'SYNTHETIC-INTEGRATION',frontDeskConfirmed:true,expiry:{mode:'no_fixed_expiry'}}).data;
 x.m.update(x.op.token,b.bindingId,{...intent(START),expectedRevision:{binding:1,registry:1},reasonCategory:'qualification-withdrawn'},true);
 x.s.save(x.op.token,{...intent(START,'absent'),coverage:{startDate:'2026-09-16',endDate:'2026-09-23'},courses:[]});x.s.publish(x.op.token,{...intent(START),expectedRevision:{draft:1,publication:'absent'},replaceCoverageConfirmed:true});assert.deepEqual(x.o.current().data,before);assert.equal(x.m.profile(x.user.token).derivedState,'revoked');
 const receipt=randomUUID();x.m.deleteAccount(x.user.token,{...intent(START,1),confirmed:true,receiptDigest:digest(receipt.padEnd(64,'0').slice(0,64))});
 const runner=scheduler(x);await settle(()=>runner.pending.size===3);assert.equal(x.db.prepare('SELECT state FROM deletion_jobs').get().state,'completed');assert.equal(x.db.prepare('SELECT state FROM member_registry').get().state,'revoked');assert.deepEqual(x.o.current().data,before);assert.equal(x.s.read(null,null,'today').data.days[0].state,'empty');
 x.time(START+900000);assert.equal(x.o.current().data.state,'expired');assert.equal(x.o.current().data.validUntil,before.validUntil);await runner.stop();
});

test('CP5 production cleanup wiring runs all domains at startup/hourly, preserves controls and retries failed retention',async t=>{
 const x=await fixture(t),runner=scheduler(x);await settle(()=>runner.pending.size===3);assert.ok([...runner.pending.values()].every(job=>job.at===START+HOUR));
 x.o.publish(x.op.token,{...intent(START,'absent'),level:'quiet',observedJustNow:true});x.o.control(x.op.token,{...intent(START,1),state:'withdrawn',reasonCategory:'correction'});const control=x.o.current().data;
 x.m.createPair(x.user.token,intent(START,'absent'));
 x.db.prepare("INSERT INTO observation_events VALUES(999,'quiet',?,?,?,'synthetic-history')").run(START-31*DAY,START-31*DAY,START-31*DAY+900000);
 x.db.prepare("INSERT INTO audit VALUES(?,? ,NULL,'synthetic.old','success',1,?,'test')").run(randomUUID(),'synthetic-history',START-91*DAY);
 x.db.prepare('INSERT INTO operations VALUES(?,?,?,?,?,?,?,NULL)').run('synthetic-history','schedule.publish',randomUUID(),'digest','{}',1,START-2*DAY);
 x.db.prepare('INSERT INTO schedule_snapshots VALUES(?,?,?,?,?,?,?,?)').run(randomUUID(),999,'Asia/Taipei','{}',START-31*DAY,'[]',START-31*DAY,START-30*DAY);
 x.db.exec("CREATE TRIGGER fail_integrated_cleanup BEFORE DELETE ON audit BEGIN SELECT RAISE(ABORT,'test'); END");x.time(START+HOUR);await runner.due();assert.ok(runner.errors.includes('member'));assert.equal(x.db.prepare('SELECT last_error FROM membership_cleanup').get().last_error,'CLEANUP_FAILED');assert.equal(x.db.prepare('SELECT count(*) n FROM schedule_snapshots').get().n,0);assert.equal(x.db.prepare('SELECT 1 FROM observation_events WHERE revision=999').get(),undefined);assert.deepEqual(x.o.current().data,control);assert.equal(x.db.prepare('SELECT count(*) n FROM pairings').get().n,0);
 x.db.exec('DROP TRIGGER fail_integrated_cleanup');x.time(START+2*HOUR);await runner.due();assert.equal(x.db.prepare("SELECT count(*) n FROM audit WHERE actor_id='synthetic-history'").get().n,0);assert.equal(x.db.prepare("SELECT count(*) n FROM operations WHERE actor_id='synthetic-history'").get().n,0);assert.equal(x.db.prepare('SELECT last_error FROM membership_cleanup').get().last_error,null);assert.deepEqual(x.o.current().data,control);await runner.stop();assert.equal(runner.pending.size,0);
});

test('CP5 old intents cannot execute after 24-hour result eviction and fresh operator reauthentication',async t=>{
 const x=await fixture(t),body={...intent(START,'absent'),coverage:{startDate:'2026-09-16',endDate:'2026-09-23'},courses:[]};x.s.save(x.op.token,body);const old=x.s.getDraft(x.op.token).draftRevision;
 x.time(START+DAY);const op=await x.service.login(x.fixture('synthetic-integrated-op'));assert.equal(op.userId,x.op.userId);const runner=scheduler(x);await settle(()=>runner.pending.size===3);
 assert.equal(x.service.operationResult(op.token,body.operationId,'schedule.draft.save').state,'unknown');assert.throws(()=>x.s.save(op.token,body),e=>e.code==='INTENT_EXPIRED');assert.equal(x.s.getDraft(op.token).draftRevision,old);await runner.stop();
});

test('CP5 one secure pairing collision retries successfully without replacing another user request',async t=>{
 const x=await fixture(t),values=['00000001','00000001','00000002'],m=createMembershipService(x.db,x.c,x.service,{randomCode:()=>values.shift()});const first=m.createPair(x.user.token,intent(START,'absent')).data,second=m.createPair(x.op.token,intent(START,'absent')).data;assert.notEqual(first.displayCode,second.displayCode);assert.equal(m.pairing(x.user.token).pairingId,first.pairingId);assert.equal(values.length,0);assert.equal(x.db.prepare("SELECT sum(count) n FROM membership_limits WHERE kind='generation'").get().n,2);
});
