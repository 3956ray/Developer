import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setupDemo } from './demo-support.mjs';
import { createDemoCleanup } from '../server/demo-cleanup.mjs';
const DAY=86400000,START=Date.UTC(2026,8,17);
test('DM09/12 mixed import/run/step backlog shares 100 deleted-row budget, yields, rolls back failure and retains active/review state',async t=>{
 const x=setupDemo(t);let time=START;
 x.db.prepare("INSERT INTO source_bindings(singleton,source_id,gym_id,source_label,schema_version,mapping_version,time_zone) VALUES(1,'cleanup',?,'合成',1,1,'Asia/Taipei')").run(x.c.gymId);
 const insertBatch=x.db.prepare("INSERT INTO import_batches VALUES(?,'cleanup',?,'hash',?, ?,?,'candidate','{}','{}',NULL,NULL)");
 for(let n=0;n<140;n++)insertBatch.run(randomUUID(),n+1,new Date(START-2*DAY).toISOString(),START-2*DAY,START-DAY);
 const insertRun=x.db.prepare('INSERT INTO demo_runs(run_id,scenario,anchor_date,state,created_at,updated_at,terminal_at) VALUES(?,?,?,?,?,?,?)');
 const insertStep=x.db.prepare("INSERT INTO demo_steps VALUES(?,?,'operator-a',?,'/v1/operator/observations','POST','observation.publish','{}','committed','{}')");
 for(let n=0;n<30;n++){const id=randomUUID();insertRun.run(id,'crowd-v1','2026-08-01','completed',START-32*DAY,START-31*DAY,START-31*DAY);for(let step=0;step<4;step++)insertStep.run(id,step,'0'.repeat(64));}
 const active=randomUUID();insertRun.run(active,'crowd-v1','2026-08-01','needs_review',START-32*DAY,START-31*DAY,null);insertStep.run(active,0,'1'.repeat(64));
 const count=()=>['import_batches','demo_runs','demo_steps'].map(table=>x.db.prepare('SELECT count(*) n FROM '+table).get().n).reduce((a,b)=>a+b,0);
 const original=count();assert.equal(original,292);
 const failure=createDemoCleanup(x.db,x.c,{now:()=>time,afterDelete:()=>{throw new Error('injected');}});assert.equal((await failure.run()).failed,true);assert.equal(count(),original);assert.equal(failure.status().deleted_total,0);
 time+=3600000;let previous=count(),batches=0,yielded=false;const sizes=[];
 setImmediate(()=>{yielded=true;});
 const cleanup=createDemoCleanup(x.db,x.c,{now:()=>time,afterDelete:()=>{const remaining=count(),removed=previous-remaining;assert.ok(removed<=100);if(batches>0)assert.equal(yielded,true);sizes.push(removed);previous=remaining;batches++;}});
 await cleanup.run();assert.deepEqual(sizes,[100,100,90]);assert.equal(cleanup.status().deleted_total,290);assert.equal(count(),2);assert.equal(x.db.prepare('SELECT state FROM demo_runs WHERE run_id=?').get(active).state,'needs_review');assert.equal(x.db.prepare('SELECT count(*) n FROM demo_steps WHERE run_id=?').get(active).n,1);
 t.diagnostic('mixed backlog deleted 100 + 100 + 90 rows including all child steps; batch failure rolled back; event loop yielded; active review run retained');
});
