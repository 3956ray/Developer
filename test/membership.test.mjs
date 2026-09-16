import test from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {setupIdentity,intent,roleWrite} from './identity-support.mjs';
import {createMembershipService} from '../server/membership.mjs';
import {createMemberCleanup} from '../server/member-cleanup.mjs';
import {expiry,memberKey,dateEnd} from '../server/member-crypto.mjs';
import {digest} from '../server/protocol.mjs';
import {openDatabase} from '../server/database.mjs';
import {createIdentityService} from '../server/identity.mjs';
const START=Date.parse('2026-09-16T00:00:00.000Z');
async function setup(t,options={}){let time=START;const x=setupIdentity(t,{now:()=>time});const op=await x.service.login(x.fixture('synthetic-operator')),user=await x.service.login(x.fixture('synthetic-member'));x.service.changeRole(roleWrite(x.c,op.userId,time));return{...x,op,user,m:createMembershipService(x.db,x.c,x.service,options),time:v=>{time=v;},now:()=>time};}
function pair(x,who=x.user){return x.m.createPair(who.token,intent(x.now(),x.m.pairing(who.token).revision)).data;}
function form(x,p,ref='SYNTHETIC-REF-001',restore=false){const i=x.m.inspect(x.op.token,{memberRef:ref,pairingId:p.pairingId,code:p.displayCode});return{...intent(x.now()),expectedRevision:{pairing:p.revision,binding:i.requesterBinding.revision,registry:i.registry.revision},pairingId:p.pairingId,code:p.displayCode,memberRef:ref,frontDeskConfirmed:true,expiry:{mode:'no_fixed_expiry'}};}
function bound(x,ref='SYNTHETIC-REF-001',who=x.user){const p=pair(x,who);const f=form(x,p,ref);return x.m.bind(x.op.token,f).data;}
function revoke(x,b){return x.m.update(x.op.token,b.bindingId,{...intent(x.now()),expectedRevision:{binding:b.bindingRevision,registry:b.registryRevision},reasonCategory:'qualification-withdrawn'},true).data;}
function deleteRequest(x){const receipt=randomBytes(32).toString('hex');return{receipt,body:{...intent(x.now(),x.service.session(x.user.token,'poll').accountRevision),confirmed:true,receiptDigest:digest(receipt)}};}

test('M01/M02/R02: leading zero, encrypted code, same-key display, one use and exact 600s expiry',async t=>{
 const x=await setup(t,{randomCode:()=> '00000001'});const request=intent(START,'absent'),p=x.m.createPair(x.user.token,request).data;
 assert.equal(p.displayCode,'00000001');assert.equal(x.m.createPair(x.user.token,request).data.displayCode,p.displayCode);
 const row=x.db.prepare('SELECT * FROM pairings').get();assert.notEqual(row.ciphertext,p.displayCode);assert.equal(row.nonce.length,24);assert.equal(row.tag.length,32);
 const f=form(x,p);const b=x.m.bind(x.op.token,f);assert.equal(x.m.profile(x.user.token).derivedState,'valid');assert.equal(x.m.pairing(x.user.token).displayCode,undefined);
 assert.equal(x.m.bind(x.op.token,f).operation.replayed,true);assert.throws(()=>x.m.bind(x.op.token,{...f,...intent(START),expectedRevision:f.expectedRevision}),e=>e.code==='REVISION_CONFLICT');
 assert.equal(x.m.createPair(x.user.token,request).data.state,'consumed');
 const another=await x.service.login(x.fixture('synthetic-other'));const q=pair(x,another);x.time(START+599999);assert.equal(x.m.pairing(another.token).state,'active');x.time(START+600000);assert.equal(x.m.pairing(another.token).state,'expired');assert.equal(x.m.pairing(another.token).displayCode,undefined);
 assert.equal(x.db.prepare('SELECT count(*) n FROM bindings WHERE current=1').get().n,1);
 assert.equal(x.db.prepare('SELECT * FROM member_registry').get().member_key,memberKey(x.c,'SYNTHETIC-REF-001'));
});

