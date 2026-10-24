package sa.rihla.map

import kotlin.math.*
import sa.rihla.core.*
import sa.rihla.data.*

/** Read-only, indexed analysis of original fixes. A gap is never a distance-bearing edge. */
data class RouteSection(
    val from: Int,
    val to: Int,
    val start: RoutePoint,
    val end: RoutePoint,
    val recordedMeters: Double,
    val gpsMeters: Double,
    val directMeters: Double,
    val elapsedMs: Long,
    val knownMs: Long,
    val gapMs: Long,
    val gaps: Int,
    val stoppedMs: Long,
    val movingMs: Long,
    val stops: Int,
    val averageAccuracy: Double,
    val worstAccuracy: Double,
    val maxSpeed: Double,
    val weakPoints: Int,
) {
    val averageGpsKmh: Double?
        get() = if (knownMs > 0) gpsMeters * 3600 / knownMs else null

    val range
        get() = from..to

    val points
        get() = to - from + 1

    fun fuel(trip: Outing?): EstimatedFuel? =
        trip
            ?.takeIf {
                gaps == 0 &&
                    (recordedMeters > 0 || gpsMeters < 1) &&
                    it.consumptionMinMl > 0 &&
                    it.fuelPriceHalala > 0
            }
            ?.let {
                estimateFuel(
                    recordedMeters,
                    ConsumptionProfile(it.consumptionMinMl, it.consumptionMaxMl, it.estimateSource),
                    it.fuelPriceHalala,
                )
            }
}

