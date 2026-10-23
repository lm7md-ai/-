package sa.rihla.ui

import android.content.Intent
import android.location.LocationManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.*
import sa.rihla.data.LocalBackup
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
    var pending by remember { mutableStateOf<Pair<String, sa.rihla.data.BackupPreview>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val backup = remember { LocalBackup(vm.app.database) }
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
                        pending = payload
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        vm.error.value = "backup_invalid"
                    } finally {
                        busy = false
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
    pending?.let { (text, preview) ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text(tr("restore_backup")) },
            text = {
                Column {
                    Text(num(preview.outings.toDouble(), 0) + " " + tr("outings_count"))
                    Text(num(preview.expenses.toDouble(), 0) + " " + tr("expenses"))
                    Text(tr("restore_empty_only"))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { backup.restore(text) }
                                vm.app.settings.smsImport(false)
                                vm.app.settings.onboard()
                                sa.rihla.tracking.HomeGeofences.restore(vm.app)
                                pending = null
                                vm.error.value = "backup_restored"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                vm.error.value =
                                    if (error.message?.contains("BACKUP_NOT_EMPTY") == true)
                                        "restore_empty_only"
                                    else "backup_invalid"
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
                TextButton(enabled = !busy, onClick = { pending = null }) { Text(tr("cancel")) }
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
            tr(if (PurchaseNotificationService.allowed(context) || sa.rihla.sms.PurchaseSmsReceiver.allowed(context)) "allowed" else "denied"),
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
