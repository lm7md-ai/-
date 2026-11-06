package sa.rihla.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sa.rihla.RihlaApp
import sa.rihla.assistant.PersonalAssistantStore
import sa.rihla.audio.AudioBackup
import sa.rihla.audio.AudioDatabase
import sa.rihla.core.ReceiptStore
import sa.rihla.core.SaudiZone

data class UnifiedBackupSummary(
    val outings: Int,
    val expenses: Int,
    val audioTracks: Int,
    val transfers: Int,
    val receipts: Int,
    val exportedAt: Long,
)

/**
 * Bundles all 3 databases (journeys.db, audio_library.db, incoming_transfers.db),
 * DataStore Settings, PersonalAssistantStore (custom phrases & NB weights), and receipt photos
 * into a single portable ZIP archive (`mashwari-unified-backup.zip`).
 * Supports instant non-empty restore via SMART_MERGE or atomic FULL_OVERWRITE, plus rolling 7-day auto-backups.
 */
class UnifiedBackupManager(
    private val context: Context,
    private val journeyDb: JourneyDatabase = (context.applicationContext as RihlaApp).database,
    private val audioDb: AudioDatabase = (context.applicationContext as RihlaApp).audioDatabase,
    private val transferStore: TransferStore = (context.applicationContext as RihlaApp).transfers,
    private val settings: Settings = (context.applicationContext as RihlaApp).settings,
    private val assistantStore: PersonalAssistantStore = PersonalAssistantStore(context),
) {
    companion object {
        private val lock = Mutex()
        const val FORMAT = "MASHWARI_UNIFIED_BACKUP_V7"
        const val MAX_ENTRY_BYTES = 120 * 1024 * 1024
        const val MAX_ROLLING_BACKUPS = 7
    }

    fun autoBackupDir(): File =
        File(
            context.getExternalFilesDir("MashwariBackup") ?: File(context.filesDir, "backups"),
            "",
        ).apply { mkdirs() }

    suspend fun exportToStream(output: OutputStream): UnifiedBackupSummary =
        withContext(Dispatchers.IO) {
            val journeysJson = LocalBackup(journeyDb).export()
            val audioJson = AudioBackup(audioDb).export()
            val transfersJson = transferStore.export()
            val settingsJson = settings.exportJson()
            val assistantJson = assistantStore.export()
            val receiptFiles =
                ReceiptStore.receiptsDir(context)
                    .listFiles()
                    ?.filter { it.isFile && it.length() in 1..(15 * 1024 * 1024) }
                    .orEmpty()
            val now = System.currentTimeMillis()
            val jPreview = LocalBackup.preview(journeysJson)
            val audioTracksCount = JSONObject(audioJson).getJSONArray("tracks").length()
            val transfersCount = JSONObject(transfersJson).getJSONArray("items").length()
            val manifest =
                JSONObject()
                    .put("format", FORMAT)
                    .put("versionCode", 70)
                    .put("exportedAt", now)
                    .put("outings", jPreview.outings)
                    .put("expenses", jPreview.expenses)
                    .put("audioTracks", audioTracksCount)
                    .put("transfers", transfersCount)
                    .put("receipts", receiptFiles.size)
                    .toString()

            ZipOutputStream(output.buffered()).use { zip ->
                fun writeTextEntry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                writeTextEntry("manifest.json", manifest)
                writeTextEntry("journeys.json", journeysJson)
                writeTextEntry("audio_library.json", audioJson)
                writeTextEntry("incoming_transfers.json", transfersJson)
                writeTextEntry("settings.json", settingsJson)
                writeTextEntry("mashwari-my-assistant.json", assistantJson)
                for (receipt in receiptFiles) {
                    val safeName = receipt.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    zip.putNextEntry(ZipEntry("receipts/$safeName"))
                    receipt.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.finish()
            }
            UnifiedBackupSummary(
                outings = jPreview.outings,
                expenses = jPreview.expenses,
                audioTracks = audioTracksCount,
                transfers = transfersCount,
                receipts = receiptFiles.size,
                exportedAt = now,
            )
        }

    suspend fun restoreFromStream(
        input: InputStream,
        mode: RestoreMode = RestoreMode.SMART_MERGE,
    ): UnifiedBackupSummary =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val textEntries = HashMap<String, String>()
                val receiptBytes = HashMap<String, ByteArray>()
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name
                        require(!name.contains("..") && !name.startsWith("/")) { "INVALID_ZIP_PATH" }
                        val raw = readBounded(zip, MAX_ENTRY_BYTES)
                        if (name.startsWith("receipts/")) {
                            val fileName = name.removePrefix("receipts/")
                            if (fileName.isNotBlank() && !fileName.contains('/')) {
                                receiptBytes[fileName] = raw
                            }
                        } else {
                            textEntries[name] = raw.toString(Charsets.UTF_8)
                        }
                        zip.closeEntry()
                    }
                }

                // Support both Unified ZIP archives and legacy plain JSON backups if passed here
                val manifestRaw = textEntries["manifest.json"] ?: error("MISSING_UNIFIED_MANIFEST")
                val manifest = JSONObject(manifestRaw)
                require(manifest.getString("format") == FORMAT) { "UNSUPPORTED_UNIFIED_FORMAT" }

                val journeysJson = textEntries["journeys.json"] ?: error("MISSING_JOURNEYS_JSON")
                val audioJson = textEntries["audio_library.json"] ?: error("MISSING_AUDIO_JSON")
                val transfersJson = textEntries["incoming_transfers.json"]
                val settingsJson = textEntries["settings.json"]
                val assistantJson = textEntries["mashwari-my-assistant.json"]

                // Validate journeys & audio previews BEFORE mutating any store
                val jPreview = LocalBackup.preview(journeysJson)
                JSONObject(audioJson).also {
                    require(it.getString("format") == "MASHWARI_AUDIO")
                }

                // 1. Restore receipt files first so Expense.receiptImageUri paths resolve locally
                val receiptsDir = ReceiptStore.receiptsDir(context)
                if (mode == RestoreMode.FULL_OVERWRITE) {
                    receiptsDir.listFiles()?.forEach { it.delete() }
                }
                val remappedReceiptPaths = HashMap<String, String>()
                for ((fileName, bytes) in receiptBytes) {
                    val target = File(receiptsDir, fileName)
                    if (mode == RestoreMode.FULL_OVERWRITE || !target.exists()) {
                        target.writeBytes(bytes)
                    }
                    remappedReceiptPaths[fileName] = target.absolutePath
                }

                // Remap receiptImageUri filenames inside journeysJson to current device's filesDir/receipts/
                val normalizedJourneysJson = remapReceiptPaths(journeysJson, receiptsDir)

                // 2. Restore databases & stores
                val overwrite = mode == RestoreMode.FULL_OVERWRITE
                LocalBackup(journeyDb).restore(normalizedJourneysJson, mode)
                val audioCount = AudioBackup(audioDb).restore(audioJson, mode)
                val transferCount =
                    if (transfersJson != null) transferStore.restore(transfersJson, overwrite)
                    else 0
                if (settingsJson != null) {
                    settings.restoreJson(settingsJson, overwrite)
                }
                if (assistantJson != null) {
                    if (overwrite) {
                        assistantStore.restore(assistantJson)
                    } else {
                        mergeAssistantStore(assistantJson)
                    }
                }

                UnifiedBackupSummary(
                    outings = jPreview.outings,
                    expenses = jPreview.expenses,
                    audioTracks = audioCount,
                    transfers = transferCount,
                    receipts = receiptBytes.size,
                    exportedAt = manifest.optLong("exportedAt", System.currentTimeMillis()),
                )
            }
        }

    private fun mergeAssistantStore(assistantJson: String) {
        val currentExamples = assistantStore.examples()
        if (currentExamples.isEmpty()) {
            runCatching { assistantStore.restore(assistantJson) }
            return
        }
        runCatching {
            val root = JSONObject(assistantJson)
            val arr = root.optJSONArray("examples") ?: return@runCatching
            val incoming =
                (0 until arr.length()).map { idx ->
                    val o = arr.getJSONObject(idx)
                    sa.rihla.assistant.CommandExample(o.getString("text"), o.getString("intent"))
                }
            val combined =
                (currentExamples + incoming)
                    .distinctBy { it.text.trim() to it.intent }
                    .take(500)
            assistantStore.train(combined)
        }
    }

    private fun remapReceiptPaths(journeysJson: String, receiptsDir: File): String {
        val root = JSONObject(journeysJson)
        val tables = root.optJSONObject("tables") ?: return journeysJson
        val expenses = tables.optJSONArray("expenses") ?: return journeysJson
        for (i in 0 until expenses.length()) {
            val row = expenses.getJSONObject(i)
            if (!row.isNull("receiptImageUri") && row.has("receiptImageUri")) {
                val oldPath = row.optString("receiptImageUri", "")
                if (oldPath.isNotBlank()) {
                    val fileName = File(oldPath).name
                    val localCandidate = File(receiptsDir, fileName)
                    if (localCandidate.exists()) {
                        row.put("receiptImageUri", localCandidate.absolutePath)
                    }
                }
            }
        }
        return root.toString()
    }

    suspend fun runAutoBackupIfDue(forceOnHomeArrival: Boolean = false): File? =
        withContext(Dispatchers.IO) {
            val prefs = settings.flow.first()
            if (!prefs.autoDailyBackup) return@withContext null
            val statePrefs = context.getSharedPreferences("unified_backup_state", Context.MODE_PRIVATE)
            val todayStr =
                Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(SaudiZone)
                    .toLocalDate()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            val lastDate = statePrefs.getString("last_auto_backup_date", "")
            val lastWall = statePrefs.getLong("last_auto_backup_ms", 0L)
            val now = System.currentTimeMillis()
            if (!forceOnHomeArrival && lastDate == todayStr) return@withContext null
            if (forceOnHomeArrival && now - lastWall < 15 * 60_000L) return@withContext null

            val fileName = "mashwari-auto-$todayStr.zip"
            val localDir = autoBackupDir()
            val tempFile = File(localDir, "$fileName.tmp")
            val finalFile = File(localDir, fileName)
            tempFile.outputStream().use { out -> exportToStream(out) }
            if (finalFile.exists()) finalFile.delete()
            check(tempFile.renameTo(finalFile))

            // Also mirror to SAF folder if configured by user
            if (prefs.unifiedBackupFolderUri.isNotBlank()) {
                runCatching { mirrorToSafFolder(Uri.parse(prefs.unifiedBackupFolderUri), fileName, finalFile) }
            }

            pruneRollingBackups(localDir, MAX_ROLLING_BACKUPS)
            statePrefs
                .edit()
                .putString("last_auto_backup_date", todayStr)
                .putLong("last_auto_backup_ms", now)
                .apply()
            finalFile
        }

    suspend fun createManualSnapshotFile(): File =
        withContext(Dispatchers.IO) {
            val stamp =
                Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(SaudiZone)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val fileName = "mashwari-snapshot-$stamp.zip"
            val localDir = autoBackupDir()
            val tempFile = File(localDir, "$fileName.tmp")
            val finalFile = File(localDir, fileName)
            tempFile.outputStream().use { out -> exportToStream(out) }
            if (finalFile.exists()) finalFile.delete()
            check(tempFile.renameTo(finalFile))
            finalFile
        }

    fun listLocalAutoBackups(): List<File> =
        autoBackupDir()
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    internal fun pruneRollingBackups(dir: File, keepCount: Int = MAX_ROLLING_BACKUPS) {
        val backups =
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("mashwari-auto-") && it.name.endsWith(".zip") }
                ?.sortedByDescending { it.name }
                .orEmpty()
        backups.drop(keepCount).forEach { it.delete() }
    }

    private fun mirrorToSafFolder(treeUri: Uri, fileName: String, sourceFile: File) {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return
        tree.findFile(fileName)?.delete()
        val created = tree.createFile("application/zip", fileName) ?: return
        context.contentResolver.openOutputStream(created.uri)?.use { out ->
            sourceFile.inputStream().use { it.copyTo(out) }
        }
        val safAutos =
            tree.listFiles()
                .filter { it.name?.startsWith("mashwari-auto-") == true && it.name?.endsWith(".zip") == true }
                .sortedByDescending { it.name.orEmpty() }
        safAutos.drop(MAX_ROLLING_BACKUPS).forEach { it.delete() }
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            require(total <= maxBytes) { "ZIP_ENTRY_TOO_LARGE" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
