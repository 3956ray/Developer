import { createCipheriv, createDecipheriv, randomBytes, randomInt } from 'node:crypto';
import { fail, fields, hmac } from './protocol.mjs';
export function memberKey(c, reference) {
 if (typeof reference !== 'string' || !reference.trim() || reference.length>256) fail(422,'INVALID_MEMBER_REFERENCE');
 return hmac(c.keys.memberHmac, `member\0${c.gymId}\0${reference.trim()}`);
}
export const pairHash = (c, code) => hmac(c.keys.pairLookupHmac, `pairing\0${c.gymId}\0${code}`);
export const randomCode = () => randomInt(0,100000000).toString().padStart(8,'0');
const aad = (c,p) => Buffer.from(`${c.environment}\0${c.gymId}\0${p.pairing_id}\0${p.expires_at}`);
export function encryptCode(c,p,code) {
 const nonce=randomBytes(12), cipher=createCipheriv('aes-256-gcm',Buffer.from(c.keys.pairEncryption,'hex'),nonce); cipher.setAAD(aad(c,p));
 return { ciphertext:Buffer.concat([cipher.update(code,'utf8'),cipher.final()]).toString('hex'),nonce:nonce.toString('hex'),tag:cipher.getAuthTag().toString('hex') };
}
export function decryptCode(c,p) {
 try { const d=createDecipheriv('aes-256-gcm',Buffer.from(c.keys.pairEncryption,'hex'),Buffer.from(p.nonce,'hex')); d.setAAD(aad(c,p));d.setAuthTag(Buffer.from(p.tag,'hex'));return Buffer.concat([d.update(Buffer.from(p.ciphertext,'hex')),d.final()]).toString('utf8'); }
 catch { fail(503,'PAIRING_DATA_UNAVAILABLE'); }
}
export function expiry(input,timeZone) {
 fields(input,['mode','validUntil','localEndDate'],['mode']);
 if (!['fixed_until','no_fixed_expiry','pending_confirmation'].includes(input.mode)) fail(422,'INVALID_PERIOD');
 if(input.mode!=='fixed_until') { if(Object.keys(input).length!==1) fail(422,'INVALID_PERIOD'); return { mode:input.mode,until:null,localDate:null }; }
 if(typeof input.validUntil!=='string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(input.validUntil) || !Number.isFinite(Date.parse(input.validUntil)) || new Date(input.validUntil).toISOString()!==input.validUntil) fail(422,'INVALID_PERIOD');
 const until=Date.parse(input.validUntil);
 if(input.localEndDate!==undefined) {
  const localMs=typeof input.localEndDate==='string'?Date.parse(input.localEndDate+'T00:00:00.000Z'):NaN;
  if(!/^\d{4}-\d{2}-\d{2}$/.test(input.localEndDate) || !Number.isFinite(localMs) || new Date(localMs).toISOString().slice(0,10)!==input.localEndDate || input.localEndDate==='9999-12-31') fail(422,'INVALID_PERIOD');
  const next=new Date(localMs+86400000).toISOString().slice(0,10);
  const parts=Object.fromEntries(new Intl.DateTimeFormat('en-CA',{timeZone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(new Date(until)).map(p=>[p.type,p.value]));
  if(`${parts.year}-${parts.month}-${parts.day}`!==next || parts.hour!=='00' || parts.minute!=='00' || parts.second!=='00' || until%1000!==0) fail(422,'INVALID_PERIOD');
 }
 return {mode:input.mode,until,localDate:input.localEndDate??null};
}

export function dateEnd(localEndDate,timeZone) {
 const ms=typeof localEndDate==='string'?Date.parse(localEndDate+'T00:00:00.000Z'):NaN;
 if(!/^\d{4}-\d{2}-\d{2}$/.test(localEndDate)||!Number.isFinite(ms)||new Date(ms).toISOString().slice(0,10)!==localEndDate||localEndDate==='9999-12-31')fail(422,'INVALID_PERIOD');
 const target=ms+86400000,format=new Intl.DateTimeFormat('en-CA',{timeZone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
 const parts=time=>Object.fromEntries(format.formatToParts(new Date(time)).map(p=>[p.type,p.value]));
 const offsets=new Set();
 for(let hours=-36;hours<=36;hours+=6){const probe=target+hours*3600000,p=parts(probe);offsets.add(Date.parse(`${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}:${p.second}.000Z`)-probe);}
 const desired=new Date(target).toISOString().slice(0,10),candidates=[];
 for(const offset of offsets){const value=target-offset,p=parts(value);if(`${p.year}-${p.month}-${p.day}`===desired&&p.hour==='00'&&p.minute==='00'&&p.second==='00')candidates.push(value);}
 if(candidates.length===0)fail(422,'TIMEZONE_NONEXISTENT');if(candidates.length!==1)fail(422,'TIMEZONE_AMBIGUOUS');
 return{mode:'fixed_until',localEndDate,validUntil:new Date(candidates[0]).toISOString(),timeZone};
}
