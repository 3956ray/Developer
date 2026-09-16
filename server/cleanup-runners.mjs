import {createDemoCleanup} from './demo-cleanup.mjs';
import {createObservationCleanup,startObservationCleanup} from './observation-cleanup.mjs';
import {createMemberCleanup} from './member-cleanup.mjs';
import {createScheduleCleanup} from './schedule-cleanup.mjs';
// The same wiring is used by the production entry point and controlled-clock integration tests.
export function startCleanups(db,c,onError,options={}){
 if(c.environment!=='test'&&Object.keys(options).length)throw new Error('TEST_HOOK_FORBIDDEN');
 const constructors={observation:createObservationCleanup,member:createMemberCleanup,schedule:createScheduleCleanup};
 if(c.demo?.enabled)constructors.demo=createDemoCleanup;
 const stops=Object.entries(constructors).map(([name,create])=>startObservationCleanup(create(db,c,options.now?{now:options.now}:{}),()=>onError(name),options.timers));
 return()=>Promise.all(stops.map(stop=>stop()));
}
