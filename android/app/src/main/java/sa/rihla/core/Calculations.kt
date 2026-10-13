package sa.rihla.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

data class Fill(
    val time: Long,
    val amountHalala: Long,
    val litersMl: Long,
    val full: Boolean,
    val trackedMeters: Double,
    val reliable: Boolean = true,
    val odometerMeters: Double? = null,
)

data class Economy(val kmPerLiter: Double, val litersPer100Km: Double, val sarPerKm: Double)

fun fuelEconomy(fills: List<Fill>): Economy? {
    val ordered = fills.sortedBy { it.time }
    var anchor: Fill? = null
    var liters = 0L
    var cost = 0L
    var km = 0.0
    var used = 0L
    var paid = 0L
    var valid = true
    for (f in ordered) {
        if (anchor != null) {
            liters += f.litersMl
            cost += f.amountHalala
            valid = valid && f.reliable
        }
        if (f.full) {
            val odometer = f.odometerMeters != null && anchor?.odometerMeters != null
            val meters =
                if (odometer) f.odometerMeters!! - anchor!!.odometerMeters!!
                else f.trackedMeters - (anchor?.trackedMeters ?: f.trackedMeters)
            if (anchor != null && (valid || odometer) && meters >= 1000 && liters > 0) {
                km += meters / 1000
                used += liters
                paid += cost
            }
            anchor = f
            liters = 0
            cost = 0
            valid = f.reliable
        }
    }
    if (used <= 0 || km <= 0) return null
    return Economy(km / (used / 1000.0), (used / 1000.0) / km * 100, (paid / 100.0) / km)
}

fun decimalUnits(text: String, scale: Int): Long? =
    try {
        BigDecimal(
                text
                    .replace('٫', '.')
                    .map { if (it in '٠'..'٩') ('0'.code + it.code - '٠'.code).toChar() else it }
                    .joinToString("")
            )
            .setScale(scale, RoundingMode.HALF_UP)
            .movePointRight(scale)
            .longValueExact()
            .takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

fun calculateLiters(amount: Long, price: Long): Long =
    BigDecimal(amount)
        .divide(BigDecimal(price), 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal(1000))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()

enum class Period {
    WEEK,
    MONTH,
    YEAR,
    ALL,
}

val SaudiZone: ZoneId = ZoneId.of("Asia/Riyadh")

fun periodBounds(
    p: Period,
    now: ZonedDateTime = ZonedDateTime.now(SaudiZone),
    previous: Boolean = false,
): Pair<Long, Long> {
    if (p == Period.ALL) return 0L to Long.MAX_VALUE
    var start = now.toLocalDate()
    start =
        when (p) {
            Period.WEEK -> start.minusDays((start.dayOfWeek.value % 7).toLong())
            Period.MONTH -> start.withDayOfMonth(1)
            Period.YEAR -> start.withDayOfYear(1)
        }
    if (previous)
        start =
            when (p) {
                Period.WEEK -> start.minusWeeks(1)
                Period.MONTH -> start.minusMonths(1)
                Period.YEAR -> start.minusYears(1)
            }
    val end =
        when (p) {
            Period.WEEK -> start.plusWeeks(1)
            Period.MONTH -> start.plusMonths(1)
            Period.YEAR -> start.plusYears(1)
        }
    return start.atStartOfDay(SaudiZone).toInstant().toEpochMilli() to
        end.atStartOfDay(SaudiZone).toInstant().toEpochMilli()
}
