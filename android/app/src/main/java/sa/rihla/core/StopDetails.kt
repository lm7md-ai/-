package sa.rihla.core

import sa.rihla.data.JourneyEvent
import sa.rihla.data.Outing

data class StopDetails(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val latitude: Double,
    val longitude: Double,
) {
    fun durationMs(now: Long): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0)
}

/** Active stops are persisted in EngineState until departure creates the completed event. */
fun stopDetails(outing: Outing, events: List<JourneyEvent>): List<StopDetails> {
    val completed =
        events
            .filter { it.type == "STOP" }
            .map {
                StopDetails(it.id, it.startedAt, it.endedAt, it.latitude, it.longitude)
            }
    val state = outing.state
    return (completed +
            if (outing.endedAt == null && state.stopStart > 0) {
                listOf(
                    StopDetails(-outing.id, state.stopStart, null, state.stillLat, state.stillLon)
                )
            } else emptyList())
        .sortedBy { it.startedAt }
}

/** Select the nearest marker within a 48dp touch target; ties prefer the most recent stop. */
fun hitStop(
    stops: List<StopDetails>,
    x: Float,
    y: Float,
    radius: Float,
    project: (Double, Double) -> Pair<Float, Float>,
): StopDetails? =
    stops
        .asReversed()
        .map { stop ->
            val p = project(stop.latitude, stop.longitude)
            stop to ((p.first - x) * (p.first - x) + (p.second - y) * (p.second - y))
        }
        .filter { it.second <= radius * radius }
        .minByOrNull { it.second }
        ?.first
