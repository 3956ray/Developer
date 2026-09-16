import { openSync, closeSync, readFileSync, fstatSync, constants } from 'node:fs';
import { fail, parseStrict } from '../server/protocol.mjs';
// Credentials enter only stdin or an owner-only regular file, never argv values.
export function privateInput(path = '-') {
  let fd;
  try {
    fd=path==='-'?0:openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);
    if(fd!==0){const s=fstatSync(fd);if(!s.isFile()||(s.mode&0o077)||s.size>4096)fail(422,'PRIVATE_INPUT_REQUIRED');}
    const text=readFileSync(fd,'utf8');if(Buffer.byteLength(text)>4096)fail(422,'PRIVATE_INPUT_REQUIRED');return parseStrict(text);
  } finally {if(fd!==undefined&&fd!==0)closeSync(fd);}
}
