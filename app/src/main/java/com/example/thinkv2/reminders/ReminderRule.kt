package com.example.thinkv2.reminders

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

private val UTC: TimeZone get() = TimeZone.getTimeZone("UTC")

data class LocalDay(val year: Int,val month: Int,val day: Int) : Comparable<LocalDay> {
    fun epoch(): Long = GregorianCalendar(UTC,Locale.ROOT).apply {
        clear();isLenient=false;set(year,month-1,day)
    }.timeInMillis
    fun plus(days: Int): LocalDay = from(epoch()+days*86_400_000L,UTC)
    val weekday: Int get() = ((GregorianCalendar(UTC).apply { timeInMillis=epoch() }.get(Calendar.DAY_OF_WEEK)+5)%7)+1
    override fun toString() = String.format(Locale.ROOT,"%04d-%02d-%02d",year,month,day)
    override fun compareTo(other: LocalDay) = epoch().compareTo(other.epoch())
    companion object {
        fun parse(text: String): LocalDay {
            require(text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            val parts=text.split('-').map { it.toInt() }
            val result=LocalDay(parts[0],parts[1],parts[2]);require(result.year in 1970..9999);result.epoch();return result
        }
        fun from(millis: Long,zone: TimeZone): LocalDay {
            val c=GregorianCalendar(zone,Locale.ROOT).apply { timeInMillis=millis }
            return LocalDay(c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH))
        }
    }
}

enum class Repeat { ONCE, DAILY, WEEKLY }
data class ReminderRule(val repeat: Repeat,val start: LocalDay,val hour: Int,val minute: Int,val weekdays: Int=0) {
    fun validate() {
        start.epoch();require(start.year in 1970..9999 && hour in 0..23 && minute in 0..59)
        require(weekdays in 0..127 && (repeat!=Repeat.WEEKLY || weekdays!=0))
    }
    fun selected(day: LocalDay) = day>=start && when(repeat) {
        Repeat.ONCE -> day==start
        Repeat.DAILY -> true
        Repeat.WEEKLY -> weekdays and (1 shl (day.weekday-1)) != 0
    }
    fun occurrence(day: LocalDay,zone: TimeZone): Occurrence {
        val wall=day.epoch()+hour*3_600_000L+minute*60_000L
        // Candidate UTC offsets around the day cover both sides of a DST transition.
        val offsets=(-48..48).map { zone.getOffset(wall+it*3_600_000L) }.distinct()
        fun candidates(local: Long) = offsets.map { local-it }.filter { instant -> instant+zone.getOffset(instant)==local }
        var matches=candidates(wall)
        if(matches.isEmpty()) {
            // A gap goes to the first valid local minute, not to e.g. 03:30 for a missing 02:30.
            for(delta in 1..2880) {
                matches=candidates(wall+delta*60_000L)
                if(matches.isNotEmpty()) break
            }
        }
        check(matches.isNotEmpty()) { "unresolvable_local_time" }
        return Occurrence("${day}T${String.format(Locale.ROOT,"%02d:%02d",hour,minute)}",matches.minOrNull()!!)
    }
    fun nextAfter(now: Long,zone: TimeZone,lastHandled: String=""): Occurrence? {
        validate()
        if(repeat==Repeat.ONCE) return occurrence(start,zone).takeIf { it.at>now && it.key>lastHandled }
        val today=LocalDay.from(now,zone)
        val lastDay=if(lastHandled.isEmpty()) start else LocalDay.parse(lastHandled.take(10))
        val first=maxOf(start,today,lastDay)
        for(delta in 0..14) {
            val day=first.plus(delta)
            if(selected(day)) {
                val next=occurrence(day,zone)
                if(next.at>now && next.key>lastHandled) return next
            }
        }
        return null
    }
    fun latestDue(now: Long,zone: TimeZone): Occurrence? {
        validate()
        if(repeat==Repeat.ONCE) return occurrence(start,zone).takeIf { it.at<=now }
        val today=LocalDay.from(now,zone)
        for(delta in 0..8) {
            val day=today.plus(-delta)
            if(selected(day)) {
                val occurrence=occurrence(day,zone)
                if(occurrence.at<=now) return occurrence
            }
        }
        return null
    }
}
data class Occurrence(val key: String,val at: Long)
data class ReminderPlan(val id: String,val noteId: String,val revision: Long,val rule: ReminderRule,
    val requested: Boolean,val status: String,val nextKey: String="",val nextAt: Long=0,val lastKey: String="",val problem: String="")
data class ReminderEvent(val reminderId: String,val revision: Long,val key: String,val at: Long,val checkedAt: Long,val outcome: String,val reported: Int,val reason: String="")
