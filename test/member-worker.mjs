import {readFileSync,existsSync} from 'node:fs';
import {loadConfig} from '../server/config.mjs';
import {openDatabase} from '../server/database.mjs';
import {createIdentityService} from '../server/identity.mjs';
import {createMembershipService} from '../server/membership.mjs';
const c=loadConfig(process.argv[2]);if(c.environment!=='test')throw new Error('TEST_ONLY');
const input=JSON.parse(readFileSync(process.argv[3])),db=openDatabase(c);let held=false;
const service=createIdentityService(db,c,{now:()=>{
 if(input.hold&&!held){held=true;process.send('locked');const end=Date.now()+5000;while(!existsSync(input.release)){if(Date.now()>end)throw new Error('BARRIER_TIMEOUT');Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,10);}}
 return input.timeFile?Number(readFileSync(input.timeFile,'utf8')):input.time;
}}),m=createMembershipService(db,c,service);
function run(){if(process.send)process.send('attempting');let result;try{m[input.action](input.token,input.body);result={status:'committed'};}catch(e){result={status:e.code??'STORAGE_FAILURE'};}db.close();if(process.send){process.send(result);process.disconnect();}else console.log(JSON.stringify(result));}
if(process.send){process.send('ready');process.once('message',run);}else run();
