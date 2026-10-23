package sa.rihla.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sa.rihla.core.*

data class RecordOutcome(val accepted: Boolean, val ended: Boolean)

class JourneyRepository(private val db: JourneyDatabase, private val settings: Settings) {
    val dao = db.dao()
    private val engine = TrackingEngine()
    private val lock = Mutex()

    suspend fun mergeTrips(first: Long, second: Long) = lock.withLock {
        TripEditor(db).merge(first, second)
    }

    suspend fun splitTrip(id: Long, time: Long) = lock.withLock { TripEditor(db).split(id, time) }

    suspend fun updateDetails(
        id: Long,
        title: String? = null,
        note: String? = null,
        toggleFavorite: Boolean = false,
    ) = lock.withLock {
        db.withTransaction {
            val previous = dao.details(id) ?: TripDetails(id)
            require((title?.length ?: 0) <= 80 && (note?.length ?: 0) <= 2000)
            dao.details(
                previous.copy(
                    title = title?.trim() ?: previous.title,
                    note = note?.trim() ?: previous.note,
                    favorite = if (toggleFavorite) !previous.favorite else previous.favorite,
                )
            )
        }
    }

    suspend fun importSms(packageName: String, body: String, receivedAt: Long): Boolean =
        lock.withLock {
            val preferences = settings.flow.first()
            if (!preferences.smsImport || receivedAt < preferences.smsImportSince)
                return@withLock false
            SmsExpenseImporter(db).import(packageName, body, receivedAt)
        }

    suspend fun importPastedSms(packageName: String, body: String): Boolean = lock.withLock {
        SmsExpenseImporter(db).import(packageName, body, System.currentTimeMillis())
    }

    suspend fun start(automatic: Boolean, startedAt: Long = System.currentTimeMillis()): Long =
        lock.withLock {
            db.withTransaction {
                dao.active()?.id
                    ?: createOuting(startedAt, automatic).also {
                        dao.activate(ActiveSlot(outingId = it))
                    }
            }
        }

    private suspend fun createOuting(startedAt: Long, automatic: Boolean): Long {
        val preferences = settings.flow.first()
        val expenses = dao.expenseRecords().associateBy { it.id }
        val economy =
            if (preferences.useMeasured)
                fuelEconomy(
                    dao.fuelRecords().mapNotNull { f ->
                        expenses[f.expenseId]?.let { e ->
                            Fill(
                                f.createdAt,
                                e.amountHalala,
                                f.litersMl,
                                f.full,
                                f.trackedMeters,
                                f.reliable,
                                f.odometerMeters,
                            )
                        }
                    }
                )
            else null
        val profile =
            consumptionProfile(preferences.engine, preferences.customConsumptionMl, economy)
        return dao.insert(
            Outing(
                startedAt = startedAt,
                automatic = automatic,
                fuelPriceHalala = preferences.fuelPriceHalala,
                consumptionMinMl = profile.minMlPer100Km,
                consumptionMaxMl = profile.maxMlPer100Km,
                estimateSource = profile.source,
                fuelGrade = preferences.fuelGrade,
            )
        )
    }

    fun area(p: SavedPlace?) = p?.let {
        Area(
            it.latitude,
            it.longitude,
            it.radius,
            it.polygon.split(';').mapNotNull { s ->
                val v = s.split(',')
                if (v.size == 2)
                    v[0].toDoubleOrNull()?.let { a -> v[1].toDoubleOrNull()?.let { b -> a to b } }
                else null
            },
        )
    }

    suspend fun record(f: Fix): Boolean = recordDetailed(f).ended

    suspend fun recordDetailed(f: Fix): RecordOutcome = lock.withLock {
        db.withTransaction {
            val o = dao.active() ?: return@withTransaction RecordOutcome(false, false)
            val places = dao.places()
            val result =
                engine.accept(
                    o.state,
                    f,
                    area(places.find { it.type == "HOME" }),
                    area(places.find { it.type == "UNIVERSITY" }),
                    allowHomeEnd = !insideActiveTemporaryPlace(places, f),
                )
            if (!result.accepted) return@withTransaction RecordOutcome(false, false)
            dao.point(
                RoutePoint(
                    outingId = o.id,
                    latitude = f.lat,
                    longitude = f.lon,
                    accuracy = f.accuracy,
                    speed = f.speed,
                    altitude = f.altitude,
                    bearing = f.bearing,
                    recordedAt = f.time,
                    distanceDelta = result.state.meters - o.state.meters,
                    uncertainGap = result.state.gapMs > o.state.gapMs,
                    breakBefore =
                        o.state.lastTime > 0 &&
                            (f.discontinuous || f.time - o.state.lastTime > 90_000),
                )
            )
            result.events.forEach { store(o.id, it) }
            var updated =
                o.copy(
                    state = result.state,
                    classification =
                        if (result.state.universityCounted && o.classification == "OTHER")
                            "HOME_TO_UNIVERSITY"
                        else o.classification,
                )
            if (result.autoEnd) updated = finish(updated, f.time, true)
            dao.update(updated)
            RecordOutcome(true, result.autoEnd)
        }
    }

