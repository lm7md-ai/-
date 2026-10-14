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
import sa.rihla.core.StopDetails
import sa.rihla.core.hitStop
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
                                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? AND length(tile_data)<=2000000",
                                arrayOf(
                                    level.toString(),
                                    parentX.toString(),
                                    ((1 shl level) - 1 - parentY).toString(),
                                ),
                            )
                            .use { if (it.moveToFirst()) it.getBlob(0) else null } ?: continue
                    if (!TileImages.valid(data)) return null
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
        runCatching { OfflineMapStore.existing(c) }
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
                val bytes = connection.inputStream.use { TileImages.read(it) }
                if (!TileImages.valid(bytes)) return null
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

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val tiles = android.util.LruCache<String, Bitmap>(80)
    private val loading = mutableSetOf<String>()
    private val failed = mutableMapOf<String, Long>()
    var points: List<RoutePoint> = emptyList()
    var events: List<JourneyEvent> = emptyList()
    var stops: List<StopDetails> = emptyList()
    var onStopPick: ((StopDetails) -> Unit)? = null
    var places: List<SavedPlace> = emptyList()
    var selected: Pair<Double, Double>? = null
    var lastSelection: Pair<Double, Double>? = null
    var polygonDraft: List<Pair<Double, Double>> = emptyList()
    var onPick: ((Double, Double) -> Unit)? = null
    var pickStopsWithSelection = false
    var layers = MapLayers()
    var measurement: List<Pair<Double, Double>> = emptyList()
    private var country: CountryScene? = null
    private var countryViewport: List<Double> = emptyList()
    private var loadedViewport: List<Double> = emptyList()
    val isCountryReady: Boolean
        get() = country != null && loadedViewport == countryViewport

    private var countryJob: Job? = null
    private val countryPaths = linkedMapOf<String, Path>()
    private var renderedCountry: CountryScene? = null
    private var renderedViewport: List<Double> = emptyList()
    private var countryFailed = false
    private var zoom = 11
    private val tilePixels = 256.0 * resources.displayMetrics.density
    private var lat = 21.55
    private var lon = 39.6
    private var centered = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val destinationRect = RectF()
    private val routePath = Path()
    private var routePoints: List<RoutePoint>? = null
    private var routeViewport: List<Double> = emptyList()
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
                    if (
                        onStopPick != null &&
                            layers.stops &&
                            (onPick == null || pickStopsWithSelection)
                    ) {
                        val stop =
                            hitStop(stops, e.x, e.y, 24f * resources.displayMetrics.density) { a, b
                                ->
                                val marker = world(a, b)
                                (marker.first - p.first + width / 2).toFloat() to
                                    (marker.second - p.second + height / 2).toFloat()
                            }
                        if (stop != null) {
                            onStopPick?.invoke(stop)
                            return true
                        }
                    }
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
                        zoom = (zoom - 1).coerceAtLeast(4)
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

    fun changeZoom(delta: Int) {
        zoom = (zoom + delta).coerceIn(4, 18)
        centered = true
        invalidate()
    }

    fun countryOverview() {
        zoom = 4
        center(24.5, 45.0)
    }

    fun navigate(a: Double, b: Double) {
        zoom = 13
        center(a, b)
    }

    fun fitRoute() {
        centered = false
        fit()
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
        canvas.drawColor(Color.rgb(243, 239, 230))
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
                    val b = bytes?.takeIf(TileImages::valid)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
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
        val viewportKey = listOf(lat, lon, zoom.toDouble(), width.toDouble(), height.toDouble())
        if (countryViewport != viewportKey && width > 0 && height > 0) {
            countryViewport = viewportKey
            countryJob?.cancel()
            val nw = coordinate(left, top)
            val se = coordinate(left + width, top + height)
            val level = zoom
            countryJob = scope.launch {
                delay(120)
                val scene = runCatching {
                    SaudiMap.scene(
                        context,
                        nw.second,
                        se.first,
                        se.second,
                        nw.first,
                        level.coerceAtLeast(5),
                    )
                }
                    .getOrNull()
                withContext(Dispatchers.Main) {
                    if (countryViewport == viewportKey) {
                        country = scene
                        loadedViewport = viewportKey
                        countryFailed = scene == null
                        invalidate()
                    }
                }
            }
        }
        country?.let { scene ->
            paint.style = Paint.Style.STROKE
            if (renderedCountry !== scene || renderedViewport != viewportKey) {
                countryPaths.clear()
                scene.roads.asReversed().forEach { road ->
                    val path = countryPaths.getOrPut(road.kind) { Path() }
                    road.points.forEachIndexed { i, point ->
                        val p = screen(point.first, point.second)
                        if (i == 0) path.moveTo(p.first, p.second)
                        else path.lineTo(p.first, p.second)
                    }
                }
                renderedCountry = scene
                renderedViewport = viewportKey
            }
            countryPaths.forEach { (kind, path) ->
                paint.color =
                    when (kind) {
                        "coast" -> Color.rgb(92, 157, 182)
                        "motorway",
                        "trunk" -> Color.rgb(218, 156, 65)
                        "primary" -> Color.rgb(225, 184, 100)
                        else -> Color.WHITE
                    }
                paint.strokeWidth =
                    (if (kind in listOf("motorway", "trunk")) 3f else 1.6f) *
                        resources.displayMetrics.density
                canvas.drawPath(path, paint)
            }
            if (zoom >= 15)
                scene.roads.forEach { road ->
                    if (zoom >= 15 && road.name.isNotBlank() && road.points.size > 1) {
                        val p = road.points[road.points.size / 2]
                        val xy = screen(p.first, p.second)
                        if (xy.first in 15f..width - 15f && xy.second in 30f..height - 60f) {
                            paint.style = Paint.Style.FILL
                            paint.color = Color.rgb(80, 74, 65)
                            paint.textSize = 10f * resources.displayMetrics.density
                            canvas.drawText(road.name.take(30), xy.first, xy.second, paint)
                            paint.style = Paint.Style.STROKE
                        }
                    }
                }
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(45, 62, 71)
            paint.textSize = 13f * resources.displayMetrics.density
            scene.labels.forEach { label ->
                val p = screen(label.latitude, label.longitude)
                canvas.drawText(label.name, p.first, p.second, paint)
            }
            if (scene.clipped) {
                paint.textSize = 10f * resources.displayMetrics.density
                canvas.drawText("كبّر لعرض تفاصيل إضافية", 16f, height - 100f, paint)
            }
        }
        if (!drawn && country == null) {
            paint.color = Color.DKGRAY
            paint.textSize = 32f
            canvas.drawText(
                if (countryFailed) context.getString(R.string.map_unavailable)
                else "تجهيز خريطة السعودية محليًا…",
                20f,
                45f,
                paint,
            )
        }
        paint.color = Color.rgb(16, 118, 106)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        val route = routePath
        val viewport = listOf(left, top, zoom.toDouble())
        if (routePoints !== points || routeViewport != viewport) {
            route.reset()
            points.forEachIndexed { i, p ->
                val v = screen(p.latitude, p.longitude)
                if (i == 0 || p.breakBefore) route.moveTo(v.first, v.second)
                else route.lineTo(v.first, v.second)
            }
            routePoints = points
            routeViewport = viewport
        }
        if (layers.route) {
            if (layers.speed)
                points.zipWithNext().forEach { (a, b) ->
                    if (!b.breakBefore) {
                        val first = screen(a.latitude, a.longitude)
                        val second = screen(b.latitude, b.longitude)
                        paint.color =
                            if (b.speed * 3.6 < 30) Color.rgb(49, 111, 224)
                            else if (b.speed * 3.6 < 80) Color.rgb(16, 160, 132)
                            else Color.rgb(228, 139, 48)
                        canvas.drawLine(
                            first.first,
                            first.second,
                            second.first,
                            second.second,
                            paint,
                        )
                    }
                }
            else canvas.drawPath(route, paint)
        }
        (if (layers.places) places else emptyList()).forEach { p ->
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
        (if (layers.stops) events else emptyList())
            .filter { it.type == "STOP" }
            .forEach { marker(it.latitude, it.longitude, Color.rgb(208, 128, 16)) }
        (if (layers.stops) stops else emptyList())
            .filter { it.endedAt == null }
            .forEach {
                marker(it.latitude, it.longitude, Color.rgb(208, 128, 16))
            }
        selected?.let { marker(it.first, it.second, Color.RED) }
        if (layers.accuracy)
            points.lastOrNull()?.let { last ->
                val p = screen(last.latitude, last.longitude)
                val metersPerPixel =
                    156543.03392 * cos(last.latitude * PI / 180) /
                        (1 shl zoom) /
                        resources.displayMetrics.density
                paint.color = Color.argb(40, 49, 111, 224)
                canvas.drawCircle(
                    p.first,
                    p.second,
                    (last.accuracy / metersPerPixel).toFloat(),
                    paint,
                )
            }
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(144, 61, 155)
        paint.strokeWidth = 4f
        measurement.zipWithNext().forEach { (a, b) ->
            val first = screen(a.first, a.second)
            val second = screen(b.first, b.second)
            canvas.drawLine(first.first, first.second, second.first, second.second, paint)
        }
        paint.style = Paint.Style.FILL
        measurement.forEach { marker(it.first, it.second, Color.rgb(144, 61, 155)) }
        val metersPerPixel =
            156543.03392 * cos(lat * PI / 180) / (1 shl zoom) / resources.displayMetrics.density
        val scaleMeters =
            listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000)
                .lastOrNull { it <= metersPerPixel * 100 * resources.displayMetrics.density } ?: 10
        paint.color = Color.DKGRAY
        paint.strokeWidth = 3f
        paint.textSize = 11f * resources.displayMetrics.density
        canvas.drawLine(
            16f,
            height - 65f,
            (16 + scaleMeters / metersPerPixel).toFloat(),
            height - 65f,
            paint,
        )
        canvas.drawText(
            if (scaleMeters >= 1000) "${scaleMeters/1000} كم" else "$scaleMeters م",
            16f,
            height - 75f,
            paint,
        )
        canvas.drawText("N ↑", width - 65f, 45f, paint)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
            loading.clear()
            countryViewport = emptyList()
        }
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
    stops: List<StopDetails> = emptyList(),
    onStopPick: ((StopDetails) -> Unit)? = null,
    camera: MapCamera? = null,
    layers: MapLayers = MapLayers(),
    measurement: List<Pair<Double, Double>> = emptyList(),
    pickStopsWithSelection: Boolean = false,
    centerSelection: Boolean = true,
    onPick: ((Double, Double) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences by remember {
        (context.applicationContext as sa.rihla.RihlaApp).settings.flow
    }
        .collectAsState(initial = sa.rihla.data.Preferences())
    AndroidView(
        factory = { RouteMapView(it).also { view -> camera?.view = view } },
        modifier = modifier,
        update = { v ->
            val changed =
                v.points != points ||
                    v.events != events ||
                    v.places != places ||
                    v.selected != selected ||
                    v.polygonDraft != polygon ||
                    v.stops != stops ||
                    v.layers != layers ||
                    v.measurement != measurement
            v.points = points
            v.events = events
            v.places = places
            v.selected = selected
            v.polygonDraft = polygon
            v.onPick = onPick
            v.stops = stops
            v.onStopPick = onStopPick
            v.pickStopsWithSelection = pickStopsWithSelection
            v.layers = layers
            v.measurement = measurement
            camera?.view = v
            v.online(preferences.onlineMaps)
            v.fit()
            if (
                centerSelection &&
                    points.isEmpty() &&
                    selected != null &&
                    v.lastSelection != selected
            ) {
                v.center(selected.first, selected.second)
                v.lastSelection = selected
            }
            if (changed) v.invalidate()
        },
    )
}
