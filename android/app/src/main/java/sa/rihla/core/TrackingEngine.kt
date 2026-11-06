package sa.rihla.core

import androidx.room.ColumnInfo
import kotlin.math.*

data class Fix(
    val lat: Double,
    val lon: Double,
    val time: Long,
    val accuracy: Double,
    val speed: Double = 0.0,
    val altitude: Double = 0.0,
    val bearing: Double = 0.0,
    val hasSpeed: Boolean = true,
    val speedAccuracy: Double? = null,
    val elapsedNanos: Long = 0,
    val discontinuous: Boolean = false,
    val provider: String = "",
    val hasBearing: Boolean = true,
)

data class Area(
    val lat: Double,
    val lon: Double,
    val radius: Double,
    val polygon: List<Pair<Double, Double>> = emptyList(),
) {
    fun containsPoint(ptLat: Double, ptLon: Double): Boolean {
        if (polygon.size < 3) return distance(lat, lon, ptLat, ptLon) <= radius
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if (
                (a.second > ptLon) != (b.second > ptLon) &&
                    ptLat <
                        (b.first - a.first) * (ptLon - a.second) / (b.second - a.second) + a.first
            )
                inside = !inside
            j = i
        }
        return inside
    }

    fun contains(f: Fix): Boolean {
        if (polygon.size < 3) return distance(lat, lon, f.lat, f.lon) + f.accuracy <= radius
        return containsPoint(f.lat, f.lon)
    }

    /**
     * Relaxed home arrival check for indoor / shaded parking (carport, ground floor) where
     * GPS accuracy degrades to 40m..160m while stationary (speed ~ 0).
     * Accepts when fix center is inside home boundary OR circles overlap reasonably at zero/low speed.
     */
    fun containsHomeRelaxed(f: Fix): Boolean {
        if (f.accuracy !in 0.1..160.0) return false
        if (containsPoint(f.lat, f.lon)) return true
        val stationaryOrSlow = !f.hasSpeed || f.speed < 1.5
        if (!stationaryOrSlow) return false
        if (polygon.size < 3) {
            val overlapMargin = min(f.accuracy * 0.5, 45.0)
            return distance(lat, lon, f.lat, f.lon) <= radius + overlapMargin
        }
        return polygon.any { (pLat, pLon) -> distance(pLat, pLon, f.lat, f.lon) <= 35.0 }
    }

    /** Approximate distance in meters outside the area boundary (0.0 if inside). */
    fun distanceOutsideBoundaryMeters(ptLat: Double, ptLon: Double): Double {
        if (containsPoint(ptLat, ptLon)) return 0.0
        if (polygon.size < 3) return (distance(lat, lon, ptLat, ptLon) - radius).coerceAtLeast(0.0)
        val minVertex = polygon.minOf { (pLat, pLon) -> distance(pLat, pLon, ptLat, ptLon) }
        val centerDist = (distance(lat, lon, ptLat, ptLon) - radius).coerceAtLeast(0.0)
        return min(minVertex, centerDist)
    }
}

