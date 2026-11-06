package sa.rihla.core

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import sa.rihla.audio.AudioBookmark
import sa.rihla.audio.AudioTrack
import sa.rihla.audio.audioTime
import sa.rihla.data.*

enum class StoryNodeType {
    OUTING_START,
    AUDIO_PLAYED,
    PARKING_SAVED,
    STOP_EVENT,
    EXPENSE_ITEM,
    FATHER_TRANSFER,
    OUTING_END,
}

data class DailyStoryNode(
    val timestampMs: Long,
    val type: StoryNodeType,
    val title: String,
    val subtitle: String,
    val badgeText: String = "",
    val outingId: Long? = null,
    val expenseId: Long? = null,
    val receiptImageUri: String? = null,
    val trackId: String? = null,
    val seekPositionMs: Long? = null,
) {
    val formattedTime: String
        get() =
            Instant.ofEpochMilli(timestampMs)
                .atZone(SaudiZone)
                .format(DateTimeFormatter.ofPattern("hh:mm a", Locale.forLanguageTag("ar-SA")))
}

object DailyStoryBuilder {
    fun buildForDate(
        date: LocalDate,
        outings: List<Outing>,
        events: List<JourneyEvent>,
        expenses: List<Expense>,
        rules: List<MerchantRule>,
        parkingPins: List<CampusParkingPin>,
        tracks: List<AudioTrack>,
        bookmarks: List<AudioBookmark>,
        transfers: List<TransferRow>,
        aliases: Set<String>,
        defaultProfile: ConsumptionProfile,
        defaultFuelPriceHalala: Long,
    ): List<DailyStoryNode> {
        val dayStart = date.atStartOfDay(SaudiZone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(SaudiZone).toInstant().toEpochMilli() - 1L
        val nodes = ArrayList<DailyStoryNode>()

        // 1. Outing starts & Home/Trip endings
        for (o in outings) {
            if (o.startedAt in dayStart..dayEnd) {
                val originLabel =
                    when {
                        o.state.startedHome -> "انطلاق من المنزل باللانسر"
                        o.state.startedUniversity -> "انطلاق من جامعة أم القرى"
                        else -> "بداية مشوار"
                    }
                nodes +=
                    DailyStoryNode(
                        timestampMs = o.startedAt,
                        type = StoryNodeType.OUTING_START,
                        title = originLabel,
                        subtitle = if (o.automatic) "رصد تلقائي ذكي" else "تشغيل يدوي",
                        outingId = o.id,
                    )
            }
            val endAt = o.endedAt
            if (endAt != null && endAt in dayStart..dayEnd) {
                val km = "%.1f كم".format(Locale.forLanguageTag("ar-SA"), o.state.meters / 1000.0)
                val mins = (o.state.drivingMs / 60_000L).coerceAtLeast(1L)
                val profile =
                    if (o.consumptionMinMl > 0)
                        ConsumptionProfile(o.consumptionMinMl, o.consumptionMaxMl, o.estimateSource)
                    else defaultProfile
                val price = if (o.fuelPriceHalala > 0) o.fuelPriceHalala else defaultFuelPriceHalala
                val fuel = estimateFuel(o.state.meters, profile, price)
                val fuelTxt =
                    fuel?.let {
                        val avgSar = ((it.minHalala + it.maxHalala) / 2.0) / 100.0
                        " · وقود تقديري %.2f ر.س".format(Locale.forLanguageTag("ar-SA"), avgSar)
                    }.orEmpty()
                nodes +=
                    DailyStoryNode(
                        timestampMs = endAt,
                        type = StoryNodeType.OUTING_END,
                        title = if (o.automaticEnd) "عودة للمنزل وانتهاء الرحلة" else "انتهاء المشوار",
                        subtitle = "المسافة $km · قيادة $mins د$fuelTxt",
                        badgeText = km,
                        outingId = o.id,
                    )
            }
        }

        // 2. Stops & UQU Visits
        for (ev in events) {
            if (ev.startedAt !in dayStart..dayEnd) continue
            if (ev.type == "VISIT" && ev.confirmed) {
                val durMin = ((ev.endedAt - ev.startedAt) / 60_000L).coerceAtLeast(1L)
                nodes +=
                    DailyStoryNode(
                        timestampMs = ev.startedAt,
                        type = StoryNodeType.STOP_EVENT,
                        title = "وصول لجامعة أم القرى (العابدية)",
                        subtitle = "مدة التواجد في الحرم الجامعي: $durMin دقيقة",
                        outingId = ev.outingId,
                    )
            } else if (ev.type == "STOP") {
                val durMin = ((ev.endedAt - ev.startedAt) / 60_000L).coerceAtLeast(1L)
                nodes +=
                    DailyStoryNode(
                        timestampMs = ev.startedAt,
                        type = StoryNodeType.STOP_EVENT,
                        title = "توقف أثناء المشوار",
                        subtitle = "مدة التوقف $durMin دقيقة",
                        outingId = ev.outingId,
                    )
            }
        }

        // 3. Campus Parking Pins
        for (pin in parkingPins) {
            if (pin.savedAt in dayStart..dayEnd) {
                val notePart = if (pin.note.isNotBlank()) " · ${pin.note}" else ""
                nodes +=
                    DailyStoryNode(
                        timestampMs = pin.savedAt,
                        type = StoryNodeType.PARKING_SAVED,
                        title = "📍 حفظ موقف سيارتي في الجامعة",
                        subtitle = "دقة ±${pin.accuracy.toInt()} م$notePart",
                        outingId = pin.outingId,
                    )
            }
        }

        // 4. Expenses (Rajhi SMS & Manual + Receipts)
        for (exp in expenses) {
            if (exp.createdAt !in dayStart..dayEnd) continue
            val matchedRule =
                if (exp.merchant.isNotBlank())
                    rules.firstOrNull { it.pattern.isNotBlank() && exp.merchant.contains(it.pattern, ignoreCase = true) }
                else null
            val displayMerchant =
                matchedRule?.customName?.takeIf { it.isNotBlank() }
                    ?: exp.merchant.ifBlank {
                        when (exp.category) {
                            "FUEL" -> "تعبئة بنزين"
                            "FOOD" -> "مطاعم وقهوة"
                            else -> "مصروف شخصي"
                        }
                    }
            val sar = "%.2f ر.س".format(Locale.forLanguageTag("ar-SA"), exp.amountHalala / 100.0)
            val srcText = if (exp.source == "SMS_NOTIFICATION") "رسالة الراجحي" else "تسجيل يدوي"
            val receiptNote = if (!exp.receiptImageUri.isNullOrBlank()) " · 🧾 مرفق فاتورة" else ""
            nodes +=
                DailyStoryNode(
                    timestampMs = exp.createdAt,
                    type = StoryNodeType.EXPENSE_ITEM,
                    title = "شراء: $displayMerchant",
                    subtitle = "$srcText$receiptNote",
                    badgeText = sar,
                    outingId = exp.outingId,
                    expenseId = exp.id,
                    receiptImageUri = exp.receiptImageUri,
                )
        }

        // 5. Audio Lectures played today + Bookmarks
        val bookmarksByTrack = bookmarks.groupBy { it.trackId }
        for (track in tracks) {
            if (track.lastPlayedAt in dayStart..dayEnd) {
                val tMarks = bookmarksByTrack[track.id].orEmpty()
                val latestMark = tMarks.maxByOrNull { it.id }
                val posLabel = audioTime(track.positionMs)
                val markInfo = latestMark?.let { " · علامة: ${it.label}" }.orEmpty()
                nodes +=
                    DailyStoryNode(
                        timestampMs = track.lastPlayedAt,
                        type = StoryNodeType.AUDIO_PLAYED,
                        title = "🎧 استماع: ${track.title}",
                        subtitle = "وصلت إلى الدقيقة $posLabel$markInfo",
                        trackId = track.id,
                        seekPositionMs = latestMark?.positionMs ?: track.positionMs,
                    )
            }
        }

        // 6. Father Incoming Transfers
        for (trRow in transfers) {
            if (trRow.time in dayStart..dayEnd && isFatherTransfer(trRow, aliases)) {
                val sar = "%.2f ر.س".format(Locale.forLanguageTag("ar-SA"), trRow.amount / 100.0)
                nodes +=
                    DailyStoryNode(
                        timestampMs = trRow.time,
                        type = StoryNodeType.FATHER_TRANSFER,
                        title = "💸 حوالة واردة من الوالد",
                        subtitle = trRow.sender.ifBlank { "حوالة مؤكدة" },
                        badgeText = "+$sar",
                    )
            }
        }

        return nodes.sortedBy { it.timestampMs }
    }
}
