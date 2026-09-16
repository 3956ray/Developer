import {loadConfig} from '../server/config.mjs';
import {openDatabase} from '../server/database.mjs';
import {createScheduleCleanup} from '../server/schedule-cleanup.mjs';
process.umask(0o077);let db;
try{const [action,path]=process.argv.slice(2),c=loadConfig(path);db=openDatabase(c);const cleanup=createScheduleCleanup(db,c);if(action==='run'){if((await cleanup.run(true)).failed)throw new Error('FAILED');}else if(action!=='status')throw new Error('COMMAND');console.log(JSON.stringify({ok:true,data:cleanup.status()}));}catch{console.error('SCHEDULE_CLEANUP_UNAVAILABLE');process.exitCode=1;}finally{db?.close();}
