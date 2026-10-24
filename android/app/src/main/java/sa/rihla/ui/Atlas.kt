package sa.rihla.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Abstract brand mark, never presented as a recorded route or as map data. */
@Composable
fun AtlasMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val path =
            Path().apply {
                moveTo(size.width * .16f, size.height * .86f)
                lineTo(size.width * .16f, size.height * .38f)
                quadraticTo(
                    size.width * .16f,
                    size.height * .16f,
                    size.width * .38f,
                    size.height * .16f,
                )
                lineTo(size.width * .84f, size.height * .16f)
                lineTo(size.width * .84f, size.height * .64f)
                quadraticTo(
                    size.width * .84f,
                    size.height * .86f,
                    size.width * .62f,
                    size.height * .86f,
                )
                lineTo(size.width * .42f, size.height * .86f)
            }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
        drawCircle(color, 3.dp.toPx(), Offset(size.width * .16f, size.height * .86f))
        drawCircle(color, 3.dp.toPx(), Offset(size.width * .42f, size.height * .86f))
    }
}

@Composable
fun AtlasLink(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.padding(14.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotEmpty())
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AtlasStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Page identifiers remain stable for assistant commands and saved navigation state. */
@Composable
fun AtlasNavigation(page: String, detail: Boolean, rail: Boolean, onOpen: (String) -> Unit) {
    val destinations =
        listOf(
            "dashboard" to androidx.compose.material.icons.Icons.Default.Home,
            "history" to androidx.compose.material.icons.Icons.Default.Route,
            "map" to androidx.compose.material.icons.Icons.Default.Map,
            "audio" to androidx.compose.material.icons.Icons.Default.Headphones,
            "settings" to androidx.compose.material.icons.Icons.Default.GridView,
        )
    val labels = destinations.map { (key, _) -> tr(if (key == "settings") "more" else key) }
    fun active(key: String) =
        !detail &&
            (page == key ||
                (key == "audio" && page in setOf("player", "studio")) ||
                (key == "settings" &&
                    page in
                        setOf(
                            "assistant",
                            "models",
                            "voice",
                            "assistant_profile",
                            "expenses",
                            "father_transfers",
                            "statistics",
                            "vehicle",
                            "calendar",
                            "temporary_places",
                            "repeated_routes",
                            "tracking_health",
                        )))
    if (rail)
        NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
            Spacer(Modifier.height(16.dp))
            destinations.forEachIndexed { index, (key, icon) ->
                NavigationRailItem(
                    selected = active(key),
                    onClick = { onOpen(key) },
                    icon = { Icon(icon, labels[index]) },
                    label = { Text(labels[index]) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    else
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            destinations.forEachIndexed { index, (key, icon) ->
                NavigationBarItem(
                    selected = active(key),
                    onClick = { onOpen(key) },
                    icon = { Icon(icon, labels[index]) },
                    label = { Text(labels[index], maxLines = 1) },
                )
            }
        }
}

@Composable
fun DeparturePanel(
    title: String,
    value: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
) {
    Surface(
        color = Color(0xFF1B2931),
        contentColor = Color(0xFFF6F3EC),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFE6B48C),
                )
                Icon(Icons.Default.Explore, null, Modifier.size(22.dp), tint = Color(0xFFE6B48C))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(value, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD2D8DA),
                    )
                }
                FilledIconButton(
                    onClick = onAction,
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFF0B886),
                            contentColor = Color(0xFF302316),
                        ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, tr(action), Modifier.size(28.dp))
                }
            }
        }
    }
}
