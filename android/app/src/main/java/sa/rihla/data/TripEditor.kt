package sa.rihla.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import sa.rihla.core.*

/** Explicit edits of ended trips only. All point/event/expense changes commit atomically. */
class TripEditor(private val database: JourneyDatabase) {
    suspend fun merge(firstId: Long, secondId: Long): Long = database.withTransaction {
        require(firstId != secondId)
        val dao = database.dao()
        val all = dao.outings()
        val first = all.first { it.id == firstId }
        val second = all.first { it.id == secondId }
        require(
            first.endedAt != null &&
                second.endedAt != null &&
                second.startedAt >= first.endedAt &&
                second.startedAt - first.endedAt <= 3_600_000
        )
        require(
            first.fuelPriceHalala == second.fuelPriceHalala &&
                first.consumptionMinMl == second.consumptionMinMl &&
                first.consumptionMaxMl == second.consumptionMaxMl &&
                first.fuelGrade == second.fuelGrade
        )
        val a = dao.details(firstId) ?: TripDetails(firstId)
        val b = dao.details(secondId) ?: TripDetails(secondId)
        val note =
            listOf(
                    a.note,
                    b.title.takeIf { it.isNotBlank() }?.let { "المشوار المدمج: $it" }.orEmpty(),
                    b.note,
                )
                .filter { it.isNotBlank() }
                .joinToString("\n")
        require(note.length <= 2000)
        val db = database.openHelper.writableDatabase
        val shared =
            dao.points(firstId).first().lastOrNull()?.let { last ->
                dao.points(secondId).first().firstOrNull()?.let { next ->
                    last.recordedAt == next.recordedAt &&
                        last.latitude == next.latitude &&
                        last.longitude == next.longitude &&
                        next.distanceDelta == 0.0
                }
            } == true
        if (shared)
            db.execSQL(
                "DELETE FROM points WHERE id=(SELECT id FROM points WHERE outingId=? ORDER BY recordedAt LIMIT 1)",
                arrayOf(secondId),
            )
        else
            db.execSQL(
                "UPDATE points SET breakBefore=1,uncertainGap=1 WHERE id=(SELECT id FROM points WHERE outingId=? ORDER BY recordedAt LIMIT 1)",
                arrayOf(secondId),
            )
        for (table in listOf("points", "events", "expenses")) db.execSQL(
            "UPDATE `$table` SET outingId=? WHERE outingId=?",
            arrayOf(firstId, secondId),
        )
        val s = first.state
        val t = second.state
        dao.update(
            first.copy(
                endedAt = second.endedAt,
                classification = "OTHER",
                state =
                    t.copy(
                        meters = s.meters + t.meters,
                        drivingMs = s.drivingMs + t.drivingMs,
                        stoppedMs = s.stoppedMs + t.stoppedMs,
                        universityMs = s.universityMs + t.universityMs,
                        visits = s.visits + t.visits,
                        gapMs = s.gapMs + t.gapMs + second.startedAt - first.endedAt,
                        universityCounted = s.universityCounted || t.universityCounted,
                        homeToUniversityMs = s.homeToUniversityMs + t.homeToUniversityMs,
                    ),
            )
        )
        dao.details(
            a.copy(
                title = a.title.ifBlank { b.title },
                note = note,
                favorite = a.favorite || b.favorite,
            )
        )
        dao.delete(secondId)
        firstId
    }

