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
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
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
            primary = Color(0xFFFFBC83),
            onPrimary = Color(0xFF482609),
            primaryContainer = Color(0xFF603C20),
            onPrimaryContainer = Color(0xFFFFDCC2),
            secondary = Color(0xFFA5D5D0),
            onSecondary = Color(0xFF103735),
            secondaryContainer = Color(0xFF244542),
            onSecondaryContainer = Color(0xFFC2ECE5),
            background = Color(0xFF10171D),
            onBackground = Color(0xFFECE7DF),
            surface = Color(0xFF172128),
            onSurface = Color(0xFFECE7DF),
            surfaceVariant = Color(0xFF283139),
            onSurfaceVariant = Color(0xFFBDC2C6),
            surfaceContainerLowest = Color(0xFF0D1318),
            surfaceContainerLow = Color(0xFF141E25),
            surfaceContainer = Color(0xFF1B262E),
            surfaceContainerHigh = Color(0xFF253039),
            surfaceContainerHighest = Color(0xFF303B43),
            outline = Color(0xFF899298),
            outlineVariant = Color(0xFF39434B),
            surfaceTint = Color(0xFFFFBC83),
        )
    else
        lightColorScheme(
            primary = Color(0xFF8E481C),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFF6DDC7),
            onPrimaryContainer = Color(0xFF482609),
            secondary = Color(0xFF315F5A),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDCECE7),
            onSecondaryContainer = Color(0xFF183D38),
            background = Color(0xFFF6F3EC),
            onBackground = Color(0xFF1B2931),
            surface = Color(0xFFFFFDFA),
            onSurface = Color(0xFF1B2931),
            surfaceVariant = Color(0xFFEAE6DE),
            onSurfaceVariant = Color(0xFF586168),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF1EEE6),
            surfaceContainer = Color(0xFFEDE9E1),
            surfaceContainerHigh = Color(0xFFE6E2D9),
            surfaceContainerHighest = Color(0xFFDFDBD2),
            outline = Color(0xFF797C7B),
            outlineVariant = Color(0xFFD8D5CD),
            surfaceTint = Color(0xFF8E481C),
        )

@Composable
fun MetricGrid(values: List<Pair<String, String>>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            values.chunked(2).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { (label, value) ->
                        Column(
                            Modifier.weight(1f).padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                tr(label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(value, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
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
        shape = RoundedCornerShape(16.dp),
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
    Surface(
        color = Color(0xFF1B2931),
        contentColor = Color(0xFFF6F3EC),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f),
                    color = Color(0xFFE6B48C),
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(icon, null, tint = Color(0xFFE6B48C), modifier = Modifier.size(24.dp))
            }
            Text(value, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, color = Color(0xFFD2D8DA), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 52.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF0B886),
                        contentColor = Color(0xFF302316),
                    ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.ArrowOutward, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
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
