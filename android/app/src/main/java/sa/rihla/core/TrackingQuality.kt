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

    fun inspect(f: Fix): String? {
        reacquired = false
        if (
            !f.lat.isFinite() ||
                !f.lon.isFinite() ||
                f.lat !in -90.0..90.0 ||
                f.lon !in -180.0..180.0 ||
                f.accuracy !in .1..60.0 ||
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
            // When low reported speed contradicts a large displacement, require a second
            // nearby fix before reacquiring; an isolated urban-canyon spike never moves the path.
            val trustedSlow =
                f.hasSpeed && f.speed < 2.5 && (f.speedAccuracy == null || f.speedAccuracy <= 2)
            if (dt <= 30 && trustedSlow && step > max(35.0, previous.accuracy + f.accuracy + 15)) {
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
): Long =
    if (!stopped && precise && (speed >= 18 || boost)) 2000 else trackingInterval(stopped, precise)

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
            b.breakBefore || b.recordedAt - a.recordedAt > 90000
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
