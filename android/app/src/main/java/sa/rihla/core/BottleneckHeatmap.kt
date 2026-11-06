package sa.rihla.core

import kotlin.math.floor
import kotlin.math.roundToInt
import sa.rihla.data.*

data class BottleneckCell(
    val gridRow: Int,
    val gridCol: Int,
    val centerLat: Double,
    val centerLon: Double,
    val outingCount: Int,
    val avgSlowSpeedKmh: Double,
    val avgDelaySeconds: Int,
) {
    val isSevere: Boolean
        get() = outingCount >= 5 || avgSlowSpeedKmh < 10.0
}

data class GeoMerchantPin(
    val key: String,
    val displayName: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val placeLabel: String,
    val visitCount: Int,
    val totalHalala: Long,
    val lastVisitedAt: Long,
)

object BottleneckAnalyzer {
    // ~50m grid step near Jeddah/Makkah latitudes (21.3° - 21.6° N)
    private const val LAT_STEP = 0.00045
    private const val LON_STEP = 0.00048
    private const val SLOW_SPEED_MPS = 20.0 / 3.6 // < 20 km/h
    private const val FREE_FLOW_MPS = 55.0 / 3.6 // reference free-flow 55 km/h for delay estimate

    fun computeBottlenecks(
        outings: List<Outing>,
        pointsByOuting: Map<Long, List<RoutePoint>>,
        eventsByOuting: Map<Long, List<JourneyEvent>>,
        places: List<SavedPlace>,
        minRecurringOutings: Int = 3,
        maxRecentOutings: Int = 30,
    ): List<BottleneckCell> {
        val recent = outings.sortedByDescending { it.startedAt }.take(maxRecentOutings)
        if (recent.size < minRecurringOutings) return emptyList()

        val exclusionZones =
            places
                .filter { it.type == "HOME" || it.type == "UNIVERSITY" || it.type.startsWith("TEMP_HOME") }
                .map { Area(it.latitude, it.longitude, it.radius.coerceAtLeast(120.0), parsePolygon(it.polygon)) }

        class CellAccumulator {
            val outingIds = HashSet<Long>()
            var latSum = 0.0
            var lonSum = 0.0
            var speedMpsSum = 0.0
            var sampleCount = 0
            val delayPerOutingMs = HashMap<Long, Long>()
        }

        val grid = HashMap<Pair<Int, Int>, CellAccumulator>()
        for (outing in recent) {
            val pts = pointsByOuting[outing.id].orEmpty()
            if (pts.size < 2) continue
            val stops =
                eventsByOuting[outing.id]
                    .orEmpty()
                    .filter { it.type == "STOP" }
            for (i in pts.indices) {
                val p = pts[i]
                // Must be moving slowly (< 20 km/h) but not completely parked (> 0.6 m/s ~ 2.2 km/h)
                if (!p.speed.isFinite() || p.speed !in 0.6..SLOW_SPEED_MPS || p.accuracy > 35.0) continue
                // Exclude points inside HOME / UNIVERSITY or during confirmed STOP events
                if (exclusionZones.any { it.containsPoint(p.latitude, p.longitude) }) continue
                if (stops.any { p.recordedAt in it.startedAt..it.endedAt || distance(it.latitude, it.longitude, p.latitude, p.longitude) <= 55.0 }) {
                    continue
                }
                val row = floor(p.latitude / LAT_STEP).toInt()
                val col = floor(p.longitude / LON_STEP).toInt()
                val acc = grid.getOrPut(row to col) { CellAccumulator() }
                acc.outingIds += outing.id
                acc.latSum += p.latitude
                acc.lonSum += p.longitude
                acc.speedMpsSum += p.speed
                acc.sampleCount++
                if (i > 0) {
                    val prev = pts[i - 1]
                    val dtMs = (p.recordedAt - prev.recordedAt).coerceIn(0L, 15_000L)
                    val freeFlowMs = ((p.distanceDelta / FREE_FLOW_MPS) * 1000.0).toLong()
                    val extraMs = (dtMs - freeFlowMs).coerceAtLeast(500L)
                    acc.delayPerOutingMs[outing.id] = (acc.delayPerOutingMs[outing.id] ?: 0L) + extraMs
                }
            }
        }

        return grid.entries
            .filter { it.value.outingIds.size >= minRecurringOutings && it.value.sampleCount >= minRecurringOutings }
            .map { (key, acc) ->
                val avgLat = acc.latSum / acc.sampleCount
                val avgLon = acc.lonSum / acc.sampleCount
                val avgKmh = (acc.speedMpsSum / acc.sampleCount) * 3.6
                val avgDelaySec =
                    if (acc.delayPerOutingMs.isEmpty()) 25
                    else (acc.delayPerOutingMs.values.average() / 1000.0).roundToInt().coerceAtLeast(10)
                BottleneckCell(
                    gridRow = key.first,
                    gridCol = key.second,
                    centerLat = avgLat,
                    centerLon = avgLon,
                    outingCount = acc.outingIds.size,
                    avgSlowSpeedKmh = avgKmh,
                    avgDelaySeconds = avgDelaySec,
                )
            }
            .sortedByDescending { it.outingCount * 100 + it.avgDelaySeconds }
    }

