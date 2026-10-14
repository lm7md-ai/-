package sa.rihla.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import sa.rihla.core.EngineState

@Entity(tableName = "outings", indices = [Index("startedAt"), Index("endedAt")])
data class Outing(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val automatic: Boolean = false,
    val automaticEnd: Boolean = false,
    val classification: String = "OTHER",
    @ColumnInfo(defaultValue = "0") val fuelPriceHalala: Long = 0,
    @ColumnInfo(defaultValue = "0") val consumptionMinMl: Long = 0,
    @ColumnInfo(defaultValue = "0") val consumptionMaxMl: Long = 0,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val estimateSource: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'91'") val fuelGrade: String = "91",
    @Embedded val state: EngineState = EngineState(),
)

@Entity(
    tableName = "active_slot",
    foreignKeys =
        [
            ForeignKey(
                entity = Outing::class,
                parentColumns = ["id"],
                childColumns = ["outingId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["outingId"], unique = true)],
)
data class ActiveSlot(@PrimaryKey val singleton: Int = 1, val outingId: Long)

@Entity(
    tableName = "points",
    foreignKeys =
        [
            ForeignKey(
                entity = Outing::class,
                parentColumns = ["id"],
                childColumns = ["outingId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["outingId", "recordedAt"], unique = true), Index("recordedAt")],
)
data class RoutePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val outingId: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val speed: Double,
    val altitude: Double,
    val bearing: Double,
    val recordedAt: Long,
    val breakBefore: Boolean = false,
    val distanceDelta: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val uncertainGap: Boolean = false,
)

@Entity(
    tableName = "events",
    foreignKeys =
        [
            ForeignKey(
                entity = Outing::class,
                parentColumns = ["id"],
                childColumns = ["outingId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["outingId", "type", "startedAt"], unique = true)],
)
data class JourneyEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val outingId: Long,
    val type: String,
    val startedAt: Long,
    val endedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val confirmed: Boolean,
)

@Entity(tableName = "places")
data class SavedPlace(
    @PrimaryKey val type: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    val polygon: String = "",
)

@Entity(
    tableName = "expenses",
    foreignKeys =
        [
            ForeignKey(
                entity = Outing::class,
                parentColumns = ["id"],
                childColumns = ["outingId"],
                onDelete = ForeignKey.SET_NULL,
            )
        ],
    indices = [Index("outingId"), Index("createdAt"), Index(value = ["importKey"], unique = true)],
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val outingId: Long? = null,
    val category: String,
    val amountHalala: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'MANUAL'") val source: String = "MANUAL",
    @ColumnInfo(defaultValue = "''") val merchant: String = "",
    val importKey: String? = null,
)

@Entity(
    tableName = "fuel",
    foreignKeys =
        [
            ForeignKey(
                entity = Expense::class,
                parentColumns = ["id"],
                childColumns = ["expenseId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["expenseId"], unique = true), Index("createdAt")],
)
data class FuelFillUp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val createdAt: Long,
    val litersMl: Long,
    val priceHalala: Long,
    val full: Boolean,
    val trackedMeters: Double,
    val reliable: Boolean,
    val odometerMeters: Double? = null,
)

@Dao
interface JourneyDao {
    @Query("DELETE FROM places WHERE type=:type AND type LIKE 'PIN:%'")
    suspend fun deletePin(type: String)

    @Query("DELETE FROM merchant_rules") suspend fun clearRules()

    @Query("DELETE FROM recurring_expenses") suspend fun clearRecurring()

    @Query("SELECT * FROM merchant_rules ORDER BY LENGTH(pattern) DESC, id")
    fun rulesFlow(): Flow<List<MerchantRule>>

    @Query("SELECT * FROM merchant_rules ORDER BY LENGTH(pattern) DESC, id")
    suspend fun rules(): List<MerchantRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun rule(value: MerchantRule)

    @Query("DELETE FROM merchant_rules WHERE id=:id") suspend fun deleteRule(id: Long)

    @Query("SELECT * FROM recurring_expenses ORDER BY dayOfMonth, id")
    fun recurringFlow(): Flow<List<RecurringExpense>>

    @Query("SELECT * FROM recurring_expenses WHERE id=:id")
    suspend fun recurring(id: Long): RecurringExpense?

    @Insert suspend fun recurring(value: RecurringExpense)

    @Query("DELETE FROM recurring_expenses WHERE id=:id") suspend fun deleteRecurring(id: Long)

    @Query("SELECT * FROM trip_details") fun detailsFlow(): Flow<List<TripDetails>>

    @Query("SELECT * FROM trip_details WHERE outingId=:id")
    suspend fun details(id: Long): TripDetails?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun details(value: TripDetails)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun claimSms(receipt: ImportedSms): Long

    @Query("SELECT * FROM imported_sms WHERE receivedAt BETWEEN :start AND :end")
    suspend fun smsReceipts(start: Long, end: Long): List<ImportedSms>

    @Query("DELETE FROM imported_sms") suspend fun clearImportedSms()

    @Query("DELETE FROM expenses WHERE id=:id AND source='SMS_NOTIFICATION'")
    suspend fun deleteImportedExpense(id: Long)

    @Query("SELECT * FROM outings ORDER BY startedAt DESC") fun observeOutings(): Flow<List<Outing>>

    @Query("SELECT * FROM outings ORDER BY startedAt") suspend fun outings(): List<Outing>

    @Query(
        "SELECT outings.* FROM outings JOIN active_slot ON outings.id=active_slot.outingId LIMIT 1"
    )
    suspend fun active(): Outing?

    @Insert suspend fun insert(o: Outing): Long

    @Update suspend fun update(o: Outing)

    @Insert suspend fun activate(slot: ActiveSlot)

    @Query("DELETE FROM active_slot") suspend fun deactivate()

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun point(p: RoutePoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun event(e: JourneyEvent)

    @Query("SELECT * FROM points WHERE outingId=:id ORDER BY recordedAt")
    fun points(id: Long): Flow<List<RoutePoint>>

    @Query("SELECT * FROM events WHERE outingId=:id ORDER BY startedAt")
    fun events(id: Long): Flow<List<JourneyEvent>>

    @Query("SELECT * FROM places") fun placesFlow(): Flow<List<SavedPlace>>

    @Query("SELECT * FROM places") suspend fun places(): List<SavedPlace>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun place(p: SavedPlace)

    @Query("SELECT * FROM expenses ORDER BY createdAt DESC") fun expenses(): Flow<List<Expense>>

    @Query("SELECT COALESCE(SUM(distanceDelta),0) FROM points WHERE recordedAt<=:time")
    suspend fun trackedAt(time: Long): Double

    @Query("SELECT MAX(createdAt) FROM fuel WHERE createdAt<:time")
    suspend fun lastFuelTime(time: Long): Long?

    @Query("SELECT * FROM fuel ORDER BY createdAt") suspend fun fuelRecords(): List<FuelFillUp>

    @Query("SELECT * FROM expenses") suspend fun expenseRecords(): List<Expense>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM points WHERE uncertainGap=1 AND recordedAt>:start AND recordedAt<=:end)"
    )
    suspend fun uncertainGap(start: Long, end: Long): Boolean

    @Query("UPDATE fuel SET reliable=0") suspend fun invalidateFuel()

    @Query("SELECT * FROM fuel ORDER BY createdAt") fun fuel(): Flow<List<FuelFillUp>>

    @Insert suspend fun expense(e: Expense): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun importExpense(e: Expense): Long

    @Query(
        "SELECT id FROM outings WHERE startedAt<=:time AND (endedAt IS NULL OR endedAt>:time) ORDER BY startedAt DESC LIMIT 1"
    )
    suspend fun outingAt(time: Long): Long?

    @Query("UPDATE expenses SET category=:category WHERE id=:id AND source='SMS_NOTIFICATION'")
    suspend fun categorizeImportedExpense(id: Long, category: String)

    @Insert suspend fun fuel(f: FuelFillUp)

    @Query("DELETE FROM expenses WHERE id=:id") suspend fun deleteExpense(id: Long)

    @Query("DELETE FROM outings WHERE id=:id AND endedAt IS NOT NULL") suspend fun delete(id: Long)

    @Query("UPDATE outings SET classification=:classification WHERE id=:id")
    suspend fun classify(id: Long, classification: String)

    @Query("DELETE FROM outings") suspend fun clearOutings()

    @Query("DELETE FROM expenses") suspend fun clearExpenses()

    @Query("DELETE FROM places") suspend fun clearPlaces()
}

@Database(
    entities =
        [
            Outing::class,
            ActiveSlot::class,
            RoutePoint::class,
            JourneyEvent::class,
            SavedPlace::class,
            Expense::class,
            FuelFillUp::class,
            ImportedSms::class,
            TripDetails::class,
            MerchantRule::class,
            RecurringExpense::class,
        ],
    version = 6,
    exportSchema = true,
)
abstract class JourneyDatabase : RoomDatabase() {
    abstract fun dao(): JourneyDao
}

val MIGRATION_1_2 =
    object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outings_endedAt ON outings (endedAt)")
            db.execSQL("ALTER TABLE points ADD COLUMN uncertainGap INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE points SET uncertainGap=breakBefore")
        }
    }

val MIGRATION_2_3 =
    object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outings ADD COLUMN stillDrivingMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE outings ADD COLUMN fuelPriceHalala INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE outings ADD COLUMN consumptionMinMl INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE outings ADD COLUMN consumptionMaxMl INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "ALTER TABLE outings ADD COLUMN estimateSource TEXT NOT NULL DEFAULT 'UNKNOWN'"
            )
            db.execSQL("ALTER TABLE outings ADD COLUMN fuelGrade TEXT NOT NULL DEFAULT '91'")
        }
    }

val MIGRATION_3_4 =
    object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
            db.execSQL("ALTER TABLE expenses ADD COLUMN merchant TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE expenses ADD COLUMN importKey TEXT")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_importKey ON expenses (importKey)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS imported_sms (`key` TEXT NOT NULL, receivedAt INTEGER NOT NULL, PRIMARY KEY(`key`))"
            )
        }
    }

