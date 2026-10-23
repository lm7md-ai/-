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
    val loaded: Boolean = false,
    val details: List<TripDetails> = emptyList(),
    val rules: List<MerchantRule> = emptyList(),
    val recurring: List<RecurringExpense> = emptyList(),
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
                        f.odometerMeters,
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
    @OptIn(ExperimentalCoroutinesApi::class)
    private val clock =
        repo.dao
            .observeOutings()
            .map { outings -> outings.any { it.endedAt == null } }
            .distinctUntilChanged()
            .flatMapLatest { active ->
                flow {
                    while (true) {
                        val now = System.currentTimeMillis()
                        emit(now)
                        delay(screenRefreshDelay(now, active))
                    }
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
                ScreenData(o, e, f, p, s, loaded = true)
            }
            .combine(clock) { d, t -> d.copy(now = t) }
            .combine(repo.dao.detailsFlow()) { d, details -> d.copy(details = details) }
            .combine(repo.dao.rulesFlow()) { d, rules -> d.copy(rules = rules) }
            .combine(repo.dao.recurringFlow()) { d, recurring -> d.copy(recurring = recurring) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScreenData())
    val error = MutableStateFlow<String?>(null)

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "operation_failed"
            }
        }
    }

    fun start() {
        if (!TrackingService.launch(app)) error.value = "tracking_failed"
    }

    fun favorite(id: Long) {
        work { repo.updateDetails(id, toggleFavorite = true) }
    }

    fun tripDetails(id: Long, title: String, note: String) {
        work { repo.updateDetails(id, title, note) }
    }

    fun budget(value: String): Boolean {
        val amount =
            if (value.isBlank() || normalizeSms(value).trim() == "0") 0L
            else decimalUnits(value, 2) ?: return false
        if (amount !in 0..100_000_000) return false
        work { app.settings.budget(amount) }
        return true
    }

    fun bluetooth(address: String) {
        work { app.settings.bluetooth(address) }
    }

    fun turnDetails(value: Boolean) {
        work { app.settings.turnDetails(value) }
    }

    fun precise(value: Boolean) {
        work { app.settings.precise(value) }
    }

    fun categoryBudget(category: String, amount: String): Boolean {
        val value =
            if (amount.isBlank() || normalizeSms(amount).trim() == "0") 0L
            else decimalUnits(amount, 2) ?: return false
        if (value !in 0..100_000_000) return false
        work { app.settings.categoryBudget(category, value) }
        return true
    }

    fun rule(pattern: String, category: String): Boolean {
        if (pattern.trim().length !in 2..100 || category !in listOf("FUEL", "FOOD", "OTHER"))
            return false
        work {
            repo.dao.rule(
                MerchantRule(
                    pattern = normalizeSms(pattern).trim().lowercase(),
                    category = category,
                )
            )
        }
        return true
    }

    fun deleteRule(id: Long) {
        work { repo.dao.deleteRule(id) }
    }

    fun recurring(title: String, amount: String, category: String, day: String): Boolean {
        val value = decimalUnits(amount, 2) ?: return false
        val date = normalizeSms(day).trim().toIntOrNull() ?: return false
        if (
            date !in 1..31 ||
                title.trim().length !in 1..80 ||
                value > 100_000_000 ||
                category !in listOf("FUEL", "FOOD", "OTHER")
        )
            return false
        work {
            repo.dao.recurring(
                RecurringExpense(
                    title = title.trim(),
                    category = category,
                    amountHalala = value,
                    dayOfMonth = date,
                )
            )
        }
        return true
    }

    fun payRecurring(id: Long) {
        work { repo.payRecurring(id) }
    }

    fun deleteRecurring(id: Long) {
        work { repo.dao.deleteRecurring(id) }
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
        work {
            repo.dao.place(p)
            sa.rihla.tracking.HomeGeofences.restore(app)
        }
    }

    fun deleteTemporaryPlace(type: String) {
        work {
            repo.dao.deleteTemporaryPlace(type)
            sa.rihla.tracking.HomeGeofences.restore(app)
        }
    }

    fun deletePin(type: String) {
        work { repo.dao.deletePin(type) }
    }

    fun mergeTrips(first: Long, second: Long) {
        work { repo.mergeTrips(first, second) }
    }

    fun splitTrip(id: Long, time: Long) {
        work { repo.splitTrip(id, time) }
    }

    fun theme(value: String) {
        work { app.settings.theme(value) }
    }

    fun onlineMaps(value: Boolean) {
        work { app.settings.onlineMaps(value) }
    }

    fun smsImport(value: Boolean) {
        work { app.settings.smsImport(value) }
    }

    fun importPastedSms(body: String, result: (Boolean) -> Unit) {
        work {
            result(
                repo.importPastedSms(
                    android.provider.Telephony.Sms.getDefaultSmsPackage(app) ?: "sms",
                    body,
                )
            )
        }
    }

    fun categorizeImportedExpense(id: Long, category: String) {
        require(category in listOf("FUEL", "FOOD", "OTHER"))
        work { repo.dao.categorizeImportedExpense(id, category) }
    }

    fun deleteImportedExpense(id: Long) {
        work { repo.dao.deleteImportedExpense(id) }
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
        odometer: String = "",
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
            val reading =
                if (category != "FUEL" || odometer.isBlank()) null
                else decimalUnits(odometer, 3)?.toDouble() ?: return false
            work { repo.addExpense(category, paid, l, p, full, time, outing, reading) }
            return true
        } catch (_: ArithmeticException) {
            return false
        }
    }

    fun clear() {
        work {
            app.settings.smsImport(false)
            app.stopService(trackingIntent)
            Detection.register(app, false)
            repo.clear()
            app.settings.clear()
            java.io.File(app.filesDir, "offline.mbtiles").delete()
            java.io.File(app.filesDir, "tiles").deleteRecursively()
        }
    }
}