fun distance(a: Double, b: Double, c: Double, d: Double): Double {
    val r = PI / 180
    val h = sin((c - a) * r / 2).pow(2) + cos(a * r) * cos(c * r) * sin((d - b) * r / 2).pow(2)
    return 6371000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    return (((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0)).toFloat()
}

fun arabicCompassDirection(bearingDeg: Float): String =
    when (((bearingDeg + 22.5f) % 360f / 45f).toInt()) {
        0 -> "شمال"
        1 -> "شمال شرق"
        2 -> "شرق"
        3 -> "جنوب شرق"
        4 -> "جنوب"
        5 -> "جنوب غرب"
        6 -> "غرب"
        else -> "شمال غرب"
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
    @ColumnInfo(defaultValue = "NULL") val distanceLat: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val distanceLon: Double? = null,
    @ColumnInfo(defaultValue = "0") val distanceTime: Long = 0,
    @ColumnInfo(defaultValue = "0") val distanceAccuracy: Double = 0.0,
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
    fun accept(
        s: EngineState,
        f: Fix,
        home: Area?,
        university: Area?,
        allowHomeEnd: Boolean = true,
    ): Update {
        val relaxedIndoorHomeFix =
            allowHomeEnd &&
                s.leftHome &&
                home != null &&
                f.accuracy in 60.0001..160.0 &&
                (!f.hasSpeed || f.speed < 1.5) &&
                home.containsHomeRelaxed(f)
        if (
            !f.lat.isFinite() ||
                !f.lon.isFinite() ||
                f.lat !in -90.0..90.0 ||
                f.lon !in -180.0..180.0 ||
                (f.accuracy !in 0.1..60.0 && !relaxedIndoorHomeFix) ||
                !f.speed.isFinite() ||
                f.speed !in 0.0..65.0 ||
                (f.speedAccuracy != null && (!f.speedAccuracy.isFinite() || f.speedAccuracy < 0)) ||
                !f.altitude.isFinite() ||
                !f.bearing.isFinite() ||
                f.time <= s.lastTime
        )
            return Update(s, false)
        val dt = if (s.lastTime == 0L) 0L else f.time - s.lastTime
        val step = if (s.lastLat == null) 0.0 else distance(s.lastLat, s.lastLon!!, f.lat, f.lon)
        if (!relaxedIndoorHomeFix && dt > 0 && step / (dt / 1000.0) > 65.0) return Update(s, false)
        val gap = dt > 90_000 || f.discontinuous
        val speedTrusted = f.hasSpeed && (f.speedAccuracy == null || f.speedAccuracy in 0.0..2.0)
        val anchorLat = s.distanceLat ?: s.lastLat
        val anchorLon = s.distanceLon ?: s.lastLon
        val anchorTime = if (s.distanceTime > 0) s.distanceTime else s.lastTime
        val anchorAccuracy = if (s.distanceTime > 0) s.distanceAccuracy else s.lastAccuracy
        val anchorStep =
            if (anchorLat == null || anchorLon == null) 0.0
            else distance(anchorLat, anchorLon, f.lat, f.lon)
        val anchorDt = (f.time - anchorTime) / 1000.0
        val derivedMoving =
            if (relaxedIndoorHomeFix) false
            else if (!speedTrusted)
                anchorDt in 1.0..90.0 &&
                    anchorStep / anchorDt >= 2.5 &&
                    anchorStep > max(8.0, f.accuracy + anchorAccuracy)
            else
                dt in 8000..90000 &&
                    step / (dt / 1000.0) >= 5.0 &&
                    step > f.accuracy + s.lastAccuracy + 20
        val movement = !relaxedIndoorHomeFix && ((speedTrusted && f.speed >= 1.0) || derivedMoving)
        // The persisted anchor accumulates short steps instead of dropping each one.
        val significant = anchorStep > max(6.0, (f.accuracy + anchorAccuracy) * .5)
        val credit =
            !relaxedIndoorHomeFix &&
                !gap &&
                movement &&
                significant &&
                anchorTime > 0 &&
                f.time - anchorTime <= 90_000
        val holdUnknown =
            !relaxedIndoorHomeFix &&
                !speedTrusted &&
                !movement &&
                s.stopStart == 0L &&
                f.time - anchorTime <= 30_000
        val resetAnchor =
            anchorLat == null ||
                gap ||
                (!movement && !holdUnknown) ||
                credit ||
                f.time - anchorTime > 90_000
        var n =
            s.copy(
                lastLat = if (relaxedIndoorHomeFix && s.lastLat != null) s.lastLat else f.lat,
                lastLon = if (relaxedIndoorHomeFix && s.lastLon != null) s.lastLon else f.lon,
                lastTime = f.time,
                lastAccuracy = f.accuracy,
                meters = s.meters + if (credit) anchorStep else 0.0,
                distanceLat = if (relaxedIndoorHomeFix) anchorLat else if (resetAnchor) f.lat else anchorLat,
                distanceLon = if (relaxedIndoorHomeFix) anchorLon else if (resetAnchor) f.lon else anchorLon,
                distanceTime = if (relaxedIndoorHomeFix) anchorTime else if (resetAnchor) f.time else anchorTime,
                distanceAccuracy = if (relaxedIndoorHomeFix) anchorAccuracy else if (resetAnchor) f.accuracy else anchorAccuracy,
                gapMs = s.gapMs + if (gap && !(s.stopStart > 0 && !movement) && !relaxedIndoorHomeFix) dt else 0L,
                segmentStart = if (s.segmentStart == 0L) f.time else s.segmentStart,
            )
        val events = mutableListOf<Event>()
        val strictAtHome = home?.contains(f) == true
        if (!s.originKnown)
            n =
                n.copy(
                    originKnown = true,
                    startedHome = strictAtHome,
                    startedUniversity = university?.contains(f) == true,
                )
        if (
            !relaxedIndoorHomeFix &&
                home != null &&
                distance(home.lat, home.lon, f.lat, f.lon) > home.radius + min(f.accuracy, 40.0)
        )
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
            val nearby =
                relaxedIndoorHomeFix ||
                    (s.stillSince > 0 && distance(s.stillLat, s.stillLon, f.lat, f.lon) <= 65)
            val since = if (nearby && s.stillSince > 0) s.stillSince else f.time
            val stationaryCredit =
                if (nearby && s.stillSince > 0)
                    s.stillDrivingMs + (if (s.stopStart == 0L && !gap) dt else 0L)
                else 0L
            n =
                n.copy(
                    stillSince = since,
                    stillLat = if (nearby && s.stillSince > 0) s.stillLat else f.lat,
                    stillLon = if (nearby && s.stillSince > 0) s.stillLon else f.lon,
                    stillDrivingMs = stationaryCredit,
                )
            if (s.stopStart == 0L && f.time - since >= 180_000) {
                events += Event("DRIVE", s.segmentStart, since)
                n =
                    n.copy(
                        stopStart = since,
                        drivingMs = (n.drivingMs - stationaryCredit).coerceAtLeast(0),
                    )
            }
            n = n.copy(phase = if (n.stopStart > 0) "TRACKING_STOPPED" else "DRIVING_DETECTED")
        }
        val inUniversity = !relaxedIndoorHomeFix && university?.contains(f) == true
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
        val homeArrivalCandidate =
            allowHomeEnd &&
                !movement &&
                n.leftHome &&
                (strictAtHome || home?.containsHomeRelaxed(f) == true)
        n =
            n.copy(
                homeSince =
                    if (homeArrivalCandidate) {
                        if (s.homeSince > 0L) s.homeSince else f.time
                    } else 0L
            )
        if (homeArrivalCandidate) n = n.copy(phase = "AT_HOME_PENDING_END")
        return Update(
            n,
            true,
            events,
            homeArrivalCandidate && f.time - n.homeSince >= 300_000 && n.stopStart > 0,
        )
    }

    /**
     * Independent watchdog check: allows auto-ending a trip when indoor GPS fixes stop arriving
     * after entering AT_HOME_PENDING_END, or within 60s of Lancer Bluetooth ACL_DISCONNECTED near home.
     */
    fun shouldWatchdogAutoEnd(
        s: EngineState,
        now: Long,
        home: Area?,
        bluetoothDisconnectAt: Long = 0L,
    ): Boolean {
        if (s.phase == "ENDED" || home == null) return false
        // 1. Fast Bluetooth disconnect auto-end within 60 seconds when within homeRadius + 60m
        if (
            bluetoothDisconnectAt > 0L &&
                now - bluetoothDisconnectAt >= 60_000L &&
                s.lastLat != null &&
                s.lastLon != null &&
                (s.leftHome || s.meters >= 100.0) &&
                home.distanceOutsideBoundaryMeters(s.lastLat, s.lastLon) <= 60.0
        ) {
            return true
        }
        // 2. Complete AT_HOME_PENDING_END after 5 minutes even if indoor GPS ceased delivering fixes
        if (s.phase == "AT_HOME_PENDING_END" && s.homeSince > 0L && now - s.homeSince >= 300_000L) {
            return true
        }
        return false
    }

    /**
     * True when the outing has been stationary > 5 min (outside home arrival) or confirmed at UQU,
     * allowing RedMagic 11 Pro to drop into Stationary Deep Sleep Mode.
     */
    fun shouldEnterStationaryDeepSleep(s: EngineState, now: Long): Boolean {
        if (s.phase == "ENDED" || s.phase == "AT_HOME_PENDING_END") return false
        if (s.phase == "AT_UNIVERSITY" && s.visitConfirmed) return true
        if (s.stopStart > 0L && now - s.stopStart >= 300_000L) return true
        return false
    }

    fun end(s: EngineState, now: Long): Update {
        if (s.phase == "ENDED") return Update(s, false)
        val events = mutableListOf<Event>()
        var n = s
        if (s.stopStart > 0) {
            events += Event("STOP", s.stopStart, now, s.stillLat, s.stillLon)
            n = n.copy(stoppedMs = s.stoppedMs + (now - s.stopStart).coerceAtLeast(0))
        } else if (s.segmentStart > 0 && s.lastTime >= s.segmentStart) {
            events += Event("DRIVE", s.segmentStart, s.lastTime)
        }
        if (s.universityArrival > 0) {
            val end = if (s.universityExit > 0) s.universityExit else now
            events += Event("VISIT", s.universityArrival, end, confirmed = s.visitConfirmed)
            n =
                n.copy(
                    universityMs =
                        s.universityMs + if (s.visitConfirmed) (end - s.universityArrival).coerceAtLeast(0) else 0,
                    visits = s.visits + if (s.visitConfirmed) 1 else 0,
                )
        }
        return Update(
            n.copy(phase = "ENDED", stopStart = 0, homeSince = 0, universityArrival = 0, visitConfirmed = false),
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
    if (first.accuracy !in 0.1..40.0 || next.accuracy !in 0.1..40.0 || next.speed !in 0.0..65.0)
        return false
    val dt = next.time - first.time
    if (dt !in 8000L..90000L) return false
    val moved = distance(first.lat, first.lon, next.lat, next.lon)
    val derivedSpeed = moved / (dt / 1000.0)
    val driving =
        next.speed >= 3.0 ||
            (next.speed == 0.0 &&
                derivedSpeed >= 5.0 &&
                moved > first.accuracy + next.accuracy + 20.0)
    return driving && moved >= 60.0 && derivedSpeed <= 65.0
}

/** Rolling evidence window survives waiting at home or a red light after an automatic trigger. */
class DrivingCandidate {
    private var first: Fix? = null
    var startedWaitingAt: Long = 0L
        private set

    fun reset() {
        first = null
        startedWaitingAt = 0L
    }

    fun accept(f: Fix): Fix? {
        if (startedWaitingAt == 0L) startedWaitingAt = f.time
        if (f.accuracy !in 0.1..40.0) return null
        val previous = first
        if (previous == null || f.time - previous.time > 90_000) {
            first = f
            return null
        }
        if (f.time <= previous.time) return null
        return previous.takeIf { drivingEvidence(it, f) }
    }
}