test('R02: five collisions rollback replacement and generation counter; no weak random fallback',async t=>{
 const values=['00000001','00000002'];const x=await setup(t,{randomCode:()=>values.shift()??'00000001'});pair(x);const other=await x.service.login(x.fixture('synthetic-other'));const old=pair(x,other);
 assert.throws(()=>pair(x,other),e=>e.code==='PAIRING_COLLISION_EXHAUSTED');assert.equal(x.m.pairing(other.token).displayCode,old.displayCode);
 assert.equal(x.db.prepare("SELECT count FROM membership_limits WHERE kind='generation' AND subject_id=?").get(other.userId).count,1);
});

test('R01: three creations and five operator/requester errors persist, new window recovers; cleanup race cannot recreate personal error bucket',async t=>{
 const x=await setup(t);for(let i=0;i<3;i++)pair(x);assert.throws(()=>pair(x),e=>e.code==='RATE_LIMITED');
 const p=x.m.pairing(x.user.token);for(let i=0;i<5;i++)assert.throws(()=>x.m.bind(x.op.token,{...form(x,p),frontDeskConfirmed:false}),e=>e.code==='PAIRING_UNAVAILABLE');
 assert.throws(()=>x.m.lookup(x.op.token,{code:p.displayCode}),e=>e.code==='RATE_LIMITED');
 const db=openDatabase(x.c),service=createIdentityService(db,x.c,{now:()=>START}),m=createMembershipService(db,x.c,service);assert.throws(()=>m.lookup(x.op.token,{code:p.displayCode}),e=>e.code==='RATE_LIMITED');db.close();
 x.time(START+600000);const next=pair(x);assert.equal(next.state,'active');
 const racing=createMembershipService(x.db,x.c,x.service,{beforeErrorCount:()=>{x.db.prepare('DELETE FROM pairings WHERE user_id=?').run(x.user.userId);x.db.prepare("UPDATE accounts SET state='deleting' WHERE user_id=?").run(x.user.userId);x.db.prepare('DELETE FROM membership_limits WHERE subject_id=?').run(x.user.userId);}});
 assert.throws(()=>racing.bind(x.op.token,{...form(x,next),frontDeskConfirmed:false}),e=>e.code==='PAIRING_UNAVAILABLE');
 assert.equal(x.db.prepare('SELECT count(*) n FROM membership_limits WHERE subject_id=?').get(x.user.userId).n,0);
});

test('M03: revoked precedence, same binding explicit restoration and occupied slots; unbind cannot reset revoked registry',async t=>{
 const x=await setup(t);const b=bound(x);const withdrawn=revoke(x,b);assert.equal(x.m.profile(x.user.token).derivedState,'revoked');
 const p=pair(x);const f=form(x,p);assert.throws(()=>x.m.bind(x.op.token,f),e=>e.code==='RESTORE_REQUIRED');
 const restored=x.m.restore(x.op.token,f).data;assert.equal(restored.bindingId,b.bindingId);assert.equal(x.m.profile(x.user.token).derivedState,'valid');
 const again=revoke(x,restored),other=await x.service.login(x.fixture('synthetic-other'));const otherPair=pair(x,other);
 assert.throws(()=>x.m.restore(x.op.token,form(x,otherPair)),e=>e.code==='BINDING_CONFLICT');
 x.m.unbind(x.user.token,{...intent(START),expectedRevision:{binding:again.bindingRevision,registry:again.registryRevision,pairing:x.m.pairing(x.user.token).revision},confirmed:true});
 const f2=form(x,otherPair);assert.throws(()=>x.m.bind(x.op.token,f2),e=>e.code==='RESTORE_REQUIRED');x.m.restore(x.op.token,f2);assert.equal(x.m.profile(other.token).derivedState,'valid');
});