class RouteAnalysis(
    val points: List<RoutePoint>,
    val stops: List<StopDetails>,
    check: () -> Unit = {},
) {
    private val n = points.size
    private val recorded = DoubleArray(n)
    private val gps = DoubleArray(n)
    private val known = LongArray(n)
    private val missing = LongArray(n)
    private val gaps = IntArray(n)
    private val stopped = LongArray(n)
    private val accuracy = DoubleArray(n + 1)
    private val weak = IntArray(n + 1)
    private val blockSize = 128
    private val speedMax = DoubleArray((n + blockSize - 1) / blockSize)
    private val accuracyMax = DoubleArray(speedMax.size)
    private val intervals: List<Pair<Long, Long>>
    val slowSections: List<RouteSection>
    val peakIndex: Int?
    val weakestIndex: Int?
    val longestGapIndex: Int?

    init {
        // Union intervals to avoid double-counting overlapping imported stop events.
        val merged = ArrayList<Pair<Long, Long>>()
        val lastTime = points.lastOrNull()?.recordedAt ?: 0
        stops
            .sortedBy { it.startedAt }
            .forEach { stop ->
                val end = min(stop.endedAt ?: lastTime, lastTime)
                if (end > stop.startedAt) {
                    val last = merged.lastOrNull()
                    if (last != null && stop.startedAt <= last.second)
                        merged[merged.lastIndex] = last.first to max(last.second, end)
                    else merged.add(stop.startedAt to end)
                }
            }
        intervals = merged
        var stopCursor = 0
        var peak: Int? = null
        var worst: Int? = null
        var gapIndex: Int? = null
        for (i in points.indices) {
            if (i % 256 == 0) check()
            val p = points[i]
            val a = p.accuracy.takeIf { it.isFinite() && it > 0 } ?: 0.0
            val speed = p.speed.takeIf { it.isFinite() && it in 0.0..65.0 } ?: 0.0
            accuracy[i + 1] = accuracy[i] + a
            weak[i + 1] = weak[i] + if (a !in .1..25.0) 1 else 0
            speedMax[i / blockSize] = max(speedMax[i / blockSize], speed)
            accuracyMax[i / blockSize] = max(accuracyMax[i / blockSize], a)
            if (peak == null || speed > points[peak].speed) peak = i
            if (worst == null || a > points[worst].accuracy) worst = i
            if (i == 0) continue
            val before = points[i - 1]
            val dt = (p.recordedAt - before.recordedAt).coerceAtLeast(0)
            recorded[i] = recorded[i - 1]
            gps[i] = gps[i - 1]
            known[i] = known[i - 1]
            missing[i] = missing[i - 1]
            gaps[i] = gaps[i - 1]
            stopped[i] = stopped[i - 1]
            if (routeBreak(before, p)) {
                missing[i] += dt
                gaps[i]++
                if (
                    gapIndex == null ||
                        dt > (points[gapIndex].recordedAt - points[gapIndex - 1].recordedAt)
                )
                    gapIndex = i
                continue
            }
            recorded[i] += p.distanceDelta.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            gps[i] += distance(before.latitude, before.longitude, p.latitude, p.longitude)
            known[i] += dt
            while (
                stopCursor < intervals.size && intervals[stopCursor].second <= before.recordedAt
            ) stopCursor++
            var cursor = stopCursor
            while (cursor < intervals.size && intervals[cursor].first < p.recordedAt) {
                stopped[i] +=
                    (min(p.recordedAt, intervals[cursor].second) -
                            max(before.recordedAt, intervals[cursor].first))
                        .coerceAtLeast(0)
                cursor++
            }
        }
        peakIndex = peak?.takeIf { points[it].speed > 0 }
        weakestIndex = worst?.takeIf { points[it].accuracy > 25 }
        longestGapIndex = gapIndex
        val candidates = ArrayList<RouteSection>()
        var start = 0
        for (i in 1 until n) {
            if (i % 256 == 0) check()
            if (routeBreak(points[i - 1], points[i])) {
                if (gps[i - 1] - gps[start] >= 250) summary(start, i - 1)?.let(candidates::add)
                start = i
                continue
            }
            if (gps[i] - gps[start] >= 1000) {
                summary(start, i)?.let(candidates::add)
                start = i
            }
        }
        if (n > 1 && gps[n - 1] - gps[start] >= 250) summary(start, n - 1)?.let(candidates::add)
        slowSections =
            candidates
                .filter { it.gpsMeters >= 250 && it.knownMs >= 30000 }
                .sortedByDescending { it.knownMs / it.gpsMeters }
                .take(3)
    }

    fun summary(first: Int, last: Int): RouteSection? {
        if (first !in points.indices || last !in points.indices) return null
        val from = min(first, last)
        val to = max(first, last)
        val a = points[from]
        val b = points[to]
        var stopTime = 0L
        var count = 0
        // Binary search to the first intersecting interval.
        var low = 0
        var high = intervals.size
        while (low < high) {
            val mid = (low + high) / 2
            if (intervals[mid].second <= a.recordedAt) low = mid + 1 else high = mid
        }
        for (i in low until intervals.size) {
            val stop = intervals[i]
            if (stop.first >= b.recordedAt) break
            val overlap =
                (min(stop.second, b.recordedAt) - max(stop.first, a.recordedAt)).coerceAtLeast(0)
            if (overlap > 0) {
                stopTime += overlap
                count++
            }
        }
        var maxSpeed = 0.0
        var maxAccuracy = 0.0
        var i = from
        while (i <= to) {
            if (i % blockSize == 0 && i + blockSize - 1 <= to) {
                maxSpeed = max(maxSpeed, speedMax[i / blockSize])
                maxAccuracy = max(maxAccuracy, accuracyMax[i / blockSize])
                i += blockSize
            } else {
                maxSpeed =
                    max(
                        maxSpeed,
                        points[i].speed.takeIf { it.isFinite() && it in 0.0..65.0 } ?: 0.0,
                    )
                maxAccuracy =
                    max(maxAccuracy, points[i].accuracy.takeIf { it.isFinite() && it > 0 } ?: 0.0)
                i++
            }
        }
        val knownMs = known[to] - known[from]
        return RouteSection(
            from,
            to,
            a,
            b,
            recorded[to] - recorded[from],
            gps[to] - gps[from],
            distance(a.latitude, a.longitude, b.latitude, b.longitude),
            (b.recordedAt - a.recordedAt).coerceAtLeast(0),
            knownMs,
            missing[to] - missing[from],
            gaps[to] - gaps[from],
            stopTime,
            (knownMs - (stopped[to] - stopped[from])).coerceAtLeast(0),
            count,
            (accuracy[to + 1] - accuracy[from]) / (to - from + 1),
            maxAccuracy,
            maxSpeed,
            weak[to + 1] - weak[from],
        )
    }
}