    private suspend fun store(id: Long, e: Event) {
        dao.event(
            JourneyEvent(
                outingId = id,
                type = e.type,
                startedAt = e.start,
                endedAt = e.end,
                latitude = e.lat,
                longitude = e.lon,
                confirmed = e.confirmed,
            )
        )
    }

    private suspend fun finish(o: Outing, now: Long, automatic: Boolean): Outing {
        val result = engine.end(o.state, now)
        result.events.forEach { store(o.id, it) }
        dao.deactivate()
        return o.copy(
            endedAt = now,
            automaticEnd = automatic,
            state = result.state,
            classification =
                if (o.classification == "OTHER" && o.state.startedUniversity && automatic)
                    "UNIVERSITY_TO_HOME"
                else o.classification,
        )
    }

    suspend fun end(): Unit = lock.withLock {
        db.withTransaction {
            dao.active()?.let { dao.update(finish(it, System.currentTimeMillis(), false)) }
        }
    }

    suspend fun addExpense(
        category: String,
        amount: Long,
        liters: Long?,
        price: Long?,
        full: Boolean,
        time: Long,
        outingId: Long?,
        odometerMeters: Double? = null,
    ) = lock.withLock {
        db.withTransaction {
            require(amount > 0)
            require(category in listOf("FUEL", "FOOD", "OTHER"))
            if (odometerMeters != null) {
                require(odometerMeters.isFinite() && odometerMeters in 0.0..1e10)
                val records = dao.fuelRecords().filter { it.odometerMeters != null }
                require(
                    records
                        .lastOrNull { it.createdAt < time }
                        ?.odometerMeters
                        ?.let { it < odometerMeters } != false
                )
                require(
                    records
                        .firstOrNull { it.createdAt > time }
                        ?.odometerMeters
                        ?.let { it > odometerMeters } != false
                )
                require(records.none { it.createdAt == time })
            }
            val previousFill = dao.lastFuelTime(time)
            val id =
                dao.expense(
                    Expense(
                        outingId = outingId,
                        category = category,
                        amountHalala = amount,
                        createdAt = time,
                    )
                )
            if (category == "FUEL") {
                require(liters != null && liters > 0 && price != null && price > 0)
                dao.fuel(
                    FuelFillUp(
                        expenseId = id,
                        createdAt = time,
                        litersMl = liters,
                        priceHalala = price,
                        full = full,
                        trackedMeters = dao.trackedAt(time),
                        reliable = previousFill == null || !dao.uncertainGap(previousFill, time),
                        odometerMeters = odometerMeters,
                    )
                )
            }
        }
    }

    suspend fun delete(id: Long) = lock.withLock {
        db.withTransaction {
            dao.delete(id)
            dao.invalidateFuel()
        }
    }

    suspend fun clear() = lock.withLock {
        db.withTransaction {
            dao.clearOutings()
            dao.clearExpenses()
            dao.clearImportedSms()
            dao.clearPlaces()
            dao.clearRules()
            dao.clearRecurring()
        }
    }

    suspend fun payRecurring(
        id: Long,
        today: java.time.LocalDate = java.time.LocalDate.now(SaudiZone),
    ): Boolean = lock.withLock {
        db.withTransaction {
            val rule = dao.recurring(id) ?: return@withTransaction false
            val month = java.time.YearMonth.from(today)
            val due = recurringDate(rule, month)
            require(!today.isBefore(due))
            dao.importExpense(
                Expense(
                    category = rule.category,
                    amountHalala = rule.amountHalala,
                    createdAt = System.currentTimeMillis(),
                    source = "RECURRING",
                    merchant = rule.title,
                    importKey = recurringKey(id, month),
                )
            ) != -1L
        }
    }
}