    fun aggregateGeoMerchants(
        expenses: List<Expense>,
        rules: List<MerchantRule>,
    ): List<GeoMerchantPin> {
        data class Bucket(
            var displayName: String,
            var category: String,
            var latSum: Double = 0.0,
            var lonSum: Double = 0.0,
            var geoCount: Int = 0,
            var placeLabel: String = "",
            var visits: Int = 0,
            var totalHalala: Long = 0L,
            var lastAt: Long = 0L,
        )

        val buckets = LinkedHashMap<String, Bucket>()
        for (exp in expenses) {
            val rawMerchant = exp.merchant.trim()
            val matchedRule =
                if (rawMerchant.isNotBlank())
                    rules.firstOrNull { it.pattern.isNotBlank() && rawMerchant.contains(it.pattern, ignoreCase = true) }
                else null
            val display =
                matchedRule?.customName?.takeIf { it.isNotBlank() }
                    ?: rawMerchant.ifBlank {
                        when (exp.category) {
                            "FUEL" -> "محطة وقود"
                            "FOOD" -> "مطعم / كافيه"
                            else -> "متجر"
                        }
                    }
            val lat = exp.latitude ?: matchedRule?.latitude
            val lon = exp.longitude ?: matchedRule?.longitude
            if (lat == null || lon == null) continue
            val key =
                (matchedRule?.pattern?.uppercase() ?: rawMerchant.uppercase().ifBlank { "${(lat * 200).toInt()}:${(lon * 200).toInt()}" })
            val b = buckets.getOrPut(key) { Bucket(display, exp.category) }
            if (matchedRule?.customName?.isNotBlank() == true) b.displayName = matchedRule.customName
            b.latSum += lat
            b.lonSum += lon
            b.geoCount++
            if (b.placeLabel.isBlank() && exp.placeLabel.isNotBlank()) b.placeLabel = exp.placeLabel
            b.visits++
            b.totalHalala += exp.amountHalala
            if (exp.createdAt > b.lastAt) b.lastAt = exp.createdAt
        }
        return buckets.entries
            .filter { it.value.geoCount > 0 }
            .map { (k, b) ->
                GeoMerchantPin(
                    key = k,
                    displayName = b.displayName,
                    category = b.category,
                    latitude = b.latSum / b.geoCount,
                    longitude = b.lonSum / b.geoCount,
                    placeLabel = b.placeLabel,
                    visitCount = b.visits,
                    totalHalala = b.totalHalala,
                    lastVisitedAt = b.lastAt,
                )
            }
            .sortedByDescending { it.visitCount }
    }
}
