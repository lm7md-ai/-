package sa.rihla.core

import sa.rihla.data.*

data class TemporaryCommute(
    val place: String,
    val from: Int,
    val to: Int,
    val departure: Long,
    val arrival: Long,
)

/**
 * Separate view of the original route; confirmed campus arrival and a real recorded dwell are
 * required.
 */
fun temporaryCommutes(
    trip: Outing,
    points: List<RoutePoint>,
    events: List<JourneyEvent>,
    places: List<SavedPlace>,
): List<TemporaryCommute> {
    if (points.size < 2) return emptyList()
    val arrivals =
        (events.filter { it.type == "VISIT" && it.confirmed }.map { it.startedAt } +
                listOfNotNull(
                    trip.state.universityArrival.takeIf { trip.state.visitConfirmed && it > 0 }
                ))
            .distinct()
            .sorted()
    val result = ArrayList<TemporaryCommute>()
    var priorArrival = Long.MIN_VALUE
    for (arrival in arrivals) {
        val end = points.indexOfLast { it.recordedAt <= arrival }
        if (end < 1) continue
        var best: TemporaryCommute? = null
        places.forEach { place ->
            val info = temporaryPlaceInfo(place.type) ?: return@forEach
            var entered = -1
            var lastInside = -1
            for (i in 0..end) {
                val p = points[i]
                if (p.recordedAt <= priorArrival) {
                    entered = -1
                    lastInside = -1
                    continue
                }
                if (
                    i > 0 &&
                        (p.breakBefore ||
                            p.uncertainGap ||
                            p.recordedAt - points[i - 1].recordedAt > 90000)
                ) {
                    entered = -1
                    lastInside = -1
                }
                val inside =
                    p.recordedAt in info.created until info.expires &&
                        distance(p.latitude, p.longitude, place.latitude, place.longitude) +
                            p.accuracy <= place.radius
                if (inside) {
                    if (entered < 0) entered = i
                    lastInside = i
                } else if (entered >= 0 && lastInside >= entered) {
                    val start = points[entered]
                    val stop = points[lastInside]
                    val confirmed =
                        stop.recordedAt - start.recordedAt >= 120000 ||
                            events.any {
                                it.type == "STOP" &&
                                    it.confirmed &&
                                    it.startedAt <= stop.recordedAt &&
                                    it.endedAt >= start.recordedAt &&
                                    distance(
                                        it.latitude,
                                        it.longitude,
                                        place.latitude,
                                        place.longitude,
                                    ) <= place.radius
                            }
                    if (
                        confirmed &&
                            lastInside < end &&
                            (best == null || stop.recordedAt > best!!.departure)
                    )
                        best =
                            TemporaryCommute(place.type, lastInside, end, stop.recordedAt, arrival)
                    entered = -1
                    lastInside = -1
                }
            }
        }
        best?.let(result::add)
        priorArrival = arrival
    }
    return result
}
