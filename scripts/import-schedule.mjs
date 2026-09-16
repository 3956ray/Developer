import { readFileSync, statSync } from 'node:fs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createIdentityService } from '../server/identity.mjs';
import { createScheduleImport } from '../server/schedule-import.mjs';
import { requireDemo } from '../server/demo.mjs';
import { ApiError, fail, fields } from '../server/protocol.mjs';
import { privateInput } from './private-input.mjs';
function sourceText(path){if(statSync(path).size>65536)fail(422,'IMPORT_SIZE');try{return new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(readFileSync(path));}catch{fail(422,'IMPORT_INVALID_UTF8');}}
process.umask(0o077);let db;
try {
  const [action,path,...args]=process.argv.slice(2),c=loadConfig(path);requireDemo(c);db=openDatabase(c);
  const importer=createScheduleImport(db,c,createIdentityService(db,c));let result;
  if(action==='register'&&args.length===2&&args[1]==='--confirm')result=importer.register({...JSON.parse(readFileSync(args[0],'utf8')),confirmed:true});
  else if(['plan','replan','apply','status'].includes(action)){
    const index=args.indexOf('--session-file');if(index>=0&&index!==args.length-2)fail(422,'INVALID_COMMAND');
    const credentials=privateInput(index>=0?args[index+1]:'-');fields(credentials,['token']);
    const rest=index>=0?args.slice(0,index):args;
    if(action==='plan'&&rest.length===1){result=importer.plan(credentials.token,sourceText(rest[0]));}
    else if(action==='replan'&&rest.length===5&&rest[4]==='--confirm-replan'){const rev=x=>x==='absent'?x:Number(x);result=importer.plan(credentials.token,sourceText(rest[0]),{batchId:rest[1],expectedDraft:rev(rest[2]),expectedPublication:rev(rest[3])});}
    else if(action==='apply'&&rest.length===3&&rest[2]==='--confirm-replace')result=importer.apply(credentials.token,{batchId:rest[0],contentHash:rest[1],replaceDraftConfirmed:true});
    else if(action==='status'&&rest.length===1)result=importer.status(credentials.token,rest[0]);
    else fail(422,'INVALID_COMMAND');
  }else fail(422,'INVALID_COMMAND');
  console.log(JSON.stringify({ok:true,data:result}));
}catch(e){console.error(JSON.stringify({ok:false,status:e instanceof ApiError?e.status:503,error:e instanceof ApiError?e.code:'STORAGE_OR_CONFIG_UNAVAILABLE',...(e.field?{field:e.field}:{}),...(e.row?{courseRow:e.row}:{})}));process.exitCode=1;}
finally{if(db?.isOpen)db.close();}
