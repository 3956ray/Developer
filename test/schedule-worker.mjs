import {readFileSync,existsSync} from 'node:fs';
import {loadConfig} from '../server/config.mjs';
import {openDatabase} from '../server/database.mjs';
import {createIdentityService} from '../server/identity.mjs';
import {createScheduleService} from '../server/schedules.mjs';
const c=loadConfig(process.argv[2]);if(c.environment!=='test')throw new Error('TEST_ONLY');const input=JSON.parse(readFileSync(process.argv[3])),db=openDatabase(c);let held=false;
const identity=createIdentityService(db,c,{now:()=>{if(input.hold&&!held){held=true;process.send('locked');const until=Date.now()+5000;while(!existsSync(input.release)){if(Date.now()>until)throw new Error('BARRIER_TIMEOUT');Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,10);}}return input.time;}}),s=createScheduleService(db,c,identity);
function run(){process.send('attempting');let result;try{if(input.action==='revoke')identity.changeRole(input.body);else s[input.action](input.token,input.body);result={status:'committed'};}catch(e){result={status:e.code??'STORAGE_FAILURE'};}db.close();process.send(result);process.disconnect();}process.send('ready');process.once('message',run);
