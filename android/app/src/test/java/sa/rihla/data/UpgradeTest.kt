package sa.rihla.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UpgradeTest {
    @Test
    fun upgradeFromEveryPublishedSchemaKeepsAllHistoricalRows() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        for (version in 1..5) {
            val name = "upgrade-$version.db"
            context.deleteDatabase(name)
            val schema =
                JSONObject(File("schemas/sa.rihla.data.JourneyDatabase/$version.json").readText())
                    .getJSONObject("database")
            val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
            val entities = schema.getJSONArray("entities")
            old.execSQL("PRAGMA foreign_keys=ON")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(
                    indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            fun seed(table: String, overrides: Map<String, Any?>) {
                val entity =
                    (0 until entities.length())
                        .map { entities.getJSONObject(it) }
                        .first { it.getString("tableName") == table }
                val fields = entity.getJSONArray("fields")
                val values = linkedMapOf<String, Any?>()
                for (i in 0 until fields.length()) {
                    val field = fields.getJSONObject(i)
                    values[field.getString("columnName")] =
                        if (!field.optBoolean("notNull", false)) null
                        else
                            when (field.getString("affinity")) {
                                "TEXT" -> "fixture"
                                "REAL" -> 1.25
                                else -> 0L
                            }
                }
                values.putAll(overrides)
                old.execSQL(
                    "INSERT INTO `$table` (${values.keys.joinToString { "`$it`" }}) VALUES (${values.keys.joinToString { "?" }})",
                    values.values.toTypedArray(),
                )
            }
            seed("outings", mapOf("id" to 1L, "startedAt" to 1000L, "endedAt" to 9000L))
            seed("outings", mapOf("id" to 2L, "startedAt" to 10000L, "endedAt" to null))
            seed("active_slot", mapOf("singleton" to 1L, "outingId" to 2L))
            seed(
                "points",
                mapOf("id" to 1L, "outingId" to 1L, "recordedAt" to 3000L, "breakBefore" to 1L),
            )
            seed("events", mapOf("id" to 1L, "outingId" to 1L, "type" to "STOP"))
            seed("places", mapOf("type" to "HOME"))
            seed("expenses", mapOf("id" to 1L, "outingId" to 1L, "amountHalala" to 12345L))
            seed("fuel", mapOf("id" to 1L, "expenseId" to 1L, "litersMl" to 59000L))
            val snapshots = linkedMapOf<String, Pair<List<String>, List<List<String?>>>>()
            for (i in 0 until entities.length()) {
                val table = entities.getJSONObject(i).getString("tableName")
                old.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use { cursor ->
                    val rows = mutableListOf<List<String?>>()
                    while (cursor.moveToNext()) rows +=
                        (0 until cursor.columnCount).map {
                            if (cursor.isNull(it)) null else cursor.getString(it)
                        }
                    snapshots[table] = cursor.columnNames.toList() to rows
                }
            }
            old.version = version
            old.close()
            val current =
                Room.databaseBuilder(context, JourneyDatabase::class.java, name)
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                    )
                    .build()
            try {
                assertEquals(
                    2,
                    current.dao().outings().size,
                ) // Forces Room's migration and schema validation.
                assertEquals(2L, current.dao().active()!!.id)
                val db = current.openHelper.writableDatabase
                for ((table, snapshot) in snapshots) {
                    db.query(
                            "SELECT ${snapshot.first.joinToString { "`$it`" }} FROM `$table` ORDER BY rowid"
                        )
                        .use { cursor ->
                            val rows = mutableListOf<List<String?>>()
                            while (cursor.moveToNext()) rows +=
                                (0 until cursor.columnCount).map {
                                    if (cursor.isNull(it)) null else cursor.getString(it)
                                }
                            assertEquals("Schema $version / $table", snapshot.second, rows)
                        }
                }
                db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally {
                current.close()
                context.deleteDatabase(name)
            }
        }
    }
}
