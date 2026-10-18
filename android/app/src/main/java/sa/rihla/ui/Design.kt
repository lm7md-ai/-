package sa.rihla.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

val NewShapes =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(20.dp),
    )
val NewTypography =
    Typography(
        headlineLarge =
            androidx.compose.ui.text.TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
        headlineMedium =
            androidx.compose.ui.text.TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
        headlineSmall =
            androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        titleLarge =
            androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium =
            androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        labelLarge =
            androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )

fun journeyColors(dark: Boolean): ColorScheme =
    if (dark)
        darkColorScheme(
            primary = Color(0xFF6EDAC6),
            primaryContainer = Color(0xFF143E37),
            onPrimaryContainer = Color(0xFFB8F1E5),
            secondaryContainer = Color(0xFF253D4C),
            onSecondaryContainer = Color(0xFFD0E7F5),
            surfaceContainer = Color(0xFF191F23),
            surfaceContainerLow = Color(0xFF151B1E),
            surfaceContainerHigh = Color(0xFF242C31),
            surfaceContainerHighest = Color(0xFF2E383C),
            outlineVariant = Color(0xFF2E3A3D),
            surfaceTint = Color(0xFF6EDAC6),
            onPrimary = Color(0xFF00382E),
            secondary = Color(0xFFAACBDD),
            background = Color(0xFF101518),
            surface = Color(0xFF191F23),
            surfaceVariant = Color(0xFF242C31),
            onBackground = Color(0xFFE5ECEF),
            onSurface = Color(0xFFE5ECEF),
            onSurfaceVariant = Color(0xFFA3B3BA),
            outline = Color(0xFF405057),
            error = Color(0xFFFFB4AB),
        )
    else
        lightColorScheme(
            primary = Color(0xFF006D5B),
            primaryContainer = Color(0xFFD8F3E9),
            onPrimaryContainer = Color(0xFF004D40),
            secondaryContainer = Color(0xFFE3EEF6),
            onSecondaryContainer = Color(0xFF2D4B60),
            surfaceContainer = Color(0xFFEBF0EF),
            surfaceContainerLow = Color(0xFFF0F4F2),
            surfaceContainerHigh = Color(0xFFE5ECE8),
            surfaceContainerHighest = Color(0xFFDCE5E0),
            outlineVariant = Color(0xFFDEE6E1),
            surfaceTint = Color(0xFF006D5B),
            onPrimary = Color.White,
            secondary = Color(0xFF496B80),
            background = Color(0xFFF5F7F6),
            surface = Color.White,
            surfaceVariant = Color(0xFFE7EEEB),
            onBackground = Color(0xFF192C29),
            onSurface = Color(0xFF192C29),
            onSurfaceVariant = Color(0xFF63756F),
            outline = Color(0xFFCBD8D2),
        )

@Composable
fun MetricGrid(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        values.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    Surface(
                        Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                tr(label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun Panel(
    title: String,
    icon: ImageVector = Icons.Default.Insights,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    icon,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(tr(title), style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun StatusPill(text: String, active: Boolean = false) {
    val color =
        if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    Surface(color = color.copy(alpha = .10f), shape = RoundedCornerShape(30.dp)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(color, RoundedCornerShape(6.dp)))
            Text(text, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun EmptyPanel(title: String, message: String, icon: ImageVector = Icons.Default.Route) {
    EmptyState(tr(title), tr(message), icon)
}

@Composable
fun EmptyState(title: String, message: String, icon: ImageVector = Icons.Default.Route) {
    Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Hero(
    title: String,
    value: String,
    subtitle: String,
    action: String,
    icon: ImageVector = Icons.Default.Explore,
    onAction: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF083F35), Color(0xFF126B58))),
                RoundedCornerShape(24.dp),
            )
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, color = Color(0xFFB6DFD0), style = MaterialTheme.typography.labelLarge)
                Icon(
                    icon,
                    null,
                    tint = Color(0xFF9AE4C6),
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(value, color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, color = Color(0xFFD6EBE2), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF79E4CB),
                        contentColor = Color(0xFF092D38),
                    ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(tr(action))
            }
        }
    }
}

@Composable
fun PageTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = selected,
        containerColor = MaterialTheme.colorScheme.background,
        divider = {},
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String = "") {
    Column(
        Modifier.padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle.isNotBlank())
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
