import { randomUUID } from 'node:crypto';
import { ApiError, fail, fields, validateWrite, uuidPattern, digest } from './protocol.mjs';
import { memberKey, pairHash, randomCode, encryptCode, decryptCode, expiry, dateEnd } from './member-crypto.mjs';
class PairError extends ApiError { constructor(userId,locator) { super(400,'PAIRING_UNAVAILABLE');this.userId=userId;this.locator=locator; } }
const revision = row => row?.revision ?? 'absent';
const iso = time => time===null || time===undefined ? null : new Date(time).toISOString();
export function createMembershipService(db,c,identity,options={}) {
 if(c.environment!=='test' && (options.randomCode||options.beforeErrorCount)) fail(503,'TEST_HOOK_FORBIDDEN');
 const codeSource=options.randomCode??randomCode;
 const currentBinding=user=>db.prepare('SELECT * FROM bindings WHERE user_id=? AND current=1').get(user);
 const registry=key=>db.prepare('SELECT * FROM member_registry WHERE member_key=?').get(key);
 const latestPair=user=>db.prepare('SELECT * FROM pairings WHERE user_id=? ORDER BY revision DESC LIMIT 1').get(user);
 const ensureActive=user=> { if(db.prepare('SELECT state FROM accounts WHERE user_id=?').get(user)?.state!=='active') fail(409,'ACCOUNT_DELETING'); };
 function bucket(kind,user,time,limit,increment=false) {
  const start=Math.floor(time/600000)*600000;
  const row=db.prepare('SELECT count FROM membership_limits WHERE kind=? AND subject_id=? AND window_start=?').get(kind,user,start);
  if((row?.count??0)>=limit) fail(429,'RATE_LIMITED',Math.ceil((start+600000-time)/1000));
  if(increment) db.prepare('INSERT INTO membership_limits VALUES(?,?,?,?,1) ON CONFLICT(kind,subject_id,window_start) DO UPDATE SET count=count+1').run(kind,user,start,start+600000);
 }
 function checkLimits(actor,target,time) { bucket('operator-error',actor,time,5);if(target)bucket('requester-error',target,time,5); }
 function errorCount(token,error) {
  if(!(error instanceof PairError)) throw error;
  if(options.beforeErrorCount)options.beforeErrorCount();
  identity.authorized(token,{operator:true},({userId,time})=>{
   let target=null;
   if(error.locator) {
    const located=error.locator.kind==='pair'?db.prepare('SELECT user_id FROM pairings WHERE pairing_id=?').get(error.locator.id):db.prepare('SELECT user_id FROM bindings WHERE binding_id=? AND current=1').get(error.locator.id);
    if(located?.user_id===error.userId&&db.prepare('SELECT state FROM accounts WHERE user_id=?').get(error.userId)?.state==='active')target=error.userId;
   }
   checkLimits(userId,target,time);bucket('operator-error',userId,time,5,true);if(target)bucket('requester-error',target,time,5,true);
  });
  throw new ApiError(400,'PAIRING_UNAVAILABLE');
 }
 function guarded(token,action) { try{return action();}catch(e){return errorCount(token,e);} }
 function pairForCode(code,time,actor,id) {
  const expected=id?db.prepare('SELECT * FROM pairings WHERE pairing_id=?').get(id):null;
  const p=typeof code==='string'&&/^\d{8}$/.test(code)?db.prepare("SELECT * FROM pairings WHERE lookup_hmac=? ORDER BY (state='active') DESC,created_at DESC LIMIT 1").get(pairHash(c,code)):null;
  const located=expected??p;
  checkLimits(actor,located?.user_id,time);
  if(!p||p.state!=='active'||time>=p.expires_at||(id&&p.pairing_id!==id)||db.prepare('SELECT state FROM accounts WHERE user_id=?').get(p.user_id)?.state!=='active')throw new PairError(located?.user_id,located?{kind:'pair',id:located.pairing_id}:null);
  return p;
 }
 function terminal(p,state) { if(state==='consumed'){const rev=db.prepare('UPDATE accounts SET pairing_revision=pairing_revision+1 WHERE user_id=? RETURNING pairing_revision').get(p.user_id).pairing_revision;db.prepare('UPDATE pairings SET revision=? WHERE pairing_id=?').run(rev,p.pairing_id);} db.prepare('UPDATE pairings SET state=?,ciphertext=NULL,nonce=NULL,tag=NULL,key_id=NULL WHERE pairing_id=?').run(state,p.pairing_id); }
 function pairView(p,time,display=true) {
  if(!p)return {state:'none',revision:'absent',pairingId:null};
  const active=p.state==='active'&&time<p.expires_at;
  return {pairingId:p.pairing_id,revision:p.revision,state:p.state==='active'&&!active?'expired':p.state,createdAt:iso(p.created_at),expiresAt:iso(p.expires_at),requesterLabel:p.pairing_id.slice(0,8),...(active&&display?{displayCode:decryptCode(c,p)}:{})};
 }
 function memberView(user,time) {
  const b=currentBinding(user),r=b?registry(b.member_key):null,p=latestPair(user);
  let state='unbound';
  if(!b) state=p?.state==='active'&&time<p.expires_at?'pending':db.prepare('SELECT 1 FROM bindings WHERE user_id=? LIMIT 1').get(user)?'unlinked':'unbound';
  else if(b.state==='revoked'||r.state==='revoked')state='revoked';
  else if(r.expiry_mode==='pending_confirmation')state='pending_confirmation';
  else if(r.expiry_mode==='fixed_until'&&time>=r.valid_until)state='expired';
  else state='valid';
  return {bindingId:b?.binding_id??null,bindingRevision:revision(b),registryRevision:revision(r),derivedState:state,
   expiryMode:r?.expiry_mode??null,validUntil:iso(r?.valid_until),localEndDate:r?.local_end_date??null,verifiedAt:iso(r?.verified_at)};
 }
 function cas(got,expected) { if(got!==expected)fail(409,'REVISION_CONFLICT'); }
 function putRegistry(key,old,e,time,state='verified') {
  const rev=(old?.revision??0)+1;
  db.prepare('INSERT INTO member_registry VALUES(?,?,?,?,?,?,?) ON CONFLICT(member_key) DO UPDATE SET state=excluded.state,expiry_mode=excluded.expiry_mode,valid_until=excluded.valid_until,local_end_date=excluded.local_end_date,verified_at=excluded.verified_at,revision=excluded.revision').run(key,state,e.mode,e.until,e.localDate,time,rev);
  return rev;
 }
 function bind(token,body,restore) {
  validateWrite(body,['pairingId','code','memberRef','frontDeskConfirmed','expiry'],['pairing','binding','registry']);
  if(!uuidPattern.test(body.pairingId))fail(422,'INVALID_REQUEST');
  const key=memberKey(c,body.memberRef),e=expiry(body.expiry,c.timeZone);
  return guarded(token,()=>identity.domainOperation(token,restore?'membership.restore':'membership.bind',body,{operator:true},({userId,time,audit})=>{
   const candidate=db.prepare('SELECT revision FROM pairings WHERE pairing_id=?').get(body.pairingId);if(candidate)cas(candidate.revision,body.expectedRevision.pairing);
   const p=pairForCode(body.code,time,userId,body.pairingId);ensureActive(p.user_id);
   const b=currentBinding(p.user_id),r=registry(key),occupied=db.prepare('SELECT * FROM bindings WHERE member_key=? AND current=1').get(key);
   cas(p.revision,body.expectedRevision.pairing);cas(revision(b),body.expectedRevision.binding);cas(revision(r),body.expectedRevision.registry);
   if(body.frontDeskConfirmed!==true)throw new PairError(p.user_id,{kind:'pair',id:p.pairing_id});
   if(occupied&&occupied.user_id!==p.user_id)fail(409,'BINDING_CONFLICT');
   if(restore) { if(!r||r.state!=='revoked'||(b&&(b.member_key!==key||b.state!=='revoked')))fail(409,'RESTORE_REQUIRED'); }
   else { if(r?.state==='revoked')fail(409,'RESTORE_REQUIRED');if(b)fail(409,'BINDING_CONFLICT'); }
   const registryRevision=putRegistry(key,r,e,time),id=b?.binding_id??randomUUID(),rev=(b?.revision??0)+1;
   if(b)db.prepare("UPDATE bindings SET state='verified',revision=? WHERE binding_id=?").run(rev,id);
   else db.prepare("INSERT INTO bindings VALUES(?,?,?,1,'verified',1,NULL)").run(id,p.user_id,key);
   terminal(p,'consumed');audit(userId,p.user_id,restore?'membership.restore':'membership.bind',rev,time,'front-desk-confirmed');
   return {revision:rev,subjectId:p.user_id,data:{bindingId:id,bindingRevision:rev,registryRevision,pairingState:'consumed'}};
  }));
 }
 return {
  expiryPreview(token,body) { fields(body,['localEndDate']);return identity.authorized(token,{operator:true},()=>dateEnd(body.localEndDate,c.timeZone)); },
  profile(token) { return identity.authorized(token,{},({userId,time})=>memberView(userId,time)); },
  pairing(token) { return identity.authorized(token,{},({userId,time})=>pairView(latestPair(userId),time)); },
  createPair(token,body) {
   validateWrite(body);
   const result=identity.domainOperation(token,'pairing.create',body,{},({userId,time,audit})=>{
    const old=latestPair(userId),b=currentBinding(userId);cas(revision(old),body.expectedRevision);
    if(b&&b.state!=='revoked'&&registry(b.member_key).state!=='revoked')fail(409,'ALREADY_BOUND');
    bucket('generation',userId,time,3);
    db.prepare("UPDATE pairings SET state='expired',ciphertext=NULL,nonce=NULL,tag=NULL,key_id=NULL WHERE state='active' AND expires_at<=?").run(time);
    if(old?.state==='active')terminal(old,'replaced');
    const rev=db.prepare('UPDATE accounts SET pairing_revision=pairing_revision+1 WHERE user_id=? RETURNING pairing_revision').get(userId).pairing_revision;
    const p={pairing_id:randomUUID(),expires_at:time+600000};let inserted=false;
    for(let attempt=0;attempt<5;attempt++) {
     const code=codeSource();if(!/^\d{8}$/.test(code))fail(503,'RANDOM_SOURCE_INVALID');
     const hash=pairHash(c,code),encrypted=encryptCode(c,p,code);db.exec('SAVEPOINT pairing_candidate');
     try {
      db.prepare("INSERT INTO pairings VALUES(?,?,?,'active',?,?,?,?,?,?,?)").run(p.pairing_id,userId,rev,hash,encrypted.ciphertext,encrypted.nonce,encrypted.tag,'pair-v1',time,p.expires_at);
      db.exec('RELEASE pairing_candidate');inserted=true;break;
     } catch(error) {
      db.exec('ROLLBACK TO pairing_candidate');db.exec('RELEASE pairing_candidate');
      if(error.errcode!==2067||!db.prepare("SELECT 1 FROM pairings WHERE lookup_hmac=? AND state='active'").get(hash))throw error;
     }
    }
    if(!inserted)fail(503,'PAIRING_COLLISION_EXHAUSTED');
    bucket('generation',userId,time,3,true);audit(userId,userId,'pairing.create',rev,time,'user-request');
    return {revision:rev,subjectId:userId,data:{pairingId:p.pairing_id}};
   });
   result.data=identity.authorized(token,{},({userId,time})=>{ const p=db.prepare('SELECT * FROM pairings WHERE pairing_id=? AND user_id=?').get(result.data.pairingId,userId);return p?pairView(p,time):{pairingId:result.data.pairingId,state:'unavailable',revision:'absent'}; });
   return result;
  },
  cancelPair(token,body) {
   validateWrite(body,['pairingId']);
   return identity.domainOperation(token,'pairing.cancel',body,{},({userId,time,audit})=>{
    const p=latestPair(userId);if(!p||p.pairing_id!==body.pairingId)fail(404,'NOT_FOUND');cas(p.revision,body.expectedRevision);
    if(p.state!=='active'||time>=p.expires_at)fail(410,'PAIRING_EXPIRED');terminal(p,'cancelled');
    const rev=db.prepare('UPDATE accounts SET pairing_revision=pairing_revision+1 WHERE user_id=? RETURNING pairing_revision').get(userId).pairing_revision;
    db.prepare('UPDATE pairings SET revision=? WHERE pairing_id=?').run(rev,p.pairing_id);audit(userId,userId,'pairing.cancel',rev,time,'user-request');
    return {revision:rev,subjectId:userId,data:{pairingId:p.pairing_id,state:'cancelled',revision:rev}};
   });
  },
  lookup(token,body) {
   fields(body,['code']);
   return guarded(token,()=>identity.authorized(token,{operator:true},({userId,time})=>{
    checkLimits(userId,null,time);const p=pairForCode(body.code,time,userId),b=currentBinding(p.user_id),r=b?registry(b.member_key):null;
    return {...pairView(p,time,false),bindingRevision:revision(b),registryRevision:revision(r)};
   }));
  },
  inspect(token,body) {
   fields(body,['memberRef','pairingId','code'],['memberRef']);if(Object.hasOwn(body,'pairingId')!==Object.hasOwn(body,'code'))fail(422,'INVALID_REQUEST');
   const key=memberKey(c,body.memberRef);
   return guarded(token,()=>identity.authorized(token,{operator:true},({userId,time})=>{
    checkLimits(userId,null,time);const p=body.pairingId?pairForCode(body.code,time,userId,body.pairingId):null;
    const r=registry(key),b=db.prepare('SELECT * FROM bindings WHERE member_key=? AND current=1').get(key),requester=p?currentBinding(p.user_id):null;
    const view=b=>b?{id:b.binding_id,revision:b.revision,state:b.state}:{state:'absent',revision:'absent'};
    return {registry:r?{state:r.state,revision:r.revision,expiryMode:r.expiry_mode,validUntil:iso(r.valid_until),verifiedAt:iso(r.verified_at)}:{state:'absent',revision:'absent'},
     currentBinding:{...view(b),relationToRequester:!p?'unknown':b?.user_id===p.user_id?'self':'other'},requesterBinding:view(requester),pairingRevision:revision(p),restoreRequired:r?.state==='revoked'};
   }));
  },
  bind:(token,body)=>bind(token,body,false), restore:(token,body)=>bind(token,body,true),
  update(token,id,body,revoke) {
   validateWrite(body,revoke?['reasonCategory']:['reasonCategory','memberRef','frontDeskConfirmed','expiry'],['binding','registry']);
   if(!uuidPattern.test(id))fail(422,'INVALID_REQUEST');
   if(!(revoke?['qualification-withdrawn','correction']:['renewal','expiry-confirmation']).includes(body.reasonCategory))fail(422,'INVALID_REQUEST');
   const e=revoke?null:expiry(body.expiry,c.timeZone),key=revoke?null:memberKey(c,body.memberRef);
   return guarded(token,()=>identity.domainOperation(token,revoke?'membership.revoke':'membership.reverify',body,{operator:true,resource:id},({userId,time,audit})=>{
    const b=db.prepare('SELECT * FROM bindings WHERE binding_id=? AND current=1').get(id);if(!b)fail(404,'NOT_FOUND');ensureActive(b.user_id);checkLimits(userId,b.user_id,time);
    const r=registry(b.member_key);cas(b.revision,body.expectedRevision.binding);cas(r.revision,body.expectedRevision.registry);
    if(!revoke&&(body.frontDeskConfirmed!==true||key!==b.member_key))throw new PairError(b.user_id,{kind:'binding',id:b.binding_id});
    if(!revoke&&r.state==='revoked')fail(409,'RESTORE_REQUIRED');
    const registryRevision=revoke?r.revision+1:putRegistry(key,r,e,time);
    if(revoke)db.prepare("UPDATE member_registry SET state='revoked',revision=revision+1 WHERE member_key=?").run(b.member_key);
    db.prepare('UPDATE bindings SET state=?,revision=revision+1 WHERE binding_id=?').run(revoke?'revoked':'verified',id);
    audit(userId,b.user_id,revoke?'membership.revoke':'membership.reverify',b.revision+1,time,body.reasonCategory);
    return {revision:b.revision+1,subjectId:b.user_id,data:{bindingId:id,bindingRevision:b.revision+1,registryRevision,state:revoke?'revoked':'verified'}};
   }));
  },
  unbind(token,body) {
   validateWrite(body,['confirmed'],['binding','registry','pairing']);if(body.confirmed!==true)fail(422,'CONFIRMATION_REQUIRED');
   return identity.domainOperation(token,'membership.unbind',body,{fresh:true},({userId,time,audit})=>{
    const b=currentBinding(userId),r=b?registry(b.member_key):null,p=latestPair(userId);cas(revision(b),body.expectedRevision.binding);cas(revision(r),body.expectedRevision.registry);cas(revision(p),body.expectedRevision.pairing);
    if(!b)fail(409,'NOT_BOUND');db.prepare("UPDATE bindings SET current=0,state='unbound',revision=revision+1,closed_at=? WHERE binding_id=?").run(time,b.binding_id);
    if(p?.state==='active')terminal(p,'cancelled');audit(userId,userId,'membership.unbind',b.revision+1,time,'user-confirmed');
    return {revision:b.revision+1,subjectId:userId,data:{bindingId:b.binding_id,state:'unbound',revision:b.revision+1}};
   });
  },
  deleteAccount(token,body) {
   validateWrite(body,['confirmed','receiptDigest']);if(body.confirmed!==true||typeof body.receiptDigest!=='string'||!/^[a-f0-9]{64}$/.test(body.receiptDigest))fail(422,'INVALID_REQUEST');
   return identity.domainOperation(token,'account.delete',body,{fresh:true},({userId,session,time,audit})=>{
    cas(session.account_revision,body.expectedRevision);if(db.prepare('SELECT 1 FROM deletion_jobs WHERE receipt_digest=?').get(body.receiptDigest))fail(409,'RECEIPT_CONFLICT');
    const job=randomUUID();db.prepare("UPDATE accounts SET state='deleting',revision=revision+1 WHERE user_id=?").run(userId);
    db.prepare('UPDATE sessions SET revoked_at=?,revision=revision+1 WHERE user_id=? AND revoked_at IS NULL').run(time,userId);
    db.prepare('DELETE FROM operator_roles WHERE user_id=?').run(userId);
    db.prepare("UPDATE bindings SET current=0,state='unbound',revision=revision+1,closed_at=? WHERE user_id=? AND current=1").run(time,userId);
    db.prepare("UPDATE pairings SET state='cancelled',ciphertext=NULL,nonce=NULL,tag=NULL,key_id=NULL WHERE user_id=? AND state='active'").run(userId);
    db.prepare("INSERT INTO deletion_jobs(job_id,user_id,receipt_digest,state,accepted_at,next_retry_at) VALUES(?,?,?,'pending',?,?)").run(job,userId,body.receiptDigest,time,time);
    audit(userId,userId,'account.delete',session.account_revision+1,time,'user-confirmed');
    return {revision:session.account_revision+1,subjectId:userId,data:{jobId:job,state:'pending',acceptedAt:iso(time)}};
   });
  },
  deletionStatus(receipt) {
   if(typeof receipt!=='string'||!/^[a-f0-9]{64}$/.test(receipt))fail(400,'INVALID_RECEIPT');
   const j=db.prepare('SELECT * FROM deletion_jobs WHERE receipt_digest=?').get(digest(receipt)),time=identity.now();
   if(!j||(j.completed_at!==null&&time>=j.completed_at+7*86400000))return {state:'unknown'};
   return {state:j.state,acceptedAt:iso(j.accepted_at),completedAt:iso(j.completed_at),nextRetryAt:j.state==='completed'?null:iso(j.next_retry_at),delayed:j.state!=='completed'&&time-j.accepted_at>86400000};
  }
 };
}
