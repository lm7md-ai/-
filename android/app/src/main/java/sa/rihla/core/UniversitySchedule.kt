package sa.rihla.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import sa.rihla.data.Outing
import sa.rihla.data.UniversityScheduleEntry

data class DepartureAdvice(
    val entry: UniversityScheduleEntry,
    val isToday: Boolean,
    val dayLabelArabic: String,
    val estimatedTravelMinutes: Int,
    val bufferMinutes: Int,
    val recommendedDepartureMinuteOfDay: Int,
    val minutesUntilDeparture: Int?, // non-null if today
) {
    val lectureStartFormatted: String
        get() = formatMinuteOfDayArabic(entry.startMinuteOfDay)

    val recommendedDepartureFormatted: String
        get() = formatMinuteOfDayArabic(recommendedDepartureMinuteOfDay)
}

/**
 * UQU Schedule conventions: Sunday = 1, Monday = 2, Tuesday = 3, Wednesday = 4, Thursday = 5
 * (Friday = 6, Saturday = 7).
 */
fun javaDayToUquDay(dow: DayOfWeek): Int =
    when (dow) {
        DayOfWeek.SUNDAY -> 1
        DayOfWeek.MONDAY -> 2
        DayOfWeek.TUESDAY -> 3
        DayOfWeek.WEDNESDAY -> 4
        DayOfWeek.THURSDAY -> 5
        DayOfWeek.FRIDAY -> 6
        DayOfWeek.SATURDAY -> 7
    }

fun uquDayArabicName(uquDay: Int): String =
    when (uquDay) {
        1 -> "الأحد"
        2 -> "الاثنين"
        3 -> "الثلاثاء"
        4 -> "الأربعاء"
        5 -> "الخميس"
        6 -> "الجمعة"
        else -> "السبت"
    }

fun formatMinuteOfDayArabic(minuteOfDay: Int): String {
    val normalized = ((minuteOfDay % 1440) + 1440) % 1440
    val hour24 = normalized / 60
    val min = normalized % 60
    val period = if (hour24 < 12) "ص" else "م"
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    return "%02d:%02d %s".format(java.util.Locale.US, hour12, min, period)
}

object UniversityDepartureAdvisor {
    const val DEFAULT_TRAVEL_MINUTES = 25
    const val SAFETY_BUFFER_MINUTES = 10

    fun averageWeekdayCommuteMinutes(outings: List<Outing>): Int {
        val validMs =
            outings
                .filter { outing ->
                    val dow = Instant.ofEpochMilli(outing.startedAt).atZone(SaudiZone).dayOfWeek
                    javaDayToUquDay(dow) in 1..5 &&
                        outing.state.homeToUniversityMs in (5 * 60_000L)..(120 * 60_000L)
                }
                .takeLast(20)
                .map { it.state.homeToUniversityMs }
        if (validMs.isEmpty()) return DEFAULT_TRAVEL_MINUTES
        return (validMs.average() / 60_000.0).roundToInt().coerceIn(8, 120)
    }

    fun nextLectureAdvice(
        schedule: List<UniversityScheduleEntry>,
        outings: List<Outing>,
        nowMs: Long = System.currentTimeMillis(),
    ): DepartureAdvice? {
        val enabled = schedule.filter { it.enabled }
        if (enabled.isEmpty()) return null
        val zdt: ZonedDateTime = Instant.ofEpochMilli(nowMs).atZone(SaudiZone)
        val todayUqu = javaDayToUquDay(zdt.dayOfWeek)
        val nowMin = zdt.hour * 60 + zdt.minute
        val travelMin = averageWeekdayCommuteMinutes(outings)
        val totalLead = travelMin + SAFETY_BUFFER_MINUTES

        // 1. Remaining or currently starting lecture today
        val todayNext =
            enabled
                .filter { it.dayOfWeek == todayUqu && it.endMinuteOfDay > nowMin }
                .minByOrNull { it.startMinuteOfDay }
        if (todayNext != null) {
            val depMin = (todayNext.startMinuteOfDay - totalLead).coerceAtLeast(0)
            return DepartureAdvice(
                entry = todayNext,
                isToday = true,
                dayLabelArabic = "اليوم (${uquDayArabicName(todayUqu)})",
                estimatedTravelMinutes = travelMin,
                bufferMinutes = SAFETY_BUFFER_MINUTES,
                recommendedDepartureMinuteOfDay = depMin,
                minutesUntilDeparture = depMin - nowMin,
            )
        }

        // 2. Next upcoming study day within the next 7 days
        for (offset in 1..7) {
            val candidateDay = ((todayUqu - 1 + offset) % 7) + 1
            val firstOnDay =
                enabled
                    .filter { it.dayOfWeek == candidateDay }
                    .minByOrNull { it.startMinuteOfDay }
            if (firstOnDay != null) {
                val depMin = (firstOnDay.startMinuteOfDay - totalLead).coerceAtLeast(0)
                val label =
                    if (offset == 1) "غداً (${uquDayArabicName(candidateDay)})"
                    else uquDayArabicName(candidateDay)
                return DepartureAdvice(
                    entry = firstOnDay,
                    isToday = false,
                    dayLabelArabic = label,
                    estimatedTravelMinutes = travelMin,
                    bufferMinutes = SAFETY_BUFFER_MINUTES,
                    recommendedDepartureMinuteOfDay = depMin,
                    minutesUntilDeparture = null,
                )
            }
        }
        return null
    }
}
