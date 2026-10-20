package sa.rihla.map

import kotlin.math.*
import sa.rihla.core.distance

data class RoadMatch(
    val latitude: Double,
    val longitude: Double,
    val road: Int,
    val segment: Int,
    val fraction: Double,
    val offsetMeters: Double,
    val name: String,
)

/**
 * Conservative geometric matching. No routing graph, lane choice, or altitude inference is claimed.
 */
class RoadMatcher(private val roads: List<RoadFeature>, private val check: () -> Unit = {}) {
    private val origin =
        LocalMeters(
            roads.firstOrNull()?.points?.firstOrNull()?.first ?: 0.0,
            roads.firstOrNull()?.points?.firstOrNull()?.second ?: 0.0,
        )

    private data class Segment(val road: Int, val index: Int, val a: XY, val b: XY)

    private val segments = ArrayList<Segment>()
    private val cells = HashMap<Pair<Int, Int>, MutableList<Int>>()
    val available: Boolean

    init {
        var safe = true
        roads.forEachIndexed { road, feature ->
            if (road % 64 == 0) check()
            feature.points.zipWithNext().forEachIndexed { index, (a, b) ->
                if (segments.size >= 80000 || cells.size >= 200000) safe = false
                if (safe) {
                    val p = origin.xy(a.first, a.second)
                    val q = origin.xy(b.first, b.second)
                    val count = ceil(hypot(q.x - p.x, q.y - p.y) / 32).toInt().coerceAtLeast(1)
                    if (count <= 10000) {
                        val id = segments.size
                        segments.add(Segment(road, index, p, q))
                        val seen = HashSet<Pair<Int, Int>>()
                        for (i in 0..count) {
                            val t = i.toDouble() / count
                            val cell =
                                floor((p.x + (q.x - p.x) * t) / 64).toInt() to
                                    floor((p.y + (q.y - p.y) * t) / 64).toInt()
                            if (seen.add(cell)) cells.getOrPut(cell) { ArrayList() }.add(id)
                        }
                    } else safe = false
                }
            }
        }
        available = safe && segments.isNotEmpty()
        if (!available) {
            cells.clear()
            segments.clear()
        }
    }

    fun match(
        lat: Double,
        lon: Double,
        accuracy: Double,
        direction: Pair<Pair<Double, Double>, Pair<Double, Double>>?,
    ): RoadMatch? {
        if (!available || accuracy !in .1..35.0) return null
        val point = origin.xy(lat, lon)
        val x = floor(point.x / 64).toInt()
        val y = floor(point.y / 64).toInt()
        val candidates = HashSet<Int>()
        for (a in x - 1..x + 1) for (b in y - 1..y + 1) cells[a to b]?.let { candidates.addAll(it) }
        if (candidates.size > 512) return null
        val heading = direction?.let {
            val a = origin.xy(it.first.first, it.first.second)
            val b = origin.xy(it.second.first, it.second.second)
            atan2(b.y - a.y, b.x - a.x)
        }
        val radius = max(10.0, accuracy * 1.5).coerceAtMost(35.0)
        val scored = ArrayList<Pair<Double, RoadMatch>>()
        for (id in candidates) {
            val s = segments[id]
            val (t, offset) = projection(point, s.a, s.b)
            if (offset > radius) continue
            val roadAngle = atan2(s.b.y - s.a.y, s.b.x - s.a.x)
            val angle =
                heading?.let {
                    abs(atan2(sin(it - roadAngle), cos(it - roadAngle))).let { v ->
                        min(v, abs(PI - v))
                    }
                } ?: 0.0
            if (heading != null && angle > Math.toRadians(35.0)) continue
            val xy = XY(s.a.x + (s.b.x - s.a.x) * t, s.a.y + (s.b.y - s.a.y) * t)
            val geo = origin.coordinate(xy)
            val match =
                RoadMatch(geo.first, geo.second, s.road, s.index, t, offset, roads[s.road].name)
            val score = offset + angle * 8
            scored.add(score to match)
        }
        val ordered = scored.sortedBy { it.first }
        val best = ordered.firstOrNull() ?: return null
        val second =
            ordered.drop(1).firstOrNull {
                it.second.road != best.second.road ||
                    abs(it.second.segment - best.second.segment) > 1
            }
        if (second != null && second.first - best.first < max(6.0, accuracy * .6)) {
            if (
                distance(
                    best.second.latitude,
                    best.second.longitude,
                    second.second.latitude,
                    second.second.longitude,
                ) > 3
            )
                return null
            return best.second.copy(name = "تقاطع أو طرق متداخلة")
        }
        return best.second
    }

    /** Follow only a shared, explicitly recorded road polyline, and reject implausible detours. */
    fun bridge(a: RoadMatch?, b: RoadMatch?): List<Pair<Double, Double>> {
        if (a == null || b == null || a.road != b.road || abs(a.segment - b.segment) > 200)
            return emptyList()
        val points = roads[a.road].points
        val from = a.segment + a.fraction
        val to = b.segment + b.fraction
        val middle =
            if (to >= from)
                ((floor(from).toInt() + 1)..ceil(to).toInt() - 1).mapNotNull {
                    points.getOrNull(it)
                }
            else
                ((ceil(from).toInt() - 1) downTo floor(to).toInt() + 1).mapNotNull {
                    points.getOrNull(it)
                }
        if (middle.isEmpty()) return emptyList()
        val all = listOf(a.latitude to a.longitude) + middle + listOf(b.latitude to b.longitude)
        val length =
            all.zipWithNext().sumOf { (p, q) -> distance(p.first, p.second, q.first, q.second) }
        val direct = distance(a.latitude, a.longitude, b.latitude, b.longitude)
        return if (length <= max(direct * 1.6, direct + 20)) middle else emptyList()
    }
}
