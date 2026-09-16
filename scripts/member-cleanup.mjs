import {loadConfig} from '../server/config.mjs';
import {openDatabase} from '../server/database.mjs';
import {createMemberCleanup} from '../server/member-cleanup.mjs';
process.umask(0o077);let db;
try {const [action,path,jobId]=process.argv.slice(2),c=loadConfig(path);db=openDatabase(c);const cleaner=createMemberCleanup(db,c);
 if(action==='retry')cleaner.retry(jobId);else if(action==='run'){if((await cleaner.run(true)).failed)throw new Error('FAILED');}else if(action!=='status')throw new Error('COMMAND');
 console.log(JSON.stringify({ok:true,data:cleaner.status()}));
}catch{console.error('MEMBER_CLEANUP_UNAVAILABLE');process.exitCode=1;}finally{if(db)db.close();}
