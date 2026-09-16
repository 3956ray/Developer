import { randomUUID } from 'node:crypto';
import { transaction } from './database.mjs';
import { ApiError, fail, fields, parseStrict, digest, canonical } from './protocol.mjs';
import { range, period } from './schedule-time.mjs';
import { requireDemo } from './demo.mjs';
import { createScheduleService } from './schedules.mjs';
const DAY = 86400000;
const id = value => typeof value === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/.test(value);
const safeText = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max && !/[\x00-\x1f@]|:\/\//.test(value);
function invalid(field, row, code = 'IMPORT_INVALID') { const e = new ApiError(422, code); e.field = field; if (row) e.row = row; throw e; }
function parse(text, c) {
  let input;
  if (typeof text !== 'string' || Buffer.byteLength(text) > 65536) invalid('file', null, 'IMPORT_SIZE');
  try { input = parseStrict(text); fields(input, ['schemaVersion','sourceId','gymId','sourceRevision','mode','capturedAt','timeZone','coverage','courses']); }
  catch { invalid('file'); }
  if (input.schemaVersion !== 1 || input.mode !== 'full') invalid('schemaVersion/mode');
  if (!id(input.sourceId) || input.gymId !== c.gymId) invalid('sourceId/gymId');
  if (!Number.isSafeInteger(input.sourceRevision) || input.sourceRevision < 1) invalid('sourceRevision');
  if (input.timeZone !== c.timeZone) invalid('timeZone');
  if (typeof input.capturedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(input.capturedAt) || !Number.isFinite(Date.parse(input.capturedAt)) || new Date(input.capturedAt).toISOString() !== input.capturedAt) invalid('capturedAt');
  try { fields(input.coverage,['startDate','endDate']); range(input.coverage.startDate,input.coverage.endDate,c.timeZone); } catch { invalid('coverage'); }
  if (!Array.isArray(input.courses) || input.courses.length > 200) invalid('courses');
  const seen = new Set();
  input.courses.forEach((course, i) => {
    try {
      fields(course,['externalId','name','localStart','localEnd','status']);
      if (!id(course.externalId) || seen.has(course.externalId)) invalid('externalId',i+1);
      seen.add(course.externalId);
      if (!safeText(course.name,100) || !['scheduled','cancelled'].includes(course.status)) invalid('name/status',i+1);
      period({start:course.localStart,end:course.localEnd},c.timeZone);
    } catch (e) { if (e.row) throw e; invalid('course/time',i+1,e.code || 'IMPORT_INVALID'); }
  });
  return input;
}
export function createScheduleImport(db,c,identity) {
  requireDemo(c); const schedules=createScheduleService(db,c,identity);
  const source=()=>db.prepare('SELECT * FROM source_bindings WHERE singleton=1').get();
  function binding(sourceId) { const s=source(); if(!s||s.source_id!==sourceId||s.gym_id!==c.gymId||s.time_zone!==c.timeZone) fail(422,'SOURCE_NOT_REGISTERED'); return s; }
  function version(s,rev,hash) {
    if(rev<s.applied_revision)fail(409,'SOURCE_REVISION_OLD');
    const known=db.prepare('SELECT content_hash FROM import_versions WHERE source_id=? AND source_revision=?').get(s.source_id,rev);
    if(known&&known.content_hash!==hash)fail(409,'SOURCE_HASH_CONFLICT');
    return rev===s.applied_revision?JSON.parse(s.applied_result_json):null;
  }
  const view=b=>({batchId:b.batch_id,sourceRevision:b.source_revision,contentHash:b.content_hash,state:b.state,expiresAt:new Date(b.expires_at).toISOString(),diff:JSON.parse(b.diff_json),...(b.result_json?{result:JSON.parse(b.result_json)}:{})});
  return {
    register(input) {
      fields(input,['sourceId','sourceLabel','gymId','timeZone','confirmed']);
      if(!id(input.sourceId)||!safeText(input.sourceLabel,60)||input.gymId!==c.gymId||input.timeZone!==c.timeZone||input.confirmed!==true)fail(422,'SOURCE_REGISTRATION_INVALID');
      return transaction(db,()=>{const s=source();if(s){if(s.source_id===input.sourceId&&s.source_label===input.sourceLabel&&s.time_zone===input.timeZone)return{sourceId:s.source_id,registered:true};fail(409,'SOURCE_ALREADY_OWNED');}
        db.prepare('INSERT INTO source_bindings(singleton,source_id,gym_id,source_label,schema_version,mapping_version,time_zone) VALUES(1,?,?,?,?,?,?)').run(input.sourceId,c.gymId,input.sourceLabel,1,1,c.timeZone);return{sourceId:input.sourceId,registered:true};});
    },
    plan(token,text,replacement) {
      return identity.authorized(token,{operator:true},({time})=>{
        const input=parse(text,c),s=binding(input.sourceId),hash=digest(text);
        if(replacement){fields(replacement,['batchId','expectedDraft','expectedPublication']);const old=db.prepare('SELECT * FROM import_batches WHERE batch_id=?').get(replacement.batchId);const applied=s.applied_result_json?JSON.parse(s.applied_result_json):null;if(old?(old.source_id!==s.source_id||old.source_revision!==input.sourceRevision||old.content_hash!==hash):!(applied?.batchId===replacement.batchId&&applied.contentHash===hash))fail(409,'REPLAN_INPUT_MISMATCH');}
        const replay=version(s,input.sourceRevision,hash);
        if(replay)return{state:'applied',...replay};
        const prior=db.prepare("SELECT * FROM import_batches WHERE source_id=? AND source_revision=? AND state='candidate' ORDER BY prepared_at DESC LIMIT 1").get(s.source_id,input.sourceRevision);
        if(replacement){
          if(!prior||prior.batch_id!==replacement.batchId)fail(409,'CANDIDATE_REPLACED');
          const current=schedules.importView(),old=JSON.parse(prior.candidate_json);
          if(current.draftRevision!==replacement.expectedDraft||current.publicationRevision!==replacement.expectedPublication)fail(409,'REVISION_CONFLICT');
          if(current.draftRevision===old.expectedDraft&&current.publicationRevision===old.expectedPublication)fail(409,'CANDIDATE_STILL_CURRENT');
        }else if(prior&&time<prior.expires_at)return view(prior);
        if(prior)db.prepare("UPDATE import_batches SET state='replaced' WHERE batch_id=?").run(prior.batch_id);
        const current=schedules.importView(),old=new Map(current.courses.map(x=>[x.courseId,x])),mapping=[];
        const courses=input.courses.map(item=>{
          const mapped=db.prepare('SELECT course_id FROM source_entity_map WHERE source_id=? AND external_id=?').get(s.source_id,item.externalId);
          const courseId=mapped?.course_id??randomUUID();mapping.push({externalId:item.externalId,courseId});
          const localInput={start:item.localStart,end:item.localEnd};return{courseId,name:item.name,status:item.status,revision:old.get(courseId)?.revision??'absent',...period(localInput,c.timeZone),localInput};
        });
        const covered=range(input.coverage.startDate,input.coverage.endDate,c.timeZone),ids=new Set(courses.map(x=>x.courseId)),cancelled=[];
        for(const before of current.courses)if(!ids.has(before.courseId)&&before.startAt<covered.endAt&&before.endAt>covered.startAt){courses.push({...before,status:'cancelled'});cancelled.push(before.courseId);}
        const candidate={coverage:input.coverage,courses,expectedDraft:current.draftRevision,expectedPublication:current.publicationRevision,mapping};
        try{schedules.validateImport(candidate);}catch(e){if(e.status===503)throw e;invalid('courses',null,e.code||'IMPORT_INVALID');}
        const diff={expectedDraft:current.draftRevision,expectedPublication:current.publicationRevision,coverageBefore:current.coverage,coverageAfter:covered,publicCoverageReplaced:current.publishedCoverage,
          added:courses.filter(x=>!old.has(x.courseId)).map(x=>x.courseId),changed:courses.filter(x=>old.has(x.courseId)&&canonical(x)!==canonical(old.get(x.courseId))).map(x=>x.courseId),explicitCancelled:input.courses.filter(x=>x.status==='cancelled').map(x=>x.externalId),omittedCancelled:cancelled,removedFromCoverage:current.courses.filter(x=>!courses.some(y=>y.courseId===x.courseId)).map(x=>x.courseId),courses};
        const batchId=randomUUID();
        db.prepare('INSERT OR IGNORE INTO import_versions VALUES(?,?,?)').run(s.source_id,input.sourceRevision,hash);
        db.prepare("INSERT INTO import_batches VALUES(?,?,?,?,?,?,?,'candidate',?,?,NULL,NULL)").run(batchId,s.source_id,input.sourceRevision,hash,input.capturedAt,time,time+DAY,JSON.stringify(candidate),JSON.stringify(diff));
        return view(db.prepare('SELECT * FROM import_batches WHERE batch_id=?').get(batchId));
      });
    },
    apply(token,input) {
      fields(input,['batchId','contentHash','replaceDraftConfirmed']);if(input.replaceDraftConfirmed!==true)fail(422,'REPLACEMENT_CONFIRMATION_REQUIRED');
      return identity.authorized(token,{operator:true},context=>{
        const b=db.prepare('SELECT * FROM import_batches WHERE batch_id=?').get(input.batchId);
        if(!b){const s=source(),r=s?.applied_result_json?JSON.parse(s.applied_result_json):null;if(r?.batchId===input.batchId&&r.contentHash===input.contentHash){binding(s.source_id);return r;}fail(404,'BATCH_NOT_FOUND');}
        const s=binding(b.source_id);if(b.state==='replaced')fail(422,'CANDIDATE_EXPIRED');if(input.contentHash!==b.content_hash)fail(409,'CANDIDATE_HASH_MISMATCH');
        const replay=version(s,b.source_revision,b.content_hash);if(replay)return replay;
        if(b.state!=='candidate'||context.time>=b.expires_at)fail(422,'CANDIDATE_EXPIRED');
        const candidate=JSON.parse(b.candidate_json),provenance={kind:'import',sourceId:s.source_id,sourceLabel:s.source_label,batchId:b.batch_id,sourceRevision:b.source_revision,mappingVersion:s.mapping_version,capturedAt:b.captured_at,importedAt:new Date(context.time).toISOString()};
        const result={batchId:b.batch_id,sourceRevision:b.source_revision,contentHash:b.content_hash,...schedules.applyImport(context,candidate,provenance)};
        for(const m of candidate.mapping){const old=db.prepare('SELECT course_id FROM source_entity_map WHERE source_id=? AND external_id=?').get(s.source_id,m.externalId);if(old&&old.course_id!==m.courseId)fail(409,'MAPPING_CONFLICT');db.prepare('INSERT OR IGNORE INTO source_entity_map VALUES(?,?,?)').run(s.source_id,m.externalId,m.courseId);}
        db.prepare("UPDATE import_batches SET state='applied',result_json=?,applied_at=? WHERE batch_id=?").run(JSON.stringify(result),context.time,b.batch_id);
        db.prepare('UPDATE source_bindings SET applied_revision=?,applied_hash=?,applied_result_json=? WHERE singleton=1').run(b.source_revision,b.content_hash,JSON.stringify(result));return result;
      });
    },
    status(token,batchId) {return identity.authorized(token,{operator:true},()=>{const s=source();if(!s)fail(422,'SOURCE_NOT_REGISTERED');binding(s.source_id);const b=db.prepare('SELECT * FROM import_batches WHERE batch_id=?').get(batchId);if(b)return view(b);const result=s.applied_result_json?JSON.parse(s.applied_result_json):null;return result?.batchId===batchId?{state:'applied',result}:{state:'unknown'};});}
  };
}
