import {startService as server,stop,spawn,fork,bounded,fixtureLifetime} from './process-support.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {once} from 'node:events';
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {randomBytes} from 'node:crypto';
import {setupIdentity,intent,roleWrite} from './identity-support.mjs';
import {createMembershipService} from '../server/membership.mjs';
import {digest} from '../server/protocol.mjs';
import {root} from '../server/config.mjs';
async function setup(t){const time=Date.now(),x=setupIdentity(t,{now:()=>time});const op=await x.service.login(x.fixture('synthetic-op')),user=await x.service.login(x.fixture('synthetic-member'));x.service.changeRole(roleWrite(x.c,op.userId,time));return{...x,time,op,user,m:createMembershipService(x.db,x.c,x.service)};}
function bindRequest(x,user=x.user,ref='SYNTHETIC-HTTP-REF'){
 const p=x.m.createPair(user.token,intent(x.time,'absent')).data;return{...intent(x.time),expectedRevision:{pairing:p.revision,binding:'absent',registry:'absent'},pairingId:p.pairingId,code:p.displayCode,memberRef:ref,frontDeskConfirmed:true,expiry:{mode:'no_fixed_expiry'}};
}
let sequence=0;
function inputFile(x,input){const file=resolve(x.c.stateDir,`member-worker-${++sequence}.json`);writeFileSync(file,JSON.stringify({time:x.time,...input}),{mode:0o600});return file;}
function run(x,input){return new Promise((res,rej)=>{const p=spawn(process.execPath,['test/member-worker.mjs',x.path,inputFile(x,input)],{cwd:root,stdio:['ignore','pipe','ignore']});let out='';p.stdout.on('data',c=>{out+=c;});p.on('exit',code=>code===0?res(JSON.parse(out)):rej(new Error('worker failed')));});}
async function controlled(t,x,input){const p=fork('test/member-worker.mjs',[x.path,inputFile(x,input)],{cwd:root,stdio:['ignore','ignore','ignore','ipc']});const end=once(p,'exit'),queue=[],waiting=[];p.on('message',m=>waiting.length?waiting.shift()(m):queue.push(m));const next=()=>queue.length?Promise.resolve(queue.shift()):bounded(new Promise(r=>waiting.push(r)));assert.equal(await next(),'ready');return{p,next,end};}

test('M02 actual processes: same member and same requester races preserve both unique constraints',async t=>{
 const x=await setup(t),other=await x.service.login(x.fixture('synthetic-other'));
 const [one,two]=[bindRequest(x),bindRequest(x,other)];const results=await Promise.all([run(x,{action:'bind',token:x.op.token,body:one}),run(x,{action:'bind',token:x.op.token,body:two})]);
 assert.equal(results.filter(r=>r.status==='committed').length,1);assert.equal(x.db.prepare('SELECT count(*) n FROM bindings WHERE current=1').get().n,1);
 const third=await x.service.login(x.fixture('synthetic-third')),request=bindRequest(x,third,'SYNTHETIC-OTHER-REF');
 const result=await Promise.all([run(x,{action:'bind',token:x.op.token,body:request}),run(x,{action:'bind',token:x.op.token,body:{...request,...intent(x.time),expectedRevision:request.expectedRevision,memberRef:'SYNTHETIC-ALTERNATIVE-REF'}})]);
 assert.equal(result.filter(r=>r.status==='committed').length,1);assert.equal(x.db.prepare('SELECT count(*) n FROM bindings WHERE current=1 AND user_id=?').get(third.userId).n,1);
});

test('X01 real write locks: delete before bind rejects old work; bind before delete closes its effect',async t=>{
 for(const first of ['deleteAccount','bind']){
  const x=await setup(t),b=bindRequest(x),receipt=randomBytes(32).toString('hex'),d={...intent(x.time),confirmed:true,receiptDigest:digest(receipt)};
  const release=resolve(x.c.stateDir,'release');const inputs={bind:{action:'bind',token:x.op.token,body:b},deleteAccount:{action:'deleteAccount',token:x.user.token,body:d}};
  const second=first==='bind'?'deleteAccount':'bind';
  const a=await controlled(t,x,{...inputs[first],hold:true,release}),other=await controlled(t,x,inputs[second]);
  a.p.send('run');assert.equal(await a.next(),'attempting');assert.equal(await a.next(),'locked');
  other.p.send('run');assert.equal(await other.next(),'attempting');writeFileSync(release,'release');assert.equal((await a.next()).status,'committed');await a.end;const r=await other.next();await other.end;
  assert.equal(r.status==='committed',first==='bind');assert.equal(x.db.prepare('SELECT count(*) n FROM bindings WHERE current=1 AND user_id=?').get(x.user.userId).n,0);
  assert.equal(x.db.prepare('SELECT state FROM accounts WHERE user_id=?').get(x.user.userId).state,'deleting');
 }
});


