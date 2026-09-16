import { DatabaseSync } from 'node:sqlite';
import { readFileSync, readdirSync, existsSync, lstatSync } from 'node:fs';
import { createHash } from 'node:crypto';

export function transaction(db, action) {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = action();
    if (result && typeof result.then === 'function') throw new Error('ASYNC_TRANSACTION_FORBIDDEN');
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}
export function openDatabase(config, migrations = new URL('./migrations/', import.meta.url)) {
  if (existsSync(config.databasePath) && lstatSync(config.databasePath).isSymbolicLink()) throw new Error('DB_SYMLINK_FORBIDDEN');
  const db = new DatabaseSync(config.databasePath, { timeout: 2000, allowExtension: false, defensive: true });
  try {
    db.exec('PRAGMA foreign_keys=ON; PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; PRAGMA secure_delete=ON; PRAGMA trusted_schema=OFF;');
    transaction(db, () => {
      db.exec('CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, sha256 TEXT NOT NULL, applied_at INTEGER NOT NULL) STRICT');
      const files = readdirSync(migrations).filter(f => /^\d{3}-[a-z-]+\.sql$/.test(f)).sort();
      const applied = db.prepare('SELECT name, sha256 FROM schema_migrations').all();
      if (applied.some(row => !files.includes(row.name))) throw new Error('MIGRATION_SET_MISMATCH');
      for (const name of files) {
        const sql = readFileSync(new URL(name, migrations), 'utf8');
        const hash = createHash('sha256').update(sql).digest('hex');
        const old = applied.find(row => row.name === name);
        if (old && old.sha256 !== hash) throw new Error('MIGRATION_CHECKSUM_MISMATCH');
        if (!old) {
          db.exec(sql);
          db.prepare('INSERT INTO schema_migrations VALUES (?, ?, ?)').run(name, hash, Date.now());
        }
      }
      const identity = db.prepare('SELECT * FROM deployment WHERE singleton=1').get();
      if (identity) {
        if (identity.environment !== config.environment || identity.gym_id !== config.gymId || identity.key_fingerprint !== config.keyFingerprint) throw new Error('DB_SCOPE_OR_KEYS_MISMATCH');
      } else {
        db.prepare('INSERT INTO deployment VALUES (1, ?, ?, ?, ?)').run(config.environment, config.gymId, config.keyFingerprint, Date.now());
      }
    });
    const published=db.prepare("SELECT time_zone FROM schedule_head WHERE state='published'").get();
    if(published&&published.time_zone!==config.timeZone)throw new Error('PUBLISHED_TIMEZONE_MISMATCH');
    return db;
  } catch (error) { db.close(); throw error; }
}
