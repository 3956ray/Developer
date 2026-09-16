import {randomUUID} from 'node:crypto';
import {transaction} from './database.mjs';
import {fail,fields,validateWrite,uuidPattern,canonical} from './protocol.mjs';
import {range,period,localDate,dateMs,addDate,dayBoundary} from './schedule-time.mjs';
const revision=row=>row?.revision??'absent';
const cas=(actual,expected)=>{if(actual!==expected)fail(409,'REVISION_CONFLICT');};
const publicProvenance=(row,simulation)=>{const p=row?JSON.parse(row.provenance_json):{kind:'manual'};return Object.fromEntries(Object.entries({...p,simulation}).filter(([k])=>['kind','sourceLabel','capturedAt','importedAt','editedAt','simulation'].includes(k)));};
const overlaps=(c,start,end)=>start<end&&Date.parse(c.startAt)<end&&Date.parse(c.endAt)>start;
export function createScheduleService(db,c,identity){
 const head=()=>db.prepare('SELECT * FROM schedule_head WHERE singleton=1').get();
 const draft=()=>db.prepare('SELECT * FROM schedule_draft WHERE singleton=1').get();
 function zoneGuard(){const h=head();if(h?.state==='published'&&h.time_zone!==c.timeZone)fail(503,'PUBLISHED_TIMEZONE_MISMATCH');}
 function viewDraft(){const d=draft(),h=head();return{draftRevision:revision(d),publicationRevision:revision(h),publicationState:h?.state??'unpublished',timeZone:c.timeZone,draftTimeZone:d?.time_zone??c.timeZone,coverage:d?JSON.parse(d.coverage_json):null,courses:d?JSON.parse(d.courses_json):[],provenance:d?JSON.parse(d.provenance_json):{kind:'manual'},publishedCoverage:h?.state==='published'?JSON.parse(db.prepare('SELECT coverage_json FROM schedule_snapshots WHERE id=?').get(h.current_snapshot_id).coverage_json):null};}
 function validateCourses(courses,coverage,old=[]){if(!Array.isArray(courses)||courses.length>200)fail(422,'INVALID_COURSES');const seen=new Set(),previous=new Map(old.map(x=>[x.courseId,x]));
  const output=courses.map(item=>{fields(item,['courseId','name','startAt','endAt','status','revision','localInput']);if(!uuidPattern.test(item.courseId)||seen.has(item.courseId)||typeof item.name!=='string'||!item.name.trim()||item.name.length>100||!['scheduled','cancelled'].includes(item.status))fail(422,'INVALID_COURSE');seen.add(item.courseId);const before=previous.get(item.courseId);cas(before?.revision??'absent',item.revision);const times=period(item.localInput,c.timeZone);if(times.startAt!==item.startAt||times.endAt!==item.endAt)fail(422,'LOCAL_UTC_MISMATCH');if(!overlaps(item,Date.parse(coverage.startAt),Date.parse(coverage.endAt)))fail(422,'COURSE_OUTSIDE_COVERAGE');
   const same=before&&canonical({...before,revision:0})===canonical({...item,revision:0});return{...item,revision:same?before.revision:(before?.revision??0)+1};});
  for(const item of old)if(!seen.has(item.courseId)&&overlaps(item,Date.parse(coverage.startAt),Date.parse(coverage.endAt)))fail(422,'EXPLICIT_CANCELLATION_REQUIRED');return output;
 }
 function snapshot(time,from,to){zoneGuard();const h=head(),query=range(from,to,c.timeZone),s=h?.state==='published'?db.prepare('SELECT * FROM schedule_snapshots WHERE id=?').get(h.current_snapshot_id):null;if(h?.state==='published'&&!s)fail(503,'SCHEDULE_DATA_UNAVAILABLE');
  const coverage=s?JSON.parse(s.coverage_json):null,all=s?JSON.parse(s.courses_json):[];
  const days=[];for(let day=from;day<to;day=addDate(day,1)){const covered=!!coverage&&day>=coverage.startDate&&day<coverage.endDate;const courses=covered?all.filter(x=>overlaps(x,dayBoundary(day,c.timeZone),dayBoundary(addDate(day,1),c.timeZone))):[];days.push({date:day,covered,state:h?.state==='withdrawn'?'withdrawn':!covered?'unpublished':courses.length?'scheduled':'empty',courses});}
  const relevant=all.filter(x=>days.some(d=>d.covered&&d.courses.some(y=>y.courseId===x.courseId))),today=localDate(time,c.timeZone),nowCovered=!!coverage&&today>=coverage.startDate&&today<coverage.endDate;
  return{publicationRevision:revision(h),state:h?.state==='withdrawn'?'withdrawn':days.some(d=>d.covered)?'published':'unpublished',publicationState:h?.state??'unpublished',source:'gym-schedule',provenance:publicProvenance(s,c.environment==='test'),timeZone:c.timeZone,simulation:c.environment==='test',environment:c.environment,query,coverage,days,courses:relevant,currentCourses:nowCovered?all.filter(x=>x.status==='scheduled'&&Date.parse(x.startAt)<=time&&time<Date.parse(x.endAt)):[],publishedAt:s?new Date(s.published_at).toISOString():null};
 }
 // Internal synchronous hooks for the import service's existing authorized transaction.
 // They do not open transactions or publish; the caller atomically commits batch/mapping/cursor.
 function imported(input){zoneGuard();const old=draft();const coverage=range(input.coverage.startDate,input.coverage.endDate,c.timeZone);
  const courses=validateCourses(input.courses,coverage,old?JSON.parse(old.courses_json):[]);
  const live=courses.filter(x=>x.status==='scheduled').sort((a,b)=>a.startAt.localeCompare(b.startAt));
  for(let n=1;n<live.length;n++)if(live[n-1].endAt>live[n].startAt)fail(422,'COURSE_OVERLAP');
  return{coverage,courses};
 }
 return{
  importView(){return viewDraft();},
  validateImport(input){return imported(input);},
  applyImport(context,input,provenance){
   if(!db.isTransaction)fail(503,'IMPORT_TRANSACTION_REQUIRED');
   const old=draft();cas(revision(old),input.expectedDraft);cas(revision(head()),input.expectedPublication);
   const {coverage,courses}=imported(input),rev=(old?.revision??0)+1;
   db.prepare('INSERT INTO schedule_draft(singleton,revision,time_zone,coverage_json,courses_json,provenance_json) VALUES(1,?,?,?,?,?) ON CONFLICT(singleton) DO UPDATE SET revision=excluded.revision,time_zone=excluded.time_zone,coverage_json=excluded.coverage_json,courses_json=excluded.courses_json,provenance_json=excluded.provenance_json').run(rev,c.timeZone,JSON.stringify(coverage),JSON.stringify(courses),JSON.stringify(provenance));
   context.audit(context.userId,null,'schedule.import.apply',rev,context.time,'import-confirmed');
   db.prepare('UPDATE sessions SET last_interactive_at=? WHERE session_id=?').run(context.time,context.session.session_id);
   return{draftRevision:rev,publicationRevision:revision(head())};
  },
  summary(time){const today=localDate(time,c.timeZone),result=snapshot(time,today,addDate(today,1));return{...result,today,weekStart:addDate(today,-((new Date(dateMs(today)).getUTCDay()+6)%7))};},
  read(from,to,view){return transaction(db,()=>{const time=identity.now(),today=localDate(time,c.timeZone);if(view){if(!['today','week'].includes(view)||from||to)fail(422,'INVALID_RANGE');from=view==='today'?today:addDate(today,-((new Date(dateMs(today)).getUTCDay()+6)%7));to=addDate(from,view==='today'?1:7);}return{serverNow:new Date(time).toISOString(),data:snapshot(time,from,to)};});},
  getDraft(token){return identity.authorized(token,{operator:true},()=>{zoneGuard();return viewDraft();});},
  preview(token,input){return identity.authorized(token,{operator:true},()=>period(input,c.timeZone));},
  save(token,body){validateWrite(body,['coverage','courses']);fields(body.coverage,['startDate','endDate']);return identity.domainOperation(token,'schedule.draft.save',body,{operator:true},({userId,time,audit})=>{zoneGuard();const old=draft();cas(revision(old),body.expectedRevision);const coverage=range(body.coverage.startDate,body.coverage.endDate,c.timeZone),courses=validateCourses(body.courses,coverage,old?JSON.parse(old.courses_json):[]),rev=(old?.revision??0)+1;
   db.prepare('INSERT INTO schedule_draft(singleton,revision,time_zone,coverage_json,courses_json,provenance_json) VALUES(1,?,?,?,?,?) ON CONFLICT(singleton) DO UPDATE SET revision=excluded.revision,time_zone=excluded.time_zone,coverage_json=excluded.coverage_json,courses_json=excluded.courses_json,provenance_json=excluded.provenance_json').run(rev,c.timeZone,JSON.stringify(coverage),JSON.stringify(courses),JSON.stringify(old&&JSON.parse(old.provenance_json).kind!=='manual'?{...JSON.parse(old.provenance_json),kind:'import-edited',editedAt:new Date(time).toISOString()}:{kind:'manual'}));audit(userId,null,'schedule.draft.save',rev,time,'draft-edited');return{revision:rev,data:{draftRevision:rev,courses}};});},
  publish(token,body){validateWrite(body,['replaceCoverageConfirmed'],['draft','publication']);if(body.replaceCoverageConfirmed!==true)fail(422,'REPLACEMENT_CONFIRMATION_REQUIRED');return identity.domainOperation(token,'schedule.publish',body,{operator:true},({userId,time,audit})=>{zoneGuard();const d=draft(),h=head();cas(revision(d),body.expectedRevision.draft);cas(revision(h),body.expectedRevision.publication);if(!d)fail(422,'DRAFT_REQUIRED');if(d.time_zone!==c.timeZone)fail(422,'TIMEZONE_RECONFIRM_REQUIRED');const coverage=JSON.parse(d.coverage_json),courses=JSON.parse(d.courses_json);validateCourses(courses,coverage,courses);const scheduled=courses.filter(x=>x.status==='scheduled').sort((a,b)=>a.startAt.localeCompare(b.startAt));for(let n=1;n<scheduled.length;n++)if(scheduled[n-1].endAt>scheduled[n].startAt)fail(422,'COURSE_OVERLAP');
   const rev=(h?.revision??0)+1,id=randomUUID();if(h?.current_snapshot_id)db.prepare('UPDATE schedule_snapshots SET retired_at=? WHERE id=?').run(time,h.current_snapshot_id);db.prepare('INSERT INTO schedule_snapshots(id,publication_revision,time_zone,coverage_json,coverage_end_at,courses_json,published_at,retired_at,provenance_json) VALUES(?,?,?,?,?,?,?,NULL,?)').run(id,rev,c.timeZone,d.coverage_json,Date.parse(coverage.endAt),d.courses_json,time,d.provenance_json);db.prepare("INSERT INTO schedule_head VALUES(1,?,'published',?,?,?) ON CONFLICT(singleton) DO UPDATE SET revision=excluded.revision,state=excluded.state,current_snapshot_id=excluded.current_snapshot_id,time_zone=excluded.time_zone,changed_at=excluded.changed_at").run(rev,id,c.timeZone,time);audit(userId,null,'schedule.publish',rev,time,'coverage-replaced');return{revision:rev,data:{publicationRevision:rev,coverage}};});},
  withdraw(token,body){validateWrite(body,['reasonCategory']);if(!['correction','updates-paused'].includes(body.reasonCategory))fail(422,'INVALID_REASON');return identity.domainOperation(token,'schedule.withdraw',body,{operator:true},({userId,time,audit})=>{const h=head();cas(revision(h),body.expectedRevision);if(!h)fail(409,'NOT_PUBLISHED');if(h.current_snapshot_id)db.prepare('UPDATE schedule_snapshots SET retired_at=? WHERE id=?').run(time,h.current_snapshot_id);db.prepare("UPDATE schedule_head SET revision=revision+1,state='withdrawn',current_snapshot_id=NULL,changed_at=? WHERE singleton=1").run(time);audit(userId,null,'schedule.withdraw',h.revision+1,time,body.reasonCategory);return{revision:h.revision+1,data:{publicationRevision:h.revision+1,state:'withdrawn'}};});}
 };
}