test('D01/C02 real HTTP clients and process restart: lost receipt recovery, failing cleanup, new identity without inheritance',async t=>{
 const x=await setup(t),request=bindRequest(x);const bound=x.m.bind(x.op.token,request).data;
 x.m.update(x.op.token,bound.bindingId,{...intent(x.time),expectedRevision:{binding:1,registry:1},reasonCategory:'qualification-withdrawn'},true);
 let s=await server(x.path);
 const client=()=>async(path,body,token,scheme='Bearer')=>{const response=await fetch(`http://127.0.0.1:${s.port}/v1${path}`,{method:body?'POST':'GET',headers:{'content-type':'application/json',...(token?{authorization:scheme+' '+token}:{})},body:body?JSON.stringify(body):undefined});return{status:response.status,body:await response.json()};};
 const one=client(),two=client();assert.deepEqual((await one('/me/membership',null,x.user.token)).body.data,(await two('/me/membership',null,x.user.token)).body.data);
 const receipt=randomBytes(32).toString('hex');await one('/me/account/delete',{...intent(Date.now()),confirmed:true,receiptDigest:digest(receipt)},x.user.token);
 assert.equal((await two('/me/membership',null,x.user.token)).status,401);assert.equal((await two('/deletions/status',{},receipt,'DeletionReceipt')).body.data.state,'pending');
 x.db.exec("CREATE TRIGGER fail_cleanup BEFORE DELETE ON bindings BEGIN SELECT RAISE(ABORT,'test'); END");
 const failed=spawnSync(process.execPath,['scripts/member-cleanup.mjs','run',x.path],{cwd:root,encoding:'utf8'});assert.equal(failed.status,1);assert.equal((await two('/deletions/status',{},receipt,'DeletionReceipt')).body.data.state,'failed');
 await stop(s.p);x.db.exec('DROP TRIGGER fail_cleanup');s=await server(x.path);
 let status;for(let i=0;i<100;i++){status=(await two('/deletions/status',{},receipt,'DeletionReceipt')).body.data.state;if(status==='completed')break;await new Promise(r=>setTimeout(r,10));}assert.equal(status,'completed');
 const fresh=(await one('/sessions/exchange',x.fixture('synthetic-member'))).body.data;assert.notEqual(fresh.userId,x.user.userId);
 assert.equal((await two('/me/membership',null,fresh.token)).body.data.derivedState,'unbound');assert.equal((await one('/operator/role',null,fresh.token)).body.data.isOperator,false);
 assert.equal(x.db.prepare('SELECT state FROM member_registry').get().state,'revoked');t.diagnostic('two HTTP clients, failed deletion cleanup and whole-process restart recovered; new user has no role or binding');
});

test('R01 independent processes/session switching: persistent generation/errors; lock-time next window and storage failure',async t=>{
 const x=await setup(t);const boundary=Math.floor(x.time/600000)*600000+600000,previous=boundary-1;
 for(let n=0;n<3;n++)assert.equal((await run(x,{time:previous,action:'createPair',token:x.user.token,body:intent(previous,x.m.pairing(x.user.token).revision)})).status,'committed');
 const newSession=await x.service.login(x.fixture('synthetic-member'));
 assert.equal((await run(x,{time:previous,action:'createPair',token:newSession.token,body:intent(previous,x.m.pairing(newSession.token).revision)})).status,'RATE_LIMITED');
 const clockFile=resolve(x.c.stateDir,'clock'),release=resolve(x.c.stateDir,'window-release');writeFileSync(clockFile,String(previous));
 const lock=await controlled(t,x,{time:previous,action:'profile',token:x.op.token,hold:true,release});
 const waiter=await controlled(t,x,{timeFile:clockFile,action:'createPair',token:newSession.token,body:intent(previous,x.m.pairing(newSession.token).revision)});
 lock.p.send('run');assert.equal(await lock.next(),'attempting');assert.equal(await lock.next(),'locked');waiter.p.send('run');assert.equal(await waiter.next(),'attempting');writeFileSync(clockFile,String(boundary));writeFileSync(release,'release');
 assert.equal((await lock.next()).status,'committed');assert.equal((await waiter.next()).status,'committed');await Promise.all([lock.end,waiter.end]);
 assert.equal(x.db.prepare("SELECT count FROM membership_limits WHERE kind='generation' AND subject_id=? AND window_start=?").get(x.user.userId,boundary).count,1);
 const p=x.m.pairing(newSession.token);const op2=await x.service.login(x.fixture('synthetic-op'));
 for(let n=0;n<5;n++)assert.equal((await run(x,{time:boundary,action:'inspect',token:n%2?op2.token:x.op.token,body:{memberRef:'SYNTHETIC',pairingId:p.pairingId,code:'not-eight-digits'}})).status,'PAIRING_UNAVAILABLE');
 assert.equal((await run(x,{time:boundary,action:'lookup',token:op2.token,body:{code:p.displayCode}})).status,'RATE_LIMITED');
 for(const kind of ['operator-error','requester-error'])assert.equal(x.db.prepare('SELECT count FROM membership_limits WHERE kind=? AND window_start=?').get(kind,boundary).count,5);
 const separate=await x.service.login(x.fixture('synthetic-separate-op'));x.service.changeRole(roleWrite(x.c,separate.userId,x.time));
 assert.equal((await run(x,{time:boundary,action:'lookup',token:separate.token,body:{code:'not-a-code'}})).status,'PAIRING_UNAVAILABLE');assert.equal(x.db.prepare("SELECT count(*) n FROM membership_limits WHERE kind='requester-error'").get().n,1);
 const before=x.db.prepare('SELECT sum(count) n FROM membership_limits').get().n;x.db.exec("CREATE TRIGGER fail_pair_counter BEFORE INSERT ON membership_limits BEGIN SELECT RAISE(ABORT,'test'); END");
 assert.equal((await run(x,{time:boundary,action:'lookup',token:separate.token,body:{code:'not-a-code'}})).status,'ERR_SQLITE_ERROR');x.db.exec('DROP TRIGGER fail_pair_counter');assert.equal(x.db.prepare('SELECT sum(count) n FROM membership_limits').get().n,before);
});
