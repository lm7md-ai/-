package sa.rihla.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import sa.rihla.RihlaApp
import sa.rihla.audio.*
import sa.rihla.audio.studio.AssistantNavigation
import sa.rihla.audio.studio.AssistantReply
import sa.rihla.core.*
import sa.rihla.data.*

/** Global offline Arabic TTS speaker + observable speech log for Robolectric verification. */
object AppVoiceSpeaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    val lastSpoken = MutableStateFlow("")

    fun speak(context: Context, text: String) {
        val clean = text.trim().take(2000)
        if (clean.isBlank()) return
        lastSpoken.value = clean
        pendingText = clean
        runCatching {
            val appCtx = context.applicationContext
            if (tts == null) {
                tts =
                    TextToSpeech(appCtx) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            ready = true
                            pendingText?.let { sayInternal(it) }
                        }
                    }
            } else if (ready) {
                sayInternal(clean)
            }
        }
    }

    private fun sayInternal(value: String) {
        val engine = tts ?: return
        val arabicVoice =
            engine.voices?.firstOrNull {
                it.locale.language == "ar" && !it.isNetworkConnectionRequired
            }
        if (arabicVoice != null) {
            engine.voice = arabicVoice
        } else {
            engine.language = Locale.forLanguageTag("ar-SA")
        }
        engine.speak(value, TextToSpeech.QUEUE_FLUSH, null, "mashwari-v7-tts")
    }
}

sealed interface VoiceUndoAction {
    val label: String

    data class DeleteInsertedExpense(
        val expenseId: Long,
        override val label: String,
    ) : VoiceUndoAction

    data class RestoreDeletedExpense(
        val expense: Expense,
        val fuel: FuelFillUp?,
        override val label: String,
    ) : VoiceUndoAction

    data class DeleteAudioBookmark(
        val bookmarkId: Long,
        override val label: String,
    ) : VoiceUndoAction

    data class ClearParkingPin(
        val pinId: Long,
        override val label: String,
    ) : VoiceUndoAction

    data class NavigateBack(
        val previousPage: String,
        override val label: String,
    ) : VoiceUndoAction

    data class RestoreAudioScene(
        val previousScene: AudioScene,
        override val label: String,
    ) : VoiceUndoAction
}

data class VoiceUndoState(
    val action: VoiceUndoAction,
    val createdAt: Long = System.currentTimeMillis(),
)

object VoiceUndoBus {
    val latest = MutableStateFlow<VoiceUndoState?>(null)

    fun push(action: VoiceUndoAction) {
        latest.value = VoiceUndoState(action)
    }

    fun clear() {
        latest.value = null
    }
}

data class ParameterizedVoiceCommand(
    val kind: String, // "expense" | "fuel" | "delete_last_expense"
    val category: String = "OTHER",
    val amountHalala: Long = 0L,
    val merchant: String = "",
    val fuelOctanePriceHalala: Long = 233L, // default Saudi 95/91 blend fallback if unspecified
)

object ParameterizedVoiceParser {
    private val arabicIndicMap =
        mapOf(
            '٠' to '0',
            '١' to '1',
            '٢' to '2',
            '٣' to '3',
            '٤' to '4',
            '٥' to '5',
            '٦' to '6',
            '٧' to '7',
            '٨' to '8',
            '٩' to '9',
            '٫' to '.',
        )

    private fun normalizeDigits(input: String): String =
        buildString(input.length) {
            for (ch in input) append(arabicIndicMap[ch] ?: ch)
        }

