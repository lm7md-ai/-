package sa.rihla.map

import kotlin.math.*
import sa.rihla.core.*
import sa.rihla.data.RoutePoint

/** All geometry here is a view of recorded points. Nothing writes to the ledger. */
enum class RouteColorMode(val label: String) {
    PLAIN("لون موحد"),
    SPEED("السرعة"),
    ACCURACY("دقة الموقع"),
    TIME("الزمن"),
    STOPS("التوقفات"),
}

data class RouteDisplayOptions(
    val confidence: Boolean = true,
    val cleanStops: Boolean = true,
    val matchRoads: Boolean = false,
    val simplify: Boolean = true,
    val inspect: Boolean = false,
    val color: RouteColorMode = RouteColorMode.PLAIN,
)

data class DisplayPoint(
    val index: Int,
    val latitude: Double,
    val longitude: Double,
    val stopped: Boolean = false,
    val match: RoadMatch? = null,
)

data class RouteStroke(
    val color: Int,
    val dashed: Boolean,
    val coordinates: List<Pair<Double, Double>>,
)

data class RouteDisplayStats(
    val raw: Int = 0,
    val drawn: Int = 0,
    val cleaned: Int = 0,
    val matched: Int = 0,
    val matchingStatus: String = "",
)

data class PreparedRoute(
    val points: List<DisplayPoint>,
    val strokes: List<RouteStroke>,
    val stats: RouteDisplayStats,
    val hitIndex: RouteHitIndex,
    val bridges: Map<Int, List<Pair<Double, Double>>> = emptyMap(),
)

data class RoutePointSelection(val point: RoutePoint, val index: Int, val displayed: DisplayPoint)

fun routeBreak(a: RoutePoint, b: RoutePoint) =
    b.breakBefore ||
        b.uncertainGap ||
        b.recordedAt <= a.recordedAt ||
        b.recordedAt - a.recordedAt > 90000

internal data class XY(val x: Double, val y: Double)

internal class LocalMeters(val latitude: Double, val longitude: Double) {
    private val scale = cos(Math.toRadians(latitude)).coerceAtLeast(.01)

    fun xy(lat: Double, lon: Double) =
        XY((lon - longitude) * 111195 * scale, (lat - latitude) * 111195)

    fun coordinate(p: XY) = (latitude + p.y / 111195) to (longitude + p.x / (111195 * scale))
}

