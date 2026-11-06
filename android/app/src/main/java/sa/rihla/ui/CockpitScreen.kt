package sa.rihla.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import sa.rihla.assistant.*
import sa.rihla.audio.*
import sa.rihla.core.*
import sa.rihla.tracking.LiveTrackingHealth

private val OledBlack = Color(0xFF000000)
private val CockpitSurface = Color(0xFF0B1114)
private val CockpitBorder = Color(0xFF1E3A3A)
private val CockpitCyan = Color(0xFF38BDF8)
private val CockpitEmerald = Color(0xFF34D399)
private val CockpitAmber = Color(0xFFFBBF24)
private val CockpitText = Color(0xFFF8FAFC)
private val CockpitMuted = Color(0xFF94A3B8)

@Composable
fun CockpitScreen(
    vm: JourneyViewModel,
    audio: AudioViewModel,
    onExit: () -> Unit,
    assistant: AssistantViewModel = viewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(audio) { assistant.bindContext(audio, "cockpit") }

    val health by LiveTrackingHealth.state.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val playback by AudioLive.state.collectAsStateWithLifecycle()
    val boostFactor = SpeedAdaptiveAudio.presenceBoostFactor
    val undoState by assistant.undoState.collectAsStateWithLifecycle()
    val assistantReply by assistant.reply.collectAsStateWithLifecycle()
    val profile by assistant.profile.collectAsStateWithLifecycle()

    var voiceStatus by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }

    val onRecognized =
        rememberUpdatedState<(String) -> Unit>({ phrase ->
            listening = false
            assistant.ask(phrase, audio)
        })

    val voice =
        remember(context) {
            VoiceCommands(
                context,
                { st ->
                    voiceStatus = st
                    if (!st.contains("أستمع") && !st.startsWith("تجهيز")) listening = false
                },
                { onRecognized.value(it) },
            )
        }
    DisposableEffect(voice) { onDispose { voice.close() } }

    val micPerm =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                audio.pause()
                listening = true
                voice.start(profile.networkVoice)
            } else {
                voiceStatus = "امنح إذن الميكروفون للأوامر الصوتية أثناء القيادة"
            }
        }

    val activeOuting = ui.active
    val speedKmh = health.liveSpeedKmh.roundToInt().coerceAtLeast(0)
    val tripKm = (activeOuting?.state?.meters ?: 0.0) / 1000.0
    val tripMins =
        if (activeOuting != null)
            ((System.currentTimeMillis() - activeOuting.startedAt).coerceAtLeast(0L) / 60_000L)
        else 0L
    val estFuelHalala =
        remember(activeOuting?.state?.meters, ui.settings.fuelPriceHalala) {
            val meters = activeOuting?.state?.meters ?: 0.0
            val est =
                estimateFuel(
                    meters,
                    ConsumptionProfile(5500, 7800, "REFERENCE"),
                    ui.settings.fuelPriceHalala,
                )
            if (est == null) 0L else (est.minHalala + est.maxHalala) / 2L
        }

    val nextLecture =
        remember(ui.schedule, ui.outings) {
            UniversityDepartureAdvisor.nextLectureAdvice(
                schedule = ui.schedule,
                outings = ui.outings,
            )
        }

    Surface(
        modifier = Modifier.fillMaxSize().background(OledBlack),
        color = OledBlack,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Bar: Car Title + Adaptive Boost Pill + Exit
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.DirectionsCar, null, tint = CockpitEmerald)
                    Text(
                        "قيادة اللانسر · OLED",
                        color = CockpitText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    if (boostFactor > 0.02f) {
                        Surface(
                            color = Color(0xFF064E3B),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                "صوت تكيفي +${(boostFactor * 100).toInt()}٪",
                                color = CockpitEmerald,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onExit,
                    border = BorderStroke(1.dp, CockpitBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CockpitText),
                ) {
                    Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("إغلاق")
                }
            }

            // Center Speedometer + Trip Metrics + UQU Lecture Pill
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = speedKmh.toString(),
                    color = if (speedKmh >= 80) CockpitAmber else CockpitCyan,
                    fontSize = 84.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "كم / ساعة",
                    color = CockpitMuted,
                    fontSize = 16.sp,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CockpitMetric("المسافة", String.format(java.util.Locale.US, "%.1f كم", tripKm))
                    CockpitMetric("الزمن", "$tripMins د")
                    CockpitMetric(
                        "وقود المشوار",
                        "${DailyBriefingBuilder.formatHalalaArabic(estFuelHalala)} ر.س",
                    )
                }

                if (nextLecture != null && nextLecture.isToday) {
                    Surface(
                        color = CockpitSurface,
                        border = BorderStroke(1.dp, CockpitBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text =
                                "🎓 ${nextLecture.entry.courseTitle} · ${nextLecture.lectureStartFormatted} (${nextLecture.entry.buildingOrGate.ifBlank { "العابدية" }})",
                            color = CockpitAmber,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                // Current Lecture / Audio Banner
                Surface(
                    color = CockpitSurface,
                    border = BorderStroke(1.dp, CockpitBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text =
                                    playback.title.ifBlank {
                                        "لا يوجد مقطع مشغّل — اضغط تشغيل لقائمة ذكية على قد مشوارك"
                                    },
                                color = CockpitText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (playback.id.isNotBlank()) {
                                Text(
                                    text = "${audioTime(playback.position)} / ${audioTime(playback.duration)}",
                                    color = CockpitMuted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        TextButton(onClick = { audio.playSmartTripQueue() }) {
                            Text("قائمة ذكية", color = CockpitCyan)
                        }
                    }
                }

                if (undoState != null) {
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                undoState!!.action.label,
                                color = CockpitText,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { assistant.undoLastAction(audio) }) {
                                Text("تراجع")
                            }
                        }
                    }
                } else if (assistantReply != null || voiceStatus.isNotBlank()) {
                    Text(
                        text = voiceStatus.ifBlank { assistantReply?.reply.orEmpty() },
                        color = CockpitEmerald,
                        fontSize = 13.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Bottom 2x2 Oversized Glanceable Touch Grid (minHeight 82.dp each!)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CockpitBigButton(
                        title = "رجوع ١٥ث",
                        icon = Icons.Default.Replay,
                        accent = CockpitCyan,
                        modifier = Modifier.weight(1f),
                    ) {
                        audio.jump(-15_000L)
                    }
                    CockpitBigButton(
                        title = if (playback.playing) "إيقاف مؤقت" else "تشغيل",
                        icon = if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        accent = CockpitEmerald,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (playback.id.isBlank()) audio.playSmartTripQueue() else audio.toggle()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CockpitBigButton(
                        title = "علامة -١٠ث",
                        icon = Icons.Default.BookmarkAdd,
                        accent = CockpitAmber,
                        modifier = Modifier.weight(1f),
                    ) {
                        audio.instantCockpitBookmark()
                    }
                    CockpitBigButton(
                        title = if (listening) "إيقاف الاستماع" else "أمر صوتي فوري",
                        icon = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                        accent = if (listening) Color(0xFFF87171) else CockpitCyan,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (listening) {
                            voice.stop()
                            listening = false
                        } else if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            audio.pause()
                            listening = true
                            voice.start(profile.networkVoice)
                        } else {
                            micPerm.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CockpitMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = CockpitText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = CockpitMuted, fontSize = 12.sp)
    }
}

@Composable
private fun CockpitBigButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 82.dp),
        color = CockpitSurface,
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                color = CockpitText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
