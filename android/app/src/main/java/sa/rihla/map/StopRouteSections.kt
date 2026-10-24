package sa.rihla.map

import sa.rihla.core.StopDetails
import sa.rihla.data.Outing

/** A read-only interval bounded by stop events, not by the two closest GPS samples. */
data class StopRouteSection(
    val startedAt: Long,
    val endedAt: Long,
    val fromStop: Int?,
    val toStop: Int?,
    val liveEnd: Boolean,
    val samples: RouteSection?,
) {
    val elapsedMs: Long
        get() = (endedAt - startedAt).coerceAtLeast(0)

    val unsampledMs: Long
        get() = (elapsedMs - (samples?.elapsedMs ?: 0)).coerceAtLeast(0)

    val range: IntRange?
        get() = samples?.range
}

class StopRouteSections(val analysis: RouteAnalysis, private val trip: Outing) {
    val stops = analysis.stops.sortedBy { it.startedAt }
    private val points = analysis.points

    /** Use the tapped edge's midpoint so choosing its arrival fix still selects the approach. */
    fun at(selection: RoutePointSelection): StopRouteSection? {
        val edge = selection.segmentEnd
        val time =
            if (edge != null && edge in 1 until points.size) {
                val a = points[edge - 1].recordedAt
                a + (points[edge].recordedAt - a) / 2
            } else points.getOrNull(selection.index)?.recordedAt ?: return null
        if (time < trip.startedAt || trip.endedAt?.let { time > it } == true) return null
        if (stops.any { time >= it.startedAt && time < (it.endedAt ?: Long.MAX_VALUE) }) return null
        val from =
            stops.indexOfLast { it.endedAt?.let { end -> end <= time } == true }.takeIf { it >= 0 }
        val to = stops.indexOfFirst { it.startedAt >= time }.takeIf { it >= 0 }
        return interval(from, to)
    }

    fun before(stop: StopDetails): StopRouteSection? {
        val index = stops.indexOfFirst { it.id == stop.id }
        if (index < 0) return null
        return interval((index - 1).takeIf { it >= 0 }, index)
    }

    fun after(stop: StopDetails): StopRouteSection? {
        val index = stops.indexOfFirst { it.id == stop.id }
        if (index < 0 || stops[index].endedAt == null) return null
        return interval(index, (index + 1).takeIf { it < stops.size })
    }

    fun toLatest(stop: StopDetails): StopRouteSection? {
        if (trip.endedAt != null) return null
        val index = stops.indexOfFirst { it.id == stop.id }
        if (index < 0) return null
        val departure = stops[index].endedAt ?: return null
        return build(departure, points.lastOrNull()?.recordedAt ?: return null, index, null, true)
    }

    private fun interval(from: Int?, to: Int?): StopRouteSection? {
        val start = from?.let { stops[it].endedAt ?: return null } ?: trip.startedAt
        val end =
            to?.let { stops[it].startedAt }
                ?: trip.endedAt
                ?: points.lastOrNull()?.recordedAt
                ?: return null
        return build(start, end, from, to, to == null && trip.endedAt == null)
    }

    private fun build(
        start: Long,
        end: Long,
        from: Int?,
        to: Int?,
        live: Boolean,
    ): StopRouteSection? {
        if (end < start) return null
        // DAO points are ordered by recordedAt. Include only complete recorded edges inside
        // the interval: never prorate or invent distance around an event boundary or GPS gap.
        fun lowerBound(time: Long, inclusive: Boolean): Int {
            var low = 0
            var high = points.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (points[mid].recordedAt < time || (!inclusive && points[mid].recordedAt == time))
                    low = mid + 1
                else high = mid
            }
            return low
        }
        val first = lowerBound(start, true)
        val last = lowerBound(end, false) - 1
        val summary = if (first < last) analysis.summary(first, last) else null
        return StopRouteSection(start, end, from, to, live, summary)
    }
}
