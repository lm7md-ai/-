package sa.rihla.feature.vehicle

import android.content.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.google.android.gms.location.*
import java.time.*
import kotlinx.coroutines.*
import sa.rihla.core.*
import sa.rihla.core.Period
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
fun VehicleScreen(data: ScreenData) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Panel("car_name", androidx.compose.material.icons.Icons.Default.DirectionsCar) {
                StatusPill(tr("engine_lancer_16_at"))
                Text(tr("vehicle_specs"))
            }
            val m = metrics(data, periodBounds(Period.ALL))
            sa.rihla.ui.FuelLog(data)
            MetricsList(m)
            CostPerKilometer(data)
            val estimated = estimateTotals(data.outings, data.settings, m.economy)
            Metric(
                "estimated_fuel_cost",
                numberRange(estimated.first / 100.0, estimated.second / 100.0) + " " + tr("sar"),
            )
            Text(tr("estimate_not_expense"))
        }
    }
}
