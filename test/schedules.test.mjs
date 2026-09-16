import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setupIdentity,intent,roleWrite} from './identity-support.mjs';
import {createScheduleService} from '../server/schedules.mjs';
import {createScheduleCleanup} from '../server/schedule-cleanup.mjs';
import {createMembershipService} from '../server/membership.mjs';
import {createMemberCleanup} from '../server/member-cleanup.mjs';
import {createObservationService} from '../server/observations.mjs';
import {createIdentityService} from '../server/identity.mjs';
import {openDatabase} from '../server/database.mjs';
import {digest} from '../server/protocol.mjs';
import {period,range,localTime,dayBoundary} from '../server/schedule-time.mjs';
const START=Date.parse('2026-09-16T01:00:00.000Z');
async function setup(t){let time=START;const x=setupIdentity(t,{now:()=>time}),op=await x.service.login(x.fixture('synthetic-schedule-operator'));x.service.changeRole(roleWrite(x.c,op.userId,START));return{...x,op,s:createScheduleService(x.db,x.c,x.service),now:()=>time,time:v=>{time=v;}};}
function course(start='2026-09-16T09:00',end='2026-09-16T10:00',offset='+08:00',zone='Asia/Taipei'){const localInput={start:{local:start,offset},end:{local:end,offset}};return{courseId:randomUUID(),revision:'absent',name:'合成测试课程',status:'scheduled',localInput,...period(localInput,zone)};}
function save(x,courses=[],from='2026-09-16',to='2026-09-23'){return x.s.save(x.op.token,{...intent(x.now(),x.s.getDraft(x.op.token).draftRevision),coverage:{startDate:from,endDate:to},courses});}
function publish(x){const d=x.s.getDraft(x.op.token);return x.s.publish(x.op.token,{...intent(x.now()),expectedRevision:{draft:d.draftRevision,publication:d.publicationRevision},replaceCoverageConfirmed:true});}
function withdraw(x){return x.s.withdraw(x.op.token,{...intent(x.now(),x.s.getDraft(x.op.token).publicationRevision),reasonCategory:'correction'});}

test('S01/S02/B01 draft private, explicit empty, coverage replacement/withdrawal never revive history',async t=>{
 const x=await setup(t);assert.equal(x.s.read('2026-09-16','2026-09-17').data.days[0].state,'unpublished');save(x,[course()]);assert.equal(x.s.read('2026-09-16','2026-09-17').data.courses.length,0);publish(x);
 let view=x.s.read('2026-09-16','2026-09-18').data;assert.equal(view.courses.length,1);assert.equal(view.days[1].state,'empty');assert.equal(x.s.read('2026-09-23','2026-09-24').data.days[0].state,'unpublished');
 save(x,[],'2026-09-24','2026-09-25');publish(x);assert.equal(x.s.read('2026-09-16','2026-09-17').data.days[0].state,'unpublished');assert.equal(x.s.read('2026-09-24','2026-09-25').data.days[0].state,'empty');withdraw(x);assert.equal(x.s.read('2026-09-16','2026-09-17').data.state,'withdrawn');assert.equal(x.db.prepare('SELECT count(*) n FROM schedule_head').get().n,1);
});

test('S03/Z01 stable ID reschedule/cancel, item CAS, full set omission rejection, overlap only blocks publication',async t=>{
 const x=await setup(t),one=course(),two=course('2026-09-16T09:30','2026-09-16T10:30');save(x,[one,two]);assert.throws(()=>publish(x),e=>e.code==='COURSE_OVERLAP');const d=x.s.getDraft(x.op.token);
 assert.throws(()=>save(x,[d.courses[0]]),e=>e.code==='EXPLICIT_CANCELLATION_REQUIRED');assert.throws(()=>save(x,[one,two]),e=>e.code==='REVISION_CONFLICT');
 const moved={...d.courses[1],...course('2026-09-16T10:00','2026-09-16T11:00'),courseId:two.courseId,revision:d.courses[1].revision};save(x,[d.courses[0],moved]);publish(x);let courses=x.s.getDraft(x.op.token).courses;assert.equal(courses[1].courseId,two.courseId);assert.equal(courses[1].revision,2);assert.equal(courses[0].revision,1);
 courses[0].status='cancelled';save(x,courses);assert.equal(x.s.summary(START).currentCourses.length,1);publish(x);assert.equal(x.s.summary(START).currentCourses.length,0);assert.equal(x.s.summary(START).courses[0].status,'cancelled');
});

test('Z01 local/UTC exact boundaries, cross-midnight/week, DST missing/repeated and date coverage validation',async t=>{
 const x=await setup(t);save(x,[course('2026-09-20T23:30','2026-09-21T01:00')],'2026-09-20','2026-09-22');publish(x);x.time(Date.parse('2026-09-20T16:30:00Z'));const week=x.s.read(null,null,'week').data;assert.equal(week.query.startDate,'2026-09-21');assert.equal(week.courses.length,1);assert.equal(week.currentCourses.length,1);x.time(Date.parse('2026-09-20T17:00:00Z'));assert.equal(x.s.summary(x.now()).currentCourses.length,0);
 for(const [from,to] of [['2026-09-16','2026-09-16'],['2026-09-16','2026-10-01'],['2026-02-29','2026-03-01']])assert.throws(()=>range(from,to,'Asia/Taipei'));
 assert.equal(range('2026-12-31','2027-01-14','Asia/Taipei').endDate,'2027-01-14');
 assert.throws(()=>localTime({local:'2026-03-08T02:30',offset:'-05:00'},'America/New_York'),e=>e.code==='INVALID_LOCAL_TIME');
 assert.equal(localTime({local:'2026-11-01T01:30',offset:'-04:00'},'America/New_York'),'2026-11-01T05:30:00.000Z');assert.equal(localTime({local:'2026-11-01T01:30',offset:'-05:00'},'America/New_York'),'2026-11-01T06:30:00.000Z');assert.throws(()=>localTime({local:'2026-11-01T01:30',offset:''},'America/New_York'));
 assert.equal(dayBoundary('2026-03-09','America/New_York')-dayBoundary('2026-03-08','America/New_York'),23*3600000);assert.equal(dayBoundary('2026-11-02','America/New_York')-dayBoundary('2026-11-01','America/New_York'),25*3600000);
 assert.equal(dayBoundary('2011-12-30','Pacific/Apia'),dayBoundary('2011-12-31','Pacific/Apia'));
});

