import { readFileSync } from 'node:fs';
import { loadConfig } from '../server/config.mjs';
import { openDatabase } from '../server/database.mjs';
import { createDemoRunner } from '../server/demo-runner.mjs';
import { createIdentityService } from '../server/identity.mjs';
import { createScheduleImport } from '../server/schedule-import.mjs';
const [path,fixtureFile]=process.argv.slice(2),c=loadConfig(path),db=openDatabase(c),fixture=JSON.parse(readFileSync(fixtureFile));
process.send('ready');
process.once('message',async()=>{
 try{
  let result;
  if(fixture.action==='crash-runner'){
   const runner=createDemoRunner(db,c,fixture.baseUrl,{afterWrite:async()=>{process.send('business-committed');await new Promise(()=>{});}});
   result=await runner.step(fixture.runId,fixture.credentials);
  }else{
   const importer=createScheduleImport(db,c,createIdentityService(db,c));
   result=fixture.action.startsWith('apply')?importer.apply(fixture.token,fixture.input):importer.plan(fixture.token,fixture.text,fixture.replacement);
  }
  if(fixture.action==='apply-drop'){process.send('applied-no-result');await new Promise(()=>{});}
  process.send({ok:true,result});
 }catch(e){process.send({ok:false,code:e.code??'STORAGE_UNAVAILABLE',status:e.status??503});}
 finally{db.close();process.disconnect();}
});
