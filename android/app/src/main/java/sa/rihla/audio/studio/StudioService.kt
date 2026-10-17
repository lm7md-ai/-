package sa.rihla.audio.studio

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import sa.rihla.RihlaApp

data class StudioState(
    val stage: String = "",
    val progress: Int = 0,
    val plan: List<FilingSuggestion> = emptyList(),
    val reply: AssistantReply? = null,
)

object StudioTasks {
    val busy = MutableStateFlow(false)
    val state = MutableStateFlow(StudioState())

    fun start(context: Context, task: String, value: String = "") {
        check(!busy.value) { "توجد عملية قيد التنفيذ" }
        ContextCompat.startForegroundService(
            context,
            Intent(context, StudioService::class.java)
                .putExtra("task", task)
                .putExtra("value", value),
        )
    }

    fun cancel(context: Context) {
        context.startService(Intent(context, StudioService::class.java).setAction("cancel"))
    }
}

class StudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var child: Process? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val app
        get() = application as RihlaApp

    private val prefs by lazy { getSharedPreferences("studio_jobs", MODE_PRIVATE) }
    private var noticeAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("studio", "معالجة محلية", NotificationManager.IMPORTANCE_LOW)
            )
    }

    private fun notice(text: String): Notification {
        val cancel =
            PendingIntent.getService(
                this,
                501,
                Intent(this, StudioService::class.java).setAction("cancel"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val open =
            PendingIntent.getActivity(
                this,
                502,
                Intent(this, sa.rihla.MainActivity::class.java).putExtra("open_audio", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, "studio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("مشواري · الاستوديو المحلي")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "إلغاء", cancel)
            .build()
    }

    private fun progress(text: String, percent: Int = 0) {
        StudioTasks.state.value =
            StudioTasks.state.value.copy(stage = text, progress = percent.coerceIn(0, 100))
        if (System.currentTimeMillis() - noticeAt > 1000) {
            getSystemService(NotificationManager::class.java).notify(500, notice(text))
            noticeAt = System.currentTimeMillis()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            job?.cancel()
            child?.destroyForcibly()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val task =
            intent?.getStringExtra("task")
                ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
        val value = intent.getStringExtra("value").orEmpty()
        if (task !in setOf("ask", "classify", "separate", "move")) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type =
            if (Build.VERSION.SDK_INT >= 35 && task == "separate")
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(500, notice("تجهيز المعالجة"), type)
        else startForeground(500, notice("تجهيز المعالجة"))
        StudioTasks.busy.value = true
        StudioTasks.state.value =
            StudioTasks.state.value.copy(stage = "تجهيز المعالجة", progress = 0, reply = null)
        prefs.edit().putBoolean("running", true).apply()
        wakeLock =
            getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:studio")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        job = scope.launch {
            try {
                when (task) {
                    "ask" -> {
                        progress("تحميل المساعد المحلي؛ قد يستغرق البدء قليلًا")
                        val reply =
                            AssistantProtocol.unsupported(value)
                                ?: LocalAssistant(this@StudioService).use {
                                    it.open()
                                    it.ask(value)
                                }
                        StudioTasks.state.value =
                            StudioState("اقتراح جاهز للمراجعة", 100, reply = reply)
                    }
                    "classify" -> {
                        val tracks = app.audio.dao.tracks().first()
                        require(tracks.isNotEmpty())
                        progress("تحميل نموذج التصنيف")
                        val plan =
                            LocalAssistant(this@StudioService).use { ai ->
                                ai.open()
                                AudioOrganizer.classify(ai, tracks) {
                                    progress("تصنيف العناوين · $it٪", it)
                                }
                            }
                        savePlan(plan)
                        StudioTasks.state.value =
                            StudioState("راجع الموضوع والمصدر قبل التطبيق", 100, plan = plan)
                    }
                    "move" -> {
                        val plan = StudioPlans.load(this@StudioService)
                        require(plan.isNotEmpty())
                        progress("نسخ الملفات والتحقق منها")
                        val result =
                            AudioOrganizer.move(
                                this@StudioService,
                                app.audio,
                                plan,
                                android.net.Uri.parse(value),
                            ) {
                                progress("تنظيم الملفات · $it٪", it)
                            }
                        StudioTasks.state.value = StudioState(result, 100, plan = plan)
                    }
                    "separate" -> separate(value)
                }
            } catch (cancelled: CancellationException) {
                StudioTasks.state.value =
                    StudioTasks.state.value.copy(stage = "أُلغيت العملية؛ الملفات الأصلية محفوظة")
                throw cancelled
            } catch (error: Throwable) {
                StudioTasks.state.value =
                    StudioTasks.state.value.copy(
                        stage =
                            when (error) {
                                is UnsatisfiedLinkError -> "تعذر تشغيل المحرك على هذا الجهاز"
                                is IllegalArgumentException,
                                is IllegalStateException ->
                                    error.message?.take(240) ?: "تعذرت المعالجة"
                                else -> "تعذرت المعالجة المحلية؛ راجع النموذج والمساحة وإذن الملف"
                            }
                    )
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                child?.destroyForcibly()
                child = null
                StudioTasks.busy.value = false
                prefs.edit().putBoolean("running", false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun savePlan(plan: List<FilingSuggestion>) = StudioPlans.save(this, plan)

    private suspend fun separate(id: String) {
        val track = app.audio.dao.track(id) ?: error("اختر مقطعًا موجودًا")
        val models = ModelStore(this)
        val vocals = models.requireReady(LocalModels.vocals)
        val accompaniment = models.requireReady(LocalModels.accompaniment)
        val binary = File(applicationInfo.nativeLibraryDir, "libmashwari_separate.so")
        check(binary.canExecute()) { "محرك فصل الموسيقى غير متاح في هذه الحزمة" }
        val directory = File(cacheDir, "studio-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val destination = File(filesDir, "processed_audio").apply { mkdirs() }
        check(destination.usableSpace > track.durationMs / 1000 * 20000 + 128L * 1024 * 1024) {
            "المساحة المتاحة لا تكفي لحفظ نسخة الصوت المعالج"
        }
        val temporary = File(destination, "${java.util.UUID.randomUUID()}.part")
        var finalFile: File? = null
        var imported = false
        try {
            val context = coroutineContext
            fun checkActive() {
                context.ensureActive()
            }
            StudioAacEncoder(temporary).use { encoder ->
                val joiner = StereoJoiner { encoder.add(it, ::checkActive) }
                val windows = StereoWindows { samples ->
                    checkActive()
                    val input = File(directory, "input.wav")
                    val output = File(directory, "vocals.wav")
                    val background = File(directory, "background.wav")
                    // Context padding keeps inverse-STFT edge artifacts and hop rounding outside
                    // retained speech.
                    val padding = 4096 * 2
                    val padded = FloatArray(samples.size + padding * 2)
                    samples.copyInto(padded, padding)
                    StudioWave.write(input, padded)
                    output.delete()
                    background.delete()
                    val command =
                        listOf(
                            binary.path,
                            "--spleeter-vocals=${vocals.path}",
                            "--spleeter-accompaniment=${accompaniment.path}",
                            "--num-threads=2",
                            "--input-wav=${input.path}",
                            "--output-vocals-wav=${output.path}",
                            "--output-accompaniment-wav=${background.path}",
                        )
                    val builder = ProcessBuilder(command).redirectErrorStream(true)
                    builder.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
                    val process = builder.start()
                    child = process
                    val drain = Thread {
                        runCatching {
                            process.inputStream.use { stream ->
                                val buffer = ByteArray(4096)
                                while (stream.read(buffer) >= 0) {}
                            }
                        }
                    }
                        .apply {
                            isDaemon = true
                            start()
                        }
                    val deadline = System.nanoTime() + 300_000_000_000L
                    while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                        checkActive()
                        check(System.nanoTime() < deadline) {
                            "استغرقت دفعة الفصل أكثر من الحد؛ جرّب مقطعًا أقصر"
                        }
                    }
                    check(process.exitValue() == 0) {
                        "تعذر فصل الموسيقى على الجهاز (رمز ${process.exitValue()})"
                    }
                    child = null
                    drain.join(1000)
                    val result = StudioWave.read(output)
                    check(
                        result.size >= padding + samples.size &&
                            kotlin.math.abs(result.size - padded.size) <= 4096
                    ) {
                        "مدة الصوت الناتج غير متوافقة"
                    }
                    joiner.add(result.copyOfRange(padding, padding + samples.size))
                    input.delete()
                    output.delete()
                    background.delete()
                }
                progress("فصل الموسيقى؛ قد تستغرق العملية وقتًا")
                StudioAudioDecoder.decode(
                    this,
                    android.net.Uri.parse(track.uri),
                    ::checkActive,
                    { ms ->
                        val percent =
                            (ms * 90 / track.durationMs.coerceAtLeast(1)).toInt().coerceIn(0, 90)
                        progress("فصل الموسيقى · $percent٪", percent)
                    },
                    windows::add,
                )
                windows.finish()
                joiner.finish()
                progress("حفظ نسخة الصوت", 95)
                encoder.finish(::checkActive)
            }
            val file =
                File(
                    destination,
                    "${folderLabel(track.title).take(65)}-كلام-${java.util.UUID.randomUUID().toString().take(8)}.m4a",
                )
            check(temporary.renameTo(file))
            finalFile = file
            val uri = FileProvider.getUriForFile(this, "$packageName.audiofiles", file)
            val result = app.audio.importFiles(listOf(uri))
            check(result.added == 1) { "تعذر إضافة الناتج إلى المكتبة" }
            imported = true
            StudioTasks.state.value =
                StudioState(
                    "حُفظ مقطع الكلام في المكتبة؛ الأصل لم يتغير. قد تبقى آثار موسيقى أو تتأثر بعض الكلمات.",
                    100,
                )
        } finally {
            child?.destroyForcibly()
            child = null
            directory.deleteRecursively()
            temporary.delete()
            if (!imported) finalFile?.delete()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        child?.destroyForcibly()
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        job?.cancel()
        child?.destroyForcibly()
        scope.cancel()
        super.onDestroy()
    }
}
