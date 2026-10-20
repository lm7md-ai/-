package sa.rihla.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.zip.GZIPInputStream

data class RoadFeature(val kind: String, val name: String, val points: List<Pair<Double, Double>>)

data class MapPlace(val name: String, val latitude: Double, val longitude: Double)

data class CountryScene(
    val roads: List<RoadFeature>,
    val labels: List<MapPlace>,
    val clipped: Boolean,
)

object SaudiMap {
    private val lock = Any()

    fun file(context: Context): File =
        synchronized(lock) {
            val target = File(context.noBackupFilesDir, "saudi-roads-20261012-v4.db")
            if (!target.exists()) {
                val pending = File(context.noBackupFilesDir, target.name + ".pending")
                try {
                    GZIPInputStream(context.assets.open("maps/saudi-roads.pack")).use { input ->
                        pending.outputStream().use { input.copyTo(it) }
                    }
                    SQLiteDatabase.openDatabase(pending.path, null, SQLiteDatabase.OPEN_READONLY)
                        .use { db ->
                            db.rawQuery("PRAGMA quick_check", null).use {
                                require(it.moveToFirst() && it.getString(0) == "ok")
                            }
                        }
                    check(pending.renameTo(target))
                } finally {
                    pending.delete()
                }
            }
            target
        }

    fun search(context: Context, query: String): List<MapPlace> {
        if (query.trim().length < 2) return emptyList()
        return SQLiteDatabase.openDatabase(file(context).path, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                db.rawQuery(
                        "SELECT name,latitude,longitude FROM settlements WHERE instr(name,?)>0 ORDER BY LENGTH(name),name LIMIT 20",
                        arrayOf(query.trim()),
                    )
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(
                                MapPlace(
                                    cursor.getString(0),
                                    cursor.getDouble(1),
                                    cursor.getDouble(2),
                                )
                            )
                        }
                    }
            }
    }

    /** Raw geometry at all display levels, bounded to a small viewport and with an explicit cap. */
    fun matchingRoads(
        context: Context,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): CountryScene {
        if (east - west > .3 || north - south > .3)
            return CountryScene(emptyList(), emptyList(), true)
        return SQLiteDatabase.openDatabase(file(context).path, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                val roads =
                    db.rawQuery(
                            "SELECT r.kind,r.name,r.points FROM roads r WHERE " +
                                gridPredicate(west, south, east, north, 18) +
                                "r.kind!='coast' AND r.west<=? AND r.east>=? AND r.south<=? AND r.north>=? ORDER BY r.id LIMIT 8001",
                            arrayOf(
                                (east * 1e6).toString(),
                                (west * 1e6).toString(),
                                (north * 1e6).toString(),
                                (south * 1e6).toString(),
                            ),
                        )
                        .use { c ->
                            buildList {
                                while (c.moveToNext()) add(
                                    RoadFeature(
                                        c.getString(0),
                                        c.getString(1),
                                        decodeRoad(c.getString(2)),
                                    )
                                )
                            }
                        }
                CountryScene(
                    if (roads.size > 8000) emptyList() else roads,
                    emptyList(),
                    roads.size > 8000,
                )
            }
    }

    fun scene(
        context: Context,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        zoom: Int,
    ): CountryScene =
        SQLiteDatabase.openDatabase(file(context).path, null, SQLiteDatabase.OPEN_READONLY).use { db
            ->
            val limit = if (zoom <= 9) 50000 else 6000
            val roads =
                db.rawQuery(
                        "SELECT r.kind,r.name,r.points FROM roads r WHERE " +
                            gridPredicate(west, south, east, north, zoom) +
                            " r.west<=? AND r.east>=? AND r.south<=? AND r.north>=? AND r.minzoom<=? ORDER BY r.minzoom,r.id LIMIT ?",
                        arrayOf(
                            (east * 1e6).toString(),
                            (west * 1e6).toString(),
                            (north * 1e6).toString(),
                            (south * 1e6).toString(),
                            zoom.toString(),
                            (limit + 1).toString(),
                        ),
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                add(
                                    RoadFeature(
                                        c.getString(0),
                                        c.getString(1),
                                        simplifyDisplay(decodeRoad(c.getString(2)), zoom),
                                    )
                                )
                            }
                        }
                    }
            val labels =
                db.rawQuery(
                        "SELECT name,latitude,longitude FROM settlements WHERE longitude>=? AND longitude<=? AND latitude>=? AND latitude<=? ORDER BY CASE WHEN name IN ('الرياض','جدة','مكة المكرمة','مكة','المدينة المنورة','الدمام','تبوك','جازان','عرعر','نجران','بريدة','أبها','حائل') THEN 0 ELSE 1 END,LENGTH(name),id LIMIT ?",
                        arrayOf(
                            west.toString(),
                            east.toString(),
                            south.toString(),
                            north.toString(),
                            if (zoom < 10) "20" else "60",
                        ),
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext()) add(
                                MapPlace(c.getString(0), c.getDouble(1), c.getDouble(2))
                            )
                        }
                    }
            CountryScene(roads.take(limit), labels, roads.size > limit)
        }
}

