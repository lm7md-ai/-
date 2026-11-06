package sa.rihla.ui

import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.*
import sa.rihla.data.LocalBackup
import sa.rihla.data.RestoreMode
import sa.rihla.data.UnifiedBackupManager
import sa.rihla.sms.PurchaseNotificationService
import sa.rihla.tracking.TrackingService

@Composable
fun ExportDocument(
    vm: JourneyViewModel,
    label: String,
    filename: String,
    mime: String,
    enabled: Boolean = true,
    produce: suspend () -> String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            if (uri != null)
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) {
                            val bytes = produce().toByteArray(Charsets.UTF_8)
                            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                                it.write(bytes)
                            }
                        }
                        vm.error.value = "file_saved"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        vm.error.value = "operation_failed"
                    } finally {
                        busy = false
                    }
                }
        }
    OutlinedButton(
        onClick = { launcher.launch(filename) },
        enabled = !busy && enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.FileDownload, null)
        Spacer(Modifier.width(8.dp))
        Text(tr(if (busy) "working" else label))
    }
}

@Composable
fun DataTools(vm: JourneyViewModel, data: ScreenData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingJson by remember { mutableStateOf<Pair<String, sa.rihla.data.BackupPreview>?>(null) }
    var pendingZipUri by remember { mutableStateOf<Uri?>(null) }
    var restoreMode by remember { mutableStateOf(RestoreMode.SMART_MERGE) }
    var statusMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var snapshotRefresh by remember { mutableIntStateOf(0) }
    val backup = remember { LocalBackup(vm.app.database) }
    val unified = remember { UnifiedBackupManager(vm.app) }
    val localSnapshots = remember(snapshotRefresh) { unified.listLocalAutoBackups() }

    val zipExport =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    busy = true
                    try {
                        val summary =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                                    unified.exportToStream(out)
                                }
                            }
                        statusMessage =
                            "✓ صُدّرت النسخة الموحدة (${summary.outings} مشوار · ${summary.expenses} مصروف · ${summary.audioTracks} مقطع · ${summary.transfers} حوالة · ${summary.receipts} فاتورة)"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        vm.error.value = "operation_failed"
                    } finally {
                        busy = false
                    }
                }
            }
        }

    val zipImport =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) pendingZipUri = uri
        }

    val safFolderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                scope.launch {
                    vm.app.settings.update { it.copy(unifiedBackupFolderUri = uri.toString()) }
                    statusMessage = "✓ رُبط مجلد النسخ الاحتياطي التلقائي بنجاح"
                }
            }
        }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    busy = true
                    try {
                        val payload =
                            withContext(Dispatchers.IO) {
                                val bytes =
                                    context.contentResolver.openInputStream(uri)!!.use { input ->
                                        val output = java.io.ByteArrayOutputStream()
                                        val buffer = ByteArray(32768)
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            require(output.size() + count <= LocalBackup.maxBytes)
                                            output.write(buffer, 0, count)
                                        }
                                        output.toByteArray()
                                    }
                                val text = bytes.toString(Charsets.UTF_8)
                                text to LocalBackup.preview(text)
                            }
                        pendingJson = payload
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        vm.error.value = "backup_invalid"
                    } finally {
                        busy = false
                    }
                }
        }

    // v7.0 Unified Single-Archive Backup & Smart Merge Restore Card
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("📦 النسخة الاحتياطية الموحدة الشاملة (v7.0 ZIP)", style = MaterialTheme.typography.titleMedium)
            Text(
                "ملف واحد (.zip) يضم كل مشاويرك، مصاريفك، الفواتير المصورة، مكتبة المحاضرات وعلاماتها ونصوصها، حوالات الوالد، وإعداداتك ومساعدك الشخصي.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    zipExport.launch(
                        "mashwari-unified-${LocalDate.now(sa.rihla.core.SaudiZone)}.zip"
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Archive, null)
                Spacer(Modifier.width(8.dp))
                Text("تصدير النسخة الموحدة الشاملة (.zip)")
            }
            OutlinedButton(
                onClick = {
                    zipImport.launch(
                        arrayOf("application/zip", "application/octet-stream", "*/*")
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Restore, null)
                Spacer(Modifier.width(8.dp))
                Text("استعادة أو دمج نسخة موحدة (.zip)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("نسخ تلقائي يومي لآخر 7 نسخ")
                    Text(
                        "يُحفظ تلقائيًا عند فتح التطبيق أو الوصول للمنزل مع حذف الأقدم من 7 نسخ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = data.settings.autoDailyBackup,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            vm.app.settings.update { it.copy(autoDailyBackup = enabled) }
                        }
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { safFolderPicker.launch(null) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (data.settings.unifiedBackupFolderUri.isBlank()) "ربط مجلد خارجي"
                        else "تغيير المجلد الخارجي ✓"
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val f = unified.createManualSnapshotFile()
                                snapshotRefresh++
                                statusMessage = "✓ حُفظت لقطة فورية: ${f.name}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("حفظ لقطة الآن")
                }
            }
            if (localSnapshots.isNotEmpty()) {
                Text(
                    "النسخ التلقائية المحفوظة محليًا (${localSnapshots.size}/7): آخرها ${localSnapshots.first().name}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (statusMessage.isNotBlank()) {
                Text(statusMessage, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    Panel("backup_tools", Icons.Default.FolderOpen) {
        Text(tr("backup_explanation"))
        ExportDocument(
            vm,
            "export_backup",
            "rihla-${LocalDate.now(sa.rihla.core.SaudiZone)}.json",
            "application/json",
        ) {
            backup.export()
        }
        OutlinedButton(
            onClick = {
                picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            Text(tr(if (busy) "working" else "restore_backup"))
        }
        Text(tr("backup_privacy"), style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse(
                            "https://github.com/lm7md-ai/-/blob/main/android/docs/WINDOWS_MIGRATION.md"
                        ),
                    )
                )
            }
        ) {
            Text(tr("legacy_transfer_help"))
        }
    }

    pendingZipUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingZipUri = null },
            title = { Text("استعادة النسخة الموحدة (.zip)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اختر أسلوب الاستعادة:")
                    FilterChip(
                        selected = restoreMode == RestoreMode.SMART_MERGE,
                        onClick = { restoreMode = RestoreMode.SMART_MERGE },
                        label = { Text("دمج ذكي بدون مسح بياناتي الحالية (موصى به)") },
                    )
                    FilterChip(
                        selected = restoreMode == RestoreMode.FULL_OVERWRITE,
                        onClick = { restoreMode = RestoreMode.FULL_OVERWRITE },
                        label = { Text("استبدال كامل لجميع السجلات") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val summary =
                                    withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(uri)!!.use { inp ->
                                            unified.restoreFromStream(inp, restoreMode)
                                        }
                                    }
                                vm.app.settings.onboard()
                                sa.rihla.tracking.HomeGeofences.restore(vm.app)
                                pendingZipUri = null
                                statusMessage =
                                    "✓ تمت الاستعادة (${summary.outings} مشوار · ${summary.expenses} مصروف · ${summary.audioTracks} مقطع)"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                vm.error.value = "backup_invalid"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("بدء الاستعادة")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingZipUri = null }) {
                    Text(tr("cancel"))
                }
            },
        )
    }

    pendingJson?.let { (text, preview) ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingJson = null },
            title = { Text(tr("restore_backup")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(num(preview.outings.toDouble(), 0) + " " + tr("outings_count"))
                    Text(num(preview.expenses.toDouble(), 0) + " " + tr("expenses"))
                    FilterChip(
                        selected = restoreMode == RestoreMode.SMART_MERGE,
                        onClick = { restoreMode = RestoreMode.SMART_MERGE },
                        label = { Text("دمج ذكي مع البيانات الحالية") },
                    )
                    FilterChip(
                        selected = restoreMode == RestoreMode.FULL_OVERWRITE,
                        onClick = { restoreMode = RestoreMode.FULL_OVERWRITE },
                        label = { Text("استبدال كامل للسجل") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { backup.restore(text, restoreMode) }
                                vm.app.settings.smsImport(false)
                                vm.app.settings.onboard()
                                sa.rihla.tracking.HomeGeofences.restore(vm.app)
                                pendingJson = null
                                vm.error.value = "backup_restored"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                vm.error.value = "backup_invalid"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(tr("restore_backup"))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingJson = null }) {
                    Text(tr("cancel"))
                }
            },
        )
    }
}

@Composable
fun DeviceReadiness(data: ScreenData) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val battery =
        refresh >= 0 &&
            context
                .getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
    val gps =
        context
            .getSystemService(LocationManager::class.java)
            .isProviderEnabled(LocationManager.GPS_PROVIDER)
    Panel("device_readiness", Icons.Default.VerifiedUser) {
        Text(
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            style = MaterialTheme.typography.bodySmall,
        )
        Metric(
            "fine_location",
            tr(if (TrackingService.fineAllowed(context)) "allowed" else "denied"),
        )
        Metric("gps_signal", tr(if (gps) "enabled" else "disabled"))
        Metric(
            "background_location",
            tr(if (TrackingService.backgroundAllowed(context)) "allowed" else "denied"),
        )
        Metric("background_battery", tr(if (battery) "unrestricted" else "system_managed"))
        Metric(
            "sms_access",
            tr(
                if (
                    PurchaseNotificationService.allowed(context) ||
                        sa.rihla.sms.PurchaseSmsReceiver.allowed(context)
                )
                    "allowed"
                else "denied"
            ),
        )
        Text(tr("redmagic_help"), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(tr("battery_settings"))
        }
    }
}
