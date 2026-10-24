package sa.rihla.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import sa.rihla.RihlaApp
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.sms.*

@Composable
fun FatherTransfersScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as RihlaApp
    val store = app.transfers
    val revision by store.changes.collectAsState()
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<TransferRow>>(emptyList()) }
    var names by remember { mutableStateOf(store.aliases.joinToString("\n")) }
    var settings by remember { mutableStateOf(store.cutoff == 0L) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var filter by remember { mutableStateOf("FATHER") }
    var paste by remember { mutableStateOf(false) }
    var body by remember { mutableStateOf("") }
    var pasteTime by remember { mutableStateOf("") }
    LaunchedEffect(revision) { rows = withContext(Dispatchers.IO) { store.rows() } }
    val aliases = remember(revision) { store.aliases }
    val cutoff = remember(revision) { store.cutoff }
    val totals = remember(rows, aliases, cutoff) { transferTotals(rows, aliases, cutoff) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            result ->
            message =
                if (result[Manifest.permission.READ_SMS] == true)
                    "مُنح إذن الأرشيف. اضغط تحليل الرسائل للبدء."
                else
                    "لم يمنح Android قراءة الأرشيف. استخدم استيراد XML أو اللصق؛ لا يلزم تغيير تطبيق الرسائل الافتراضي."
        }
    fun action(block: suspend () -> String) {
        if (busy) return
        task = scope.launch {
            busy = true
            try {
                message = block()
            } catch (cancel: CancellationException) {
                message = "توقف الفحص؛ العناصر التي أضيفت محفوظة ويمكن إعادة الفحص."
                throw cancel
            } catch (_: Exception) {
                message = "تعذرت العملية أو صيغة الملف غير مدعومة؛ العناصر المضافة سابقًا محفوظة."
            } finally {
                busy = false
            }
        }
    }
    val xml =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                action {
                    val result =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use {
                                TransferArchive.xml(it, store)
                            } ?: error("FILE_UNAVAILABLE")
                        }
                    "فُحصت ${result.first} رسالة واردة وأضيفت ${result.second} حوالة. راجع غير المؤكد والمكرر المحتمل."
                }
        }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                action {
                    val text = withContext(Dispatchers.IO) { store.export() }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(text.toByteArray())
                        } ?: error("FILE_UNAVAILABLE")
                    }
                    "حُفظت نسخة سجل الحوالات."
                }
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                action {
                    val added =
                        withContext(Dispatchers.IO) {
                            val text =
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    readTransferBackup(input)
                                } ?: error("FILE_UNAVAILABLE")
                            store.restore(text)
                        }
                    "استعيدت $added حوالة دون استبدال قراراتك الحالية."
                }
        }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("حوالات الوالد", style = MaterialTheme.typography.titleLarge)
            Text(
                "حساب محلي من رسائل الحوالات الواردة التي تعرّف عليها التطبيق وأكد مصدرها. لا يتصل بحساب البنك ولا يضيف الحوالات إلى المصروفات.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferMetric("مجموع حوالات الوالد", totals.all, Modifier.weight(1f))
                TransferMetric(
                    "عدد الحوالات",
                    totals.count.toLong(),
                    Modifier.weight(1f),
                    money = false,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferMetric("القديمة: قبل التفعيل", totals.old, Modifier.weight(1f))
                TransferMetric("الجديدة: منذ التفعيل", totals.fresh, Modifier.weight(1f))
            }
            if (cutoff > 0)
                Text(
                    "وقت التفعيل الثابت: ${stamp(cutoff,true)}؛ التصنيف بتاريخ الرسالة الأصلي وليس وقت استيرادها",
                    style = MaterialTheme.typography.bodySmall,
                )
            totals.last?.let { last ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("آخر حوالة من الوالد", style = MaterialTheme.typography.titleMedium)
                        Text("${num(last.amount/100.0,2)} ر.س · ${stamp(last.time)}")
                        Text(last.sender.ifBlank { "أُكدت يدويًا" })
                    }
                }
            }
            TextButton(onClick = { settings = !settings }) {
                Text(if (settings) "إخفاء إعداد الميزة" else "إعداد اسم المحوّل والأذونات")
            }
            if (settings) {
                OutlinedTextField(
                    names,
                    { if (it.length <= 4000) names = it },
                    label = { Text("اسم الوالد كما يظهر بعد الفاصلة المنقوطة") },
                    supportingText = {
                        Text(
                            "أدخل الاسم الكامل؛ صيغة إضافية لكل سطر. لا تستخدم اسم البنك أو آخر أرقام الحساب وحدها."
                        )
                    },
                )
                Button(
                    enabled =
                        !busy &&
                            names
                                .lines()
                                .filter { it.isNotBlank() }
                                .all { it.trim().length in 2..120 && it.any(Char::isLetter) } &&
                            names.isNotBlank(),
                    onClick = {
                        action {
                            withContext(Dispatchers.IO) {
                                store.configure(
                                    names
                                        .lines()
                                        .filter { it.isNotBlank() }
                                        .map { it.trim() }
                                        .toSet(),
                                    store.cutoff.takeIf { it > 0 } ?: System.currentTimeMillis(),
                                    true,
                                )
                            }
                            "فُعّلت المتابعة. يظل وقت التقسيم ثابتًا عند إعادة الفحص أو تعديل الاسم."
                        }
                    },
                ) {
                    Text(
                        if (cutoff == 0L) "تفعيل الميزة وتثبيت وقت الفصل"
                        else "حفظ الاسم وتفعيل المتابعة"
                    )
                }
                if (store.enabled)
                    TextButton(
                        onClick = {
                            action {
                                withContext(Dispatchers.IO) {
                                    store.configure(store.aliases, store.cutoff, false)
                                }
                                "أوقفت متابعة الرسائل الجديدة؛ السجل محفوظ."
                            }
                        }
                    ) {
                        Text("إيقاف المتابعة الجديدة مؤقتًا")
                    }
                Button(
                    onClick = {
                        permission.launch(
                            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                        )
                    },
                    enabled = !busy,
                ) {
                    Text("منح إذني الأرشيف والرسائل الجديدة")
                }
                Text(
                    "قراءة الأرشيف: ${if(TransferArchive.allowed(context))"مسموحة" else "غير ممنوحة"} · الرسائل الجديدة: ${if(PurchaseSmsReceiver.allowed(context))"مسموحة" else "غير ممنوحة"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                enabled = !busy && cutoff > 0,
                onClick = {
                    if (!TransferArchive.allowed(context))
                        permission.launch(
                            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                        )
                    else
                        action {
                            val result =
                                TransferArchive.scan(context, store) { examined, added ->
                                    message = "فحص $examined رسالة · إضافة $added حوالة"
                                }
                            "انتهى فحص ${result.first} رسالة؛ أضيفت ${result.second} حوالة. راجع العناصر غير المؤكدة."
                        }
                },
            ) {
                Text("تحليل جميع رسائل الهاتف القديمة والحالية")
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { task?.cancel() }) { Text("إيقاف الفحص") }
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        xml.launch(
                            arrayOf(
                                "text/xml",
                                "application/xml",
                                "text/plain",
                                "application/octet-stream",
                            )
                        )
                    },
                    enabled = !busy && cutoff > 0,
                ) {
                    Text("استيراد نسخة رسائل XML")
                }
                TextButton(onClick = { paste = !paste }) { Text("إضافة حوالة باللصق") }
                TextButton(
                    onClick = { export.launch("father-transfers.json") },
                    enabled = !busy && cutoff > 0,
                ) {
                    Text("تصدير نسخة سجل الحوالات")
                }
                TextButton(
                    onClick = { restore.launch(arrayOf("application/json", "text/plain")) },
                    enabled = !busy,
                ) {
                    Text("استعادة سجل حوالات JSON")
                }
            }
            Text(
                "الإجماليات تخص الرسائل المتاحة التي تم التعرف عليها. الرسائل المحذوفة تحتاج نسخة رسائل سابقة. المكرر المحتمل لا يُحتسب تلقائيًا؛ راجعه قبل تأكيده. سجل الحوالات له نسخة JSON مستقلة عن نسخة المشاوير.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (paste) {
                OutlinedTextField(
                    body,
                    { if (it.length <= 10000) body = it },
                    label = { Text("نص الحوالة الواردة") },
                    minLines = 4,
                )
                OutlinedTextField(
                    pasteTime,
                    { pasteTime = it },
                    label = { Text("تاريخ الرسالة عند غيابه من النص: yyyy-MM-dd HH:mm") },
                )
                val parsed = remember(body) { IncomingTransferParser.parse(body) }
                parsed?.let {
                    Text(
                        "المبلغ المستخرج: ${num(it.amount/100.0,2)} ر.س · المحوّل: ${it.sender.ifBlank{"غير واضح"}}"
                    )
                }
                TextButton(
                    enabled = !busy && cutoff > 0 && parsed != null,
                    onClick = {
                        action {
                            val time = IncomingTransferParser.messageTime(pasteTime)
                            val added = withContext(Dispatchers.IO) { store.import(body, time) }
                            if (added) "أضيفت الحوالة؛ راجع تصنيفها."
                            else
                                "لم تضف: مكررة، أو تاريخها غير متاح/غير صالح. أدخل التاريخ والوقت عند غيابهما."
                        }
                    },
                ) {
                    Text("إضافة الحوالة المستخرجة")
                }
            }
            FlowRow {
                listOf("FATHER" to "حوالات والدي", "REVIEW" to "للمراجعة", "ALL" to "كل الوارد")
                    .forEach { (key, label) ->
                        FilterChip(filter == key, { filter = key }, label = { Text(label) })
                    }
            }
        }
        val visible = rows.filter {
            when (filter) {
                "FATHER" -> isFatherTransfer(it, aliases)
                "REVIEW" -> it.decision == 0 && !isFatherTransfer(it, aliases)
                else -> true
            }
        }
        items(visible, key = { it.id }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${num(row.amount/100.0,2)} ر.س · ${stamp(row.time)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(row.sender.ifBlank { "اسم المحوّل غير واضح؛ يحتاج تأكيدًا" })
                    Text(
                        if ((row.receivedAt.takeIf { it > 0 } ?: row.time) < cutoff) "حوالة قديمة"
                        else "حوالة جديدة"
                    )
                    if (row.duplicate)
                        Text(
                            "مكرر محتمل: قد تكون الرسالة نفسها من مصدر آخر؛ لا تؤكدها قبل المراجعة.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    Text(
                        when (row.decision) {
                            1 -> "أكدتها من الوالد"
                            -1 -> "مستبعدة من الإجمالي"
                            else ->
                                if (isFatherTransfer(row, aliases)) "مطابقة للاسم المحدد"
                                else "غير مؤكدة من الوالد"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow {
                        TextButton(
                            onClick = {
                                action {
                                    withContext(Dispatchers.IO) { store.decide(row.id, 1) }
                                    "حُفظ تأكيد الحوالة."
                                }
                            },
                            enabled = !busy,
                        ) {
                            Text("تأكيد: من والدي")
                        }
                        TextButton(
                            onClick = {
                                action {
                                    withContext(Dispatchers.IO) { store.decide(row.id, -1) }
                                    "استُبعدت من الإجمالي دون حذف السجل."
                                }
                            },
                            enabled = !busy,
                        ) {
                            Text("استبعاد")
                        }
                        if (
                            row.sender.any(Char::isLetter) &&
                                transferName(row.sender) !in aliases.map(::transferName) &&
                                aliases.size < 30
                        )
                            TextButton(
                                onClick = {
                                    action {
                                        names =
                                            (names.lines().filter { it.isNotBlank() } + row.sender)
                                                .map { it.trim() }
                                                .distinct()
                                                .joinToString("\n")
                                        withContext(Dispatchers.IO) {
                                            store.configure(
                                                store.aliases + row.sender,
                                                store.cutoff,
                                                store.enabled,
                                            )
                                        }
                                        "أضيفت صيغة الاسم للوالد؛ حدّثت مطابقة السجل."
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text("اعتماد هذه الصيغة لاسم الوالد")
                            }
                        if (row.decision != 0)
                            TextButton(
                                onClick = {
                                    action {
                                        withContext(Dispatchers.IO) { store.decide(row.id, 0) }
                                        "أُلغي القرار اليدوي."
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text("إلغاء القرار")
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferMetric(label: String, value: Long, modifier: Modifier, money: Boolean = true) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                if (money) num(value / 100.0, 2) + " ر.س" else num(value.toDouble(), 0),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
