import { randomUUID } from 'node:crypto';
import { transaction } from './database.mjs';
import { ApiError, fail, digest } from './protocol.mjs';
import { dateMs } from './schedule-time.mjs';
import { requireDemo } from './demo.mjs';
import { scenarioSteps, actorSession, initialState, prepareStep, sendBody } from './demo-scenarios.mjs';
export function createDemoRunner(db,c,baseUrl,options={}){
 requireDemo(c);if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(baseUrl)||c.timeZone!=='Asia/Taipei')fail(422,'DEMO_ENDPOINT_INVALID');
 const now=options.now??Date.now,owner=randomUUID();
 async function http(path,method='GET',body,token,scheme='Bearer'){
  let response,b;try{response=await fetch(baseUrl+path,{method,headers:{'content-type':'application/json',...(token?{authorization:scheme+' '+token}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(10000)});b=await response.json();}catch{fail(503,'NETWORK_UNCONFIRMED');}
  const n=b?.namespace;if(!n||n.environment!==c.environment||n.gymId!==c.gymId||n.identityMode!=='test'||n.simulation!==true||n.demoEnabled!==true)fail(503,'DEMO_NAMESPACE_MISMATCH');
  if(!response.ok||b.ok!==true)fail(response.status,b.error?.code??'INVALID_RESPONSE');return b;
 }
 function recoverLock(){const lock=db.prepare('SELECT * FROM demo_runner_lock WHERE singleton=1').get();if(!lock)return;
  try{process.kill(lock.pid,0);return;}catch(e){if(e.code!=='ESRCH')return;}
  db.prepare("UPDATE demo_runs SET state='interrupted',updated_at=?,error_code='PROCESS_INTERRUPTED' WHERE run_id=? AND terminal_at IS NULL AND state='running'").run(now(),lock.run_id);
  db.prepare('DELETE FROM demo_runner_lock WHERE singleton=1 AND owner=?').run(lock.owner);
 }
 function acquire(runId){transaction(db,()=>{recoverLock();if(db.prepare('SELECT 1 FROM demo_runner_lock').get())fail(409,'RUNNER_BUSY');db.prepare('INSERT INTO demo_runner_lock VALUES(1,?,?,?)').run(owner,process.pid,runId);});}
 function release(){transaction(db,()=>db.prepare('DELETE FROM demo_runner_lock WHERE owner=?').run(owner));}
 function get(runId){const r=db.prepare('SELECT * FROM demo_runs WHERE run_id=?').get(runId);if(!r)fail(404,'RUN_NOT_FOUND');return r;}
 function summary(r){return{runId:r.run_id,scenario:r.scenario,scenarioVersion:1,anchorDate:r.anchor_date,stepId:r.step_index,state:r.state,lastRevision:r.last_revision_json?JSON.parse(r.last_revision_json):null,error:r.error_code};}
 function committed(run,step,response){
  const data=response.result??response.data,rev=response.appliedRevision??response.operation?.appliedRevision,time=response.committedAt??response.operation?.committedAt;
  const last=JSON.parse(run.last_revision_json),type=step.operation_type,body=JSON.parse(step.request_json);
  if(type.startsWith('observation.'))last.observation=rev;
  if(type==='schedule.draft.save')last.draft=rev;
  if(type==='schedule.publish'||type==='schedule.withdraw')last.publication=rev;
  if(type==='pairing.create'&&step.actor_alias==='member-a')last.pairing=rev;
  if(type==='membership.bind'||type==='membership.restore'){last.binding=data.bindingRevision;last.registry=data.registryRevision;last.pairing=body.expectedRevision.pairing+1;}
  if(type==='membership.revoke'){last.binding=data.bindingRevision;last.registry=data.registryRevision;}
  const next=run.step_index+1,done=next>=scenarioSteps(run.scenario).length;
  const safe={operationId:body.operationId,appliedRevision:rev??null,committedAt:time??null};
  transaction(db,()=>{db.prepare("UPDATE demo_steps SET state='committed',result_json=? WHERE run_id=? AND step_index=?").run(JSON.stringify(safe),run.run_id,run.step_index);
   db.prepare('UPDATE demo_runs SET step_index=?,state=?,last_revision_json=?,updated_at=?,terminal_at=?,error_code=NULL WHERE run_id=?').run(next,done?'completed':'ready',JSON.stringify(last),now(),done?now():null,run.run_id);});
 }
 async function execute(runId,credentials,resume=false,confirmation,held=false){
  if(!held)acquire(runId);let run;
  try{
   run=get(runId);if(run.terminal_at!==null)fail(409,'RUN_TERMINAL');if(run.state!=='ready'&&!resume&&!held)fail(409,'EXPLICIT_RESUME_REQUIRED');
   transaction(db,()=>db.prepare("UPDATE demo_runs SET state='running',updated_at=? WHERE run_id=?").run(now(),runId));
   let step=db.prepare('SELECT * FROM demo_steps WHERE run_id=? AND step_index=?').get(runId,run.step_index);
   if(step){
    const body=JSON.parse(step.request_json);
    if(step.operation_type==='account.delete'){
     if(typeof credentials.deletionReceipt!=='string'||digest(credentials.deletionReceipt)!==body.receiptDigest)fail(422,'PRIVATE_RECEIPT_REQUIRED');
     const result=await http('/v1/deletions/status','POST',undefined,credentials.deletionReceipt,'DeletionReceipt');
     if(result.data.state!=='unknown'){committed(run,step,{result:result.data});return summary(get(runId));}
    }
    const actor=await actorSession(db,c,http,credentials,step.actor_alias);if(actor.digest!==step.actor_hmac)fail(403,'RUN_ACTOR_CHANGED');
    const result=await http('/v1/operations/'+body.operationId+'?type='+step.operation_type,'GET',undefined,actor.token);
    if(result.data.state==='committed'){committed(run,step,result.data);return summary(get(runId));}
    const age=Date.parse(result.serverNow)-Date.parse(body.requestCreatedAt),window=step.operation_type==='observation.publish'?60000:300000;
    if(age>window){transaction(db,()=>db.prepare("UPDATE demo_runs SET state=?,error_code=?,updated_at=? WHERE run_id=?").run(step.state==='sent'?'needs_review':'paused',step.state==='sent'?'OUTCOME_UNKNOWN':'INTENT_EXPIRED',now(),runId));return summary(get(runId));}
    if(!resume||confirmation!=='retry')fail(422,'EXPLICIT_RETRY_REQUIRED');
   }else{
    const prepared=await prepareStep(db,c,run,http,credentials,confirmation);
    transaction(db,()=>db.prepare("INSERT INTO demo_steps VALUES(?,?,?,?,?,?,?,?,'prepared',NULL)").run(runId,run.step_index,prepared.actor_alias,prepared.actor_hmac,prepared.path,prepared.method,prepared.operation_type,JSON.stringify(prepared.body)));
    step=db.prepare('SELECT * FROM demo_steps WHERE run_id=? AND step_index=?').get(runId,run.step_index);
   }
   const actor=await actorSession(db,c,http,credentials,step.actor_alias);if(actor.digest!==step.actor_hmac)fail(403,'RUN_ACTOR_CHANGED');
   const body=await sendBody(run,step,http,credentials);
   transaction(db,()=>db.prepare("UPDATE demo_steps SET state='sent' WHERE run_id=? AND step_index=?").run(runId,run.step_index));
   let response;
   try{response=await http(step.path,step.method,body,actor.token);}
   catch(e){
    // Only a first attempt with a definite rejection is known not to have committed.
    // A later 401/403 must not erase uncertainty from an earlier sent request.
    if(step.state==='prepared'&&[400,401,403,409,410,422,429].includes(e.status))transaction(db,()=>db.prepare("UPDATE demo_steps SET state='prepared' WHERE run_id=? AND step_index=?").run(runId,run.step_index));
    throw e;
   }
   if(options.afterWrite)await options.afterWrite();
   committed(run,step,response);return summary(get(runId));
  }catch(e){if(run&&run.terminal_at===null)transaction(db,()=>db.prepare("UPDATE demo_runs SET state='paused',error_code=?,updated_at=? WHERE run_id=? AND terminal_at IS NULL").run(e instanceof ApiError?e.code:'PROCESS_INTERRUPTED',now(),runId));throw e;}
  finally{if(!held)release();}
 }
 return{
  async start(scenario,anchorDate,credentials,confirmedExisting=false){scenarioSteps(scenario);dateMs(anchorDate);const runId=randomUUID();acquire(runId);
   try{if(db.prepare('SELECT 1 FROM demo_runs WHERE terminal_at IS NULL').get())fail(409,'ACTIVE_RUN_EXISTS');
    const run={run_id:runId,scenario,anchor_date:anchorDate};
    for(const alias of scenario.startsWith('membership-')?['member-a','member-b','operator-a']:scenario.startsWith('lifecycle-')?['member-a']:['operator-a'])await actorSession(db,c,http,credentials,alias);
    const initial=await initialState(run,http,credentials);
    if(!confirmedExisting&&Object.values(initial).some(v=>v!=='absent'))fail(409,'EXISTING_DATA_CONFIRMATION_REQUIRED');
    transaction(db,()=>db.prepare("INSERT INTO demo_runs(run_id,scenario,anchor_date,state,created_at,updated_at,last_revision_json) VALUES(?,?,?,'ready',?,?,?)").run(runId,scenario,anchorDate,now(),now(),JSON.stringify(initial)));
    return summary(get(runId));
   }finally{release();}
  },
  step:(id,credentials,confirmation)=>execute(id,credentials,false,confirmation),
  resume:(id,credentials,confirmation)=>execute(id,credentials,true,confirmation),
  status(id){return transaction(db,()=>{recoverLock();return summary(get(id));});},
  cancel(id,confirmed){if(confirmed!==true)fail(422,'CONFIRMATION_REQUIRED');acquire(id);try{const r=get(id);if(r.state==='needs_review'||db.prepare("SELECT 1 FROM demo_steps WHERE run_id=? AND state='sent'").get(id))fail(409,'OUTCOME_REVIEW_REQUIRED');transaction(db,()=>db.prepare("UPDATE demo_runs SET state='cancelled',terminal_at=?,updated_at=? WHERE run_id=? AND terminal_at IS NULL").run(now(),now(),id));return summary(get(id));}finally{release();}},
  async play(id,credentials,intervalSeconds){if(intervalSeconds!==60||get(id).scenario!=='crowd-v1')fail(422,'FINITE_PLAY_ONLY');
   acquire(id);try{let result;for(let n=0;n<3;n++){result=await execute(id,credentials,false,undefined,true);if(result.state==='completed')return result;if(n<2){transaction(db,()=>db.prepare("UPDATE demo_runs SET state='running',updated_at=? WHERE run_id=?").run(now(),id));await(options.sleep?options.sleep(60000):new Promise(r=>setTimeout(r,60000)));}}return result;}finally{release();}
  }
 };
}