test('M03: explicit pending/fixed/no-expiry and server time boundary; dates reject invalid/overflow and handle leap/cross-year',async t=>{
 const x=await setup(t),p=pair(x),f=form(x,p);f.expiry={mode:'pending_confirmation'};let b=x.m.bind(x.op.token,f).data;assert.equal(x.m.profile(x.user.token).derivedState,'pending_confirmation');
 const renew={...intent(START),expectedRevision:{binding:b.bindingRevision,registry:b.registryRevision},memberRef:'SYNTHETIC-REF-001',frontDeskConfirmed:true,reasonCategory:'renewal',expiry:{mode:'fixed_until',validUntil:new Date(START+1000).toISOString()}};
 b=x.m.update(x.op.token,b.bindingId,renew,false).data;x.time(START+999);assert.equal(x.m.profile(x.user.token).derivedState,'valid');x.time(START+1000);assert.equal(x.m.profile(x.user.token).derivedState,'expired');
 for(const date of ['2026-13-01','2026-02-29','9999-12-31'])assert.throws(()=>expiry({mode:'fixed_until',validUntil:'2027-01-01T00:00:00.000Z',localEndDate:date},'Asia/Taipei'),e=>e.code==='INVALID_PERIOD');
 assert.equal(expiry({mode:'fixed_until',validUntil:'2028-02-29T16:00:00.000Z',localEndDate:'2028-02-29'},'Asia/Taipei').until,Date.parse('2028-02-29T16:00:00.000Z'));
 assert.equal(expiry({mode:'fixed_until',validUntil:'2026-12-31T16:00:00.000Z',localEndDate:'2026-12-31'},'Asia/Taipei').until,Date.parse('2026-12-31T16:00:00.000Z'));
 assert.throws(()=>expiry({mode:'no_fixed_expiry',validUntil:null},'Asia/Taipei'));
});

test('I01/M04: path target is in idempotency digest and audit failure rolls back bind',async t=>{
 const x=await setup(t),one=bound(x),other=await x.service.login(x.fixture('synthetic-other')),two=bound(x,'SYNTHETIC-REF-002',other);
 const request={...intent(START),expectedRevision:{binding:1,registry:1},reasonCategory:'qualification-withdrawn'};
 x.m.update(x.op.token,one.bindingId,request,true);assert.throws(()=>x.m.update(x.op.token,two.bindingId,request,true),e=>e.code==='IDEMPOTENCY_CONFLICT');assert.equal(x.m.profile(other.token).derivedState,'valid');
 assert.throws(()=>x.m.lookup(x.user.token,{code:'00000000'}),e=>e.code==='FORBIDDEN');
 const third=await x.service.login(x.fixture('synthetic-third')),p=pair(x,third),f=form(x,p,'SYNTHETIC-REF-003');
 x.db.exec("CREATE TRIGGER fail_member_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test'); END");assert.throws(()=>x.m.bind(x.op.token,f));x.db.exec('DROP TRIGGER fail_member_audit');assert.equal(x.m.pairing(third.token).state,'active');assert.equal(x.m.profile(third.token).derivedState,'pending');
});

test('M05/X01/C02: fresh auth boundaries, lost-response receipt, failed bounded deletion resumes and new login inherits nothing',async t=>{
 const x=await setup(t),b=bound(x);revoke(x,b);x.service.changeRole(roleWrite(x.c,x.user.userId,START));
 x.time(START+300001);const req=deleteRequest(x);assert.throws(()=>x.m.deleteAccount(x.user.token,req.body),e=>e.code==='FRESH_AUTH_REQUIRED');
 x.time(START+300000);req.body.requestCreatedAt=new Date(x.now()).toISOString();x.m.deleteAccount(x.user.token,req.body);
 assert.equal(x.m.deletionStatus(req.receipt).state,'pending');assert.throws(()=>x.m.profile(x.user.token),e=>e.code==='SESSION_INVALID');await assert.rejects(()=>x.service.login(x.fixture('synthetic-member')),e=>e.code==='ACCOUNT_DELETING');
 let fail=true;const cleanup=createMemberCleanup(x.db,x.c,{now:x.now,afterBatch:phase=>{if(phase===2&&fail){fail=false;throw new Error('injected');}}});await cleanup.run(true);assert.equal(x.m.deletionStatus(req.receipt).state,'failed');
 x.time(START+2*86400000);assert.equal(x.m.deletionStatus(req.receipt).delayed,true);
 const db=openDatabase(x.c),newIdentity=createIdentityService(db,x.c,{now:x.now}),resumed=createMemberCleanup(db,x.c,{now:x.now});await resumed.run(true);
 const m=createMembershipService(db,x.c,newIdentity);assert.equal(m.deletionStatus(req.receipt).state,'completed');assert.equal(db.prepare('SELECT 1 FROM accounts WHERE user_id=?').get(x.user.userId),undefined);
 assert.equal(db.prepare('SELECT state FROM member_registry').get().state,'revoked');assert.equal(db.prepare('SELECT count(*) n FROM audit WHERE actor_id=? OR subject_id=?').get(x.user.userId,x.user.userId).n,0);
 const fresh=await newIdentity.login(x.fixture('synthetic-member'));assert.notEqual(fresh.userId,x.user.userId);assert.equal(m.profile(fresh.token).derivedState,'unbound');assert.equal(newIdentity.role(fresh.token).isOperator,false);
 const completion=x.now();x.time(completion+7*86400000);assert.equal(m.deletionStatus(req.receipt).state,'unknown');db.close();
});

