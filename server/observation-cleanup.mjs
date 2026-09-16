import { transaction } from './database.mjs';
export const HOUR = 3600000;
export const RETENTION = 30 * 86400000;
export function createObservationCleanup(db, c, options = {}) {
  if (c.environment !== 'test' && (options.now || options.afterDelete)) throw new Error('TEST_HOOK_FORBIDDEN');
  const now = options.now ?? Date.now;
  function status() { return db.prepare('SELECT * FROM observation_cleanup WHERE singleton=1').get(); }
  function batch(force = false) {
    try {
      return transaction(db, () => {
        const time = now(); let progress = status();
        if (!force && progress.state !== 'running' && progress.next_run_at > time) return { done: true, skipped: true, status: progress };
        if (progress.state === 'idle') {
          db.prepare("UPDATE observation_cleanup SET state='running',cursor_revision=0,cutoff_at=? WHERE singleton=1").run(time - RETENTION);
          progress = status();
        } else if (progress.state === 'failed') {
          db.prepare("UPDATE observation_cleanup SET state='running' WHERE singleton=1").run();
        }
        const rows = db.prepare('SELECT revision FROM observation_events WHERE published_at<=? AND revision>? ORDER BY revision LIMIT 100').all(progress.cutoff_at, progress.cursor_revision);
        const remove = db.prepare('DELETE FROM observation_events WHERE revision=?');
        for (const row of rows) remove.run(row.revision);
        if (options.afterDelete) options.afterDelete();
        const done = rows.length < 100, completedAt = now();
        db.prepare(`UPDATE observation_cleanup SET state=?,cursor_revision=?,cutoff_at=?,next_run_at=?,last_success_at=?,deleted_total=deleted_total+?,last_error_category=NULL WHERE singleton=1`)
          .run(done ? 'idle' : 'running', done ? 0 : rows.at(-1).revision, done ? null : progress.cutoff_at,
            done ? completedAt + HOUR : completedAt, done ? completedAt : progress.last_success_at, rows.length);
        return { done, removed: rows.length, status: status() };
      });
    } catch {
      try {
        transaction(db, () => {
          // If the first batch rolled back, preserve a cutoff for the retry without pretending any rows were removed.
          db.prepare("UPDATE observation_cleanup SET state='failed',cutoff_at=coalesce(cutoff_at,?),next_run_at=?,error_count=error_count+1,last_error_category='CLEANUP_FAILED' WHERE singleton=1").run(now() - RETENTION, now() + HOUR);
        });
      } catch { /* DB unavailable: runner emits only a fixed error category. */ }
      return { done: true, failed: true };
    }
  }
  async function run(force = false) {
    let result = batch(force);
    while (!result.done) { await new Promise(resolve => setImmediate(resolve)); result = batch(); }
    return result;
  }
  return { status, batch, run, now };
}
export function startObservationCleanup(cleanup, onError = () => {}, timers = { setTimeout, clearTimeout }) {
  let stopped = false, timer, task = Promise.resolve();
  const execute = force => {
    if (stopped) return task;
    task = cleanup.run(force).then(result => {
      if (result.failed) onError();
      return result;
    }).catch(() => { onError(); return { failed: true }; }).then(result => {
      if (stopped) return;
      let delay = HOUR;
      if (!result.failed) {
        try { delay = Math.max(1, cleanup.status().next_run_at - cleanup.now()); } catch { onError(); }
      }
      timer = timers.setTimeout(() => execute(false), delay);
      if (timer.unref) timer.unref();
    });
    return task;
  };
  execute(true);
  return async () => { stopped = true; timers.clearTimeout(timer); await task; };
}
