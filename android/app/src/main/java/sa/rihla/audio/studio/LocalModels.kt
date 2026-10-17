package sa.rihla.audio.studio

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import androidx.work.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ModelFile(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val url: String,
)

object LocalModels {
    val assistant =
        ModelFile(
            "assistant",
            "Qwen2.5-1.5B-q8.litertlm",
            1597922856,
            "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/5e6e7a58d03777898b31330a15d2e0a5a9107de3/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        )
    private const val separationBase =
        "https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems-fp16/resolve/93ba771920ade509f8cbd6825b1a90856c797e08/"
    val vocals =
        ModelFile(
            "vocals",
            "vocals.fp16.onnx",
            19681017,
            "24cef84aedcd1fe87c0b743ef3370ad34dc1fabf6c9014d6128a75a538c7b668",
            separationBase + "vocals.fp16.onnx",
        )
    val accompaniment =
        ModelFile(
            "accompaniment",
            "accompaniment.fp16.onnx",
            19681024,
            "d14cea55793cc531a5875f5f4da08207d1c5ab9292e8e0099a104eecb014fcc0",
            separationBase + "accompaniment.fp16.onnx",
        )
    val all = listOf(assistant, vocals, accompaniment)

    fun checksum(file: File, checkActive: () -> Unit = {}) =
        file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkActive()
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
}

data class ModelStatus(
    val ready: Boolean = false,
    val progress: Int = 0,
    val state: String = "غير منزّل",
)

class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("local_models", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)

    fun file(model: ModelFile) =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), model.name)

    private fun stamp(file: File) = "${file.length()}:${file.lastModified()}"

    fun ready(model: ModelFile) =
        file(model).let {
            it.isFile &&
                it.length() == model.size &&
                prefs.getString("verified_${model.id}", null) == "${model.sha256}:${stamp(it)}"
        }

    fun requireReady(model: ModelFile): File {
        check(ready(model)) { "نزّل النموذج وانتظر التحقق منه أولًا" }
        return file(model)
    }

    fun status(model: ModelFile): ModelStatus {
        if (ready(model)) return ModelStatus(true, 100, "جاهز · يعمل دون إنترنت")
        val id = prefs.getLong(model.id, -1)
        if (id < 0)
            return ModelStatus(state = prefs.getString("error_${model.id}", "غير منزّل").orEmpty())
        manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded =
                    c.getLong(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                val progress = (downloaded * 100 / model.size).toInt().coerceIn(0, 99)
                return ModelStatus(
                    progress = progress,
                    state =
                        when (state) {
                            DownloadManager.STATUS_SUCCESSFUL -> "اكتمل التنزيل؛ جارٍ التحقق"
                            DownloadManager.STATUS_FAILED -> "فشل التنزيل؛ ألغِه ثم أعد المحاولة"
                            DownloadManager.STATUS_PAUSED -> "متوقف مؤقتًا؛ راجع الشبكة والمساحة"
                            else -> "تنزيل ${progress}٪"
                        },
                )
            }
        }
        return ModelStatus(state = "التنزيل غير موجود؛ أعد المحاولة")
    }

    fun download(model: ModelFile, metered: Boolean) {
        check(!StudioTasks.busy.value) { "انتظر انتهاء المعالجة" }
        if (ready(model)) return
        check(context.getExternalFilesDir("models") != null) { "مجلد التنزيل غير متاح حاليًا" }
        val destination = file(model)
        destination.parentFile!!.mkdirs()
        check(destination.parentFile!!.usableSpace > model.size + 256L * 1024 * 1024) {
            "المساحة المتاحة غير كافية"
        }
        cancel(model)
        val request =
            DownloadManager.Request(Uri.parse(model.url))
                .setTitle("مشواري · ${model.name}")
                .setAllowedOverMetered(metered)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(context, "models", model.name)
        val id = manager.enqueue(request)
        prefs
            .edit()
            .putLong(model.id, id)
            .remove("verified_${model.id}")
            .remove("error_${model.id}")
            .apply()
    }

    fun cancel(model: ModelFile) {
        check(!StudioTasks.busy.value) { "انتظر انتهاء المعالجة" }
        WorkManager.getInstance(context).cancelUniqueWork("verify_${model.id}")
        val id = prefs.getLong(model.id, -1)
        if (id >= 0) manager.remove(id)
        file(model).delete()
        prefs
            .edit()
            .remove(model.id)
            .remove("verified_${model.id}")
            .remove("error_${model.id}")
            .apply()
    }

    fun checkDownloads() {
        for (model in LocalModels.all) if (
            !ready(model) && status(model).state.startsWith("اكتمل")
        ) {
            val data = workDataOf("model" to model.id, "download" to prefs.getLong(model.id, -1))
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "verify_${model.id}",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ModelVerifyWorker>().setInputData(data).build(),
                )
        }
    }

    suspend fun verify(model: ModelFile, id: Long) =
        withContext(Dispatchers.IO) {
            if (prefs.getLong(model.id, -1) != id || id < 0) return@withContext
            val active = currentCoroutineContext()
            val f = file(model)
            val valid =
                f.isFile &&
                    f.length() == model.size &&
                    LocalModels.checksum(f) { active.ensureActive() } == model.sha256
            if (prefs.getLong(model.id, -1) != id) return@withContext
            if (valid)
                prefs
                    .edit()
                    .putString("verified_${model.id}", "${model.sha256}:${stamp(f)}")
                    .apply()
            else {
                prefs
                    .edit()
                    .putString("error_${model.id}", "فشل التحقق من النموذج؛ أعد تنزيله")
                    .remove("verified_${model.id}")
                    .remove(model.id)
                    .apply()
                manager.remove(id)
                f.delete()
            }
        }
}

class ModelVerifyWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val model =
            LocalModels.all.find { it.id == inputData.getString("model") }
                ?: return Result.failure()
        return try {
            ModelStore(applicationContext).verify(model, inputData.getLong("download", -1))
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ModelStore(context).checkDownloads()
    }
}
