package sa.rihla.audio

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sa.rihla.RihlaApp
import sa.rihla.core.SaudiZone

/**
 * Automatically creates an audio bookmark rewound by 10 seconds when the user arrives
 * at UQU, arrives home, confirms a stop, or disconnects Lancer Bluetooth.
 */
object AutoStopBookmarkManager {
    private val mutex = Mutex()
    private var lastSavedTrackId: String = ""
    private var lastSavedBucket: Long = -1L
    private var lastPausedAtMs: Long = 0L
    private var wasPlaying: Boolean = false

    fun observePlaybackState(playing: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (wasPlaying && !playing) {
            lastPausedAtMs = nowMs
        } else if (playing) {
            lastPausedAtMs = nowMs
        }
        wasPlaying = playing
    }

    fun rewindTenSeconds(positionMs: Long): Long = (positionMs - 10_000L).coerceAtLeast(0L)

    fun formatStopBookmarkLabel(reasonArabic: String, timestampMs: Long): String {
        val zdt = Instant.ofEpochMilli(timestampMs).atZone(SaudiZone)
        val dayName =
            when (zdt.dayOfWeek.value) {
                7 -> "الأحد"
                1 -> "الاثنين"
                2 -> "الثلاثاء"
                3 -> "الأربعاء"
                4 -> "الخميس"
                5 -> "الجمعة"
                else -> "السبت"
            }
        val timeText =
            zdt.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.forLanguageTag("ar-SA")))
        return "📍 $reasonArabic — $dayName $timeText"
    }

    suspend fun onStopOrArrival(
        app: RihlaApp,
        reasonArabic: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): AudioBookmark? = mutex.withLock {
        val prefs = app.settings.flow.first()
        if (!prefs.autoStopBookmark) return@withLock null
        val playback = AudioLive.state.value
        val trackId = playback.id.takeIf { it.isNotBlank() } ?: return@withLock null
        val recentlyActive =
            playback.playing || (lastPausedAtMs > 0L && timestampMs - lastPausedAtMs <= 60_000L)
        if (!recentlyActive) return@withLock null
        val bucket = timestampMs / 300_000L // 5-minute deduplication window per stop
        if (trackId == lastSavedTrackId && bucket == lastSavedBucket) return@withLock null
        val targetPos = rewindTenSeconds(playback.position)
        val existing = app.audio.dao.bookmarksForTrack(trackId)
        if (
            existing.any {
                kotlin.math.abs(it.positionMs - targetPos) < 15_000L && it.label.startsWith("📍")
            }
        ) {
            lastSavedTrackId = trackId
            lastSavedBucket = bucket
            return@withLock null
        }
        val label = formatStopBookmarkLabel(reasonArabic, timestampMs)
        val item = AudioBookmark(trackId = trackId, positionMs = targetPos, label = label)
        val id = app.audio.dao.bookmark(item)
        lastSavedTrackId = trackId
        lastSavedBucket = bucket
        item.copy(id = id)
    }

    fun resetForTests() {
        lastSavedTrackId = ""
        lastSavedBucket = -1L
        lastPausedAtMs = 0L
        wasPlaying = false
    }
}
