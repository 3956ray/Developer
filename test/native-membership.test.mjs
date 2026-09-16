import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash,randomBytes} from 'node:crypto';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const START=Date.parse('2026-09-16T00:00:00.000Z');
function moduleFrom(path,context={}){const sandbox={module:{exports:{}},...context};vm.runInNewContext(readFileSync(path,'utf8'),sandbox);return sandbox.module.exports;}
function harness({confirm=true,deleteLost=false}={}){
 let token='old-session',identity='old-user',state='valid',receiptState='unknown',mono=0,cleared=0;
 const stores=new Map(),calls=[];stores.set('gym.operator-member-operation.v1',{userId:'old-user'});
 const api={config:{environment:'test',baseUrl:'http://127.0.0.1:8787'},load:()=>token,clear:()=>{token=null;cleared++;['gym.member-intent.v1','gym.observation-write.v1','gym.operator-member-operation.v1'].forEach(k=>stores.delete(k));},operationId:async()=> '00000000-0000-4000-8000-000000000001',
  request:async(path,method,body,auth,scheme)=>{
   calls.push({path,method,body,auth,scheme});let data;
   if(path==='/deletions/status')data={state:receiptState};
   else if(path.startsWith('/session?'))data={userId:identity,accountRevision:1,authAt:new Date(START).toISOString()};
   else if(path==='/me/membership')data={derivedState:state,bindingRevision:1,registryRevision:1,expiryMode:'no_fixed_expiry',verifiedAt:new Date(START).toISOString()};
   else if(path==='/me/pairing'&&method==='GET')data={state:'none',revision:'absent'};
   else if(path==='/me/account/delete'){assert.ok(stores.has('gym.deletion-receipt.v1'));receiptState='pending';if(deleteLost)throw{code:'NETWORK_UNCONFIRMED'};data={state:'pending'};}
   else data={};return{data,serverNow:new Date(START+mono).toISOString()};
  }};
 const presentation=moduleFrom('miniprogram/lib/observation-view.js',{Intl,Date});
 const receiptToken='a'.repeat(64),receipt={create:async()=>({token:receiptToken,digest:createHash('sha256').update(receiptToken).digest('hex')})};
 const wx={getPerformance:()=>({now:()=>mono}),getStorageSync:k=>stores.get(k),setStorageSync:(k,v)=>stores.set(k,v),removeStorageSync:k=>stores.delete(k),showModal:opts=>opts.success({confirm})};
 const factory=moduleFrom('miniprogram/lib/member-page.js',{require:name=>name==='./session'?api:name==='./receipt'?receipt:presentation,wx,setInterval:()=>1,clearInterval(){},Date});
 const page=factory();page.setData=data=>Object.assign(page.data,data);page.onLoad();
 return{page,api,stores,calls,flush:()=>new Promise(r=>setImmediate(r)),now:value=>{mono=value;},completed:()=>{receiptState='completed';},loginNew:()=>{token='new-session';identity='new-user';state='unbound';},get:()=>({token,identity,cleared})};
}
test('receipt SHA256 ASCII implementation matches standard vectors; secure receipt is generated before use',async()=>{
 const bytes=randomBytes(32);const receipt=moduleFrom('miniprogram/lib/receipt.js',{Uint8Array,wx:{getRandomValues:o=>o.success({randomValues:bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+32)})}});
 for(const text of ['', 'abc','a'.repeat(64),'x'.repeat(1000),...Array.from({length:20},()=>randomBytes(32).toString('hex'))])assert.equal(receipt.sha256(text),createHash('sha256').update(text).digest('hex'));
 const generated=await receipt.create();assert.equal(generated.token.length,64);assert.equal(generated.digest,createHash('sha256').update(generated.token).digest('hex'));
});

test('P01/M05 native refusal sends no mutation and stale auth requires active reauthentication',async()=>{
 const h=harness({confirm:false});h.page.onShow();await h.flush();h.page.ask({currentTarget:{dataset:{action:'delete'}}});await h.flush();assert.equal(h.calls.filter(c=>c.path==='/me/account/delete').length,0);
 h.now(300001);await h.page.perform('unbind');assert.match(h.page.data.message,/主动重新登录/);assert.equal(h.calls.filter(c=>c.path==='/me/membership/unbind').length,0);
});