private fun gridPredicate(
    west: Double,
    south: Double,
    east: Double,
    north: Double,
    zoom: Int,
): String {
    if (zoom <= 9) return ""
    fun x(lon: Double) = ((lon + 180) / 360 * 256).toInt().coerceIn(0, 255)
    fun y(lat: Double) =
        ((1 -
                kotlin.math.asinh(kotlin.math.tan(Math.toRadians(lat.coerceIn(-85.0, 85.0)))) /
                    Math.PI) / 2 * 256)
            .toInt()
            .coerceIn(0, 255)
    val cells =
        (x(west)..x(east)).flatMap { col -> (y(north)..y(south)).map { row -> row * 256 + col } }
    // Values come only from bounded numeric viewport coordinates, never user text.
    return "r.id IN (SELECT roadId FROM road_cells WHERE cell IN (${cells.joinToString()})) AND "
}

private fun simplifyDisplay(
    points: List<Pair<Double, Double>>,
    zoom: Int,
): List<Pair<Double, Double>> {
    if (zoom >= 14 || points.size <= 2) return points
    val epsilon = 360.0 / (256 * (1 shl zoom)) * .4
    return buildList {
        add(points.first())
        points.drop(1).dropLast(1).forEach { point ->
            if (
                kotlin.math.hypot(point.first - last().first, point.second - last().second) >=
                    epsilon
            )
                add(point)
        }
        add(points.last())
    }
}

internal fun decodeRoad(encoded: String): List<Pair<Double, Double>> {
    var cursor = 0
    var latitude = 0
    var longitude = 0
    fun component(): Int {
        var value = 0
        var shift = 0
        var next: Int
        do {
            require(cursor < encoded.length && shift <= 30)
            next = encoded[cursor++].code - 63
            require(next in 0..63)
            value = value or ((next and 31) shl shift)
            shift += 5
        } while (next >= 32)
        return if (value and 1 != 0) (value shr 1).inv() else value shr 1
    }
    return buildList {
        while (cursor < encoded.length) {
            latitude += component()
            longitude += component()
            add(latitude / 1_000_000.0 to longitude / 1_000_000.0)
        }
    }
}

data class MapLayers(
    val route: Boolean = true,
    val stops: Boolean = true,
    val places: Boolean = true,
    val speed: Boolean = false,
    val accuracy: Boolean = true,
)

class MapCamera {
    var view: RouteMapView? = null

    fun navigate(lat: Double, lon: Double) {
        view?.navigate(lat, lon)
    }

    fun zoom(delta: Int) {
        view?.changeZoom(delta)
    }

    fun fit() {
        view?.fitRoute()
    }

    fun center(lat: Double, lon: Double) {
        view?.center(lat, lon)
    }

    fun inspect(index: Int) {
        view?.selectRecordedPoint(index)
    }

    fun country() {
        view?.countryOverview()
    }
}
