package com.example.thinkv2.reminders

import com.example.thinkv2.notes.Sql

object ReminderSchema {
    fun migrate(db: Sql) {
        db.execute("CREATE TABLE reminders (id TEXT PRIMARY KEY,note_id TEXT NOT NULL UNIQUE,revision INTEGER NOT NULL," +
            "kind TEXT NOT NULL CHECK(kind IN ('ONCE','DAILY','WEEKLY')),start_day TEXT NOT NULL,hour INTEGER NOT NULL CHECK(hour BETWEEN 0 AND 23)," +
            "minute INTEGER NOT NULL CHECK(minute BETWEEN 0 AND 59),weekdays INTEGER NOT NULL CHECK(weekdays BETWEEN 0 AND 127)," +
            "requested INTEGER NOT NULL CHECK(requested IN (0,1)),status TEXT NOT NULL,next_key TEXT NOT NULL DEFAULT ''," +
            "next_at INTEGER NOT NULL DEFAULT 0,last_key TEXT NOT NULL DEFAULT '',problem TEXT NOT NULL DEFAULT '')")
        db.execute("CREATE TABLE reminder_events (reminder_id TEXT NOT NULL,revision INTEGER NOT NULL,instance TEXT NOT NULL," +
            "at INTEGER NOT NULL,checked_at INTEGER NOT NULL,outcome TEXT NOT NULL,reported INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL DEFAULT ''," +
            "PRIMARY KEY(reminder_id,revision,instance))")
        db.execute("CREATE TABLE reminder_runtime (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execute("CREATE INDEX reminder_event_time ON reminder_events(checked_at DESC)")
    }
}
