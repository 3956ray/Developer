import { transaction } from './database.mjs';
const DAY=86400000;
export function createDemoCleanup(db,c,options={}){
 if(c.environment!=='test'&&Object.keys(options).length)throw new Error('TEST_HOOK_FORBIDDEN');
 const now=options.now??Date.now,status=()=>db.prepare('SELECT * FROM demo_cleanup WHERE singleton=1').get();
 function batch(force=false){try{return transaction(db,()=>{
  const time=now();if(!force&&status().next_run_at>time)return{done:true,skipped:true};
  let removed=db.prepare("DELETE FROM import_batches WHERE batch_id IN (SELECT batch_id FROM import_batches WHERE (state!='applied' AND expires_at<=?) OR (state='applied' AND applied_at+?<=?) LIMIT 100)").run(time,30*DAY,time).changes;
  // One shared deletion budget includes child steps; parent deletion cannot cascade
  // uncounted rows because only parents with no remaining steps are eligible.
  if(removed<100)removed+=db.prepare('DELETE FROM demo_steps WHERE rowid IN (SELECT rowid FROM demo_steps WHERE run_id IN (SELECT run_id FROM demo_runs WHERE terminal_at IS NOT NULL AND terminal_at+?<=?) LIMIT ?)').run(30*DAY,time,100-removed).changes;
  if(removed<100)removed+=db.prepare('DELETE FROM demo_runs WHERE run_id IN (SELECT run_id FROM demo_runs WHERE terminal_at IS NOT NULL AND terminal_at+?<=? AND NOT EXISTS(SELECT 1 FROM demo_steps WHERE demo_steps.run_id=demo_runs.run_id) LIMIT ?)').run(30*DAY,time,100-removed).changes;
  if(options.afterDelete)options.afterDelete();const done=removed<100;
  db.prepare('UPDATE demo_cleanup SET next_run_at=?,last_success_at=CASE WHEN ? THEN ? ELSE last_success_at END,deleted_total=deleted_total+?,last_error=NULL WHERE singleton=1').run(done?time+3600000:0,done?1:0,time,removed);return{done,removed:removed};
 });}catch{try{transaction(db,()=>db.prepare("UPDATE demo_cleanup SET next_run_at=?,error_count=error_count+1,last_error='CLEANUP_FAILED' WHERE singleton=1").run(now()+3600000));}catch{}return{done:true,failed:true};}}
 async function run(force=false){let result=batch(force);while(!result.done){await new Promise(r=>setImmediate(r));result=batch(force);}return result;}
 return{now,status,batch,run};
}
