package sa.rihla.core

import java.time.LocalDate
import java.time.YearMonth
import sa.rihla.data.*

fun merchantCategory(merchant: String, rules: List<MerchantRule>): String {
    val value = normalizeSms(merchant).lowercase().trim()
    return rules
        .sortedWith(compareByDescending<MerchantRule> { it.pattern.length }.thenBy { it.id })
        .firstOrNull {
            it.pattern.isNotBlank() && value.contains(normalizeSms(it.pattern).lowercase().trim())
        }
        ?.category ?: "OTHER"
}

fun recurringDate(value: RecurringExpense, month: YearMonth): LocalDate =
    month.atDay(value.dayOfMonth.coerceIn(1, month.lengthOfMonth()))

fun recurringKey(id: Long, month: YearMonth) = "recurring:$id:$month"

data class DestinationSummary(
    val name: String,
    val count: Int,
    val meters: Double,
    val drivingMs: Long,
)

fun destinationSummaries(
    outings: List<Outing>,
    details: List<TripDetails>,
    places: List<SavedPlace>,
): List<DestinationSummary> {
    val titles = details.associateBy { it.outingId }
    return outings
        .filter { it.endedAt != null && it.state.lastLat != null && it.state.lastLon != null }
        .groupBy { outing ->
            val s = outing.state
            places
                .firstOrNull {
                    distance(s.lastLat!!, s.lastLon!!, it.latitude, it.longitude) <= it.radius
                }
                ?.type
                ?: titles[outing.id]?.title?.takeIf { it.isNotBlank() }
                ?: "${"%.2f".format(java.util.Locale.US, s.lastLat)}, ${"%.2f".format(java.util.Locale.US, s.lastLon)}"
        }
        .map { (name, trips) ->
            DestinationSummary(
                name,
                trips.size,
                trips.sumOf { it.state.meters },
                trips.sumOf { it.state.drivingMs },
            )
        }
        .sortedByDescending { it.count }
}

data class HourSummary(val hour: Int, val trips: Int, val averageDrivingMs: Long)

fun departureHours(outings: List<Outing>): List<HourSummary> =
    outings
        .filter { it.endedAt != null && it.state.drivingMs > 0 }
        .groupBy { java.time.Instant.ofEpochMilli(it.startedAt).atZone(SaudiZone).hour }
        .map { (hour, trips) ->
            HourSummary(hour, trips.size, trips.map { it.state.drivingMs }.average().toLong())
        }
        .sortedBy { it.hour }

/** Reject stale provider fixes before they enter the persisted engine. */
fun usableLocation(ageMillis: Long, accuracy: Double, mock: Boolean): Boolean =
    !mock && ageMillis in 0..60_000 && accuracy.isFinite() && accuracy in 0.1..60.0

fun trackingInterval(stopped: Boolean, precise: Boolean): Long =
    if (stopped) { if (precise) 15_000 else 30_000 } else if (precise) 3_000 else 5_000

data class RouteComparison(
    val representative: Long,
    val trips: Int,
    val averageMeters: Double,
    val averageMs: Long,
    val origin: Pair<Double, Double>,
    val destination: Pair<Double, Double>,
)

fun routeComparisons(records: List<Pair<Outing, List<RoutePoint>>>): List<RouteComparison> {
    fun cell(point: RoutePoint, scale: Double) =
        "${kotlin.math.round(point.latitude * scale)},${kotlin.math.round(point.longitude * scale)}"
    return records
        .filter { (trip, points) ->
            trip.endedAt != null && points.size >= 3 && trip.state.gapMs == 0L
        }
        .groupBy { (_, points) ->
            val corridor =
                points
                    .map { cell(it, 100.0) }
                    .fold(mutableListOf<String>()) { cells, cell ->
                        cells.apply { if (lastOrNull() != cell) add(cell) }
                    }
            cell(points.first(), 500.0) +
                ":" +
                cell(points.last(), 500.0) +
                ":" +
                corridor.joinToString(";")
        }
        .map { (_, group) ->
            val (trip, points) = group.first()
            RouteComparison(
                trip.id,
                group.size,
                group.map { it.first.state.meters }.average(),
                group.map { it.first.endedAt!! - it.first.startedAt }.average().toLong(),
                points.first().latitude to points.first().longitude,
                points.last().latitude to points.last().longitude,
            )
        }
        .sortedByDescending { it.trips }
}
