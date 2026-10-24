package sa.rihla.map

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.*
import org.json.JSONObject
import sa.rihla.R
import sa.rihla.audio.studio.AssistantNavigation
import sa.rihla.audio.studio.AssistantProtocol
import sa.rihla.audio.studio.LocalAssistant

/**
 * Only aggregate facts leave this function; no coordinates, SMS or identifiers enter the prompt.
 */
fun routeAiFacts(section: RouteSection): String =
    JSONObject()
        .apply {
            put("elapsed_ms", section.elapsedMs)
            put("recorded_meters", section.recordedMeters)
            put("gps_meters", section.gpsMeters)
            put("direct_meters", section.directMeters)
            put("moving_ms", section.movingMs)
            put("stopped_ms", section.stoppedMs)
            put("gap_ms", section.gapMs)
            put("gaps", section.gaps)
            put("stops", section.stops)
            put("average_gps_kmh", section.averageGpsKmh ?: JSONObject.NULL)
            put("max_recorded_kmh", section.maxSpeed * 3.6)
            put("average_accuracy_m", section.averageAccuracy)
            put("worst_accuracy_m", section.worstAccuracy)
            put("weak_points", section.weakPoints)
            put("point_count", section.points)
        }
        .toString()

suspend fun generateRouteReport(
    section: RouteSection,
    generate: suspend (String, String) -> String,
): String {
    // A downloaded file is not proof of inference. Require a real, parseable response first.
    val probe =
        AssistantProtocol.json(
            generate("Return only the JSON object requested.", "Return exactly {\"ok\":true}")
        )
    check(probe.optBoolean("ok", false))
    val response =
        generate(
            "You are an offline journey analyst. Answer in Arabic. Interpret ONLY supplied aggregate GPS facts. " +
                "Return a JSON object with observations and limitations, each an array of 1 to 6 short strings. " +
                "Discuss distance, time, stops, speed and recording quality where supported. " +
                "Do not invent traffic, routes, driving causes, safety judgments, costs, fuel, forecasts or missing movement. " +
                "Do not recalculate or claim to verify measurements. Explain gaps and uncertainty. No actions or commands.",
            routeAiFacts(section),
        )
    val json = AssistantProtocol.json(response)
    fun lines(key: String): List<String> {
        val array = json.getJSONArray(key)
        require(array.length() in 1..6)
        return (0 until array.length()).map {
            val line = array.getString(it).trim()
            require(line.length in 1..700)
            line
        }
    }
    return (lines("observations") + lines("limitations")).joinToString("\n\n")
}

@Composable
fun RouteAiAnalysis(section: RouteSection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshotEnd by
        remember(section.from, section.start.recordedAt) { mutableStateOf<Long?>(null) }
    var busy by remember(section.from, section.start.recordedAt) { mutableStateOf(false) }
    var report by remember(section.from, section.start.recordedAt) { mutableStateOf<String?>(null) }
    var failed by remember(section.from, section.start.recordedAt) { mutableStateOf(false) }
    var job by remember(section.from, section.start.recordedAt) { mutableStateOf<Job?>(null) }
    DisposableEffect(section.from, section.start.recordedAt) { onDispose { job?.cancel() } }
    Column {
        TextButton(
            enabled = !busy,
            onClick = {
                job = scope.launch {
                    snapshotEnd = section.end.recordedAt
                    busy = true
                    failed = false
                    report = null
                    try {
                        report =
                            withContext(Dispatchers.Default) {
                                LocalAssistant(context.applicationContext).use { assistant ->
                                    assistant.open()
                                    withTimeout(120_000) {
                                        generateRouteReport(section, assistant::generate)
                                    }
                                }
                            }
                    } catch (e: CancellationException) {
                        if (e is TimeoutCancellationException) failed = true else throw e
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text(stringResource(R.string.route_ai_analyze))
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { job?.cancel() }) {
                Text(stringResource(R.string.route_ai_cancel))
            }
        }
        if (failed)
            Text(stringResource(R.string.route_ai_failed), color = MaterialTheme.colorScheme.error)
        report?.let {
            Text(
                stringResource(R.string.route_ai_success),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                stringResource(
                    R.string.route_ai_snapshot,
                    sa.rihla.ui.stamp(snapshotEnd ?: section.end.recordedAt, true),
                )
            )
            Text(it)
        }
        Text(stringResource(R.string.route_ai_note), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { AssistantNavigation.requests.tryEmit("models") }) {
            Text(stringResource(R.string.route_ai_models))
        }
    }
}
