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
        large = RoundedCornerShape(28.dp),
    )
val NewTypography =
    Typography(
        headlineLarge =
            androidx.compose.ui.text.TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
        headlineMedium =
            androidx.compose.ui.text.TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        headlineSmall =
            androidx.compose.ui.text.TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleLarge =
            androidx.compose.ui.text.TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
        titleMedium =
            androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
        labelLarge =
            androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )

fun journeyColors(dark: Boolean): ColorScheme =
    if (dark)
        darkColorScheme(
            primary = Color(0xFF83AFFF),
            primaryContainer = Color(0xFF203F72),
            onPrimaryContainer = Color(0xFFD9E7FF),
            secondaryContainer = Color(0xFF123F39),
            onSecondaryContainer = Color(0xFFB8F1DF),
            surfaceContainer = Color(0xFF101B2E),
            onPrimary = Color(0xFF052250),
            secondary = Color(0xFF63DDC0),
            background = Color(0xFF090F1C),
            surface = Color(0xFF101B2E),
            surfaceVariant = Color(0xFF1A2840),
            onBackground = Color(0xFFE8EEF9),
            onSurface = Color(0xFFE8EEF9),
            onSurfaceVariant = Color(0xFFA8B7CF),
            outline = Color(0xFF39475E),
            error = Color(0xFFFFB4AB),
        )
    else
        lightColorScheme(
            primary = Color(0xFF225BEA),
            primaryContainer = Color(0xFFE4ECFF),
            onPrimaryContainer = Color(0xFF16376E),
            secondaryContainer = Color(0xFFDDF5ED),
            onSecondaryContainer = Color(0xFF075B4C),
            surfaceContainer = Color(0xFFEEF2F9),
            onPrimary = Color.White,
            secondary = Color(0xFF087A64),
            background = Color(0xFFF3F5FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFE9EFF9),
            onBackground = Color(0xFF101D35),
            onSurface = Color(0xFF101D35),
            onSurfaceVariant = Color(0xFF60718C),
            outline = Color(0xFFC9D3E4),
        )

@Composable
fun MetricGrid(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        values.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    Surface(
                        Modifier.weight(1f),
                        shape = RoundedCornerShape(22.dp),
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
        shape = RoundedCornerShape(24.dp),
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
    Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
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
            Text(tr(title), style = MaterialTheme.typography.titleMedium)
            Text(tr(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Hero(title: String, value: String, subtitle: String, action: String, onAction: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF102346), Color(0xFF1745A0))),
                RoundedCornerShape(30.dp),
            )
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, color = Color(0xFFB8D1FF), style = MaterialTheme.typography.labelLarge)
                Icon(
                    Icons.Default.Route,
                    null,
                    tint = Color(0xFF78E3CA),
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(value, color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, color = Color(0xFFD1DFF5), style = MaterialTheme.typography.bodyMedium)
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
