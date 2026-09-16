import {spawn as spawnProcess,fork as forkProcess} from 'node:child_process';
import {rmSync} from 'node:fs';
import {dirname} from 'node:path';
import {root} from '../server/config.mjs';
const fixtures=new Map(),children=new WeakMap();
export async function bounded(promise,ms=5000,label='CHILD_WAIT_TIMEOUT'){
 let timer;try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error(label)),ms);})]);}finally{clearTimeout(timer);}
}
export function fixtureLifetime(t,path,close=()=>{}){
 const owned=new Set(),closers=[close];let finished=false;
 const lifetime={beforeRemove:fn=>closers.push(fn),async cleanup(){
  if(finished)return;const results=await Promise.allSettled([...owned].map(p=>stop(p)));
  const errors=results.filter(r=>r.status==='rejected').map(r=>r.reason);
  if([...owned].some(p=>!children.get(p).closed))throw new AggregateError(errors,'OWNED_CHILD_STILL_RUNNING_FIXTURE_RETAINED');
  for(const fn of closers)try{await fn();}catch(e){errors.push(e);}
  if(errors.length)throw new AggregateError(errors,'FIXTURE_RESOURCES_FAILED_DIRECTORY_RETAINED');
  // Remove only after every owned process has emitted close, including its stdio.
  try{rmSync(dirname(path),{recursive:true,force:true});}catch(e){errors.push(e);}
  fixtures.delete(path);finished=true;
  if(errors.length)throw new AggregateError(errors,'FIXTURE_TEARDOWN_FAILED');
 }};fixtures.set(path,{owned});t.after(()=>lifetime.cleanup());return lifetime;
}
function own(path,launch){const fixture=fixtures.get(path);if(!fixture)throw new Error('UNREGISTERED_FIXTURE');const p=launch();fixture.owned.add(p);const state={closed:false,expired:false,stopping:null};
 state.done=new Promise(resolve=>p.once('close',()=>{state.closed=true;clearTimeout(state.watchdog);resolve();}));
 p.on('error',error=>{state.error=error;});state.watchdog=setTimeout(()=>{state.expired=true;p.kill('SIGKILL');},30000);state.watchdog.unref();children.set(p,state);return p;
}
export function spawn(command,args,options){const path=args[0]==='scripts/db.mjs'?args[2]:args[1];return own(path,()=>spawnProcess(command,args,options));}
export function fork(module,args,options){return own(args[0],()=>forkProcess(module,args,options));}
export async function stop(p,signal='SIGTERM'){
 const state=children.get(p);if(!state)throw new Error('UNOWNED_CHILD');if(state.stopping)return state.stopping;
 state.stopping=(async()=>{if(!state.closed){p.kill(signal);try{await bounded(state.done,2000,'CHILD_STOP_TIMEOUT');}catch(error){p.kill('SIGKILL');await bounded(state.done,2000,'CHILD_KILL_TIMEOUT');throw error;}}if(state.expired)throw new Error('CHILD_LIFETIME_TIMEOUT');})();return state.stopping;
}
export async function startService(path,{args=['server/main.mjs',path],timeout=5000}={}){
 const p=spawn(process.execPath,args,{cwd:root,stdio:['ignore','pipe','pipe']});let output='',logs='';p.stderr.on('data',c=>{logs+=c;});
 const ready=new Promise((resolve,reject)=>{p.once('error',reject);p.once('close',()=>reject(new Error('SERVER_START_FAILED')));p.stdout.on('data',c=>{output+=c;logs+=c;if(output.includes('\n'))try{const message=JSON.parse(output.split('\n')[0]);if(!Number.isInteger(message.port))throw new Error('INVALID_READY');resolve(message.port);}catch(e){reject(e);}});});
 try{return{p,port:await bounded(ready,timeout,'SERVER_START_TIMEOUT'),logs:()=>logs};}catch(error){try{await stop(p);}catch(cleanup){throw new AggregateError([error,cleanup],'SERVER_START_AND_STOP_FAILED');}throw error;}
}