@Entity(tableName = "imported_sms")
data class ImportedSms(@PrimaryKey val key: String, val receivedAt: Long)

@Entity(
    tableName = "trip_details",
    foreignKeys =
        [
            ForeignKey(
                entity = Outing::class,
                parentColumns = ["id"],
                childColumns = ["outingId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class TripDetails(
    @PrimaryKey val outingId: Long,
    val title: String = "",
    val note: String = "",
    val favorite: Boolean = false,
)

val MIGRATION_4_5 =
    object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS trip_details (outingId INTEGER NOT NULL, title TEXT NOT NULL, note TEXT NOT NULL, favorite INTEGER NOT NULL, PRIMARY KEY(outingId), FOREIGN KEY(outingId) REFERENCES outings(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
        }
    }

@Entity(tableName = "merchant_rules", indices = [Index(value = ["pattern"], unique = true)])
data class MerchantRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val category: String,
)

@Entity(tableName = "recurring_expenses")
data class RecurringExpense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val amountHalala: Long,
    val dayOfMonth: Int,
)

val MIGRATION_5_6 =
    object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE fuel ADD COLUMN odometerMeters REAL")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS merchant_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, pattern TEXT NOT NULL, category TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_rules_pattern ON merchant_rules(pattern)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS recurring_expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, amountHalala INTEGER NOT NULL, dayOfMonth INTEGER NOT NULL)"
            )
        }
    }
