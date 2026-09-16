import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { requireDemo } from '../server/demo.mjs';
import { createDemoRunner } from '../server/demo-runner.mjs';
import { ApiError, fail } from '../server/protocol.mjs';
import { privateInput } from './private-input.mjs';
export async function runDemo(path,argv){
 const c=loadConfig(path);requireDemo(c);let db;
 try{db=openDatabase(c);const args=[...argv],take=name=>{const i=args.indexOf(name);if(i<0)return undefined;if(i===args.length-1)fail(422,'INVALID_COMMAND');const value=args[i+1];args.splice(i,2);return value;};
  const sessionFile=take('--sessions-file'),port=take('--port'),anchor=take('--anchor-date'),interval=take('--interval-seconds'),confirmation=take('--confirm');
  const [action,target]=args;if(args.length!==2)fail(422,'INVALID_COMMAND');
  const chosen=port===undefined?c.port:Number(port);if(!Number.isInteger(chosen)||chosen<1||chosen>65535)fail(422,'PORT_REQUIRED');
  const runner=createDemoRunner(db,c,'http://127.0.0.1:'+chosen);let result;
  if(action==='status')result=runner.status(target);
  else if(action==='cancel')result=runner.cancel(target,confirmation==='cancel');
  else{const credentials=privateInput(sessionFile??'-');if(Object.keys(credentials).some(k=>!['member-a','member-b','operator-a','deletionReceipt'].includes(k)))fail(422,'INVALID_CREDENTIAL_FIELDS');
   if(action==='start')result=await runner.start(target,anchor,credentials,confirmation==='existing');
   else if(action==='step')result=await runner.step(target,credentials,confirmation);
   else if(action==='resume')result=await runner.resume(target,credentials,confirmation);
   else if(action==='play')result=await runner.play(target,credentials,Number(interval));
   else fail(422,'INVALID_COMMAND');
  }
  console.log(JSON.stringify({ok:true,data:result}));
 }catch(e){console.error(JSON.stringify({ok:false,status:e instanceof ApiError?e.status:503,error:e instanceof ApiError?e.code:'STORAGE_OR_CONFIG_UNAVAILABLE'}));process.exitCode=1;}
 finally{if(db?.isOpen)db.close();}
}
