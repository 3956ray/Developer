import { createServer } from 'node:http';
import { loadConfig } from './config.mjs';
import { openDatabase } from './database.mjs';
import { createHandler } from './http.mjs';
import { createObservationCleanup, startObservationCleanup } from './observation-cleanup.mjs';
import {createScheduleCleanup} from './schedule-cleanup.mjs';
import { createMemberCleanup } from './member-cleanup.mjs';
process.umask(0o077);
let db, stopCleanup;
try {
  const c = loadConfig(process.argv[2]); db = openDatabase(c);
  const server = createServer(createHandler(db, c));
  const stopObservations = startObservationCleanup(createObservationCleanup(db, c), () => console.error('OBSERVATION_CLEANUP_FAILED'));
  const stopMembers = startObservationCleanup(createMemberCleanup(db, c), () => console.error('MEMBER_CLEANUP_FAILED'));
  const stopSchedules=startObservationCleanup(createScheduleCleanup(db,c),()=>console.error('SCHEDULE_CLEANUP_FAILED'));
  stopCleanup = () => Promise.all([stopObservations(), stopMembers(),stopSchedules()]);
  server.requestTimeout = 10000; server.headersTimeout = 10000;
  server.on('error', async error => { await stopCleanup(); db.close(); process.stderr.write(`SERVER_START_FAILED: ${error.code || 'UNKNOWN'}\n`); process.exitCode = 1; });
  server.listen(c.port, c.host, () => console.log(JSON.stringify({ event: 'ready', port: server.address().port, environment: c.environment, gymId: c.gymId, simulation: c.simulation })));
  const stop = () => server.close(async () => { await stopCleanup(); db.close(); process.exitCode = 0; });
  process.once('SIGTERM', stop); process.once('SIGINT', stop);
} catch { if (db) db.close(); process.stderr.write('STARTUP_REJECTED: check explicit configuration, keys and database\n'); process.exitCode = 1; }
