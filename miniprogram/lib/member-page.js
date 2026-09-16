const api=require('./session'),receipt=require('./receipt'),timing=require('./observation-view');
const scope=()=>api.config.environment+':'+api.config.baseUrl;
const labels={unbound:'未绑定',unlinked:'已解绑',pending:'待馆方核验',valid:'已核验 · 有效',expired:'会员资格已过期',revoked:'会员资格已撤销',pending_confirmation:'已核验 · 有效期待确认'};
const PENDING='gym.member-intent.v1',RECEIPT='gym.deletion-receipt.v1';
function mono(){try{return wx.getPerformance().now();}catch(_){return null;}}
function read(key){try{const value=wx.getStorageSync(key);return value&&value.scope===scope()?value:null;}catch(_){return null;}}
function clearPersonal(){api.clear();for(const key of ['gym.member-intent.v1','gym.observation-write.v1','gym.operator-member-operation.v1']){try{wx.removeStorageSync(key);}catch(_){}}}
module.exports=function(){return {
 data:{title:'请主动登录',detail:'公共浏览不需要会员绑定。',pairCode:'',pairLabel:'',expiryText:'',verifiedText:'',message:'',busy:false,authenticated:false,pending:false,deletionState:'',testing:api.config.environment==='test'},
 onLoad(){this._generation=0;this._clock=timing.clock(mono);this._pending=read(PENDING);this._receipt=read(RECEIPT);this.setData({pending:!!this._pending});},
 onShow(){this._pending=read(PENDING);this.setData({pending:!!this._pending});this._visible=true;this._clock.reset();this.setData({authenticated:false,pairCode:'',title:'正在确认会员状态'});this.refresh();this._poll=setInterval(()=>{if(this._visible)this.refresh();},60000);this._tick=setInterval(()=>this.render(),1000);},
 onHide(){this._visible=false;this._generation++;this._clock.reset();clearInterval(this._poll);clearInterval(this._tick);if(this._pair)delete this._pair.displayCode;this.setData({pairCode:'',authenticated:false});},onUnload(){this.onHide();},
 render(){if(!this._visible||!this._profile)return;const time=this._clock.now();if(time===null||!this._online){this.setData({title:'会员状态待确认',pairCode:'',authenticated:false});return;}let state=this._profile.derivedState;
  if(state==='valid'&&this._profile.expiryMode==='fixed_until'&&time>=Date.parse(this._profile.validUntil))state='expired';
  const p=this._pair,active=p&&p.state==='active'&&time<Date.parse(p.expiresAt);if(p&&!active)delete p.displayCode;if(state==='pending'&&!active)state='unbound';
  this.setData({title:labels[state]||'会员状态待确认',authenticated:true,pairCode:active?p.displayCode:'',pairLabel:active?p.requesterLabel:'',expiryText:this._profile.expiryMode==='no_fixed_expiry'?'馆方确认无固定期限':this._profile.expiryMode==='pending_confirmation'?'有效期待确认':this._profile.validUntil||'',verifiedText:this._profile.verifiedAt||'',detail:'登录不等于会员资格；资格以馆方原系统核验为依据。'});
 },
 async refresh(){if(!this._visible)return;if(this._reading||this.data.busy){this._queued=true;return;}this._reading=true;const generation=this._generation;
  try{if(this._receipt){const r=await api.request('/deletions/status','POST',undefined,this._receipt.token,'DeletionReceipt');if(generation!==this._generation)return;this.setData({deletionState:r.data.state==='unknown'?'未找到可确认删除记录，请登录核对，不能视为已删除。':r.data.state==='completed'?'数据清理已完成':r.data.delayed?'清理延迟，账户仍停用':r.data.state==='failed'?'清理暂未完成，将重试':'账户已停用，数据清理处理中'});if(r.data.state!=='unknown'&&!api.load()){this._pending=null;this._profile=null;this._pair=null;this.setData({authenticated:false,pairCode:'',pending:false,title:'账户已停用'});return;}}
   const token=api.load();if(!token){this.setData({authenticated:false,title:'请先在“我的”主动登录',pairCode:''});return;}
   const started=mono(),profile=await api.request('/me/membership','GET',undefined,token);if(generation!==this._generation||!this._visible)return;const pair=await api.request('/me/pairing','GET',undefined,token);
   if(generation!==this._generation||!this._visible)return;this._profile=profile.data;this._pair=pair.data;this._clock.accept(pair.serverNow,started);this._online=true;this.render();
  }catch(e){if(generation===this._generation){this._online=false;this._clock.reset();this.setData({authenticated:false,pairCode:'',title:'会员状态待确认',message:e.status===401?'登录失效，请主动重新登录。':'连接失败，请稍后刷新。'});if(e.status===401)clearPersonal();}}
  finally{this._reading=false;const queued=this._queued;this._queued=false;if(queued&&this._visible)this.refresh();}
 },
 refreshNow(){return this.refresh();},
 target(){return JSON.stringify({profile:this._profile,pair:this._pair,token:api.load(),generation:this._generation});},
 ask(event){const action=event.currentTarget.dataset.action;if(this.data.busy||this._reading||this._pending)return;const messages={create:'配对码只用于前台核对，持有码不代表会员资格。是否申请？',cancel:'取消当前待核验请求？',unbind:'解除本账号与会员的关联；这不取消原馆合同，之后需重新核验。',delete:'账户会立即停用，随后清理个人关联。原馆合同及最小资格/撤销登记保留。是否删除？'};if(!messages[action])return;const target=this.target();wx.showModal({title:'确认个人操作',content:messages[action],success:r=>{if(r.confirm)this.perform(action,target);}});},
 async perform(action,target=this.target()){if(this.data.busy||this._reading||!this.data.authenticated||this._pending)return;if(target!==this.target()){this.setData({message:'确认期间状态已变化，请刷新并重新确认。'});return;}const profile={...this._profile},pair={...this._pair};const time=this._clock.now();if(time===null)return;this.setData({busy:true});const generation=this._generation;
  try{const token=api.load(),s=await api.request('/session?interaction=poll','GET',undefined,token);if(['unbind','delete'].includes(action)&&Date.parse(s.serverNow)-Date.parse(s.data.authAt)>300000)throw{code:'FRESH_AUTH_REQUIRED',status:422};
   const body={operationId:await api.operationId(),requestCreatedAt:new Date(time).toISOString(),expectedRevision:action==='create'||action==='cancel'?pair.revision:action==='delete'?s.data.accountRevision:{binding:profile.bindingRevision,registry:profile.registryRevision,pairing:pair.revision}};
   let path,type;
   if(action==='create'){path='/me/pairing';type='pairing.create';}else if(action==='cancel'){path='/me/pairing/cancel';type='pairing.cancel';body.pairingId=pair.pairingId;}else if(action==='unbind'){path='/me/membership/unbind';type='membership.unbind';body.confirmed=true;}else{
    path='/me/account/delete';type='account.delete';body.confirmed=true;const generated=await receipt.create();body.receiptDigest=generated.digest;
    const saved={scope:scope(),token:generated.token};wx.setStorageSync(RECEIPT,saved);this._receipt=saved;
   }
   if(generation!==this._generation||!this._visible)return;if(target!==this.target())throw{status:409};
   const pending={scope:scope(),userId:s.data.userId,path,type,body};if(action!=='delete')wx.setStorageSync(PENDING,pending);this._pending=pending;this.setData({pending:true});
   try{await api.request(path,'POST',body,token);if(generation!==this._generation||api.load()!==token)return;this.clearPending();this.setData({message:action==='delete'?'账户已停用，数据清理处理中。':'操作已提交，正在刷新当前状态。'});}finally{if(action==='delete'){const ownsSession=api.load()===token;if(ownsSession)clearPersonal();this._pending=null;if(generation===this._generation&&ownsSession){this._generation++;this._profile=null;this._pair=null;this.setData({authenticated:false,pairCode:'',pending:false,title:'正在确认删除结果'});}}}
  }catch(e){if(generation===this._generation)this.failure(e);}finally{this.setData({busy:false});if(this._visible)this.refresh();}
 },
 clearPending(){this._pending=null;try{wx.removeStorageSync(PENDING);}catch(_){}this.setData({pending:false});},
 failure(e){if(e.code==='FRESH_AUTH_REQUIRED'){this.setData({message:'请返回“我的”主动重新登录，再重新确认此操作。'});return;}if([400,409,410,422].includes(e.status)){this.clearPending();this.setData({message:'状态已变化或请求无效，请刷新后重新确认。'});}else this.setData({message:'结果待确认，请查询原操作，不要重复申请。'});},
 async resolvePending(){if(!this._pending||this.data.busy)return;this.setData({busy:true});try{const token=api.load(),s=await api.request('/session?interaction=poll','GET',undefined,token);if(s.data.userId!==this._pending.userId)throw{status:403};const p=this._pending,r=await api.request('/operations/'+p.body.operationId+'?type='+p.type,'GET',undefined,token);if(r.data.state==='committed'){this.clearPending();this.setData({message:'原操作已提交，正在刷新。'});}else this.setData({message:'尚无法确认结果。可重试原请求，超时需重新确认。',canRetry:true});}catch(e){this.failure(e);}finally{this.setData({busy:false});this.refresh();}},
 async retry(){if(!this._pending||this.data.busy||!this.data.canRetry)return;const time=this._clock.now();if(time===null)return;if(time-Date.parse(this._pending.body.requestCreatedAt)>300000){this.clearPending();this.setData({message:'请求已超时，请刷新并重新确认。'});this.refresh();return;}this.setData({busy:true});try{const token=api.load(),s=await api.request('/session?interaction=poll','GET',undefined,token);if(s.data.userId!==this._pending.userId)throw{status:403};await api.request(this._pending.path,'POST',this._pending.body,token);this.clearPending();}catch(e){this.failure(e);}finally{this.setData({busy:false});this.refresh();}}
};};
