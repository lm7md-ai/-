package sa.rihla.core

import kotlin.math.*
import sa.rihla.data.RoutePoint

/** Provider-neutral gate; never snaps to a road or fabricates fixes through a tunnel. */
class FixQualityGate {
    var reacquired: Boolean = false
        private set

    private var last: Fix? = null
    private var candidate: Fix? = null
    private var lastElapsed = 0L

    fun reset() {
        last = null
        candidate = null
        lastElapsed = 0
    }

    fun inspect(f: Fix, allowRelaxedIndoorAccuracy: Boolean = false): String? {
        reacquired = false
        val maxAcc = if (allowRelaxedIndoorAccuracy) 160.0 else 60.0
        if (
            !f.lat.isFinite() ||
                !f.lon.isFinite() ||
                f.lat !in -90.0..90.0 ||
                f.lon !in -180.0..180.0 ||
                f.accuracy !in .1..maxAcc ||
                f.speed !in 0.0..65.0 ||
                (f.speedAccuracy != null && (!f.speedAccuracy.isFinite() || f.speedAccuracy < 0)) ||
                !f.altitude.isFinite() ||
                !f.bearing.isFinite()
        )
            return "INVALID"
        if (f.elapsedNanos > 0 && lastElapsed > 0 && f.elapsedNanos <= lastElapsed)
            return "DUPLICATE"
        val previous = last
        if (previous != null) {
            val dt = (f.time - previous.time) / 1000.0
            if (dt <= 0) return "ORDER"
            val step = distance(previous.lat, previous.lon, f.lat, f.lon)
            if (dt <= 90 && step / dt > 65) return "JUMP"
            val trustedSlow =
                f.hasSpeed && f.speed < 2.5 && (f.speedAccuracy == null || f.speedAccuracy <= 2)
            if (
                !allowRelaxedIndoorAccuracy &&
                    dt <= 30 &&
                    trustedSlow &&
                    step > max(35.0, previous.accuracy + f.accuracy + 15)
            ) {
                val pending = candidate
                candidate = f
                if (
                    pending == null ||
                        f.time - pending.time !in 1000..15000 ||
                        distance(pending.lat, pending.lon, f.lat, f.lon) >
                            max(15.0, f.accuracy + pending.accuracy)
                )
                    return "SUSPECT"
                reacquired = true
            }
        }
        last = f
        candidate = null
        if (f.elapsedNanos > 0) lastElapsed = f.elapsedNanos
        return null
    }
}

fun adaptiveTrackingInterval(
    stopped: Boolean,
    precise: Boolean,
    speed: Double,
    boost: Boolean,
    turn: Boolean = false,
    straightRoad: Boolean = false,
    accuracy: Double = 15.0,
    deepSleep: Boolean = false,
    instantWakeup: Boolean = false,
): Long {
    if (instantWakeup) return 1000L
    if (deepSleep) return 300_000L
    if (stopped) return trackingInterval(true, precise)
    if (turn || (precise && (speed >= 18 || boost))) return 2000L
    if (straightRoad && accuracy in 0.1..10.0 && !boost) return 4500L
    return trackingInterval(false, precise)
}

data class RouteQuality(
    val points: Int,
    val medianAccuracy: Double?,
    val goodFixes: Int,
    val gaps: Int,
    val gapMs: Long,
    val startDelayMs: Long,
    val tailDelayMs: Long,
)

fun routeQuality(points: List<RoutePoint>, start: Long, end: Long): RouteQuality {
    val valid =
        points
            .filter { it.recordedAt in start..end && it.accuracy.isFinite() && it.accuracy > 0 }
            .sortedBy { it.recordedAt }
    val accuracy = valid.map { it.accuracy }.sorted()
    val median =
        if (accuracy.isEmpty()) null
        else if (accuracy.size % 2 == 1) accuracy[accuracy.size / 2]
        else (accuracy[accuracy.size / 2 - 1] + accuracy[accuracy.size / 2]) / 2
    val missing =
        valid.zipWithNext().filter { (a, b) ->
            b.breakBefore || b.uncertainGap || b.recordedAt - a.recordedAt > 90000
        }
    return RouteQuality(
        valid.size,
        median,
        valid.count { it.accuracy <= 15 },
        missing.size,
        missing.sumOf { (a, b) -> (b.recordedAt - a.recordedAt).coerceAtLeast(0) },
        valid.firstOrNull()?.let { (it.recordedAt - start).coerceAtLeast(0) }
            ?: (end - start).coerceAtLeast(0),
        valid.lastOrNull()?.let { (end - it.recordedAt).coerceAtLeast(0) } ?: 0,
    )
}

/** Short, signed heading history catches gradual turns without boosting on stationary jitter. */
class TurnDetailDetector {
    private var previous: Fix? = null
    private var heading: Double? = null
    private val changes = java.util.ArrayDeque<Pair<Long, Double>>()
    var lastAbsHeadingDelta: Double = 0.0
        private set

    val isStraightRoad: Boolean
        get() = heading != null && lastAbsHeadingDelta < 4.0

    fun reset() {
        previous = null
        heading = null
        lastAbsHeadingDelta = 0.0
        changes.clear()
    }

    fun inspect(f: Fix): Boolean {
        val old = previous
        if (
            f.discontinuous ||
                f.accuracy > 25 ||
                (f.hasSpeed && f.speed < 4) ||
                (old != null && f.time - old.time !in 1..15000)
        )
            reset()
        val before = previous
        previous = f
        if (f.accuracy > 25 || (f.hasSpeed && f.speed < 4)) return false
        val course =
            if (f.hasBearing && f.hasSpeed && f.speed >= 4) f.bearing
            else {
                if (before == null) return false
                val step = distance(before.lat, before.lon, f.lat, f.lon)
                if (
                    step < max(12.0, (before.accuracy + f.accuracy) * .75) ||
                        step / ((f.time - before.time) / 1000.0) < 4
                )
                    return false
                val lat1 = Math.toRadians(before.lat)
                val lat2 = Math.toRadians(f.lat)
                val lon = Math.toRadians(f.lon - before.lon)
                Math.toDegrees(
                    atan2(
                        sin(lon) * cos(lat2),
                        cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon),
                    )
                )
            }
        val last = heading
        heading = course
        if (last == null) return false
        val delta = ((course - last + 540) % 360) - 180
        lastAbsHeadingDelta = abs(delta)
        if (abs(delta) > 120) {
            changes.clear()
            return false
        }
        changes.addLast(f.time to delta)
        while (changes.isNotEmpty() && changes.first.first < f.time - 12000) changes.removeFirst()
        if (abs(changes.sumOf { it.second }) < 22) return false
        changes.clear()
        return true
    }
}
