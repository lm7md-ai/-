package sa.rihla.ui

import androidx.compose.runtime.Composable
import sa.rihla.core.*
import sa.rihla.data.Outing
import sa.rihla.data.Preferences

fun costProfile(settings: Preferences, economy: Economy?): ConsumptionProfile =
    consumptionProfile(
        settings.engine,
        settings.customConsumptionMl,
        if (settings.useMeasured) economy else null,
    )

fun outingProfile(outing: Outing, fallback: ConsumptionProfile): ConsumptionProfile =
    if (outing.consumptionMinMl > 0 && outing.consumptionMaxMl >= outing.consumptionMinMl)
        ConsumptionProfile(outing.consumptionMinMl, outing.consumptionMaxMl, outing.estimateSource)
    else fallback

fun outingEstimate(outing: Outing, settings: Preferences, economy: Economy?): EstimatedFuel? =
    estimateFuel(
        outing.state.meters,
        outingProfile(outing, costProfile(settings, economy)),
        outing.fuelPriceHalala.takeIf { it > 0 } ?: settings.fuelPriceHalala,
    )

fun estimateTotals(
    outings: List<Outing>,
    settings: Preferences,
    economy: Economy?,
): Pair<Long, Long> {
    val estimates = outings.mapNotNull { outingEstimate(it, settings, economy) }
    return estimates.sumOf { it.minHalala } to estimates.sumOf { it.maxHalala }
}

fun numberRange(min: Double, max: Double, digits: Int = 2): String =
    if (min == max) num(min, digits) else num(min, digits) + " – " + num(max, digits)

@Composable
fun CostPerKilometer(data: ScreenData) {
    val economy = metrics(data, periodBounds(Period.ALL)).economy
    val profile = costProfile(data.settings, economy)
    val min = profile.minMlPer100Km / 100000.0
    val max = profile.maxMlPer100Km / 100000.0
    Metric("estimated_consumption_km", numberRange(min, max, 3) + " " + tr("liters_per_km"))
    Metric(
        "estimated_cost_km",
        numberRange(
            min * data.settings.fuelPriceHalala / 100.0,
            max * data.settings.fuelPriceHalala / 100.0,
            3,
        ) + " " + tr("sar_km"),
    )
    Metric("estimate_basis", tr("basis_" + profile.source.lowercase()))
}

@Composable
fun EstimatedCostCard(outing: Outing, data: ScreenData) {
    val economy = metrics(data, periodBounds(Period.ALL)).economy
    val estimate = outingEstimate(outing, data.settings, economy) ?: return
    val profile = outingProfile(outing, costProfile(data.settings, economy))
    Heading("estimated_fuel_cost")
    Metric(
        "estimated_fuel_cost",
        numberRange(estimate.minHalala / 100.0, estimate.maxHalala / 100.0) + " " + tr("sar"),
    )
    Metric(
        "estimated_liters",
        numberRange(estimate.minLiters, estimate.maxLiters) + " " + tr("liters"),
    )
    Metric(
        "fuel_grade",
        tr(
            "grade_" + if (outing.fuelPriceHalala > 0) outing.fuelGrade else data.settings.fuelGrade
        ),
    )
    Metric("estimate_basis", tr("basis_" + profile.source.lowercase()))
    androidx.compose.material3.Text(
        tr("estimate_not_expense"),
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
    )
    if (outing.fuelPriceHalala == 0L) androidx.compose.material3.Text(tr("legacy_estimate"))
}
