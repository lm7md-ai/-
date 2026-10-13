package sa.rihla.feature.onboarding

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import sa.rihla.data.*
import sa.rihla.feature.calendar.*
import sa.rihla.feature.dashboard.*
import sa.rihla.feature.expenses.*
import sa.rihla.feature.history.*
import sa.rihla.feature.onboarding.*
import sa.rihla.feature.settings.*
import sa.rihla.feature.statistics.*
import sa.rihla.feature.tripdetail.*
import sa.rihla.feature.vehicle.*
import sa.rihla.tracking.*
import sa.rihla.ui.*

@Composable
fun Onboarding(vm: JourneyViewModel, data: ScreenData) {
    var stage by remember { mutableIntStateOf(0) }
    var place by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hero(tr("brand_caption"), tr("welcome"), tr("hero_subtitle"), "quick_start") {
            vm.onboard()
        }
        when (stage) {
            0 -> {
                Text(tr("intro"), style = MaterialTheme.typography.bodyLarge)
                Text(tr("privacy"))
                Action("permissions") { stage = 1 }
                DataTools(vm, data)
            }
            1 -> {
                PermissionsPanel(vm)
                Text(tr("manual_available"))
                Action("configure_home") { stage = 2 }
            }
            else -> {
                Text(tr("no_official_boundary"))
                Action("configure_home") { place = "HOME" }
                Text(tr(if (data.places.any { it.type == "HOME" }) "done" else "no_home"))
                Action("configure_university") { place = "UNIVERSITY" }
                Text(
                    tr(if (data.places.any { it.type == "UNIVERSITY" }) "done" else "no_university")
                )
                Action(
                    "finish_setup",
                    enabled =
                        data.places.any { it.type == "HOME" } &&
                            data.places.any { it.type == "UNIVERSITY" },
                ) {
                    vm.onboard()
                }
            }
        }
    }
    if (place != null) PlaceDialog(vm, data, place!!) { place = null }
}
