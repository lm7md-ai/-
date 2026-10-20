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
    if (stopped) {
        if (precise) 15_000 else 30_000
    } else if (precise) 3_000 else 5_000

data class RouteComparison(
    val representative: Long,
    val trips: Int,
    val averageMeters: Double,
    val averageMs: Long,
    val origin: Pair<Double, Double>,
    val destination: Pair<Double, Double>,
    val fastestMs: Long = 0,
    val slowestMs: Long = 0,
    val averageStoppedMs: Long = 0,
    val averageStops: Double = 0.0,
    val bestHour: HourSummary? = null,
)

/** Compact arc-length samples: analysis does not retain every point of every historical trip. */
data class RouteSample(
    val trip: Outing,
    val shape: List<Pair<Double, Double>>,
    val length: Double,
    val stops: Int,
)

fun routeSample(trip: Outing, points: List<RoutePoint>, stops: Int = 0): RouteSample? {
    if (
        trip.endedAt == null ||
            trip.endedAt <= trip.startedAt ||
            trip.state.gapMs > 0 ||
            points.size < 3
    )
        return null
    if (
        points.any { it.breakBefore || it.uncertainGap } ||
            points.zipWithNext().any { (a, b) ->
                b.recordedAt <= a.recordedAt || b.recordedAt - a.recordedAt > 90000
            }
    )
        return null
    val lengths = DoubleArray(points.size)
    for (i in 1 until points.size) lengths[i] =
        lengths[i - 1] +
            distance(
                points[i - 1].latitude,
                points[i - 1].longitude,
                points[i].latitude,
                points[i].longitude,
            )
    val total = lengths.last()
    if (total < 100) return null
    var edge = 1
    val shape =
        (0..23).map { index ->
            val target = total * index / 23
            while (edge < points.lastIndex && lengths[edge] < target) edge++
            val a = points[edge - 1]
            val b = points[edge]
            val fraction =
                ((target - lengths[edge - 1]) /
                        (lengths[edge] - lengths[edge - 1]).coerceAtLeast(.0001))
                    .coerceIn(0.0, 1.0)
            (a.latitude + (b.latitude - a.latitude) * fraction) to
                (a.longitude + (b.longitude - a.longitude) * fraction)
        }
    return RouteSample(trip, shape, total, stops)
}

fun compareRouteSamples(samples: List<RouteSample>): List<RouteComparison> {
    fun gap(a: Pair<Double, Double>, b: Pair<Double, Double>) =
        distance(a.first, a.second, b.first, b.second)
    fun same(a: RouteSample, b: RouteSample): Boolean {
        if (
            gap(a.shape.first(), b.shape.first()) > 180 ||
                gap(a.shape.last(), b.shape.last()) > 180 ||
                kotlin.math.abs(a.length - b.length) / kotlin.math.max(a.length, b.length) > .3
        )
            return false
        val offsets = a.shape.zip(b.shape).map { (p, q) -> gap(p, q) }
        return offsets.average() <= 60 && offsets.max() <= 150
    }
    val groups = ArrayList<MutableList<RouteSample>>()
    samples
        .sortedByDescending { it.trip.startedAt }
        .forEach { sample ->
            val group = groups.firstOrNull { same(it.first(), sample) }
            if (group == null) groups.add(mutableListOf(sample)) else group.add(sample)
        }
    return groups
        .map { group ->
            val sample = group.first()
            val elapsed = group.map { it.trip.endedAt!! - it.trip.startedAt }
            val hour =
                group
                    .groupBy {
                        java.time.Instant.ofEpochMilli(it.trip.startedAt).atZone(SaudiZone).hour
                    }
                    .filterValues { it.size >= 3 }
                    .map { (hour, values) ->
                        HourSummary(
                            hour,
                            values.size,
                            values.map { it.trip.endedAt!! - it.trip.startedAt }.average().toLong(),
                        )
                    }
                    .minByOrNull { it.averageDrivingMs }
            RouteComparison(
                sample.trip.id,
                group.size,
                group.map { it.trip.state.meters }.average(),
                elapsed.average().toLong(),
                sample.shape.first(),
                sample.shape.last(),
                elapsed.min(),
                elapsed.max(),
                group.map { it.trip.state.stoppedMs }.average().toLong(),
                group.map { it.stops }.average(),
                hour,
            )
        }
        .sortedByDescending { it.trips }
}

fun routeComparisons(records: List<Pair<Outing, List<RoutePoint>>>): List<RouteComparison> =
    compareRouteSamples(records.mapNotNull { (trip, points) -> routeSample(trip, points) })