test('M03 timezone conversion: configured non+8 zones, DST changes, skipped and repeated midnight',()=>{
 assert.equal(dateEnd('2026-01-10','America/New_York').validUntil,'2026-01-11T05:00:00.000Z');
 assert.equal(dateEnd('2026-03-08','America/New_York').validUntil,'2026-03-09T04:00:00.000Z');
 assert.equal(dateEnd('2026-06-01','Asia/Kathmandu').validUntil,'2026-06-01T18:15:00.000Z');
 assert.throws(()=>dateEnd('2011-12-29','Pacific/Apia'),e=>e.code==='TIMEZONE_NONEXISTENT');
 assert.throws(()=>dateEnd('2026-10-31','America/Havana'),e=>e.code==='TIMEZONE_AMBIGUOUS');
 assert.equal(expiry({mode:'fixed_until',localEndDate:'2026-10-31',validUntil:'2026-11-01T05:00:00.000Z'},'America/Havana').until,Date.parse('2026-11-01T05:00:00.000Z'));
});

test('C01 bounded deletion/retention cursor: at most 100 rows, exact cutoffs and permanent revoked registry',async t=>{
 const x=await setup(t),b=bound(x);revoke(x,b);const req=deleteRequest(x);x.m.deleteAccount(x.user.token,req.body);
 const insert=x.db.prepare('INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,1)');
 for(let n=0;n<205;n++)insert.run('synthetic-session-'+n,x.user.userId,'synthetic-digest-'+n,START,START,START,START+86400000,START);
 const cleaner=createMemberCleanup(x.db,x.c,{now:x.now});cleaner.batch(true);
 assert.equal(x.db.prepare('SELECT count(*) n FROM sessions WHERE user_id=?').get(x.user.userId).n,106);assert.equal(x.db.prepare('SELECT phase FROM deletion_jobs').get().phase,0);
 const reopened=openDatabase(x.c),continued=createMemberCleanup(reopened,x.c,{now:x.now});await continued.run(true);reopened.close();
 assert.equal(x.m.deletionStatus(req.receipt).state,'completed');assert.equal(x.db.prepare('SELECT state FROM member_registry').get().state,'revoked');
 const limit=x.db.prepare('INSERT INTO membership_limits VALUES(?,?,?,?,1)');limit.run('generation','synthetic-old',START-600000,START);limit.run('generation','synthetic-new',START,START+600000);
 x.time(START+3600000-1);await cleaner.run(true);assert.equal(x.db.prepare("SELECT count(*) n FROM membership_limits WHERE subject_id LIKE 'synthetic-%'").get().n,2);
 x.time(START+3600000);await cleaner.run(true);assert.equal(x.db.prepare("SELECT count(*) n FROM membership_limits WHERE subject_id LIKE 'synthetic-%'").get().n,1);
 x.time(START+7*86400000);await cleaner.run(true);assert.equal(x.db.prepare('SELECT count(*) n FROM deletion_jobs').get().n,0);assert.equal(x.db.prepare('SELECT state FROM member_registry').get().state,'revoked');
});
