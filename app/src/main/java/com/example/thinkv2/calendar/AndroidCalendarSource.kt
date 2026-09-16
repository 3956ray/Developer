package com.example.thinkv2.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract

/** Read-only provider boundary. No insert/update/delete/applyBatch or sync-adapter identity. */
class AndroidCalendarSource(context: Context): CalendarSource {
    private val app=context.applicationContext
    override fun permitted()=app.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED
    private fun permission() { if(!permitted()) throw SecurityException("calendar_permission") }
    private val calendarColumns=arrayOf("_id","account_name","account_type","name","calendar_displayName","calendar_timezone","ownerAccount","visible")
    private val eventColumns=arrayOf("_id","calendar_id","title","description","dtstart","dtend","duration","allDay","eventTimezone","eventEndTimezone","eventLocation",
        "rrule","rdate","exrule","exdate","original_id","original_sync_id","originalInstanceTime","originalAllDay","eventStatus","hasAlarm","deleted","_sync_id","uid2445")
    private fun query(uri: Uri,columns: Array<String>,where: String?,args: Array<String>?,limit: Int): List<Map<String,String?>> {
        permission()
        val cursor=app.contentResolver.query(uri,columns,where,args,null) ?: throw CalendarFailure("provider_unavailable")
        return cursor.use { c ->
            val indexes=columns.map { c.getColumnIndexOrThrow(it) };var bytes=0L
            buildList {
                while(c.moveToNext()) {
                    calendarCheck(size<limit,"source_limit")
                    val row=columns.indices.associate { i -> columns[i] to if(c.isNull(indexes[i])) null else c.getString(indexes[i]) }
                    bytes+=row.values.sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L };calendarCheck(bytes<=4*1024*1024,"source_limit");add(row)
                }
            }.also { permission() }
        }
    }
    override fun calendars()=query(CalendarContract.Calendars.CONTENT_URI,calendarColumns,null,null,100).map(::CalendarRef)
    override fun read(calendar: CalendarRef,range: CalendarRange): CalendarSnapshot {
        val fresh=query(CalendarContract.Calendars.CONTENT_URI,calendarColumns,"_id=?",arrayOf(calendar.id),1).singleOrNull()?.let(::CalendarRef)
            ?: throw CalendarFailure("source_missing")
        calendarCheck(fresh.identity==calendar.identity,"source_changed")
        calendarCheck(fresh.fields["visible"]!="0","hidden_calendar")
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it,range.begin);ContentUris.appendId(it,range.end) }.build()
        val ids=query(uri,arrayOf("event_id"),"calendar_id=?",arrayOf(calendar.id),5000).mapNotNull { it["event_id"] }.toMutableSet()
        // Keep moved and cancelled exceptions even when no visible instance survives at its original slot.
        val exceptions=query(CalendarContract.Events.CONTENT_URI,eventColumns,
            "calendar_id=? AND deleted=0 AND (original_id IS NOT NULL OR original_sync_id IS NOT NULL) AND ((originalInstanceTime>=? AND originalInstanceTime<?) OR (dtstart<? AND coalesce(dtend,dtstart)>=?))",
            arrayOf(calendar.id,range.begin.toString(),range.end.toString(),range.end.toString(),range.begin.toString()),300)
        ids.addAll(exceptions.map { it.getValue("_id")!! })
        ids.addAll(exceptions.mapNotNull { it["original_id"] })
        for(exception in exceptions.filter { it["original_id"].isNullOrBlank() && !it["original_sync_id"].isNullOrBlank() }) {
            val parent=query(CalendarContract.Events.CONTENT_URI,arrayOf("_id"),"calendar_id=? AND _sync_id=? AND deleted=0",arrayOf(calendar.id,exception.getValue("original_sync_id")!!),1).singleOrNull() ?: throw CalendarFailure("source_changed")
            ids+=parent.getValue("_id")!!
        }
        calendarCheck(ids.size<=300,"source_limit")
        var totalBytes=0L
        val events=ids.sorted().mapNotNull { id ->
            val fields=query(CalendarContract.Events.CONTENT_URI,eventColumns,"_id=? AND calendar_id=? AND deleted=0",arrayOf(id,calendar.id),1).singleOrNull()
                ?: throw CalendarFailure("source_changed")
            val reminders=query(CalendarContract.Reminders.CONTENT_URI,arrayOf("_id","event_id","minutes","method"),"event_id=?",arrayOf(id),100).sortedBy { it["_id"] }
            CalendarEvent(fresh,fields,reminders).also {
                val bytes=it.payload.toByteArray(Charsets.UTF_8).size
                calendarCheck(bytes<=CalendarEvent.MAX_PAYLOAD_BYTES,"event_limit")
                totalBytes+=bytes;calendarCheck(totalBytes<=4*1024*1024,"source_limit")
            }
        }
        permission();return CalendarSnapshot(fresh,range,events)
    }
}