test('C02 native lost delete response preserves receipt, erases personal caches; old completed receipt never logs out newly created account',async()=>{
 const h=harness({deleteLost:true});h.page.onShow();await h.flush();await h.page.perform('delete');await h.flush();
 assert.equal(h.get().token,null);assert.ok(h.stores.has('gym.deletion-receipt.v1'));assert.equal(h.stores.has('gym.operator-member-operation.v1'),false);assert.equal(h.page.data.pairCode,'');
 assert.equal(h.calls.filter(c=>c.path==='/me/account/delete').length,1);assert.match(h.page.data.deletionState,/处理中/);
 h.page.onHide();h.completed();h.loginNew();const clears=h.get().cleared;h.page.onShow();await h.flush();
 assert.equal(h.get().token,'new-session');assert.equal(h.get().cleared,clears);assert.equal(h.page.data.authenticated,true);assert.equal(h.page.data.title,'未绑定');assert.match(h.page.data.deletionState,/已完成/);
 assert.ok(h.calls.filter(c=>c.path==='/deletions/status').every(c=>c.scheme==='DeletionReceipt'));assert.ok(h.stores.has('gym.deletion-receipt.v1'));
});

test('P01 native session clear covers all personal-operation keys but leaves deletion receipt',()=>{
 const removed=[];const api=moduleFrom('miniprogram/lib/session.js',{require:()=>({environment:'test',baseUrl:'http://127.0.0.1:8787'}),wx:{removeStorageSync:k=>removed.push(k)}});api.clear();
 for(const key of ['gym.business-session.v1','gym.member-intent.v1','gym.observation-write.v1','gym.operator-member-operation.v1'])assert.ok(removed.includes(key));assert.equal(removed.includes('gym.deletion-receipt.v1'),false);
});

test('native operator date conversion delegates to configured-zone backend; raw references never go to durable operation cache',async()=>{
 let page;const calls=[],store=new Map();
 const api={load:()=> 'synthetic-token',config:{environment:'test',baseUrl:'http://127.0.0.1:8787'},request:async(path,method,body)=>{calls.push({path,body});return{data:{mode:'fixed_until',localEndDate:'2026-03-08',validUntil:'2026-03-09T04:00:00.000Z',timeZone:'America/New_York'}};}};
 vm.runInNewContext(readFileSync('miniprogram/pages/member-operator/index.js','utf8'),{require:()=>api,Page:p=>{page=p;},wx:{getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v)}});
 page.setData=data=>Object.assign(page.data,data);page.onLoad();page.data.mode='fixed_until';page.data.endDate='2026-03-08';const converted=await page.expiry();
 assert.equal(converted.validUntil,'2026-03-09T04:00:00.000Z');assert.equal(calls[0].path,'/operator/members/expiry-preview');
 assert.equal(store.size,0);
});

function operatorHarness(){
 let page,modal,holdPath,release;const calls=[],store=new Map();
 const inspect={registry:{state:'verified',revision:1},currentBinding:{state:'none',revision:'absent'},requesterBinding:{revision:'absent'}};
 const api={load:()=> 'synthetic-token',operationId:async()=> '00000000-0000-4000-8000-000000000001',config:{environment:'test',baseUrl:'http://127.0.0.1:8787'},request:async(path,method,body)=>{
  calls.push({path,body});if(path===holdPath)await new Promise(r=>{release=r;});
  if(path==='/operator/members/bind')throw{code:'NETWORK_UNCONFIRMED'};
  return{serverNow:new Date(START).toISOString(),data:path.includes('/session?')?{userId:'operator'}:path.endsWith('/lookup')?{pairingId:'pair',revision:1,requesterLabel:'synthetic'}:path.endsWith('/expiry-preview')?{localEndDate:'2026-03-08',validUntil:'2026-03-09T04:00:00.000Z'}:inspect};
 }};
 vm.runInNewContext(readFileSync('miniprogram/pages/member-operator/index.js','utf8'),{require:()=>api,Page:p=>{page=p;},wx:{getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v),removeStorageSync:k=>store.delete(k),showModal:o=>{modal=o;}}});
 page.setData=data=>Object.assign(page.data,data);page.onLoad();page._visible=true;Object.assign(page.data,{authorized:true,code:'00123456',memberRef:'synthetic-member-reference',confirmation:true});
 return{page,calls,store,hold:path=>{holdPath=path;},release:()=>release(),confirm:()=>modal.success({confirm:true}),flush:()=>new Promise(r=>setImmediate(r)),input:(field,value)=>page.input({currentTarget:{dataset:{field}},detail:{value}})};
}
test('native member confirmation freezes membership revisions rather than silently unbinding refreshed target',async()=>{
 const h=harness();h.page.onShow();await h.flush();const old=h.page.target();h.page._profile.bindingRevision=2;
 await h.page.perform('unbind',old);assert.match(h.page.data.message,/重新确认/);assert.equal(h.calls.some(c=>c.path==='/me/membership/unbind'),false);
});
test('native operator ignores delayed inspection after reference edit',async()=>{
 const h=operatorHarness();h.hold('/operator/members/inspect');const work=h.page.inspect();await h.flush();h.input('memberRef','different-reference');h.release();await work;
 assert.equal(h.page._inspected,null);await h.page.submit('bind');assert.equal(h.calls.some(c=>c.path==='/operator/members/bind'),false);
});
test('native operator confirmation and awaited expiry reject changed input; actual lost submit cache excludes raw credentials',async()=>{
 const changed=operatorHarness();await changed.page.inspect();changed.page.act({currentTarget:{dataset:{action:'bind'}}});changed.page.confirm({detail:{value:[]}});changed.confirm();await changed.flush();assert.equal(changed.calls.some(c=>c.path==='/operator/members/bind'),false);
 const delayed=operatorHarness();delayed.page.data.mode='fixed_until';delayed.page.data.endDate='2026-03-08';await delayed.page.inspect();delayed.hold('/operator/members/expiry-preview');const work=delayed.page.submit('bind');await delayed.flush();delayed.input('endDate','2026-03-09');delayed.release();await work;assert.equal(delayed.calls.some(c=>c.path==='/operator/members/bind'),false);
 const lost=operatorHarness();await lost.page.inspect();await lost.page.submit('bind');const posted=lost.calls.find(c=>c.path==='/operator/members/bind');assert.ok(posted);assert.equal(posted.body.code,'00123456');assert.equal(posted.body.memberRef,'synthetic-member-reference');assert.equal(lost.page.data.pending,true);
 const persisted=JSON.stringify([...lost.store.values()]);for(const raw of ['00123456','synthetic-member-reference','synthetic-token'])assert.equal(persisted.includes(raw),false);assert.ok(lost.page._intent);lost.page.onHide();assert.equal(lost.page._intent,null);assert.equal(lost.page.data.code,'');
});

