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
    private var request: Job? = null

    init {
        viewModelScope.launch {
            StudioTasks.state.collect { state ->
                if (state.reply != null) {
                    reply.value = state.reply
                    executed.value = false
                    source.value = "Qwen · محلي"
                }
            }
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

    fun ask(text: String) {
        if (busy.value) return
        request = viewModelScope.launch {
            busy.value = true
            reply.value = null
            executed.value = false
            results.value = emptyList()
            message.value = ""
            try {
                require(text.trim().length in 1..1200)
                AssistantProtocol.unsupported(text)?.let {
                    reply.value = it
                    source.value = "حدود أوامر التطبيق"
                    return@launch
                }
                val prediction = withContext(Dispatchers.IO) { store.model().predict(text) }
                if (prediction != null) {
                    val intent = prediction.intent
                    if (intent.action == "summary") {
                        val zone = java.time.ZoneId.of("Asia/Riyadh")
                        val now = java.time.Instant.now()
                        val today = now.atZone(zone).toLocalDate()
                        val start =
                            (if (intent.value == "expenses_month") today.withDayOfMonth(1)
                                else today)
                                .atStartOfDay(zone)
                                .toInstant()
                                .toEpochMilli()
                        val text =
                            if (intent.value.startsWith("expenses")) {
                                val rows =
                                    app.repository.dao.expenses().first().filter {
                                        it.createdAt in start..now.toEpochMilli()
                                    }
                                val total =
                                    rows.fold(0L) { sum, row ->
                                        Math.addExact(sum, row.amountHalala)
                                    }
                                val money =
                                    java.text.NumberFormat.getNumberInstance(
                                            java.util.Locale.forLanguageTag("ar-SA")
                                        )
                                        .apply {
                                            minimumFractionDigits = 2
                                            maximumFractionDigits = 2
                                        }
                                        .format(java.math.BigDecimal.valueOf(total, 2))
                                "المصروفات المسجلة ${if(intent.value=="expenses_month")"هذا الشهر" else "اليوم"}: $money ر.س · ${rows.size} عملية."
                            } else {
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
                        reply.value = AssistantReply(text)
                        source.value = "حساب مباشر من سجلات التطبيق بتوقيت السعودية"
                        return@launch
                    }
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
                    reply.value =
                        if (prefix.startsWith("شغل") && tracks.size == 1)
                            AssistantProtocol.proposal(
                                AssistantReply("", "play_audio", tracks.single().id)
                            )
                        else
                            AssistantReply(
                                if (tracks.isEmpty())
                                    "لا توجد مقاطع مطابقة؛ جرّب كلمات من العنوان أو المصدر"
                                else "نتائج البحث؛ اختر المقطع الذي تقصده"
                            )
                    source.value = "بحث محلي في كامل المكتبة"
                    return@launch
                }
                if (ModelStore(app).ready(LocalModels.assistant) && !StudioTasks.busy.value) {
                    StudioTasks.start(app, "ask", text)
                    source.value = "Qwen · جارٍ إعداد اقتراح"
                } else {
                    reply.value =
                        AssistantReply(
                            "لم أتعرف على أمر واضح. علّمني هذه العبارة من تخصيص مساعدي، أو نزّل Qwen للأسئلة الأوسع."
                        )
                    source.value = "نموذج أوامرك المحلي"
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

    fun execute(audio: AudioViewModel) = work {
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
                check(audio.connected.value) {
                    "المشغل غير متصل بعد؛ افتح مكتبة الصوتيات ثم حاول مجددًا"
                }
                if (command.action != "play_audio")
                    check(AudioLive.state.value.id.isNotBlank()) { "اختر مقطعًا من المكتبة أولًا" }
            }
            when (command.action) {
                "navigate" -> {
                    require(command.value in AssistantNavigation.pages)
                    AssistantNavigation.requests.emit(command.value)
                }
                "resume_audio" -> {
                    check(AudioLive.state.value.id.isNotBlank()) { "اختر مقطعًا من المكتبة أولًا" }
                    audio.resume()
                }
                "pause_audio" -> audio.pause()
                "next_audio" -> audio.next()
                "previous_audio" -> audio.previous()
                "scene" -> audio.scene(AudioScene.entries.first { it.key == command.value })
                "play_audio",
                "favorite_audio" -> {
                    val track = app.audio.dao.track(command.value) ?: error("المقطع غير موجود")
                    if (command.action == "play_audio") audio.play(track, listOf(track))
                    else if (!track.favorite) audio.favorite(track.id)
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
