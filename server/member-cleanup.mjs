import { transaction } from './database.mjs';
const HOUR=3600000, DAY=86400000;
export function createMemberCleanup(db,c,options={}) {
 if(c.environment!=='test'&&(options.now||options.afterBatch))throw new Error('TEST_HOOK_FORBIDDEN');
 const now=options.now??Date.now;
 const status=()=>db.prepare('SELECT * FROM membership_cleanup WHERE singleton=1').get();
 const remove=(table,where,args)=>db.prepare(`DELETE FROM ${table} WHERE rowid IN (SELECT rowid FROM ${table} WHERE ${where} LIMIT 100)`).run(...args).changes;
 function jobBatch(job,time) {
  const user=job.user_id;let changed=0;
  const phases=[
   ()=>remove('sessions','user_id=?',[user]),()=>remove('pairings','user_id=?',[user]),
   ()=>remove('bindings','user_id=?',[user]),()=>remove('operations','actor_id=? OR subject_id=?',[user,user]),
   ()=>remove('privacy_consents','user_id=?',[user]),()=>remove('membership_limits','subject_id=?',[user]),
   ()=>db.prepare("UPDATE audit SET actor_id=CASE WHEN actor_id=? THEN '已删除账户' ELSE actor_id END,subject_id=CASE WHEN subject_id=? THEN '已删除账户' ELSE subject_id END WHERE id IN (SELECT id FROM audit WHERE actor_id=? OR subject_id=? LIMIT 100)").run(user,user,user,user).changes,
   ()=>db.prepare("UPDATE observation_events SET actor_id='已删除账户' WHERE revision IN (SELECT revision FROM observation_events WHERE actor_id=? LIMIT 100)").run(user).changes,
   ()=>remove('test_login_fixtures','synthetic_subject IN (SELECT open_id FROM wechat_identities WHERE user_id=?)',[user])
  ];
  if(job.phase<phases.length)changed=phases[job.phase]();
  else {
   for(const table of ['sessions','pairings','bindings','privacy_consents'])if(db.prepare(`SELECT 1 FROM ${table} WHERE user_id=? LIMIT 1`).get(user))throw new Error('PERSONAL_ROWS_REMAIN');
   for(const table of ['operations','audit'])if(db.prepare(`SELECT 1 FROM ${table} WHERE actor_id=? OR subject_id=? LIMIT 1`).get(user,user))throw new Error('PERSONAL_ROWS_REMAIN');
   if(db.prepare('SELECT 1 FROM membership_limits WHERE subject_id=? LIMIT 1').get(user)||db.prepare('SELECT 1 FROM observation_events WHERE actor_id=? LIMIT 1').get(user))throw new Error('PERSONAL_ROWS_REMAIN');
   db.prepare('DELETE FROM operator_roles WHERE user_id=?').run(user);
   db.prepare('DELETE FROM wechat_identities WHERE user_id=?').run(user);
   db.prepare("UPDATE deletion_jobs SET user_id=NULL,state='completed',completed_at=?,last_error=NULL WHERE job_id=?").run(time,job.job_id);
   db.prepare('DELETE FROM accounts WHERE user_id=?').run(user);
  }
  if(options.afterBatch)options.afterBatch(job.phase);
  if(job.phase<phases.length)db.prepare("UPDATE deletion_jobs SET state='pending',phase=?,last_error=NULL WHERE job_id=?").run(changed<100?job.phase+1:job.phase,job.job_id);
 }
 function retention(phase,time) {
  const actions=[
   ()=>remove('pairings',"state!='active' OR expires_at<=?",[time]),
   ()=>remove('membership_limits','window_end+?<=?',[HOUR,time]),
   ()=>remove('bindings','current=0 AND closed_at+?<=?',[30*DAY,time]),
   ()=>remove('sessions','min(coalesce(revoked_at,expires_at),expires_at,last_interactive_at+?)+?<=?',[DAY,23*HOUR,time]),
   ()=>remove('operations','created_at+?<=?',[DAY,time]),
   ()=>remove('audit','time+?<=?',[90*DAY,time]),
   ()=>remove('rate_buckets','window_end+?<=?',[HOUR,time]),
   ()=>remove('login_codes','attempted_at+?<=?',[DAY,time]),
   ()=>remove('deletion_jobs',"state='completed' AND completed_at+?<=?",[7*DAY,time])
  ];
  return phase<actions.length?{changed:actions[phase](),last:false}:{changed:0,last:true};
 }
 function batch(force=false) {
  let jobId;
  try {
   return transaction(db,()=>{
    const time=now(),progress=status();
    if(!force&&progress.next_run_at>time)return {done:true,skipped:true};
    const job=db.prepare("SELECT * FROM deletion_jobs WHERE state!='completed' AND next_retry_at<=? ORDER BY accepted_at,job_id LIMIT 1").get(force?Number.MAX_SAFE_INTEGER:time);
    if(job) {jobId=job.job_id;jobBatch(job,time);db.prepare('UPDATE membership_cleanup SET next_run_at=0 WHERE singleton=1').run();return {done:false};}
    const result=retention(progress.phase,time);if(options.afterBatch)options.afterBatch('retention');
    db.prepare('UPDATE membership_cleanup SET phase=?,next_run_at=?,last_success_at=?,last_error=NULL WHERE singleton=1').run(result.last?0:result.changed<100?progress.phase+1:progress.phase,result.last?now()+HOUR:0,result.last?now():progress.last_success_at);
    return {done:result.last};
   });
  } catch {
   try { transaction(db,()=>{
    if(jobId)db.prepare("UPDATE deletion_jobs SET state='failed',error_count=error_count+1,last_error='CLEANUP_FAILED',next_retry_at=? WHERE job_id=? AND state!='completed'").run(now()+HOUR,jobId);
    db.prepare("UPDATE membership_cleanup SET next_run_at=?,error_count=error_count+1,last_error='CLEANUP_FAILED' WHERE singleton=1").run(now()+HOUR);
   }); }catch{}
   return {done:true,failed:true};
  }
 }
 async function run(force=false) {let result=batch(force);while(!result.done){await new Promise(r=>setImmediate(r));result=batch(force);}return result;}
 function retry(jobId) {return transaction(db,()=>{const result=db.prepare("UPDATE deletion_jobs SET next_retry_at=0 WHERE job_id=? AND state!='completed'").run(jobId);if(result.changes!==1)throw new Error('JOB_NOT_PENDING');db.prepare('UPDATE membership_cleanup SET next_run_at=0 WHERE singleton=1').run();});}
 return {now,status,batch,run,retry};
}