internal fun projection(p: XY, a: XY, b: XY): Pair<Double, Double> {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val t =
        if (dx * dx + dy * dy < .000001) 0.0
        else (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    return t to hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
}

/** Iterative Douglas–Peucker in bounded chunks, preserving endpoints and all run boundaries. */
fun simplifyRouteGeometry(
    values: List<Pair<Double, Double>>,
    tolerance: Double,
    check: () -> Unit = {},
): List<Pair<Double, Double>> {
    if (values.size < 3 || tolerance <= 0) return values
    val origin = LocalMeters(values.first().first, values.first().second)
    val xy = values.map { origin.xy(it.first, it.second) }
    val keep = BooleanArray(values.size)
    keep[0] = true
    keep[values.lastIndex] = true
    var start = 0
    while (start < values.lastIndex) {
        check()
        val end = min(start + 2048, values.lastIndex)
        keep[start] = true
        keep[end] = true
        val work = java.util.ArrayDeque<Pair<Int, Int>>()
        work.add(start to end)
        while (work.isNotEmpty()) {
            val (a, b) = work.removeLast()
            var far = -1
            var error = tolerance
            for (i in a + 1 until b) {
                if (i % 256 == 0) check()
                val d = projection(xy[i], xy[a], xy[b]).second
                if (d > error) {
                    error = d
                    far = i
                }
            }
            if (far >= 0) {
                keep[far] = true
                work.add(a to far)
                work.add(far to b)
            }
        }
        start = end
    }
    return values.filterIndexed { index, _ -> keep[index] }
}

fun routeSegmentColor(
    mode: RouteColorMode,
    point: RoutePoint,
    stopped: Boolean,
    first: Long,
    last: Long,
): Int =
    when (mode) {
        RouteColorMode.PLAIN -> 0xFF10766A.toInt()
        RouteColorMode.SPEED ->
            if (point.speed * 3.6 < 30) 0xFF316FE0.toInt()
            else if (point.speed * 3.6 < 80) 0xFF10A084.toInt() else 0xFFE48B30.toInt()
        RouteColorMode.ACCURACY ->
            if (point.accuracy in .1..10.0) 0xFF11856C.toInt()
            else if (point.accuracy in 10.0..25.0) 0xFFC48611.toInt() else 0xFFC74747.toInt()
        RouteColorMode.STOPS -> if (stopped) 0xFFD08010.toInt() else 0xFF10766A.toInt()
        RouteColorMode.TIME ->
            when (
                (((point.recordedAt - first).coerceAtLeast(0).toDouble() /
                        (last - first).coerceAtLeast(1)) * 4)
                    .toInt()
                    .coerceIn(0, 3)
            ) {
                0 -> 0xFF287BC1.toInt()
                1 -> 0xFF118D8A.toInt()
                2 -> 0xFF8B62B5.toInt()
                else -> 0xFFD07137.toInt()
            }
    }

fun prepareRoute(
    raw: List<RoutePoint>,
    stops: List<StopDetails>,
    options: RouteDisplayOptions,
    toleranceMeters: Double,
    matcher: RoadMatcher? = null,
    matchingStatus: String = "",
    check: () -> Unit = {},
): PreparedRoute {
    if (raw.isEmpty())
        return PreparedRoute(
            emptyList(),
            emptyList(),
            RouteDisplayStats(matchingStatus = matchingStatus),
            RouteHitIndex(emptyList()),
        )
    val intervals = stops.sortedBy { it.startedAt }
    var stopIndex = 0
    val result = ArrayList<DisplayPoint>(raw.size)
    var cleaned = 0
    var matched = 0
    val centers = HashMap<Long, Pair<Double, Double>>()
    intervals.forEach { stop ->
        var low = 0
        var high = raw.size
        while (low < high) {
            val mid = (low + high) / 2
            if (raw[mid].recordedAt < stop.startedAt) low = mid + 1 else high = mid
        }
        val candidates =
            raw.subList(low, raw.size)
                .asSequence()
                .takeWhile { it.recordedAt <= (stop.endedAt ?: Long.MAX_VALUE) }
                .filter {
                    it.recordedAt >= stop.startedAt &&
                        it.recordedAt <= (stop.endedAt ?: Long.MAX_VALUE) &&
                        it.speed <= 1.5 &&
                        it.accuracy <= 35 &&
                        distance(stop.latitude, stop.longitude, it.latitude, it.longitude) <= 50
                }
                .take(512)
                .toList()
        if (candidates.isNotEmpty())
            centers[stop.startedAt] =
                candidates.map { it.latitude }.sorted()[candidates.size / 2] to
                    candidates.map { it.longitude }.sorted()[candidates.size / 2]
    }
    raw.forEachIndexed { index, p ->
        if (index % 256 == 0) check()
        while (
            stopIndex < intervals.size &&
                (intervals[stopIndex].endedAt ?: Long.MAX_VALUE) <= p.recordedAt
        ) stopIndex++
        val stop =
            intervals.getOrNull(stopIndex)?.takeIf {
                p.recordedAt >= it.startedAt && p.recordedAt < (it.endedAt ?: Long.MAX_VALUE)
            }
        val center = stop?.let { centers[it.startedAt] }
        val canClean =
            options.cleanStops &&
                center != null &&
                index > 0 &&
                index < raw.lastIndex &&
                !routeBreak(raw[index - 1], p) &&
                !routeBreak(p, raw[index + 1]) &&
                p.speed <= 1.5 &&
                distance(center.first, center.second, p.latitude, p.longitude) <=
                    max(15.0, p.accuracy.coerceAtMost(35.0))
        val lat = if (canClean) center.first else p.latitude
        val lon = if (canClean) center.second else p.longitude
        if (canClean && distance(lat, lon, p.latitude, p.longitude) > .1) cleaned++
        val previous = raw.getOrNull(index - 1)?.takeIf { !routeBreak(it, p) }
        val next = raw.getOrNull(index + 1)?.takeIf { !routeBreak(p, it) }
        val direction =
            when {
                previous != null &&
                    next != null &&
                    distance(
                        previous.latitude,
                        previous.longitude,
                        next.latitude,
                        next.longitude,
                    ) >= 10 ->
                    (previous.latitude to previous.longitude) to (next.latitude to next.longitude)
                previous != null &&
                    distance(previous.latitude, previous.longitude, p.latitude, p.longitude) >=
                        10 ->
                    (previous.latitude to previous.longitude) to (p.latitude to p.longitude)
                next != null &&
                    distance(p.latitude, p.longitude, next.latitude, next.longitude) >= 10 ->
                    (p.latitude to p.longitude) to (next.latitude to next.longitude)
                else -> null
            }
        val match =
            if (options.matchRoads && p.accuracy <= 35 && stop == null)
                matcher?.match(lat, lon, p.accuracy, direction)
            else null
        if (match != null) matched++
        result.add(
            DisplayPoint(
                index,
                match?.latitude ?: lat,
                match?.longitude ?: lon,
                stop != null,
                match,
            )
        )
    }
    val bridges = HashMap<Int, List<Pair<Double, Double>>>()
    val strokes = ArrayList<RouteStroke>()
    var coordinates = ArrayList<Pair<Double, Double>>()
    var color = 0
    var dashed = false
    fun flush() {
        if (coordinates.size >= 2)
            strokes.add(
                RouteStroke(
                    color,
                    dashed,
                    if (options.simplify) simplifyRouteGeometry(coordinates, toleranceMeters, check)
                    else coordinates.toList(),
                )
            )
        coordinates = ArrayList()
    }
    for (i in 1 until raw.size) {
        if (i % 256 == 0) check()
        if (routeBreak(raw[i - 1], raw[i])) {
            flush()
            continue
        }
        val a = result[i - 1]
        val b = result[i]
        val nextColor =
            routeSegmentColor(
                options.color,
                raw[i],
                b.stopped,
                raw.first().recordedAt,
                raw.last().recordedAt,
            )
        val nextDashed =
            options.confidence && (raw[i - 1].accuracy !in .1..25.0 || raw[i].accuracy !in .1..25.0)
        if (coordinates.isNotEmpty() && (nextColor != color || nextDashed != dashed)) flush()
        if (coordinates.isEmpty()) {
            color = nextColor
            dashed = nextDashed
            coordinates.add(a.latitude to a.longitude)
        }
        val bridge =
            if (
                options.matchRoads &&
                    matcher != null &&
                    raw[i].recordedAt - raw[i - 1].recordedAt <= 30000
            )
                matcher.bridge(a.match, b.match)
            else emptyList()
        if (bridge.isNotEmpty()) bridges[i] = bridge
        coordinates.addAll(bridge)
        coordinates.add(b.latitude to b.longitude)
    }
    flush()
    return PreparedRoute(
        result,
        strokes,
        RouteDisplayStats(
            raw.size,
            strokes.sumOf { it.coordinates.size },
            cleaned,
            matched,
            matchingStatus,
        ),
        RouteHitIndex(result, raw, check, bridges),
        bridges,
    )
}

/**
 * Geographic buckets are built once with the full points; display simplification never changes hit
 * testing.
 */
class RouteHitIndex(
    points: List<DisplayPoint>,
    private val raw: List<RoutePoint> = emptyList(),
    check: () -> Unit = {},
    bridges: Map<Int, List<Pair<Double, Double>>> = emptyMap(),
) {
    private val points = points
    private val meters =
        LocalMeters(points.firstOrNull()?.latitude ?: 0.0, points.firstOrNull()?.longitude ?: 0.0)
    private val cells = HashMap<Pair<Int, Int>, MutableList<Int>>()

    init {
        points.forEachIndexed { i, p ->
            if (i % 256 == 0) check()
            val q = meters.xy(p.latitude, p.longitude)
            val previous = points.getOrNull(i - 1)
            val a = previous?.let { meters.xy(it.latitude, it.longitude) } ?: q
            val valid = i > 0 && raw.size == points.size && !routeBreak(raw[i - 1], raw[i])
            val path =
                if (valid)
                    listOf(a) +
                        bridges[i].orEmpty().map { meters.xy(it.first, it.second) } +
                        listOf(q)
                else listOf(q, q)
            val seen = HashSet<Pair<Int, Int>>()
            path.zipWithNext().forEach { (start, end) ->
                val steps =
                    ceil(hypot(end.x - start.x, end.y - start.y) / 50).toInt().coerceIn(1, 1000)
                for (n in 0..steps) {
                    val t = n.toDouble() / steps
                    val cell =
                        floor((start.x + (end.x - start.x) * t) / 100).toInt() to
                            floor((start.y + (end.y - start.y) * t) / 100).toInt()
                    if (seen.add(cell)) cells.getOrPut(cell) { ArrayList() }.add(i)
                }
            }
        }
    }

    fun candidates(lat: Double, lon: Double, radius: Double): Set<Int> {
        val p = meters.xy(lat, lon)
        val r = radius * (1 / max(.1, cos(Math.toRadians(lat)))) + 1
        val fromX = floor((p.x - r) / 100).toInt()
        val toX = floor((p.x + r) / 100).toInt()
        val fromY = floor((p.y - r) / 100).toInt()
        val toY = floor((p.y + r) / 100).toInt()
        if (toX - fromX > 40 || toY - fromY > 40) return points.indices.toSet()
        val found = HashSet<Int>()
        for (x in fromX..toX) for (y in fromY..toY) cells[x to y]?.let { found.addAll(it) }
        return found
    }
}
