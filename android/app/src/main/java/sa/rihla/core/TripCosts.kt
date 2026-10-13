package sa.rihla.core

import java.math.BigDecimal
import java.math.RoundingMode

data class DurationParts(val hours: Long, val minutes: Long, val seconds: Long)

fun screenRefreshDelay(now: Long, active: Boolean): Long {
    if (active) return 1000
    val midnight =
        java.time.Instant.ofEpochMilli(now)
            .atZone(SaudiZone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(SaudiZone)
            .toInstant()
            .toEpochMilli()
    return (midnight - now).coerceAtLeast(1)
}

fun durationParts(milliseconds: Long): DurationParts {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return DurationParts(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

data class ConsumptionProfile(val minMlPer100Km: Long, val maxMlPer100Km: Long, val source: String)

data class EstimatedFuel(
    val minLiters: Double,
    val maxLiters: Double,
    val minHalala: Long,
    val maxHalala: Long,
)

/** Published reference figures are estimates, never personal measured consumption. */
fun consumptionProfile(
    engine: String,
    customMlPer100Km: Long,
    measured: Economy?,
): ConsumptionProfile {
    if (measured != null && measured.litersPer100Km.isFinite() && measured.litersPer100Km > 0) {
        val ml = (measured.litersPer100Km * 1000).toLong().coerceAtLeast(1)
        return ConsumptionProfile(ml, ml, "MEASURED")
    }
    if (engine == "CUSTOM" && customMlPer100Km in 1000..30000)
        return ConsumptionProfile(customMlPer100Km, customMlPer100Km, "CUSTOM")
    return when (engine) {
        // No verified combined rating for this user's 4A92 + 4-speed automatic.
        // Explicit provisional input, replaced by reliable personal fill-up measurements.
        "LANCER_16_AT" ->
            ConsumptionProfile(
                customMlPer100Km.coerceIn(1000, 30000),
                customMlPer100Km.coerceIn(1000, 30000),
                "PROVISIONAL",
            )
        // UNKNOWN includes the published 1.6 manual and 2.0 CVT variants, not an asserted engine.
        "LANCER_16_MANUAL" -> ConsumptionProfile(5500, 5500, "REFERENCE")
        "LANCER_20_CVT" -> ConsumptionProfile(7800, 7800, "REFERENCE")
        else -> ConsumptionProfile(5500, 7800, "UNKNOWN")
    }
}

fun estimateFuel(
    meters: Double,
    profile: ConsumptionProfile,
    priceHalalaPerLiter: Long,
): EstimatedFuel? {
    if (
        !meters.isFinite() ||
            meters < 0 ||
            priceHalalaPerLiter <= 0 ||
            profile.minMlPer100Km <= 0 ||
            profile.maxMlPer100Km < profile.minMlPer100Km
    )
        return null
    fun liters(ml: Long) =
        BigDecimal.valueOf(meters)
            .multiply(BigDecimal.valueOf(ml))
            .divide(BigDecimal(100_000_000), 12, RoundingMode.HALF_UP)
    fun cost(l: BigDecimal) =
        l.multiply(BigDecimal.valueOf(priceHalalaPerLiter))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    return try {
        val low = liters(profile.minMlPer100Km)
        val high = liters(profile.maxMlPer100Km)
        EstimatedFuel(low.toDouble(), high.toDouble(), cost(low), cost(high))
    } catch (_: ArithmeticException) {
        null
    }
}

const val SaudiFuelPriceCheckedAt = "2026-10-13"
const val SaudiFuelPriceSource = "https://www.aramco.com/en/what-we-do/energy-products/retail-fuels"

fun saudiPrice(grade: String): Long = if (grade == "95") 233 else 218
