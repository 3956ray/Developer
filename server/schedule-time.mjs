import {fail,fields} from './protocol.mjs';
export const DAY=86400000;
export function dateMs(date){const n=typeof date==='string'?Date.parse(date+'T00:00:00.000Z'):NaN;if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!Number.isFinite(n)||new Date(n).toISOString().slice(0,10)!==date||date<'0100-01-01'||date>'9998-12-31')fail(422,'INVALID_DATE');return n;}
export function addDate(date,days){return new Date(dateMs(date)+days*DAY).toISOString().slice(0,10);}
const formatters=new Map();
export function localParts(time,zone){if(!formatters.has(zone))formatters.set(zone,new Intl.DateTimeFormat('en-CA',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}));return Object.fromEntries(formatters.get(zone).formatToParts(new Date(time)).map(p=>[p.type,p.value]));}
export function localDate(time,zone){const p=localParts(time,zone);return `${p.year}-${p.month}-${p.day}`;}
// Calendar range boundary: first instant whose local calendar date reaches this date.
// Repeated midnight belongs to that same day; a wholly skipped date has zero duration.
export function dayBoundary(date,zone){const target=dateMs(date);let low=target-2*DAY,high=target+2*DAY;while(high-low>1){const mid=Math.floor((low+high)/2);if(localDate(mid,zone)<date)low=mid;else high=mid;}return high;}
export function range(from,to,zone){const days=(dateMs(to)-dateMs(from))/DAY;if(days<1||days>14)fail(422,'INVALID_COVERAGE');return{startDate:from,endDate:to,startAt:new Date(dayBoundary(from,zone)).toISOString(),endAt:new Date(dayBoundary(to,zone)).toISOString()};}
export function localTime(input,zone){fields(input,['local','offset']);if(typeof input.local!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(input.local)||typeof input.offset!=='string'||!/^[-+]\d{2}:\d{2}$/.test(input.offset))fail(422,'EXPLICIT_OFFSET_REQUIRED');dateMs(input.local.slice(0,10));
 const [hours,minutes]=input.offset.slice(1).split(':').map(Number);if(hours>14||minutes>59||(hours===14&&minutes!==0))fail(422,'INVALID_LOCAL_TIME');
 const offset=(hours*60+minutes)*60000*(input.offset[0]==='-'?-1:1),wall=Date.parse(input.local+':00.000Z'),time=wall-offset;if(!Number.isFinite(time))fail(422,'INVALID_LOCAL_TIME');
 const p=localParts(time,zone);if(`${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`!==input.local||p.second!=='00')fail(422,'INVALID_LOCAL_TIME');return new Date(time).toISOString();
}
export function period(input,zone){fields(input,['start','end']);const startAt=localTime(input.start,zone),endAt=localTime(input.end,zone);if(startAt>=endAt)fail(422,'INVALID_COURSE_INTERVAL');return{startAt,endAt};}
