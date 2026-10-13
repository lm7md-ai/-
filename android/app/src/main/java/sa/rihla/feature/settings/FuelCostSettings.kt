package sa.rihla.feature.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import sa.rihla.core.*
import sa.rihla.ui.*

@Composable
fun FuelCostSettings(vm: JourneyViewModel, data: ScreenData) {
    val prefs = data.settings
    var grade by remember(prefs.fuelGrade) { mutableStateOf(prefs.fuelGrade) }
    var price by
        remember(prefs.fuelPriceHalala) {
            mutableStateOf((prefs.fuelPriceHalala / 100.0).toString())
        }
    var engine by remember(prefs.engine) { mutableStateOf(prefs.engine) }
    var custom by
        remember(prefs.customConsumptionMl) {
            mutableStateOf((prefs.customConsumptionMl / 1000.0).toString())
        }
    var measured by remember(prefs.useMeasured) { mutableStateOf(prefs.useMeasured) }
    var invalid by remember { mutableStateOf(false) }
    Heading("automatic_cost")
    Text(tr("unknown_engine_explanation"))
    CostPerKilometer(data)
    Row {
        listOf("91", "95").forEach { g ->
            FilterChip(
                grade == g,
                {
                    grade = g
                    price = (saudiPrice(g) / 100.0).toString()
                },
                label = { Text(tr("grade_" + g)) },
            )
        }
    }
    Text(tr("fuel_price_verified"))
    FormField("price", price, { price = it })
    Heading("consumption_basis")
    Column {
        listOf("UNKNOWN", "LANCER_16_MANUAL", "LANCER_20_CVT", "CUSTOM").forEach { e ->
            FilterChip(engine == e, { engine = e }, label = { Text(tr("engine_" + e.lowercase())) })
        }
    }
    if (engine == "CUSTOM") FormField("l_100", custom, { custom = it })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(measured, { measured = it })
        Text(tr("prefer_measured"))
    }
    Text(tr("reference_explanation"), style = MaterialTheme.typography.bodySmall)
    if (invalid) Text(tr("invalid_cost_settings"), color = MaterialTheme.colorScheme.error)
    Action("save_cost_settings") {
        invalid = !vm.costSettings(grade, price, engine, custom, measured)
    }
}
