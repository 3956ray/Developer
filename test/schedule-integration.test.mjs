import {startService as server,stop,spawn,fork,bounded,fixtureLifetime} from './process-support.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {setupIdentity,intent,roleWrite} from './identity-support.mjs';
import {createScheduleService} from '../server/schedules.mjs';
import {root} from '../server/config.mjs';
let sequence=0;
async function setup(t){const time=Date.now(),x=setupIdentity(t,{now:()=>time}),op=await x.service.login(x.fixture('synthetic-schedule-op'));x.service.changeRole(roleWrite(x.c,op.userId,time));const s=createScheduleService(x.db,x.c,x.service);return{...x,op,s,time};}
const saveBody=x=>({...intent(x.time,'absent'),coverage:{startDate:'2026-09-16',endDate:'2026-09-23'},courses:[]});
const publishBody=x=>({...intent(x.time),expectedRevision:{draft:1,publication:'absent'},replaceCoverageConfirmed:true});
async function worker(t,x,input){const path=resolve(x.c.stateDir,`schedule-${++sequence}.json`);writeFileSync(path,JSON.stringify({time:x.time,...input}),{mode:0o600});const p=fork('test/schedule-worker.mjs',[x.path,path],{cwd:root,stdio:['ignore','ignore','ignore','ipc']}),end=once(p,'exit'),queue=[],wait=[];p.on('message',m=>wait.length?wait.shift()(m):queue.push(m));const next=()=>queue.length?Promise.resolve(queue.shift()):bounded(new Promise(r=>wait.push(r)));assert.equal(await next(),'ready');return{p,next,end};}
async function race(a,b){a.p.send('run');b.p.send('run');assert.equal(await a.next(),'attempting');assert.equal(await b.next(),'attempting');const results=await Promise.all([a.next(),b.next()]);await Promise.all([a.end,b.end]);return results;}
test('Z01 actual competing processes: draft CAS and dual publication CAS admit exactly one writer',async t=>{const x=await setup(t);let a=await worker(t,x,{action:'save',token:x.op.token,body:saveBody(x)}),b=await worker(t,x,{action:'save',token:x.op.token,body:saveBody(x)});assert.deepEqual((await race(a,b)).map(x=>x.status).sort(),['REVISION_CONFLICT','committed']);a=await worker(t,x,{action:'publish',token:x.op.token,body:publishBody(x)});b=await worker(t,x,{action:'publish',token:x.op.token,body:publishBody(x)});assert.deepEqual((await race(a,b)).map(x=>x.status).sort(),['REVISION_CONFLICT','committed']);assert.equal(x.db.prepare('SELECT count(*) n FROM schedule_snapshots').get().n,1);});

test('M04 actual role write lock before schedule publish blocks former operator',async t=>{const x=await setup(t);x.s.save(x.op.token,saveBody(x));const release=resolve(x.c.stateDir,'release'),a=await worker(t,x,{action:'revoke',body:{...roleWrite(x.c,x.op.userId,x.time),action:'revoke',expectedRevision:1},hold:true,release}),b=await worker(t,x,{action:'publish',token:x.op.token,body:publishBody(x)});a.p.send('run');assert.equal(await a.next(),'attempting');assert.equal(await a.next(),'locked');b.p.send('run');assert.equal(await b.next(),'attempting');writeFileSync(release,'release');assert.equal((await a.next()).status,'committed');assert.equal((await b.next()).status,'FORBIDDEN');await Promise.all([a.end,b.end]);assert.equal(x.s.read('2026-09-16','2026-09-17').data.state,'unpublished');});

test('S01/S04/D01 two public HTTP clients: private draft, atomic publish, lost write result, process restart and withdrawal',async t=>{const x=await setup(t);let svc=await server(x.path);const client=()=>async(path,method='GET',body,token)=>{const response=await fetch(`http://127.0.0.1:${svc.port}/v1${path}`,{method,headers:{'content-type':'application/json',...(token?{authorization:'Bearer '+token}:{})},body:body?JSON.stringify(body):undefined});return{status:response.status,body:await response.json()};};const a=client(),b=client(),url='/schedule?from=2026-09-16&to=2026-09-17';
 assert.equal((await a('/operator/schedule/draft')).status,401);assert.equal((await a('/operator/schedule/draft','PUT',saveBody(x),x.op.token)).status,200);assert.equal((await b(url)).body.data.state,'unpublished');
 const publish=publishBody(x);await a('/operator/schedule/publish','POST',publish,x.op.token);const queried=await b('/operations/'+publish.operationId+'?type=schedule.publish','GET',undefined,x.op.token);assert.equal(queried.body.data.state,'committed');assert.deepEqual((await a(url)).body.data,(await b(url)).body.data);assert.equal((await a(url)).body.data.days[0].state,'empty');
 await stop(svc.p);svc=await server(x.path);assert.equal((await a(url)).body.data.publicationRevision,1);await a('/operator/schedule/withdraw','POST',{...intent(Date.now(),1),reasonCategory:'correction'},x.op.token);assert.equal((await b(url)).body.data.state,'withdrawn');assert.equal((await b('/schedule?from=bad&to=bad')).status,422);assert.equal((await b('/venue')).body.data.scheduleSummary.state,'withdrawn');
 await stop(svc.p);x.db.prepare('UPDATE schedule_snapshots SET coverage_end_at=?').run(Date.now()-31*86400000);x.db.exec("CREATE TRIGGER fail_schedule_cleanup BEFORE DELETE ON schedule_snapshots BEGIN SELECT RAISE(ABORT,'test'); END");svc=await server(x.path);assert.equal(x.db.prepare('SELECT last_error FROM schedule_cleanup').get().last_error,'CLEANUP_FAILED');assert.equal((await b(url)).body.data.state,'withdrawn');await stop(svc.p);x.db.exec('DROP TRIGGER fail_schedule_cleanup');svc=await server(x.path);assert.equal(x.db.prepare('SELECT count(*) n FROM schedule_snapshots').get().n,0);assert.equal((await b(url)).body.data.state,'withdrawn');

});


test('Z01 real draft-write lock before publication rejects previously confirmed draft revision',async t=>{
 const x=await setup(t);x.s.save(x.op.token,saveBody(x));const release=resolve(x.c.stateDir,'draft-release'),a=await worker(t,x,{action:'save',token:x.op.token,body:{...saveBody(x),expectedRevision:1},hold:true,release}),b=await worker(t,x,{action:'publish',token:x.op.token,body:publishBody(x)});
 a.p.send('run');assert.equal(await a.next(),'attempting');assert.equal(await a.next(),'locked');b.p.send('run');assert.equal(await b.next(),'attempting');writeFileSync(release,'release');assert.equal((await a.next()).status,'committed');assert.equal((await b.next()).status,'REVISION_CONFLICT');await Promise.all([a.end,b.end]);assert.equal(x.s.read('2026-09-16','2026-09-17').data.state,'unpublished');
});