    fun parse(raw: String): ParameterizedVoiceCommand? {
        val norm = AppCommands.normalized(normalizeDigits(raw))
        if (
            norm in setOf("احذف اخر مصروف", "الغ اخر عمليه شراء", "امسح المصروف الاخير") ||
                (norm.contains("احذف") && norm.contains("اخر") && norm.contains("مصروف"))
        ) {
            return ParameterizedVoiceCommand(kind = "delete_last_expense")
        }

        val amountMatch =
            Regex("""(\d+(?:\.\d{1,2})?)\s*(?:ريال|ر\.س|رس|sar)?""")
                .findAll(norm)
                .mapNotNull { m ->
                    val v = m.groupValues[1]
                    if (v in listOf("91", "95") && norm.contains("بنزين") && norm.contains(Regex("""\d+.*?\d+"""))) {
                        null
                    } else {
                        v.toBigDecimalOrNull()
                    }
                }
                .firstOrNull() ?: return null

        val halala =
            amountMatch
                .multiply(BigDecimal(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .toLong()
        if (halala !in 50L..500_000L) return null

        val isFuel =
            listOf("بنزين", "وقود", "عبيت", "فللت", "ديزل").any { norm.contains(it) }
        if (isFuel) {
            val pricePerLiter =
                when {
                    norm.contains("91") -> 218L
                    norm.contains("95") -> 233L
                    else -> 233L
                }
            return ParameterizedVoiceCommand(
                kind = "fuel",
                category = "FUEL",
                amountHalala = halala,
                merchant = "محطة وقود",
                fuelOctanePriceHalala = pricePerLiter,
            )
        }

        val expenseTrigger =
            listOf("سجل", "اضف مصروف", "دفعت", "اشتريت", "شريت", "حاسبت").any {
                norm.startsWith(it) || norm.contains("$it ")
            }
        if (!expenseTrigger) return null

        val isFood =
            listOf(
                    "قهوه",
                    "كوفي",
                    "غداء",
                    "عشاء",
                    "فطور",
                    "مطعم",
                    "ساندوتش",
                    "عصير",
                    "شاي",
                    "دانكن",
                    "ستاربكس",
                    "تيم هورتنز",
                    "ماكدونالدز",
                    "البيك",
                )
                .any { norm.contains(it) }
        val cleanedItem =
            norm
                .replace(Regex("""^(?:يا مساعدي |ممكن |لو سمحت )*"""), "")
                .replace(Regex("""^(?:سجل|اضف مصروف|دفعت|اشتريت|شريت|حاسبت)\s*"""), "")
                .replace(Regex("""(?:بـ|ب|بقيمه|مبلغ)?\s*\d+(?:\.\d{1,2})?\s*(?:ريال|ر\.س|رس|sar)?"""), "")
                .replace("مصروف", "")
                .trim()
                .ifBlank { if (isFood) "قهوة وطعام" else "مصروف صوتي" }

        return ParameterizedVoiceCommand(
            kind = "expense",
            category = if (isFood) "FOOD" else "OTHER",
            amountHalala = halala,
            merchant = cleanedItem.take(60),
        )
    }
}

/** Synthesizes the real-time Morning Commute Briefing across all 3 Mashwari databases. */
object DailyBriefingBuilder {
    suspend fun buildBriefing(
        app: RihlaApp,
        nowMs: Long = System.currentTimeMillis(),
    ): String =
        withContext(Dispatchers.IO) {
            val zone = ZoneId.of("Asia/Riyadh")
            val parts = ArrayList<String>()
            parts += "صباح الخير، هذا موجز مشوارك اليوم."

            val schedule = app.repository.dao.scheduleRecords()
            val outings = app.repository.dao.outings()
            val advice =
                UniversityDepartureAdvisor.nextLectureAdvice(
                    schedule = schedule,
                    outings = outings,
                    nowMs = nowMs,
                )
            if (advice != null && advice.isToday) {
                val minsLeft = advice.minutesUntilDeparture ?: 0
                parts +=
                    if (minsLeft <= 0) {
                        "محاضرتك القادمة ${advice.entry.courseTitle} الساعة ${advice.lectureStartFormatted}؛ يُفضل الانطلاق الآن (${advice.estimatedTravelMinutes} دقيقة تقريبًا)."
                    } else {
                        "محاضرتك القادمة ${advice.entry.courseTitle} الساعة ${advice.lectureStartFormatted}؛ انطلق بعد $minsLeft دقيقة."
                    }
            } else {
                parts += "لا توجد محاضرات جامعية مجدولة متبقية لليوم."
            }

            val expenses = app.repository.dao.expenses().first()
            val balance =
                UniversityFinanceCalculator.computeRemainingTransferBalance(
                    transferRows = app.transfers.rows(),
                    aliases = app.transfers.aliases,
                    cutoffMs = app.transfers.cutoff,
                    expenses = expenses,
                    nowMs = nowMs,
                )
            if (balance.configured) {
                val riyals = formatHalalaArabic(balance.remainingHalala)
                parts += "الرصيد الفعلي المتبقي من الحوالات: $riyals ريال."
            }

            val activePin = app.repository.dao.activeParking()
            if (activePin != null) {
                val note = activePin.note.ifBlank { "موقف الجامعة" }
                parts += "آخر موقف محفوظ للسيارة: $note."
            }

            val tracks = app.audio.dao.tracks().first()
            val unfinished =
                tracks.firstOrNull { tr -> audioResume(tr.positionMs, tr.durationMs) > 0L }
            if (unfinished != null) {
                val posMin = (unfinished.positionMs / 60_000L).coerceAtLeast(1L)
                parts += "لديك محاضرة غير مكتملة: ${unfinished.title.take(45)} عند الدقيقة $posMin."
            }

            parts.joinToString(" ")
        }

    fun formatHalalaArabic(halala: Long): String {
        val nf =
            NumberFormat.getNumberInstance(Locale.forLanguageTag("ar-SA")).apply {
                minimumFractionDigits = 0
                maximumFractionDigits = 2
            }
        return nf.format(BigDecimal.valueOf(halala, 2))
    }
}

object ZeroConfirmExecutor {
    suspend fun executeParameterized(
        app: RihlaApp,
        cmd: ParameterizedVoiceCommand,
        speakReply: Boolean,
    ): AssistantReply {
        val now = System.currentTimeMillis()
        return when (cmd.kind) {
            "expense" -> {
                val active = app.repository.dao.active()
                val id =
                    app.repository.addExpense(
                        category = cmd.category,
                        amount = cmd.amountHalala,
                        liters = null,
                        price = null,
                        full = false,
                        time = now,
                        outingId = active?.id,
                        merchant = cmd.merchant,
                    )
                val sar = DailyBriefingBuilder.formatHalalaArabic(cmd.amountHalala)
                val msg = "تم تسجيل ${cmd.merchant} بمبلغ $sar ريال."
                VoiceUndoBus.push(VoiceUndoAction.DeleteInsertedExpense(id, "تراجع عن تسجيل ${cmd.merchant}"))
                if (speakReply) AppVoiceSpeaker.speak(app, msg)
                AssistantReply(msg, "executed", id.toString())
            }
            "fuel" -> {
                val active = app.repository.dao.active()
                val litersMl =
                    ((cmd.amountHalala.toDouble() / cmd.fuelOctanePriceHalala.toDouble()) * 1000.0)
                        .toLong()
                        .coerceAtLeast(1000L)
                val id =
                    app.repository.addExpense(
                        category = "FUEL",
                        amount = cmd.amountHalala,
                        liters = litersMl,
                        price = cmd.fuelOctanePriceHalala,
                        full = true,
                        time = now,
                        outingId = active?.id,
                        merchant = "بنزين اللانسر",
                    )
                val sar = DailyBriefingBuilder.formatHalalaArabic(cmd.amountHalala)
                val msg = "تم تسجيل تعبئة وقود بمبلغ $sar ريال."
                VoiceUndoBus.push(VoiceUndoAction.DeleteInsertedExpense(id, "تراجع عن تعبئة الوقود"))
                if (speakReply) AppVoiceSpeaker.speak(app, msg)
                AssistantReply(msg, "executed", id.toString())
            }
            "delete_last_expense" -> {
                val latest =
                    app.repository.dao.expenses().first().maxByOrNull { it.createdAt }
                        ?: return AssistantReply("لا يوجد مصروف مسجل لحذفه.").also {
                            if (speakReply) AppVoiceSpeaker.speak(app, it.reply)
                        }
                val deleted = app.repository.deleteExpenseById(latest.id)
                if (deleted != null) {
                    val sar = DailyBriefingBuilder.formatHalalaArabic(deleted.first.amountHalala)
                    val title = deleted.first.merchant.ifBlank { deleted.first.category }
                    VoiceUndoBus.push(
                        VoiceUndoAction.RestoreDeletedExpense(
                            expense = deleted.first,
                            fuel = deleted.second,
                            label = "تراجع عن حذف $title ($sar ر.س)",
                        )
                    )
                    val msg = "تم حذف آخر مصروف ($title · $sar ريال). يمكنك التراجع فورًا."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", latest.id.toString())
                } else {
                    AssistantReply("تعذر العثور على المصروف الأخير.")
                }
            }
            else -> AssistantReply("أمر غير معروف")
        }
    }

    suspend fun executeIntentImmediately(
        app: RihlaApp,
        intent: AppIntent,
        audio: AudioViewModel?,
        speakReply: Boolean,
        currentPage: String = "dashboard",
    ): AssistantReply {
        return when (intent.action) {
            "daily_briefing" -> {
                val briefing = DailyBriefingBuilder.buildBriefing(app)
                if (speakReply) AppVoiceSpeaker.speak(app, briefing)
                AssistantReply(briefing, "executed", "daily_briefing")
            }
            "where_is_my_car" -> {
                val pin = app.repository.dao.activeParking()
                if (pin == null) {
                    val msg = "لا يوجد موقف سيارة محفوظ حاليًا. افتح الخريطة أو قل احفظ موقف سيارتي."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "none", "")
                } else {
                    AssistantNavigation.requests.emit("map")
                    VoiceUndoBus.push(VoiceUndoAction.NavigateBack(currentPage, "رجوع للصفحة السابقة"))
                    val note = pin.note.ifBlank { "موقف الجامعة المحفوظ" }
                    val msg = "فتحت الخريطة مع بوصلة المشي إلى $note."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", "map")
                }
            }
            "save_parking" -> {
                val active = app.repository.dao.active()
                val lat = active?.state?.lastLat
                val lon = active?.state?.lastLon
                if (lat == null || lon == null) {
                    val msg = "لا تتوفر إحداثية حالية لحفظ الموقف؛ افتح الخريطة لحفظ الموقف."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "none", "")
                } else {
                    app.repository.dao.deactivateAllParking()
                    val pinId =
                        app.repository.dao.insertParking(
                            CampusParkingPin(
                                outingId = active.id,
                                latitude = lat,
                                longitude = lon,
                                accuracy = 10.0,
                                savedAt = System.currentTimeMillis(),
                                note = "موقف صوتي محفوظ",
                                active = true,
                            )
                        )
                    VoiceUndoBus.push(VoiceUndoAction.ClearParkingPin(pinId, "إلغاء حفظ الموقف"))
                    val msg = "تم حفظ موقع وقوف اللانسر الآن."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", pinId.toString())
                }
            }
            "smart_queue" -> {
                if (audio != null) {
                    audio.playSmartTripQueue()
                    val msg = "رتبت وشغلت قائمة محاضرات على قد مشوارك."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", "smart_queue")
                } else {
                    AssistantNavigation.requests.emit("audio")
                    val msg = "فتحت مكتبة الصوتيات لتشغيل القائمة الذكية."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", "audio")
                }
            }
            "bookmark_here" -> {
                val live = AudioLive.state.value
                if (live.id.isBlank()) {
                    val msg = "شغّل محاضرة أولًا لحفظ علامة مرجعية."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "none", "")
                } else {
                    val pos = (live.position - 5_000L).coerceAtLeast(0L)
                    val id =
                        app.audio.dao.bookmark(
                            AudioBookmark(
                                trackId = live.id,
                                positionMs = pos,
                                label = "علامة صوتية أثناء القيادة (${audioTime(pos)})",
                            )
                        )
                    VoiceUndoBus.push(VoiceUndoAction.DeleteAudioBookmark(id, "حذف العلامة الصوتية"))
                    val msg = "حُفظت علامة مرجعية عند الدقيقة ${(pos / 60_000L)}."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", id.toString())
                }
            }
            "delete_last_expense" ->
                executeParameterized(
                    app,
                    ParameterizedVoiceCommand(kind = "delete_last_expense"),
                    speakReply,
                )
            "unified_backup" -> {
                val file = UnifiedBackupManager(app).createManualSnapshotFile()
                val msg = "تم حفظ نسخة احتياطية موحدة شاملة (${file.name})."
                if (speakReply) AppVoiceSpeaker.speak(app, msg)
                AssistantReply(msg, "executed", file.name)
            }
            "adaptive_boost" -> {
                app.settings.update { it.copy(speedAdaptiveAudio = true) }
                val msg = "تم تفعيل تعزيز وضوح المحاضرات التلقائي مع سرعة السيارة."
                if (speakReply) AppVoiceSpeaker.speak(app, msg)
                AssistantReply(msg, "executed", "adaptive_boost")
            }
            "rewind_15" -> {
                val live = AudioLive.state.value
                if (audio != null && live.id.isNotBlank()) {
                    audio.seek((live.position - 15_000L).coerceAtLeast(0L))
                    val msg = "أرجعت المقطع ١٥ ثانية."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", "rewind_15")
                } else {
                    AssistantReply("لا يوجد مقطع قيد التشغيل حاليًا.")
                }
            }
            "navigate" -> {
                if (intent.value in AssistantNavigation.pages) {
                    AssistantNavigation.requests.emit(intent.value)
                    VoiceUndoBus.push(VoiceUndoAction.NavigateBack(currentPage, "رجوع من ${intent.title}"))
                    val msg = "تم: ${intent.title}."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", intent.value)
                } else {
                    AssistantReply("الوجهة غير متاحة.")
                }
            }
            "pause_audio" -> {
                audio?.pause()
                val msg = "تم إيقاف الصوت مؤقتًا."
                if (speakReply) AppVoiceSpeaker.speak(app, msg)
                AssistantReply(msg, "executed", "pause_audio")
            }
            "resume_audio" -> {
                audio?.resume()
                val msg = "تمت متابعة التشغيل."
                if (speakReply) AppVoiceSpeaker.speak(app, msg)
                AssistantReply(msg, "executed", "resume_audio")
            }
            "next_audio" -> {
                audio?.next()
                val msg = "انتقلت للمقطع التالي."
                if (speakReply) AppVoiceSpeaker.speak(app, msg)
                AssistantReply(msg, "executed", "next_audio")
            }
            "previous_audio" -> {
                audio?.previous()
                val msg = "عدت للمقطع السابق."
                if (speakReply) AppVoiceSpeaker.speak(app, msg)
                AssistantReply(msg, "executed", "previous_audio")
            }
            "scene" -> {
                val target = AudioScene.entries.firstOrNull { it.key == intent.value }
                if (target != null && audio != null) {
                    val prev =
                        AudioScene.entries.firstOrNull { it.key == AudioLive.state.value.scene }
                            ?: AudioScene.NATURAL
                    audio.scene(target)
                    VoiceUndoBus.push(VoiceUndoAction.RestoreAudioScene(prev, "استعادة مشهد الصوت السابق"))
                    val msg = "تم تفعيل وضع ${intent.title}."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", target.key)
                } else {
                    AssistantReply("افتح المشغل لتغيير المشهد الصوتي.")
                }
            }
            "favorite_current" -> {
                val id = AudioLive.state.value.id
                if (id.isNotBlank() && audio != null) {
                    val tr = app.audio.dao.track(id)
                    if (tr != null && !tr.favorite) audio.favorite(id)
                    val msg = "تمت إضافة المقطع الحالي للمفضلة."
                    if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    AssistantReply(msg, "executed", id)
                } else {
                    AssistantReply("شغّل مقطعًا أولًا لإضافته للمفضلة.")
                }
            }
            else -> AssistantReply("تم استلام الأمر.")
        }
    }

    suspend fun undo(app: RihlaApp, audio: AudioViewModel?): String? {
        val state = VoiceUndoBus.latest.value ?: return null
        VoiceUndoBus.clear()
        val msg =
            when (val act = state.action) {
                is VoiceUndoAction.DeleteInsertedExpense -> {
                    app.repository.deleteExpenseById(act.expenseId)
                    "تم التراجع وحذف المصروف المسجل."
                }
                is VoiceUndoAction.RestoreDeletedExpense -> {
                    app.repository.restoreDeletedExpense(act.expense, act.fuel)
                    "تم التراجع واستعادة المصروف المحذوف."
                }
                is VoiceUndoAction.DeleteAudioBookmark -> {
                    app.audio.dao.removeBookmark(act.bookmarkId)
                    "تم التراجع وحذف العلامة المرجعية."
                }
                is VoiceUndoAction.ClearParkingPin -> {
                    app.repository.dao.deactivateAllParking()
                    "تم التراجع وإلغاء حفظ الموقف."
                }
                is VoiceUndoAction.NavigateBack -> {
                    if (act.previousPage in AssistantNavigation.pages) {
                        AssistantNavigation.requests.emit(act.previousPage)
                    }
                    "تم الرجوع للصفحة السابقة."
                }
                is VoiceUndoAction.RestoreAudioScene -> {
                    audio?.scene(act.previousScene)
                    "تمت استعادة إعداد الصوت السابق."
                }
            }
        AppVoiceSpeaker.speak(app, "تم التراجع")
        return msg
    }
}