    suspend fun split(id: Long, time: Long): Long = database.withTransaction {
        val dao = database.dao()
        val trip = dao.outings().first { it.id == id }
        require(trip.endedAt != null && time > trip.startedAt && time < trip.endedAt)
        val points = dao.points(id).first()
        require(kotlin.math.abs(points.sumOf { it.distanceDelta } - trip.state.meters) <= 1.0)
        val boundary = points.indexOfFirst { it.recordedAt == time }
        require(boundary in 1 until points.lastIndex)
        val events = dao.events(id).first()
        val details = dao.details(id) ?: TripDetails(id)
        val old = database.openHelper.writableDatabase
        val second =
            dao.insert(
                trip.copy(id = 0, startedAt = time, automaticEnd = false, classification = "OTHER")
            )
        old.execSQL(
            "UPDATE points SET outingId=? WHERE outingId=? AND recordedAt>?",
            arrayOf(second, id, time),
        )
        dao.point(
            points[boundary].copy(
                id = 0,
                outingId = second,
                distanceDelta = 0.0,
                breakBefore = false,
                uncertainGap = false,
            )
        )
        old.execSQL(
            "UPDATE expenses SET outingId=? WHERE outingId=? AND createdAt>=?",
            arrayOf(second, id, time),
        )
        old.execSQL("DELETE FROM events WHERE outingId=?", arrayOf(id))
        val left = events.mapNotNull { event ->
            val end = minOf(event.endedAt, time)
            if (end > event.startedAt) event.copy(endedAt = end) else null
        }
        val right = events.mapNotNull { event ->
            val start = maxOf(event.startedAt, time)
            if (event.endedAt > start) event.copy(id = 0, outingId = second, startedAt = start)
            else null
        }
        left.forEach { dao.event(it) }
        right.forEach { dao.event(it) }
        fun replay(
            values: List<RoutePoint>,
            end: Long,
            sliced: List<JourneyEvent>,
            keepUniversityTrip: Boolean,
        ): EngineState {
            val engine = TrackingEngine()
            var state = EngineState()
            values.forEach { p ->
                state =
                    engine
                        .accept(
                            state,
                            Fix(
                                p.latitude,
                                p.longitude,
                                p.recordedAt,
                                p.accuracy,
                                p.speed,
                                p.altitude,
                                p.bearing,
                            ),
                            null,
                            null,
                        )
                        .state
            }
            state = engine.end(state, end).state
            val gaps =
                values
                    .zipWithNext()
                    .filter { (_, b) -> b.uncertainGap }
                    .map { (a, b) -> a.recordedAt to b.recordedAt }
            val drive =
                sliced
                    .filter { it.type == "DRIVE" }
                    .sumOf { event ->
                        (event.endedAt -
                                event.startedAt -
                                gaps.sumOf { (start, finish) ->
                                    (minOf(finish, event.endedAt) - maxOf(start, event.startedAt))
                                        .coerceAtLeast(0)
                                })
                            .coerceAtLeast(0)
                    }
            return state.copy(
                meters = values.sumOf { it.distanceDelta },
                drivingMs = drive,
                gapMs = gaps.sumOf { it.second - it.first },
                stoppedMs = sliced.filter { it.type == "STOP" }.sumOf { it.endedAt - it.startedAt },
                universityMs =
                    sliced
                        .filter { it.type == "VISIT" && it.confirmed }
                        .sumOf { it.endedAt - it.startedAt },
                visits = sliced.count { it.type == "VISIT" && it.confirmed },
                universityCounted = keepUniversityTrip,
                homeToUniversityMs =
                    if (keepUniversityTrip)
                        trip.state.homeToUniversityMs.coerceAtMost(end - trip.startedAt)
                    else 0,
            )
        }
        val firstValues = points.take(boundary + 1)
        val secondValues =
            points.drop(boundary).mapIndexed { index, p ->
                if (index == 0)
                    p.copy(distanceDelta = 0.0, breakBefore = false, uncertainGap = false)
                else p
            }
        val firstState =
            replay(
                    firstValues,
                    time,
                    left,
                    trip.state.universityCounted && left.any { it.type == "VISIT" && it.confirmed },
                )
                .let { state -> state.copy(visits = minOf(trip.state.visits, state.visits)) }
        val secondState =
            replay(
                    secondValues,
                    trip.endedAt,
                    right,
                    trip.state.universityCounted && !firstState.universityCounted,
                )
                .copy(visits = trip.state.visits - firstState.visits)
        dao.update(
            trip.copy(
                endedAt = time,
                automaticEnd = false,
                classification = "OTHER",
                state = firstState,
            )
        )
        dao.update(
            trip.copy(
                id = second,
                startedAt = time,
                automaticEnd = false,
                classification = "OTHER",
                state = secondState,
            )
        )
        dao.details(
            details.copy(
                outingId = second,
                title =
                    details.title.take(70).let { if (it.isBlank()) "الجزء الثاني" else "$it — 2" },
            )
        )
        second
    }
}
