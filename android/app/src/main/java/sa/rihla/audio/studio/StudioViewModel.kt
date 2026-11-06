package sa.rihla.audio.studio

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import sa.rihla.RihlaApp
import sa.rihla.audio.*

object StudioPlans {
    fun file(context: android.content.Context) = File(context.filesDir, "studio/plan.json")

    fun save(context: android.content.Context, plan: List<FilingSuggestion>) {
        val destination = file(context)
        destination.parentFile!!.mkdirs()
        val temp = File(destination.parentFile, "plan.tmp")
        temp.writeText(
            JSONArray(
                    plan.map {
                        JSONObject()
                            .put("id", it.trackId)
                            .put("topic", folderLabel(it.topic))
                            .put("source", folderLabel(it.source))
                    }
                )
                .toString()
        )
        check(temp.renameTo(destination))
    }

    fun load(context: android.content.Context): List<FilingSuggestion> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        require(f.length() <= 16 * 1024 * 1024)
        val rows = JSONArray(f.readText())
        require(rows.length() <= 20000)
        return (0 until rows.length())
            .map {
                val row = rows.getJSONObject(it)
                FilingSuggestion(
                    row.getString("id"),
                    folderLabel(row.getString("topic")),
                    folderLabel(row.getString("source")),
                )
            }
            .also { require(it.distinctBy { row -> row.trackId }.size == it.size) }
    }
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as RihlaApp
    val store = ModelStore(app)
    val statuses = flow {
        while (currentCoroutineContext().isActive) {
            runCatching {
                store.checkDownloads()
                LocalModels.all.associate { it.id to store.status(it) }
            }
                .getOrNull()
                ?.let { emit(it) }
            delay(1500)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val plan = MutableStateFlow<List<FilingSuggestion>>(emptyList())
    val message = MutableStateFlow("")
    val editing = MutableStateFlow(false)
    val transcriptProgress = MutableStateFlow<Int?>(null)
    val transcriptQuery = MutableStateFlow("")
    val folders =
        app.audio.dao
            .folders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val filings =
        app.audio.dao
            .filings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allTranscripts =
        app.audio.dao
            .allTranscriptsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredTranscripts: StateFlow<List<AudioTranscript>> =
        combine(allTranscripts, transcriptQuery) { rows, q ->
            val clean = q.trim()
            if (clean.isBlank()) rows.take(150)
            else rows.filter { it.text.contains(clean, ignoreCase = true) }.take(200)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        work { plan.value = withContext(Dispatchers.IO) { StudioPlans.load(app) } }
        viewModelScope.launch {
            StudioTasks.state.collect { if (it.plan.isNotEmpty()) plan.value = it.plan }
        }
    }

    fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message?.take(240) ?: "تعذرت العملية"
            }
        }
    }

    fun start(task: String, value: String = "") = work {
        check(!editing.value)
        StudioTasks.start(app, task, value)
    }

    fun download(model: ModelFile, metered: Boolean) = work {
        withContext(Dispatchers.IO) { store.download(model, metered) }
    }

    fun remove(model: ModelFile) = work { withContext(Dispatchers.IO) { store.cancel(model) } }

    fun preliminary(tracks: List<AudioTrack>) {
        plan.value = tracks.map(AudioOrganizer::preliminary)
    }

    fun edit(index: Int, topic: String, source: String) {
        plan.value =
            plan.value.toMutableList().also {
                it[index] = it[index].copy(topic = topic.take(80), source = source.take(80))
            }
    }

    fun apply(tree: Uri?) = work {
        check(!StudioTasks.busy.value && !editing.value)
        check(plan.value.isNotEmpty())
        editing.value = true
        try {
            val snapshot = plan.value.toList()
            withContext(Dispatchers.IO) { StudioPlans.save(app, snapshot) }
            if (tree == null) {
                withContext(Dispatchers.IO) { AudioOrganizer.applyVirtual(app.audio, snapshot) }
                message.value = "حُفظ التصنيف داخل المكتبة"
            } else {
                app.contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                StudioTasks.start(app, "move", tree.toString())
            }
        } finally {
            editing.value = false
        }
    }

    fun saveTuning(id: String, scene: AudioScene) = work {
        require(app.audio.dao.track(id) != null)
        app.audio.dao.tuning(AudioTuning(id, scene.key))
        message.value = "حُفظ ملف الاستماع لهذا المقطع؛ يطبق عند تشغيله مجددًا"
    }

    fun indexBookmarkContexts(trackId: String) = work {
        check(transcriptProgress.value == null)
        transcriptProgress.value = 10
        try {
            val count = TranscriptEngine.indexBookmarkContexts(app, app.audio, trackId)
            message.value = "تمت فهرسة $count مقطع سياقي (±30 ثانية حول العلامات المرجعية)"
        } finally {
            transcriptProgress.value = null
        }
    }

    fun indexFullLecture(trackId: String) = work {
        check(transcriptProgress.value == null)
        transcriptProgress.value = 5
        try {
            val count =
                TranscriptEngine.indexFullLecture(app, app.audio, trackId) { p ->
                    transcriptProgress.value = p
                }
            message.value = "اكتملت فهرسة المحاضرة كاملة ($count فقرة زمنية قابلة للبحث)"
        } finally {
            transcriptProgress.value = null
        }
    }

    fun importTranscriptUri(trackId: String, uri: Uri) = work {
        val raw =
            withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                } ?: error("تعذر قراءة ملف النص")
            }
        val count = TranscriptEngine.importExternalTranscript(app.audio, trackId, raw)
        message.value = "تم استيراد وفهرسة $count فقرة نصية للمحاضرة"
    }

    fun clearTrackTranscripts(trackId: String) = work {
        withContext(Dispatchers.IO) { app.audio.dao.clearTranscriptsForTrack(trackId) }
        message.value = "مُسحت فهرسة النص لهذا المقطع"
    }
}
