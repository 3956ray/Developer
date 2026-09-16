import { randomUUID } from 'node:crypto';
import { hmac, canonical, fail } from './protocol.mjs';
import { addDate, range } from './schedule-time.mjs';
const steps={
 'crowd-v1':['quiet','moderate','busy'], 'observation-pause-v1':['paused'], 'observation-withdraw-v1':['withdrawn'],
 'membership-v1':['pair','bind','revoke','pair','restore','pair-b','claim-b'],
 'schedule-v1':['save','publish','edit','publish','withdraw'], 'schedule-empty-v1':['empty','publish'],
 'lifecycle-unbind-v1':['unbind'], 'lifecycle-delete-v1':['delete'], 'lifecycle-logout-v1':['logout']
};
export const scenarioSteps=name=>steps[name]??fail(422,'SCENARIO_UNKNOWN');
export const actorDigest=(c,id)=>hmac(c.keys.intentHmac,canonical(['demo-run-actor',c.environment,c.gymId,id]));
export async function actorSession(db,c,http,credentials,alias){
 const token=credentials[alias];if(typeof token!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(token))fail(401,'ACTOR_SESSION_REQUIRED');
 const response=await http('/v1/session?interaction=poll','GET',undefined,token);
 const subject=hmac(c.keys.intentHmac,canonical(['demo-subject',c.environment,c.gymId,c.identity.appId,alias]));
 const account=db.prepare('SELECT user_id FROM wechat_identities WHERE app_id=? AND open_id=?').get(c.identity.appId,'demo_'+subject);
 if(account?.user_id!==response.data.userId)fail(403,'ACTOR_MISMATCH');
 return{token,response,digest:actorDigest(c,response.data.userId)};
}
export async function initialState(run,http,credentials){
 if(run.scenario.startsWith('crowd-')||run.scenario.startsWith('observation-')){const r=await http('/v1/observations/current');return{observation:r.data.revision};}
 if(run.scenario.startsWith('schedule-')){const r=await http('/v1/operator/schedule/draft','GET',undefined,credentials['operator-a']);return{draft:r.data.draftRevision,publication:r.data.publicationRevision};}
 const profile=await http('/v1/me/membership','GET',undefined,credentials['member-a']),pair=await http('/v1/me/pairing','GET',undefined,credentials['member-a']);
 return{binding:profile.data.bindingRevision,registry:profile.data.registryRevision,pairing:pair.data.revision};
}
const check=(actual,expected)=>{if(actual!==expected)fail(409,'SCENARIO_STATE_CHANGED');};
export async function prepareStep(db,c,run,http,credentials,confirmation){
 const kind=scenarioSteps(run.scenario)[run.step_index],last=JSON.parse(run.last_revision_json),memberRef='demo-ref-'+run.run_id;
 if(!kind)fail(409,'SCENARIO_COMPLETED');
 const alias=['pair','unbind','delete','logout'].includes(kind)?'member-a':kind==='pair-b'?'member-b':'operator-a';
 const actor=await actorSession(db,c,http,credentials,alias),created=actor.response.serverNow;
 const write=(path,type,body,method='POST')=>({actor_alias:alias,actor_hmac:actor.digest,path,method,operation_type:type,body:{operationId:randomUUID(),requestCreatedAt:created,...body}});
 if(['quiet','moderate','busy','paused','withdrawn'].includes(kind)){
  const current=await http('/v1/observations/current');check(current.data.revision,last.observation);
  return ['paused','withdrawn'].includes(kind)?write('/v1/operator/observations/control','observation.control',{expectedRevision:last.observation,state:kind,reasonCategory:kind==='paused'?'updates-paused':'correction'}):write('/v1/operator/observations','observation.publish',{expectedRevision:last.observation,level:kind,observedJustNow:true});
 }
 if(['save','empty','edit','publish','withdraw'].includes(kind)){
  const current=(await http('/v1/operator/schedule/draft','GET',undefined,actor.token)).data;check(current.draftRevision,last.draft);check(current.publicationRevision,last.publication);
  if(kind==='publish')return write('/v1/operator/schedule/publish','schedule.publish',{expectedRevision:{draft:last.draft,publication:last.publication},replaceCoverageConfirmed:true});
  if(kind==='withdraw')return write('/v1/operator/schedule/withdraw','schedule.withdraw',{expectedRevision:last.publication,reasonCategory:'updates-paused'});
  const coverage={startDate:run.anchor_date,endDate:addDate(run.anchor_date,7)},bounds=range(coverage.startDate,coverage.endDate,c.timeZone);let courses=[];
  if(kind==='save')for(const hour of ['09','11']){
   const localInput={start:{local:run.anchor_date+'T'+hour+':00',offset:'+08:00'},end:{local:run.anchor_date+'T'+String(Number(hour)+1)+':00',offset:'+08:00'}};
   const times=(await http('/v1/operator/schedule/time-preview','POST',localInput,actor.token)).data;courses.push({courseId:randomUUID(),name:'合成演示课程',status:'scheduled',revision:'absent',localInput,...times});
  }
  if(kind==='edit')for(const [index,course] of current.courses.entries()){
   if(index===0)courses.push({...course,status:'cancelled'});
   else{const localInput={start:{local:addDate(run.anchor_date,1)+'T11:00',offset:'+08:00'},end:{local:addDate(run.anchor_date,1)+'T12:00',offset:'+08:00'}};courses.push({...course,localInput,...(await http('/v1/operator/schedule/time-preview','POST',localInput,actor.token)).data});}
  }
  // Explicit full replacement retains in-coverage old courses as cancellations.
  for(const old of current.courses)if(!courses.some(x=>x.courseId===old.courseId)&&old.startAt<bounds.endAt&&old.endAt>bounds.startAt)courses.push({...old,status:'cancelled'});
  return write('/v1/operator/schedule/draft','schedule.draft.save',{expectedRevision:last.draft,coverage,courses},'PUT');
 }
 const member=kind==='claim-b'||kind==='pair-b'?'member-b':'member-a';
 const pair=(await http('/v1/me/pairing','GET',undefined,credentials[member])).data;
 const profile=(await http('/v1/me/membership','GET',undefined,credentials[member])).data;
 if(member==='member-a'){check(profile.bindingRevision,last.binding);check(profile.registryRevision,last.registry);check(pair.revision,last.pairing);}
 if(kind==='pair'||kind==='pair-b')return write('/v1/me/pairing','pairing.create',{expectedRevision:pair.revision});
 if(['bind','restore','claim-b'].includes(kind)){
  if(kind==='claim-b'&&confirmation!=='member-conflict')fail(422,'EXPLICIT_CONFLICT_CONFIRMATION_REQUIRED');
  if(pair.state!=='active')fail(422,'PAIRING_REAUTH_REQUIRED');
  await actorSession(db,c,http,credentials,member);
  const inspected=(await http('/v1/operator/members/inspect','POST',{memberRef,pairingId:pair.pairingId,code:pair.displayCode},actor.token)).data;
  // code and memberRef are reconstructed at send time, never stored with progress.
  return write('/v1/operator/members/'+(kind==='restore'?'restore':'bind'),kind==='restore'?'membership.restore':'membership.bind',{expectedRevision:{pairing:pair.revision,binding:profile.bindingRevision,registry:inspected.registry.revision},pairingId:pair.pairingId,frontDeskConfirmed:true,expiry:{mode:'no_fixed_expiry'}});
 }
 if(kind==='revoke')return write('/v1/operator/members/'+profile.bindingId+'/revoke','membership.revoke',{expectedRevision:{binding:profile.bindingRevision,registry:profile.registryRevision},reasonCategory:'qualification-withdrawn'});
 if(['unbind','delete'].includes(kind)&&confirmation!=='sensitive')fail(422,'EXPLICIT_SENSITIVE_CONFIRMATION_REQUIRED');
 if(kind==='unbind')return write('/v1/me/membership/unbind','membership.unbind',{expectedRevision:{binding:profile.bindingRevision,registry:profile.registryRevision,pairing:pair.revision},confirmed:true});
 if(kind==='delete'){
  const {digest}=await import('./protocol.mjs');if(typeof credentials.deletionReceipt!=='string'||!/^[a-f0-9]{64}$/.test(credentials.deletionReceipt))fail(422,'PRIVATE_RECEIPT_REQUIRED');
  return write('/v1/me/account/delete','account.delete',{expectedRevision:actor.response.data.accountRevision,confirmed:true,receiptDigest:digest(credentials.deletionReceipt)});
 }
 if(kind==='logout')return write('/v1/session/logout','session.logout',{expectedRevision:actor.response.data.sessionRevision});
 fail(422,'SCENARIO_UNKNOWN');
}
export async function sendBody(run,step,http,credentials){
 const body=JSON.parse(step.request_json);
 if(['membership.bind','membership.restore'].includes(step.operation_type)){
  const member=scenarioSteps(run.scenario)[run.step_index]==='claim-b'?'member-b':'member-a';
  const pair=(await http('/v1/me/pairing','GET',undefined,credentials[member])).data;
  if(pair.state!=='active'||pair.pairingId!==body.pairingId||pair.revision!==body.expectedRevision.pairing)fail(422,'PAIRING_REAUTH_REQUIRED');
  body.code=pair.displayCode;body.memberRef='demo-ref-'+run.run_id;
 }
 return body;
}
