package sa.rihla.assistant

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import sa.rihla.RihlaApp
import sa.rihla.audio.*
import sa.rihla.audio.studio.*
import sa.rihla.core.UniversityDepartureAdvisor
import sa.rihla.core.UniversityFinanceCalculator

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as RihlaApp
    val store = PersonalAssistantStore(app)
    val profile = MutableStateFlow(store.profile())
    val examples = MutableStateFlow(store.examples())
    val reply = MutableStateFlow<AssistantReply?>(null)
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val executed = MutableStateFlow(false)
    val source = MutableStateFlow("")
    val results = MutableStateFlow<List<AudioTrack>>(emptyList())
    private var boundAudio: AudioViewModel? = null
    private var currentRoute: String = "dashboard"
    private var request: Job? = null

    val undoState: StateFlow<VoiceUndoState?> = VoiceUndoBus.latest

    val zeroConfirmEnabled: StateFlow<Boolean> =
        app.settings.flow
            .map { it.zeroConfirmationVoice }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            StudioTasks.state.collect { state ->
                val llmReply = state.reply
                if (llmReply != null) {
                    val zero = app.settings.flow.first().zeroConfirmationVoice
                    if (zero && llmReply.action != "none" && llmReply.action != "executed") {
                        reply.value = llmReply
                        source.value = "Qwen · تنفيذ فوري"
                        execute(boundAudio)
                    } else {
                        reply.value = llmReply
                        executed.value = false
                        source.value = "Qwen · محلي"
                    }
                    if (profile.value.speak && llmReply.reply.isNotBlank()) {
                        AppVoiceSpeaker.speak(app, llmReply.reply)
                    }
                }
            }
        }
    }

    fun bindContext(audio: AudioViewModel?, route: String = "dashboard") {
        boundAudio = audio
        currentRoute = route
    }

    fun toggleZeroConfirmation(enabled: Boolean) = work {
        app.settings.update { it.copy(zeroConfirmationVoice = enabled) }
        message.value =
            if (enabled) "تم تفعيل وضع التنفيذ الصوتي الفوري بدون تأكيد (مع زر تراجع)"
            else "تم تفعيل وضع مراجعة الاقتراح قبل التنفيذ"
    }

    fun readDailyBriefing() = work {
        val briefing = DailyBriefingBuilder.buildBriefing(app)
        reply.value = AssistantReply(briefing, "executed", "daily_briefing")
        executed.value = true
        source.value = "موجز مشواري اليومي"
        AppVoiceSpeaker.speak(app, briefing)
    }

    fun undoLastAction(audio: AudioViewModel? = boundAudio) = work {
        val msg = ZeroConfirmExecutor.undo(app, audio)
        if (msg != null) {
            message.value = msg
            reply.value = AssistantReply(msg, "none", "")
        }
    }

    fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message?.take(200) ?: "تعذرت العملية"
            }
        }
    }

    fun save(value: AssistantProfile) = work {
        store.save(value)
        profile.value = store.profile()
        message.value = "حُفظ تخصيص مساعدك على الهاتف"
    }

    fun teach(text: String, intent: String) = work {
        check(!busy.value)
        busy.value = true
        try {
            withContext(Dispatchers.IO) {
                store.train(
                    store.examples().filterNot {
                        AppCommands.normalized(it.text) == AppCommands.normalized(text)
                    } + CommandExample(text.trim(), intent)
                )
            }
            examples.value = store.examples()
            message.value = "تدرّب نموذجك على العبارة وأصبح جاهزًا"
        } finally {
            busy.value = false
        }
    }

    fun forget(text: String) = work {
        withContext(Dispatchers.IO) { store.train(store.examples().filterNot { it.text == text }) }
        examples.value = store.examples()
        message.value = "حُذف مثال التدريب وأُعيد تدريب النموذج"
    }

    private suspend fun buildSummaryReply(summaryKey: String): String {
        val zone = java.time.ZoneId.of("Asia/Riyadh")
        val now = java.time.Instant.now()
        val today = now.atZone(zone).toLocalDate()
        return when (summaryKey) {
            "expenses_today",
            "expenses_month" -> {
                val start =
                    (if (summaryKey == "expenses_month") today.withDayOfMonth(1) else today)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()
                val rows =
                    app.repository.dao.expenses().first().filter {
                        it.createdAt in start..now.toEpochMilli()
                    }
                val total = rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountHalala) }
                val money =
                    java.text.NumberFormat.getNumberInstance(
                            java.util.Locale.forLanguageTag("ar-SA")
                        )
                        .apply {
                            minimumFractionDigits = 2
                            maximumFractionDigits = 2
                        }
                        .format(java.math.BigDecimal.valueOf(total, 2))
                "المصروفات المسجلة ${if (summaryKey == "expenses_month") "هذا الشهر" else "اليوم"}: $money ر.س · ${rows.size} عملية."
            }
            "trips_today" -> {
                val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val rows =
                    app.repository.dao.outings().filter {
                        it.startedAt in start..now.toEpochMilli()
                    }
                val distance =
                    java.text.NumberFormat.getNumberInstance(
                            java.util.Locale.forLanguageTag("ar-SA")
                        )
                        .apply { maximumFractionDigits = 1 }
                        .format(rows.sumOf { it.state.meters } / 1000)
                "مشاوير اليوم المسجلة: ${rows.size} · المسافة المسجلة: $distance كم."
            }
            "next_lecture" -> {
                val advice =
                    UniversityDepartureAdvisor.nextLectureAdvice(
                        schedule = app.repository.dao.scheduleRecords(),
                        outings = app.repository.dao.outings(),
                        nowMs = now.toEpochMilli(),
                    )
                if (advice == null) {
                    "لا توجد محاضرات مجدولة في جدول أم القرى."
                } else if (!advice.isToday) {
                    "لا توجد محاضرات متبقية اليوم. المحاضرة القادمة ${advice.dayLabelArabic}: ${advice.entry.courseTitle} الساعة ${advice.lectureStartFormatted}."
                } else {
                    val minsLeft = advice.minutesUntilDeparture ?: 0
                    if (minsLeft <= 0) {
                        "محاضرتك القادمة: ${advice.entry.courseTitle} (${advice.entry.buildingOrGate}) الساعة ${advice.lectureStartFormatted} — يُفضل الانطلاق الآن (${advice.estimatedTravelMinutes} دقيقة)."
                    } else {
                        "محاضرتك القادمة: ${advice.entry.courseTitle} (${advice.entry.buildingOrGate}) الساعة ${advice.lectureStartFormatted} — انطلق بعد $minsLeft دقيقة."
                    }
                }
            }
            "remaining_balance" -> {
                val bal =
                    UniversityFinanceCalculator.computeRemainingTransferBalance(
                        transferRows = app.transfers.rows(),
                        aliases = app.transfers.aliases,
                        cutoffMs = app.transfers.cutoff,
                        expenses = app.repository.dao.expenses().first(),
                        nowMs = now.toEpochMilli(),
                    )
                if (!bal.configured) {
                    "لا توجد حوالة مسجلة بعد لحساب الرصيد الفعلي المتبقي."
                } else {
                    val rem = DailyBriefingBuilder.formatHalalaArabic(bal.remainingHalala)
                    val spent = DailyBriefingBuilder.formatHalalaArabic(bal.totalExpensesHalala)
                    val daysHint =
                        bal.estimatedDaysRemaining?.let { " · يكفي تقريبًا $it يومًا بالمعدل الحالي" }.orEmpty()
                    "الرصيد الفعلي المتبقي من الحوالات: $rem ر.س (صُرف $spent ر.س منذ بداية الفترة)$daysHint."
                }
            }
            "university_day_cost" -> {
                val prefs = app.settings.flow.first()
                val stats =
                    UniversityFinanceCalculator.computeUniversityDayCosts(
                        outings = app.repository.dao.outings(),
                        events = app.repository.dao.allEvents(),
                        expenses = app.repository.dao.expenses().first(),
                        defaultProfile = sa.rihla.core.ConsumptionProfile(5500, 7800, "REFERENCE"),
                        defaultFuelPriceHalala = prefs.fuelPriceHalala,
                        nowMs = now.toEpochMilli(),
                    )
                if (stats.universityDaysCount == 0) {
                    "لا توجد أيام دوام جامعي مكتملة بعد لحساب متوسط تكلفة يوم الجامعة."
                } else {
                    val avg = DailyBriefingBuilder.formatHalalaArabic(stats.averageUniversityDayHalala)
                    "متوسط تكلفة يوم الجامعة: $avg ر.س عبر ${stats.universityDaysCount} يوم دوام."
                }
            }
            else -> "لا يتوفر ملخص لهذا الطلب."
        }
    }

    fun ask(text: String, audio: AudioViewModel? = boundAudio) {
        if (busy.value) return
        if (audio != null) boundAudio = audio
        request = viewModelScope.launch {
            busy.value = true
            reply.value = null
            executed.value = false
            results.value = emptyList()
            message.value = ""
            try {
                require(text.trim().length in 1..1200)
                val zeroConfirm = app.settings.flow.first().zeroConfirmationVoice
                val speakReply = profile.value.speak || zeroConfirm

                // 1) Parameterized Saudi voice mutations ("سجل قهوة بـ 18 ريال", "عبيت بنزين بـ 85 ريال", "احذف آخر مصروف")
                val parameterized = ParameterizedVoiceParser.parse(text)
                if (parameterized != null && zeroConfirm) {
                    val out =
                        ZeroConfirmExecutor.executeParameterized(
                            app = app,
                            cmd = parameterized,
                            speakReply = speakReply,
                        )
                    reply.value = out
                    executed.value = out.action == "executed"
                    source.value = "تنفيذ صوتي فوري · بدون تأكيد"
                    message.value = out.reply
                    return@launch
                }

                // 2) Safety guardrail for bulk destructive requests ("احذف جميع المشاوير")
                AssistantProtocol.unsupported(text)?.let {
                    reply.value = it
                    source.value = "حدود أوامر التطبيق"
                    return@launch
                }

                // 3) Hybrid Stage 1: Fast (<5ms) on-device Multinomial Naive Bayes (44+ commands)
                val prediction = withContext(Dispatchers.IO) { store.model().predict(text) }
                if (prediction != null) {
                    val intent = prediction.intent
                    if (intent.action == "summary") {
                        val summaryText = buildSummaryReply(intent.value)
                        reply.value = AssistantReply(summaryText)
                        source.value = "حساب مباشر من سجلات مشواري بتوقيت السعودية"
                        if (speakReply) AppVoiceSpeaker.speak(app, summaryText)
                        return@launch
                    }
                    if (zeroConfirm) {
                        val immediate =
                            ZeroConfirmExecutor.executeIntentImmediately(
                                app = app,
                                intent = intent,
                                audio = boundAudio,
                                speakReply = speakReply,
                                currentPage = currentRoute,
                            )
                        reply.value = immediate
                        executed.value = immediate.action == "executed"
                        source.value = "تنفيذ فوري · ${intent.title}"
                        message.value = immediate.reply
                        return@launch
                    } else {
                        val proposed =
                            if (intent.action == "favorite_current") {
                                val id = AudioLive.state.value.id
                                if (id.isBlank()) AssistantReply("شغّل مقطعًا أولًا لإضافته للمفضلة")
                                else AssistantReply("", "favorite_audio", id)
                            } else AssistantReply("", intent.action, intent.value)
                        reply.value = AssistantProtocol.proposal(proposed)
                        source.value = "نموذج أوامرك المحلي · ${intent.title}"
                        return@launch
                    }
                }

                // 4) Direct local library & transcript search ("شغل محاضرة ...", "ابحث عن ...")
                val normalized = AppCommands.normalized(text)
                val prefixes =
                    listOf(
                        "ابحث عن ",
                        "ابحث ",
                        "بحث ",
                        "شغل محاضره ",
                        "شغل مقطع ",
                        "شغل ",
                        "search ",
                        "play ",
                    )
                val prefix = prefixes.firstOrNull { normalized.startsWith(it) }
                if (prefix != null) {
                    val query = normalized.removePrefix(prefix).trim()
                    val tracks =
                        app.audio.dao
                            .tracks()
                            .first()
                            .filter { track ->
                                AppCommands.normalized(
                                        track.title + " " + track.artist + " " + track.album
                                    )
                                    .contains(query)
                            }
                            .take(30)
                    results.value = tracks
                    if (prefix.startsWith("شغل") && tracks.isNotEmpty() && zeroConfirm && boundAudio != null) {
                        val chosen = tracks.first()
                        boundAudio?.play(chosen, tracks)
                        val msg = "شغلت: ${chosen.title.take(50)}"
                        reply.value = AssistantReply(msg, "executed", chosen.id)
                        executed.value = true
                        source.value = "تشغيل فوري من المكتبة"
                        if (speakReply) AppVoiceSpeaker.speak(app, msg)
                    } else {
                        reply.value =
                            if (prefix.startsWith("شغل") && tracks.size == 1)
                                AssistantProtocol.proposal(
                                    AssistantReply("", "play_audio", tracks.single().id)
                                )
                            else
                                AssistantReply(
                                    if (tracks.isEmpty())
                                        "لا توجد مقاطع مطابقة؛ جرّب كلمات من العنوان أو فهرس الاستوديو"
                                    else "نتائج البحث (${tracks.size})؛ اختر المقطع الذي تقصده"
                                )
                        source.value = "بحث محلي في كامل المكتبة"
                    }
                    return@launch
                }

                // 5) Hybrid Stage 2: Qwen2.5-1.5B offline LLM for open-ended reasoning
                if (ModelStore(app).ready(LocalModels.assistant) && !StudioTasks.busy.value) {
                    StudioTasks.start(app, "ask", text)
                    source.value = "Qwen · جارٍ الفهم والتحليل محليًا"
                } else {
                    reply.value =
                        AssistantReply(
                            "لم أتعرف على أمر مباشر. جرّب مثلًا: «سجل قهوة بـ 18 ريال»، «موجز الصباح»، «وين وقفت سيارتي»، أو نزّل Qwen للأسئلة المفتوحة."
                        )
                    source.value = "نموذج أوامرك المحلي (44 أمرًا)"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "تعذر فهم الأمر"
            } finally {
                busy.value = false
            }
        }
    }

    fun execute(audio: AudioViewModel? = boundAudio) = work {
        if (executed.value) return@work
        val command = reply.value ?: return@work
        executed.value = true
        try {
            if (
                command.action in
                    setOf(
                        "play_audio",
                        "pause_audio",
                        "resume_audio",
                        "next_audio",
                        "previous_audio",
                    )
            ) {
                check(audio?.connected?.value == true) {
                    "المشغل غير متصل بعد؛ افتح مكتبة الصوتيات ثم حاول مجددًا"
                }
                if (command.action != "play_audio")
                    check(AudioLive.state.value.id.isNotBlank()) { "اختر مقطعًا من المكتبة أولًا" }
            }
            when (command.action) {
                "navigate" -> {
                    require(command.value in AssistantNavigation.pages)
                    AssistantNavigation.requests.emit(command.value)
                    VoiceUndoBus.push(VoiceUndoAction.NavigateBack(currentRoute, "رجوع للصفحة السابقة"))
                }
                "resume_audio" -> {
                    check(AudioLive.state.value.id.isNotBlank()) { "اختر مقطعًا من المكتبة أولًا" }
                    audio!!.resume()
                }
                "pause_audio" -> audio!!.pause()
                "next_audio" -> audio!!.next()
                "previous_audio" -> audio!!.previous()
                "scene" -> audio?.scene(AudioScene.entries.first { it.key == command.value })
                "play_audio",
                "favorite_audio" -> {
                    val track = app.audio.dao.track(command.value) ?: error("المقطع غير موجود")
                    if (command.action == "play_audio") audio!!.play(track, listOf(track))
                    else if (!track.favorite) audio!!.favorite(track.id)
                }
                "search_audio" -> {
                    val query = AppCommands.normalized(command.value)
                    results.value =
                        app.audio.dao
                            .tracks()
                            .first()
                            .filter {
                                AppCommands.normalized(it.title + " " + it.artist + " " + it.album)
                                    .contains(query)
                            }
                            .take(30)
                }
                "organize" -> AssistantNavigation.requests.emit("studio")
                "daily_briefing",
                "where_is_my_car",
                "save_parking",
                "smart_queue",
                "bookmark_here",
                "delete_last_expense",
                "unified_backup",
                "adaptive_boost",
                "rewind_15" -> {
                    ZeroConfirmExecutor.executeIntentImmediately(
                        app = app,
                        intent = AppIntent(command.action, command.action, command.action, command.value, emptyList()),
                        audio = audio,
                        speakReply = profile.value.speak,
                        currentPage = currentRoute,
                    )
                }
                else -> error("هذا الرد لا يتضمن أمرًا قابلًا للتنفيذ")
            }
            message.value = "أُرسل الإجراء المحدد"
        } catch (e: Exception) {
            executed.value = false
            throw e
        }
    }

    fun export(uri: Uri) = work {
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(store.export().toByteArray())
            }
        }
        message.value = "حُفظ ملف نموذجك وتخصيصك وأمثلتك"
    }

    fun restore(uri: Uri) = work {
        withContext(Dispatchers.IO) {
            val data =
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        require(output.size() + size <= PERSONAL_MODEL_MAX_BYTES)
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
            store.restore(data.toString(Charsets.UTF_8))
        }
        profile.value = store.profile()
        examples.value = store.examples()
        message.value = "استُورد تخصيصك وأُعيد تدريب النموذج محليًا"
    }
}