test('I01/M04 dual CAS, same-key replay/conflict, audit rollback, current role denial and observation TTL unchanged',async t=>{
 const x=await setup(t),obs=createObservationService(x.db,x.c,x.service);obs.publish(x.op.token,{...intent(START,'absent'),level:'quiet',observedJustNow:true});const before=obs.current();save(x,[course()]);const d=x.s.getDraft(x.op.token),body={...intent(START),expectedRevision:{draft:d.draftRevision,publication:'absent'},replaceCoverageConfirmed:true};
 x.db.exec("CREATE TRIGGER fail_schedule_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test'); END");assert.throws(()=>x.s.publish(x.op.token,body));x.db.exec('DROP TRIGGER fail_schedule_audit');assert.equal(x.s.read(null,null,'today').data.state,'unpublished');
 const p=x.s.publish(x.op.token,body);assert.equal(x.s.publish(x.op.token,body).operation.replayed,true);assert.throws(()=>x.s.publish(x.op.token,{...body,replaceCoverageConfirmed:false}),e=>e.code==='REPLACEMENT_CONFIRMATION_REQUIRED');assert.throws(()=>x.s.publish(x.op.token,{...body,expectedRevision:{draft:2,publication:'absent'}}),e=>e.code==='IDEMPOTENCY_CONFLICT');assert.throws(()=>x.s.publish(x.op.token,{...body,...intent(START),expectedRevision:body.expectedRevision}),e=>e.code==='REVISION_CONFLICT');assert.deepEqual(obs.current(),before);
 x.service.changeRole({...roleWrite(x.c,x.op.userId,START),action:'revoke',expectedRevision:1});assert.throws(()=>x.s.getDraft(x.op.token),e=>e.code==='FORBIDDEN');assert.throws(()=>x.service.operationResult(x.op.token,body.operationId,'schedule.publish'),e=>e.code==='FORBIDDEN');assert.equal(p.data.publicationRevision,1);
});

test('C01/D01 bounded cleanup rollback/restart preserves current snapshot/draft/withdrawn; published timezone frozen',async t=>{
 const x=await setup(t);save(x,[]);publish(x);const snapshot=x.db.prepare('SELECT * FROM schedule_snapshots').get();const insert=x.db.prepare('INSERT INTO schedule_snapshots VALUES(?,?,?,?,?,?,?,?)');for(let n=2;n<207;n++)insert.run(randomUUID(),n,snapshot.time_zone,snapshot.coverage_json,snapshot.coverage_end_at,snapshot.courses_json,START,START);
 x.time(snapshot.coverage_end_at+30*86400000-1);const cleanup=createScheduleCleanup(x.db,x.c,{now:x.now});assert.equal(cleanup.batch(true).removed,0);x.time(x.now()+1);let fault=true;const broken=createScheduleCleanup(x.db,x.c,{now:x.now,afterDelete:()=>{if(fault){fault=false;throw new Error('test');}}});assert.equal(broken.batch(true).failed,true);assert.equal(x.db.prepare('SELECT count(*) n FROM schedule_snapshots').get().n,206);assert.equal(cleanup.batch(true).removed,100);
 const db=openDatabase(x.c);await createScheduleCleanup(db,x.c,{now:x.now}).run(true);assert.equal(db.prepare('SELECT count(*) n FROM schedule_snapshots').get().n,1);assert.equal(db.prepare('SELECT count(*) n FROM schedule_draft').get().n,1);db.close();
 assert.throws(()=>openDatabase({...x.c,timeZone:'America/New_York'}),/PUBLISHED_TIMEZONE_MISMATCH/);x.time(START);withdraw(x);const changed=openDatabase({...x.c,timeZone:'America/New_York'});const otherZone=createScheduleService(changed,{...x.c,timeZone:'America/New_York'},createIdentityService(changed,x.c,{now:x.now}));assert.throws(()=>otherZone.publish(x.op.token,{...intent(START),expectedRevision:{draft:1,publication:2},replaceCoverageConfirmed:true}),e=>e.code==='TIMEZONE_RECONFIRM_REQUIRED');changed.close();x.time(snapshot.coverage_end_at+30*86400000);await cleanup.run(true);assert.equal(x.db.prepare('SELECT count(*) n FROM schedule_snapshots').get().n,0);assert.equal(x.s.read(null,null,'today').data.state,'withdrawn');
});

test('X01 account deletion scrubs schedule audit/operations without deleting shared publication',async t=>{
 const x=await setup(t);save(x,[course()]);publish(x);const m=createMembershipService(x.db,x.c,x.service);m.deleteAccount(x.op.token,{...intent(START,1),confirmed:true,receiptDigest:digest(randomUUID())});await createMemberCleanup(x.db,x.c,{now:x.now}).run(true);assert.equal(x.db.prepare('SELECT count(*) n FROM operations WHERE actor_id=?').get(x.op.userId).n,0);assert.equal(x.db.prepare('SELECT count(*) n FROM audit WHERE actor_id=?').get(x.op.userId).n,0);assert.equal(x.s.read(null,null,'today').data.courses.length,1);
});
