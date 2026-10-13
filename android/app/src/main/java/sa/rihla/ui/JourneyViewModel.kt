package sa.rihla.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import sa.rihla.RihlaApp
import sa.rihla.core.*
import sa.rihla.data.*
import sa.rihla.tracking.*

data class ScreenData(
    val outings: List<Outing> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val fuel: List<FuelFillUp> = emptyList(),
    val places: List<SavedPlace> = emptyList(),
    val settings: Preferences = Preferences(),
    val now: Long = System.currentTimeMillis(),
) {
    val active
        get() = outings.firstOrNull { it.endedAt == null }
}

data class Metrics(
    val outings: Int,
    val universityTrips: Int,
    val visits: Int,
    val meters: Double,
    val drivingMs: Long,
    val stoppedMs: Long,
    val universityMs: Long,
    val fuel: Long,
    val food: Long,
    val other: Long,
    val averageTravelMs: Long?,
    val fastestMs: Long?,
    val longestMs: Long?,
    val economy: Economy?,
) {
    val total
        get() = fuel + food + other
}

fun metrics(data: ScreenData, bounds: Pair<Long, Long>): Metrics {
    val list = data.outings.filter { it.startedAt >= bounds.first && it.startedAt < bounds.second }
    val expenses =
        data.expenses.filter { it.createdAt >= bounds.first && it.createdAt < bounds.second }
    val travel =
        list
            .filter { it.state.universityCounted && it.state.homeToUniversityMs > 0 }
            .map { it.state.homeToUniversityMs }
    val fills =
        data.fuel.mapNotNull { f ->
            data.expenses
                .find { it.id == f.expenseId }
                ?.let {
                    Fill(
                        f.createdAt,
                        it.amountHalala,
                        f.litersMl,
                        f.full,
                        f.trackedMeters,
                        f.reliable,
                    )
                }
        }
    return Metrics(
        list.size,
        list.count { it.state.universityCounted },
        list.sumOf { it.state.visits + if (it.state.visitConfirmed) 1 else 0 },
        list.sumOf { it.state.meters },
        list.sumOf { it.state.drivingMs },
        list.sumOf {
            it.state.stoppedMs + if (it.state.stopStart > 0) data.now - it.state.stopStart else 0
        },
        list.sumOf {
            it.state.universityMs +
                if (it.state.visitConfirmed)
                    (if (it.state.universityExit > 0) it.state.universityExit else data.now) -
                        it.state.universityArrival
                else 0
        },
        expenses.filter { it.category == "FUEL" }.sumOf { it.amountHalala },
        expenses.filter { it.category == "FOOD" }.sumOf { it.amountHalala },
        expenses.filter { it.category == "OTHER" }.sumOf { it.amountHalala },
        travel.takeIf { it.isNotEmpty() }?.average()?.toLong(),
        travel.minOrNull(),
        travel.maxOrNull(),
        fuelEconomy(fills),
    )
}

class JourneyViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as RihlaApp
    val repo = app.repository
    private val trackingIntent = android.content.Intent(app, TrackingService::class.java)
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }
    val state =
        combine(
                repo.dao.observeOutings(),
                repo.dao.expenses(),
                repo.dao.fuel(),
                repo.dao.placesFlow(),
                app.settings.flow,
            ) { o, e, f, p, s ->
                ScreenData(o, e, f, p, s)
            }
            .combine(clock) { d, t -> d.copy(now = t) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScreenData())
    val error = MutableStateFlow<String?>(null)

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: Exception) {
                error.value = "operation_failed"
            }
        }
    }

    fun start() {
        if (!TrackingService.launch(app)) error.value = "tracking_failed"
    }

    fun end() {
        work {
            repo.end()
            app.stopService(trackingIntent)
        }
    }

    fun delete(id: Long) {
        work { repo.delete(id) }
    }

    fun classify(id: Long, value: String) {
        work { repo.dao.classify(id, value) }
    }

    fun place(p: SavedPlace) {
        work { repo.dao.place(p) }
    }

    fun theme(value: String) {
        work { app.settings.theme(value) }
    }

    fun onlineMaps(value: Boolean) {
        work { app.settings.onlineMaps(value) }
    }

    fun costSettings(
        grade: String,
        price: String,
        engine: String,
        consumption: String,
        measured: Boolean,
    ): Boolean {
        val p = decimalUnits(price, 2) ?: return false
        val c = decimalUnits(consumption, 3) ?: return false
        if (p !in 1..10000 || c !in 1000..30000) return false
        work { app.settings.costSettings(grade, p, engine, c, measured) }
        return true
    }

    fun automatic(value: Boolean) {
        work {
            app.settings.automatic(value)
            Detection.register(app, value) { error.value = "detection_failed" }
        }
    }

    fun onboard() {
        work { app.settings.onboard() }
    }

    fun expense(
        category: String,
        amount: String,
        liters: String,
        price: String,
        full: Boolean,
        time: Long,
        outing: Long?,
    ): Boolean {
        try {
            var a = decimalUnits(amount, 2)
            var l = decimalUnits(liters, 3)
            var p = decimalUnits(price, 2)
            if (a == null && category == "FUEL" && l != null && p != null)
                a =
                    java.math
                        .BigDecimal(l)
                        .multiply(java.math.BigDecimal(p))
                        .divide(java.math.BigDecimal(1000), 0, java.math.RoundingMode.HALF_UP)
                        .toLong()
            if (a == null || a <= 0 || time > System.currentTimeMillis()) return false
            if (category == "FUEL") {
                if (l == null && p != null) l = calculateLiters(a, p)
                if (p == null && l != null)
                    p =
                        java.math
                            .BigDecimal(a)
                            .multiply(java.math.BigDecimal(1000))
                            .divide(java.math.BigDecimal(l), 0, java.math.RoundingMode.HALF_UP)
                            .toLong()
                if (l == null || p == null || l <= 0 || p <= 0) return false
                if (kotlin.math.abs(a - (l / 1000.0 * p)) > maxOf(2.0, a * 0.02)) return false
            }
            val paid = a
            work { repo.addExpense(category, paid, l, p, full, time, outing) }
            return true
        } catch (_: ArithmeticException) {
            return false
        }
    }

    fun clear() {
        work {
            app.stopService(trackingIntent)
            Detection.register(app, false)
            repo.clear()
            app.settings.clear()
            java.io.File(app.filesDir, "offline.mbtiles").delete()
            java.io.File(app.filesDir, "tiles").deleteRecursively()
        }
    }
}
