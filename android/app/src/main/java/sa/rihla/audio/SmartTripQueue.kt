package sa.rihla.audio

import kotlin.math.abs
import sa.rihla.core.UniversityDepartureAdvisor
import sa.rihla.data.Outing
import sa.rihla.data.UniversityScheduleEntry

data class SmartTripQueuePlan(
    val targetDurationMs: Long,
    val totalSelectedMs: Long,
    val tracks: List<AudioTrack>,
    val startPositionMs: Long,
    val descriptionArabic: String,
)

object SmartTripQueueBuilder {
    const val TOLERANCE_MS = 3 * 60_000L // ±3 minutes

    fun estimateUpcomingTripDurationMs(
        outings: List<Outing>,
        schedule: List<UniversityScheduleEntry>,
        overrideMinutes: Int? = null,
    ): Long {
        if (overrideMinutes != null && overrideMinutes in 5..240) {
            return overrideMinutes * 60_000L
        }
        val advice = UniversityDepartureAdvisor.nextLectureAdvice(schedule, outings)
        if (advice != null && advice.isToday) {
            return advice.estimatedTravelMinutes * 60_000L
        }
        val avgMin = UniversityDepartureAdvisor.averageWeekdayCommuteMinutes(outings)
        return avgMin.coerceIn(10, 90) * 60_000L
    }

    /**
     * Selects either an unfinished lecture whose remaining time matches targetDurationMs (±3m)
     * or a smart subset of favorite/recent unfinished tracks closest to targetDurationMs.
     */
    fun buildQueue(
        tracks: List<AudioTrack>,
        targetDurationMs: Long,
    ): SmartTripQueuePlan? {
        val valid = tracks.filter { it.durationMs > 30_000L }
        if (valid.isEmpty()) return null

        fun remainingMs(t: AudioTrack): Long {
            val resume = audioResume(t.positionMs, t.durationMs)
            return (t.durationMs - resume).coerceAtLeast(30_000L)
        }

        // 1. Single unfinished lecture whose remaining duration is within ±3 min of trip length
        val unfinishedExact =
            valid
                .filter { audioResume(it.positionMs, it.durationMs) > 0L }
                .filter { abs(remainingMs(it) - targetDurationMs) <= TOLERANCE_MS }
                .minByOrNull { abs(remainingMs(it) - targetDurationMs) }
        if (unfinishedExact != null) {
            val rem = remainingMs(unfinishedExact)
            val resume = audioResume(unfinishedExact.positionMs, unfinishedExact.durationMs)
            return SmartTripQueuePlan(
                targetDurationMs = targetDurationMs,
                totalSelectedMs = rem,
                tracks = listOf(unfinishedExact),
                startPositionMs = resume,
                descriptionArabic =
                    "استكمال «${unfinishedExact.title}» (${rem / 60_000L} د متبقية تناسب مشوارك)",
            )
        }

        // 2. Single track (favorite/recent/unfinished) within ±3 min
        val singleMatch =
            valid
                .sortedWith(
                    compareByDescending<AudioTrack> { audioResume(it.positionMs, it.durationMs) > 0L }
                        .thenByDescending { it.favorite }
                        .thenByDescending { it.lastPlayedAt }
                        .thenByDescending { it.addedAt }
                )
                .filter { abs(remainingMs(it) - targetDurationMs) <= TOLERANCE_MS }
                .minByOrNull { abs(remainingMs(it) - targetDurationMs) }
        if (singleMatch != null) {
            val rem = remainingMs(singleMatch)
            val resume = audioResume(singleMatch.positionMs, singleMatch.durationMs)
            return SmartTripQueuePlan(
                targetDurationMs = targetDurationMs,
                totalSelectedMs = rem,
                tracks = listOf(singleMatch),
                startPositionMs = resume,
                descriptionArabic = "تشغيل «${singleMatch.title}» (${rem / 60_000L} د على قد مشوارك)",
            )
        }

        // 3. Multi-track combination (greedy + best-fit refinement) prioritized by unfinished -> favorite -> recent
        val prioritized =
            valid.sortedWith(
                compareByDescending<AudioTrack> { audioResume(it.positionMs, it.durationMs) > 0L }
                    .thenByDescending { it.favorite }
                    .thenByDescending { it.addedAt }
            )
        val chosen = ArrayList<AudioTrack>()
        var sum = 0L
        for (candidate in prioritized) {
            val rem = remainingMs(candidate)
            if (sum + rem <= targetDurationMs + TOLERANCE_MS) {
                chosen += candidate
                sum += rem
                if (abs(sum - targetDurationMs) <= TOLERANCE_MS) break
            }
        }

        // If all tracks were longer than targetDuration + 3m, pick the unfinished or shortest/closest lecture to listen up to arrival
        if (chosen.isEmpty()) {
            val bestLong =
                prioritized.minByOrNull { abs(remainingMs(it) - targetDurationMs) } ?: return null
            val resume = audioResume(bestLong.positionMs, bestLong.durationMs)
            return SmartTripQueuePlan(
                targetDurationMs = targetDurationMs,
                totalSelectedMs = remainingMs(bestLong),
                tracks = listOf(bestLong),
                startPositionMs = resume,
                descriptionArabic =
                    "تشغيل «${bestLong.title}» لمرافقتك طوال الـ ${targetDurationMs / 60_000L} دقيقة القادمة",
            )
        }

        val firstResume = audioResume(chosen.first().positionMs, chosen.first().durationMs)
        return SmartTripQueuePlan(
            targetDurationMs = targetDurationMs,
            totalSelectedMs = sum,
            tracks = chosen,
            startPositionMs = firstResume,
            descriptionArabic =
                "قائمة ذكية من ${chosen.size} مقطع (${sum / 60_000L} د لمشوار ${targetDurationMs / 60_000L} د)",
        )
    }
}
