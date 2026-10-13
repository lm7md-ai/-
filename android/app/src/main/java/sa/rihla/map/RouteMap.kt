package sa.rihla.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.*
import android.view.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*
import kotlinx.coroutines.*
import sa.rihla.R
import sa.rihla.data.*

interface TileProvider {
    fun load(z: Int, x: Int, y: Int): ByteArray?
}

class HybridTiles(private val c: Context) : TileProvider {
    @Volatile var onlineEnabled = false

    private fun offlineTile(file: File, z: Int, x: Int, y: Int): ByteArray? {
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                for (level in z downTo 7) {
                    val factor = 1 shl (z - level)
                    val parentX = x / factor
                    val parentY = y / factor
                    val data =
                        db.rawQuery(
                                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                                arrayOf(
                                    level.toString(),
                                    parentX.toString(),
                                    ((1 shl level) - 1 - parentY).toString(),
                                ),
                            )
                            .use { if (it.moveToFirst()) it.getBlob(0) else null } ?: continue
                    if (level == z) return data
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
                    val size = (bitmap.width / factor).coerceAtLeast(1)
                    val cropped =
                        Bitmap.createBitmap(
                            bitmap,
                            ((x % factor) * bitmap.width / factor).coerceAtMost(
                                bitmap.width - size
                            ),
                            ((y % factor) * bitmap.height / factor).coerceAtMost(
                                bitmap.height - size
                            ),
                            size,
                            size,
                        )
                    val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
                    val bytes = java.io.ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                    if (scaled !== cropped) scaled.recycle()
                    if (cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                    return bytes.toByteArray()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun load(z: Int, x: Int, y: Int): ByteArray? {
        val offline = File(c.filesDir, "offline.mbtiles")
        offlineTile(offline, z, x, y)?.let {
            return it
        }
        runCatching { OfflineMapStore.bundled(c) }
            .getOrNull()
            ?.let { bundled ->
                offlineTile(bundled, z, x, y)?.let {
                    return it
                }
            }
        val dir = File(c.filesDir, "tiles").apply { mkdirs() }
        val file = File(dir, "$z-$x-$y.png")
        val expires = File(dir, "$z-$x-$y.expiry")
        val expiry = expires.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0
        if (file.exists() && expiry > System.currentTimeMillis()) return file.readBytes()
        if (!onlineEnabled) return if (file.exists()) file.readBytes() else null
        return try {
            val connection =
                URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection()
                    as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty(
                "User-Agent",
                "RihlaAndroid/1.0 (personal journey recorder; https://github.com/m7mdxzx9/-)",
            )
            if (file.exists()) connection.ifModifiedSince = file.lastModified()
            try {
                if (connection.responseCode == 304) {
                    expires.writeText((System.currentTimeMillis() + 604800000).toString())
                    return file.readBytes()
                }
                if (connection.responseCode != 200)
                    return if (file.exists()) file.readBytes() else null
                val bytes = connection.inputStream.use { it.readBytes() }
                if (
                    bytes.size > 2_000_000 ||
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null
                )
                    return null
                val maxAge =
                    Regex("max-age=(\\d+)")
                        .find(connection.getHeaderField("Cache-Control") ?: "")
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                        ?.times(1000) ?: 604800000
                file.writeBytes(bytes)
                expires.writeText(
                    (System.currentTimeMillis() + maxAge.coerceAtLeast(604800000)).toString()
                )
                bytes
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            if (file.exists()) file.readBytes() else null
        }
    }
}

class RouteMapView(c: Context) : View(c) {
    private val provider = HybridTiles(c)

    fun online(enabled: Boolean) {
        if (provider.onlineEnabled != enabled) {
            provider.onlineEnabled = enabled
            failed.clear()
            invalidate()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val tiles = android.util.LruCache<String, Bitmap>(80)
    private val loading = mutableSetOf<String>()
    private val failed = mutableMapOf<String, Long>()
    var points: List<RoutePoint> = emptyList()
    var events: List<JourneyEvent> = emptyList()
    var places: List<SavedPlace> = emptyList()
    var selected: Pair<Double, Double>? = null
    var lastSelection: Pair<Double, Double>? = null
    var polygonDraft: List<Pair<Double, Double>> = emptyList()
    var onPick: ((Double, Double) -> Unit)? = null
    private var zoom = 11
    private val tilePixels = 256.0 * resources.displayMetrics.density
    private var lat = 21.55
    private var lon = 39.6
    private var centered = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val destinationRect = RectF()
    private val routePath = Path()
    private val areaPath = Path()
    private val gestures =
        GestureDetector(
            c,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    val p = world(lat, lon)
                    setCenter(p.first + dx, p.second + dy)
                    invalidate()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    zoom = (zoom + 1).coerceAtMost(18)
                    invalidate()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val p = world(lat, lon)
                    val coord = coordinate(p.first + e.x - width / 2, p.second + e.y - height / 2)
                    onPick?.invoke(coord.first, coord.second)
                    return true
                }
            },
        )
    private val scale =
        ScaleGestureDetector(
            c,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                var factor = 1f

                override fun onScale(d: ScaleGestureDetector): Boolean {
                    factor *= d.scaleFactor
                    if (factor > 1.4f) {
                        zoom = (zoom + 1).coerceAtMost(18)
                        factor = 1f
                    } else if (factor < 0.7f) {
                        zoom = (zoom - 1).coerceAtLeast(7)
                        factor = 1f
                    }
                    invalidate()
                    return true
                }
            },
        )

    fun center(a: Double, b: Double) {
        lat = a
        lon = b
        centered = true
        invalidate()
    }

    fun fit() {
        if (points.isNotEmpty() && !centered && width > 0 && height > 0) {
            val south = points.minOf { it.latitude }
            val north = points.maxOf { it.latitude }
            val west = points.minOf { it.longitude }
            val east = points.maxOf { it.longitude }
            lat = (south + north) / 2
            lon = (west + east) / 2
            for (level in 16 downTo 7) {
                zoom = level
                val a = world(north, west)
                val b = world(south, east)
                if (b.first - a.first <= width * .8 && b.second - a.second <= height * .8) break
            }
            centered = true
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun world(a: Double, b: Double): Pair<Double, Double> {
        val n = tilePixels * (1 shl zoom)
        val r = a.coerceIn(-85.0, 85.0) * PI / 180
        return ((b + 180) / 360 * n) to ((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n)
    }

    private fun coordinate(x: Double, y: Double): Pair<Double, Double> {
        val n = tilePixels * (1 shl zoom)
        return (atan(sinh(PI * (1 - 2 * y / n))) * 180 / PI) to (x / n * 360 - 180)
    }

    private fun setCenter(x: Double, y: Double) {
        val v = coordinate(x, y)
        lat = v.first.coerceIn(-85.0, 85.0)
        lon = v.second.coerceIn(-180.0, 180.0)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.action == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(228, 235, 233))
        val center = world(lat, lon)
        val left = center.first - width / 2
        val top = center.second - height / 2
        var drawn = false
        for (x in
            floor(left / tilePixels).toInt()..floor((left + width) / tilePixels).toInt()) for (y in
            floor(top / tilePixels).toInt()..floor((top + height) / tilePixels).toInt()) {
            if (x !in 0 until (1 shl zoom) || y !in 0 until (1 shl zoom)) continue
            val key = "$zoom/$x/$y"
            val bitmap = tiles.get(key)
            if (bitmap != null) {
                destinationRect.set(
                    (x * tilePixels - left).toFloat(),
                    (y * tilePixels - top).toFloat(),
                    ((x + 1) * tilePixels - left).toFloat(),
                    ((y + 1) * tilePixels - top).toFloat(),
                )
                canvas.drawBitmap(bitmap, null, destinationRect, paint)
                drawn = true
            } else if (key !in loading && System.currentTimeMillis() - (failed[key] ?: 0) > 30000) {
                loading += key
                val z = zoom
                scope.launch {
                    val bytes = provider.load(z, x, y)
                    val b = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    withContext(Dispatchers.Main) {
                        loading -= key
                        if (b != null) tiles.put(key, b)
                        else failed[key] = System.currentTimeMillis()
                        invalidate()
                    }
                }
            }
        }
        fun screen(a: Double, b: Double): Pair<Float, Float> {
            val p = world(a, b)
            return (p.first - left).toFloat() to (p.second - top).toFloat()
        }
        if (!drawn) {
            paint.color = Color.DKGRAY
            paint.textSize = 32f
            canvas.drawText(context.getString(R.string.map_unavailable), 20f, 45f, paint)
        }
        paint.color = Color.rgb(16, 118, 106)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        val route = routePath
        route.reset()
        points.forEachIndexed { i, p ->
            val v = screen(p.latitude, p.longitude)
            if (i == 0 || p.breakBefore) route.moveTo(v.first, v.second)
            else route.lineTo(v.first, v.second)
        }
        canvas.drawPath(route, paint)
        places.forEach { p ->
            val xy = screen(p.latitude, p.longitude)
            paint.color = if (p.type == "HOME") Color.rgb(33, 95, 176) else Color.rgb(133, 77, 14)
            paint.strokeWidth = 3f
            val polygon =
                p.polygon.split(';').mapNotNull { s ->
                    val parts = s.split(',')
                    if (parts.size == 2)
                        parts[0].toDoubleOrNull()?.let { a ->
                            parts[1].toDoubleOrNull()?.let { b -> screen(a, b) }
                        }
                    else null
                }
            if (polygon.size >= 3) {
                val path = areaPath
                path.reset()
                polygon.forEachIndexed { i, v ->
                    if (i == 0) path.moveTo(v.first, v.second) else path.lineTo(v.first, v.second)
                }
                path.close()
                canvas.drawPath(path, paint)
            } else {
                val radius =
                    (p.radius / (156543.03392 * cos(p.latitude * PI / 180) / (1 shl zoom)) *
                            resources.displayMetrics.density)
                        .toFloat()
                canvas.drawCircle(xy.first, xy.second, radius, paint)
            }
        }
        if (polygonDraft.isNotEmpty()) {
            val path = areaPath
            path.reset()
            polygonDraft.forEachIndexed { i, v ->
                val p = screen(v.first, v.second)
                if (i == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second)
            }
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        fun marker(a: Double, b: Double, color: Int) {
            val p = screen(a, b)
            paint.color = Color.WHITE
            canvas.drawCircle(p.first, p.second, 12f, paint)
            paint.color = color
            canvas.drawCircle(p.first, p.second, 8f, paint)
        }
        points.firstOrNull()?.let { marker(it.latitude, it.longitude, Color.rgb(20, 128, 74)) }
        points.lastOrNull()?.let { marker(it.latitude, it.longitude, Color.rgb(31, 82, 195)) }
        events
            .filter { it.type == "STOP" }
            .forEach { marker(it.latitude, it.longitude, Color.rgb(208, 128, 16)) }
        selected?.let { marker(it.first, it.second, Color.RED) }
        paint.color = Color.argb(225, 255, 255, 255)
        canvas.drawRect(0f, height - 42f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.DKGRAY
        paint.textSize = 22f
        canvas.drawText("© OpenStreetMap contributors", 12f, height - 14f, paint)
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }
}

@Composable
fun RouteMap(
    points: List<RoutePoint>,
    events: List<JourneyEvent>,
    places: List<SavedPlace>,
    modifier: Modifier = Modifier,
    selected: Pair<Double, Double>? = null,
    polygon: List<Pair<Double, Double>> = emptyList(),
    onPick: ((Double, Double) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences by remember {
        (context.applicationContext as sa.rihla.RihlaApp).settings.flow
    }
        .collectAsState(initial = sa.rihla.data.Preferences())
    AndroidView(
        factory = { RouteMapView(it) },
        modifier = modifier,
        update = { v ->
            v.points = points
            v.events = events
            v.places = places
            v.selected = selected
            v.polygonDraft = polygon
            v.onPick = onPick
            v.online(preferences.onlineMaps)
            v.fit()
            if (points.isEmpty() && selected != null && v.lastSelection != selected) {
                v.center(selected.first, selected.second)
                v.lastSelection = selected
            }
            v.invalidate()
        },
    )
}