test('native member states: empty/revoked/expiry boundary/offline hide codes; cancel/unbind submit frozen revisions',async()=>{
 const h=harness();h.page.onShow();await h.flush();
 for(const [state,label] of [['unbound','未绑定'],['unlinked','已解绑'],['revoked','会员资格已撤销'],['pending_confirmation','已核验 · 有效期待确认']]){h.page._profile.derivedState=state;h.page.render();assert.equal(h.page.data.title,label);}
 h.page._profile.derivedState='valid';h.page._profile.expiryMode='fixed_until';h.page._profile.validUntil=new Date(START+1000).toISOString();h.now(999);h.page.render();assert.equal(h.page.data.title,'已核验 · 有效');h.now(1000);h.page.render();assert.equal(h.page.data.title,'会员资格已过期');
 h.page._pair={state:'active',pairingId:'synthetic-pair',revision:2,displayCode:'00123456',expiresAt:new Date(START+600000).toISOString()};h.page._online=false;h.page.render();assert.equal(h.page.data.pairCode,'');h.page._online=true;h.page.render();assert.equal(h.page.data.pairCode,'00123456');
 await h.page.perform('cancel');await h.flush();const cancel=h.calls.find(c=>c.path==='/me/pairing/cancel');assert.equal(cancel.body.expectedRevision,2);assert.equal(cancel.body.pairingId,'synthetic-pair');
 await h.page.perform('unbind');await h.flush();assert.equal(h.calls.find(c=>c.path==='/me/membership/unbind').body.expectedRevision.binding,1);
 h.page._pair={state:'active',displayCode:'00123456',expiresAt:new Date(START+600000).toISOString()};h.now(600000);h.page.render();assert.equal(h.page.data.pairCode,'');assert.equal(h.page._pair.displayCode,undefined);
});

test('native member unknown request keeps original operation; query then retry uses same key; conflict requires reconfirmation',async()=>{
 const h=harness();h.page.onShow();await h.flush();const original=h.api.request;let lost=true;const posts=[];
 h.api.request=async(path,method,body,...rest)=>{if(path==='/me/pairing'&&method==='POST'){posts.push(body);if(lost)throw{code:'NETWORK_UNCONFIRMED'};}if(path.startsWith('/operations/'))return{data:{state:'unknown'}};return original(path,method,body,...rest);};
 await h.page.perform('create');await h.flush();assert.equal(h.page.data.pending,true);assert.ok(h.stores.has('gym.member-intent.v1'));await h.page.resolvePending();await h.flush();assert.equal(h.page.data.canRetry,true);
 lost=false;await h.page.retry();await h.flush();assert.equal(posts.length,2);assert.equal(posts[0].operationId,posts[1].operationId);assert.equal(h.page.data.pending,false);
 h.api.request=async(path,method,body,...rest)=>{if(path==='/me/pairing'&&method==='POST')throw{status:409};return original(path,method,body,...rest);};await h.page.perform('create');await h.flush();assert.equal(h.page.data.pending,false);assert.match(h.page.data.message,/重新确认/);
});
