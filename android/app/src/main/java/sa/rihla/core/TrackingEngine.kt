package sa.rihla.core

import kotlin.math.*

data class Fix(
    val lat: Double,
    val lon: Double,
    val time: Long,
    val accuracy: Double,
    val speed: Double = 0.0,
    val altitude: Double = 0.0,
    val bearing: Double = 0.0,
)

data class Area(
    val lat: Double,
    val lon: Double,
    val radius: Double,
    val polygon: List<Pair<Double, Double>> = emptyList(),
) {
    fun contains(f: Fix): Boolean {
        if (polygon.size < 3) return distance(lat, lon, f.lat, f.lon) + f.accuracy <= radius
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if (
                (a.second > f.lon) != (b.second > f.lon) &&
                    f.lat <
                        (b.first - a.first) * (f.lon - a.second) / (b.second - a.second) + a.first
            )
                inside = !inside
            j = i
        }
        return inside
    }
}

fun distance(a: Double, b: Double, c: Double, d: Double): Double {
    val r = PI / 180
    val h = sin((c - a) * r / 2).pow(2) + cos(a * r) * cos(c * r) * sin((d - b) * r / 2).pow(2)
    return 6371000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

data class EngineState(
    val phase: String = "DRIVING_DETECTED",
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastTime: Long = 0,
    val lastAccuracy: Double = 0.0,
    val meters: Double = 0.0,
    val drivingMs: Long = 0,
    val stoppedMs: Long = 0,
    val segmentStart: Long = 0,
    val stillSince: Long = 0,
    val stillLat: Double = 0.0,
    val stillLon: Double = 0.0,
    val stopStart: Long = 0,
    val homeSince: Long = 0,
    val universityArrival: Long = 0,
    val universityExit: Long = 0,
    val visitConfirmed: Boolean = false,
    val startedHome: Boolean = false,
    val originKnown: Boolean = false,
    val leftHome: Boolean = false,
    val universityCounted: Boolean = false,
    val homeToUniversityMs: Long = 0,
    val universityMs: Long = 0,
    val visits: Int = 0,
    val gapMs: Long = 0,
    val startedUniversity: Boolean = false,
    val stillDrivingMs: Long = 0,
)

data class Event(
    val type: String,
    val start: Long,
    val end: Long,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val confirmed: Boolean = false,
)

data class Update(
    val state: EngineState,
    val accepted: Boolean,
    val events: List<Event> = emptyList(),
    val autoEnd: Boolean = false,
)

class TrackingEngine {
    fun accept(s: EngineState, f: Fix, home: Area?, university: Area?): Update {
        if (
            !f.lat.isFinite() ||
                !f.lon.isFinite() ||
                f.lat !in -90.0..90.0 ||
                f.lon !in -180.0..180.0 ||
                f.accuracy !in 0.1..60.0 ||
                f.time <= s.lastTime
        )
            return Update(s, false)
        val dt = if (s.lastTime == 0L) 0L else f.time - s.lastTime
        val step = if (s.lastLat == null) 0.0 else distance(s.lastLat, s.lastLon!!, f.lat, f.lon)
        if (dt > 0 && step / (dt / 1000.0) > 65.0) return Update(s, false)
        val gap = dt > 90_000
        val movement =
            (f.speed >= 2.5 || (dt in 1000..90000 && step / max(dt / 1000.0, 1.0) >= 2.5))
        val significant = step > max(6.0, (f.accuracy + s.lastAccuracy) * 0.5)
        var n =
            s.copy(
                lastLat = f.lat,
                lastLon = f.lon,
                lastTime = f.time,
                lastAccuracy = f.accuracy,
                meters = s.meters + if (!gap && significant && movement) step else 0.0,
                gapMs = s.gapMs + if (gap && !(s.stopStart > 0 && !movement)) dt else 0L,
                segmentStart = if (s.segmentStart == 0L) f.time else s.segmentStart,
            )
        val events = mutableListOf<Event>()
        val atHome = home?.contains(f) == true
        if (!s.originKnown)
            n =
                n.copy(
                    originKnown = true,
                    startedHome = atHome,
                    startedUniversity = university?.contains(f) == true,
                )
        if (home != null && distance(home.lat, home.lon, f.lat, f.lon) > home.radius + f.accuracy)
            n = n.copy(leftHome = true)
        if (movement) {
            if (s.stopStart > 0) {
                events += Event("STOP", s.stopStart, f.time, s.stillLat, s.stillLon)
                n = n.copy(stoppedMs = s.stoppedMs + f.time - s.stopStart, segmentStart = f.time)
            } else if (!gap) n = n.copy(drivingMs = s.drivingMs + dt)
            n =
                n.copy(
                    stillSince = 0,
                    stillDrivingMs = 0,
                    stopStart = 0,
                    homeSince = 0,
                    phase = "TRACKING_DRIVING",
                )
        } else {
            if (s.stopStart == 0L && !gap) n = n.copy(drivingMs = s.drivingMs + dt)
            val nearby = s.stillSince > 0 && distance(s.stillLat, s.stillLon, f.lat, f.lon) <= 50
            val since = if (nearby) s.stillSince else f.time
            val stationaryCredit =
                if (nearby) s.stillDrivingMs + (if (s.stopStart == 0L && !gap) dt else 0L) else 0L
            n =
                n.copy(
                    stillSince = since,
                    stillLat = if (nearby) s.stillLat else f.lat,
                    stillLon = if (nearby) s.stillLon else f.lon,
                    stillDrivingMs = stationaryCredit,
                )
            if (s.stopStart == 0L && f.time - since >= 180_000) {
                events += Event("DRIVE", s.segmentStart, since)
                n =
                    n.copy(
                        stopStart = since,
                        // Roll back only time actually credited; a GPS gap must not erase previous
                        // driving.
                        drivingMs = (n.drivingMs - stationaryCredit).coerceAtLeast(0),
                    )
            }
            n = n.copy(phase = if (n.stopStart > 0) "TRACKING_STOPPED" else "DRIVING_DETECTED")
        }
        val inUniversity = university?.contains(f) == true
        if (inUniversity) {
            n =
                n.copy(
                    universityArrival =
                        if (s.universityArrival == 0L) f.time else s.universityArrival,
                    universityExit = 0,
                )
            if (!s.visitConfirmed && f.time - n.universityArrival >= 120_000) {
                n =
                    n.copy(
                        visitConfirmed = true,
                        universityCounted =
                            s.universityCounted || (n.startedHome && n.meters >= 100),
                        homeToUniversityMs =
                            if (!s.universityCounted && n.startedHome && n.meters >= 100)
                                n.drivingMs
                            else s.homeToUniversityMs,
                    )
            }
            n = n.copy(phase = "AT_UNIVERSITY")
        } else if (s.universityArrival > 0) {
            val exit = if (s.universityExit == 0L) f.time else s.universityExit
            n = n.copy(universityExit = exit)
            if (f.time - exit >= 300_000) {
                events += Event("VISIT", s.universityArrival, exit, confirmed = s.visitConfirmed)
                n =
                    n.copy(
                        universityArrival = 0,
                        universityExit = 0,
                        visitConfirmed = false,
                        visits = s.visits + if (s.visitConfirmed) 1 else 0,
                        universityMs =
                            s.universityMs +
                                if (s.visitConfirmed) exit - s.universityArrival else 0,
                    )
            }
        }
        val endCandidate = atHome && !movement && n.leftHome
        n =
            n.copy(
                homeSince =
                    if (endCandidate) (if (s.homeSince == 0L || gap) f.time else s.homeSince) else 0
            )
        if (endCandidate) n = n.copy(phase = "AT_HOME_PENDING_END")
        return Update(
            n,
            true,
            events,
            endCandidate && f.time - n.homeSince >= 300_000 && n.stopStart > 0,
        )
    }

    fun end(s: EngineState, now: Long): Update {
        if (s.phase == "ENDED") return Update(s, false)
        val events = mutableListOf<Event>()
        var n = s
        if (s.stopStart > 0) {
            events += Event("STOP", s.stopStart, now, s.stillLat, s.stillLon)
            n = n.copy(stoppedMs = s.stoppedMs + now - s.stopStart)
        } else if (s.segmentStart > 0) events += Event("DRIVE", s.segmentStart, s.lastTime)
        if (s.universityArrival > 0) {
            val end = if (s.universityExit > 0) s.universityExit else now
            events += Event("VISIT", s.universityArrival, end, confirmed = s.visitConfirmed)
            n =
                n.copy(
                    universityMs =
                        s.universityMs + if (s.visitConfirmed) end - s.universityArrival else 0,
                    visits = s.visits + if (s.visitConfirmed) 1 else 0,
                )
        }
        return Update(
            n.copy(phase = "ENDED", stopStart = 0, universityArrival = 0, visitConfirmed = false),
            true,
            events,
        )
    }
}

fun parsePolygon(text: String): List<Pair<Double, Double>> =
    text.split(';').mapNotNull { token ->
        val parts = token.split(',')
        if (parts.size != 2) return@mapNotNull null
        val a = parts[0].toDoubleOrNull() ?: return@mapNotNull null
        val b = parts[1].toDoubleOrNull() ?: return@mapNotNull null
        if (a !in -90.0..90.0 || b !in -180.0..180.0) return@mapNotNull null
        a to b
    }

fun drivingEvidence(first: Fix, next: Fix): Boolean {
    if (first.accuracy !in 0.1..40.0 || next.accuracy !in 0.1..40.0 || next.speed !in 3.0..65.0)
        return false
    val dt = next.time - first.time
    if (dt !in 8000L..90000L) return false
    val moved = distance(first.lat, first.lon, next.lat, next.lon)
    return moved >= 60.0 && moved / (dt / 1000.0) <= 65.0
}
