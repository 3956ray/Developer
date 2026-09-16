package com.example.thinkv2.calendar

import com.example.thinkv2.backup.StrictJson
import com.example.thinkv2.reminders.LocalDay
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

class CalendarFailure(val code: String): Exception(code)
internal fun calendarCheck(ok: Boolean,code: String) { if(!ok) throw CalendarFailure(code) }
internal fun canonical(value: Any?): String=StrictJson.encode(value).toString(Charsets.UTF_8)
internal fun calendarHash(value: Any?): String=MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }

data class CalendarRef(val fields: Map<String,String?>) {
    val id get()=fields.getValue("_id")!!
    val label get()=fields["calendar_displayName"].orEmpty().ifBlank { fields["name"].orEmpty().ifBlank { "未命名日历" } }
    val identity get()=listOf("com.android.calendar",fields["account_name"],fields["account_type"],id)
}
data class CalendarRange(val first: String,val last: String,val zone: String) {
    init {
        val a=LocalDay.parse(first);val b=LocalDay.parse(last)
        calendarCheck(b>=a && b.epoch()-a.epoch()<366L*86400000,"range_limit")
        calendarCheck(zone in TimeZone.getAvailableIDs(),"range_invalid")
    }
    private fun midnight(day: LocalDay)=GregorianCalendar(TimeZone.getTimeZone(zone),Locale.ROOT).apply { clear();isLenient=false;set(day.year,day.month-1,day.day) }.timeInMillis
    val begin get()=midnight(LocalDay.parse(first))
    val end get()=midnight(LocalDay.parse(last).plus(1))
}
data class CalendarEvent(val calendar: CalendarRef,val fields: Map<String,String?>,val reminders: List<Map<String,String?>>) {
    companion object { const val MAX_PAYLOAD_BYTES=128*1024 }
    val id get()=fields.getValue("_id")!!
    val key get()=calendarHash(calendar.identity+id)
    val title get()=fields["title"].orEmpty()
    val description get()=fields["description"].orEmpty()
    val exception get()=!fields["original_id"].isNullOrBlank() || !fields["original_sync_id"].isNullOrBlank()
    val repeating get()=listOf("rrule","rdate","exrule","exdate").any { !fields[it].isNullOrBlank() }
    val unconverted get()=exception || repeating || reminders.isNotEmpty() || fields["hasAlarm"]=="1"
    val importable get()=title.isNotBlank() || description.isNotBlank()
    val payload get()=canonical(linkedMapOf("calendar" to calendar.fields.toSortedMap(),"event" to fields.toSortedMap(),"reminders" to reminders.map { it.toSortedMap() }))
    val fingerprint get()=calendarHash(payload)
    val kind get()=if(exception) "系列例外（状态原样保留）" else if(repeating) "重复主事件（仅一条）" else "单次事件"
    fun originalText(includePayload: Boolean=true): String {
        fun time(key: String): String {
            val raw=fields[key] ?: return "未提供"
            val at=raw.toLongOrNull() ?: return "原始值：$raw"
            val zone=if(fields["allDay"]=="1") "UTC" else (if(key=="dtend") fields["eventEndTimezone"].takeUnless { it.isNullOrBlank() } ?: fields["eventTimezone"] else fields["eventTimezone"]).orEmpty()
            if(zone !in TimeZone.getAvailableIDs()) return "$raw（时区未知：$zone，未转换）"
            val format=SimpleDateFormat(if(fields["allDay"]=="1") "yyyy-MM-dd" else "yyyy-MM-dd HH:mm:ss",Locale.ROOT).apply { timeZone=TimeZone.getTimeZone(zone) }
            return "${format.format(Date(at))} [$zone]（原始毫秒 $raw）"
        }
        return buildString {
            append("日历原始标题：");append(title);append("\n日历原始描述：\n");append(description)
            append("\n\n来源日历：${calendar.label}\n事件ID：$id\n类型：$kind\n")
            append("开始：${time("dtstart")}\n结束：${time("dtend")}\n全天：${fields["allDay"]=="1"}（全天结束日期为不含当天）\n")
            if(unconverted) append("重复、例外或源提醒元数据未转换为本机提醒；本机提醒未开启。\n")
            if(includePayload) append("\n源字段原样快照（null表示未提供）：\n$payload")
        }
    }
    fun noteBody()=originalText(includePayload=false)

}
data class CalendarSnapshot(val calendar: CalendarRef,val range: CalendarRange,val events: List<CalendarEvent>) {
    val token get()=calendarHash(listOf(calendar.fields.toSortedMap(),range.first,range.last,range.zone,events.sortedBy { it.key }.map { it.payload }))
}
interface CalendarSource {
    fun permitted(): Boolean
    fun calendars(): List<CalendarRef>
    fun read(calendar: CalendarRef,range: CalendarRange): CalendarSnapshot
}
enum class CalendarAction { NEW, SAME, CHANGED, SKIPPED }
data class CalendarRow(val event: CalendarEvent,val action: CalendarAction,val localChanged: Boolean=false)
data class CalendarPreview(val source: CalendarSnapshot,val localToken: String,val rows: List<CalendarRow>)
data class CalendarApplied(val noteIds: List<String>,val alreadyImported: Int)
