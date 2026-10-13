package sa.rihla.data

import android.database.Cursor
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

data class BackupPreview(val outings: Int, val expenses: Int, val points: Int)

class LocalBackup(private val database: JourneyDatabase) {
    companion object {
        val tables =
            listOf(
                "outings",
                "points",
                "events",
                "places",
                "expenses",
                "fuel",
                "active_slot",
                "imported_sms",
                "trip_details",
                "merchant_rules",
                "recurring_expenses",
            )
        const val maxBytes = 100 * 1024 * 1024

        fun preview(text: String): BackupPreview {
            val json = JSONObject(text)
            require(json.getString("format") == "RIHLA_BACKUP" && json.getInt("formatVersion") == 1)
            require(json.getInt("schemaVersion") in 1..6)
            val data = json.getJSONObject("tables")
            require(data.has("outings") && data.has("expenses") && data.has("points"))
            return BackupPreview(
                data.getJSONArray("outings").length(),
                data.getJSONArray("expenses").length(),
                data.getJSONArray("points").length(),
            )
        }
    }

    suspend fun export(): String = database.withTransaction {
        val data = JSONObject()
        val db = database.openHelper.readableDatabase
        for (table in tables) {
            val rows = JSONArray()
            db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    cursor.columnNames.forEachIndexed { index, column ->
                        row.put(
                            column,
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                else -> error("Unsupported backup field")
                            },
                        )
                    }
                    rows.put(row)
                }
            }
            data.put(table, rows)
        }
        JSONObject()
            .put("format", "RIHLA_BACKUP")
            .put("formatVersion", 1)
            .put("schemaVersion", 6)
            .put("exportedAt", System.currentTimeMillis())
            .put("tables", data)
            .toString()
    }

    /** Restore into an empty ledger only; malformed files roll back without changing live data. */
    suspend fun restore(text: String): BackupPreview {
        val preview = preview(text)
        val source = JSONObject(text).getJSONObject("tables")
        require(source.keys().asSequence().all { it in tables })
        return database.withTransaction {
            val db = database.openHelper.writableDatabase
            require(
                tables.all { table ->
                    db.query("SELECT COUNT(*) FROM `$table`").use {
                        it.moveToFirst()
                        it.getLong(0) == 0L
                    }
                }
            ) {
                "BACKUP_NOT_EMPTY"
            }
            for (table in tables) {
                val columns =
                    db.query("PRAGMA table_info(`$table`)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    }
                val rows = source.optJSONArray(table) ?: JSONArray()
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    when (table) {
                        "merchant_rules" ->
                            require(
                                row.getString("pattern").isNotBlank() &&
                                    row.getString("category") in listOf("FUEL", "FOOD", "OTHER")
                            )
                        "recurring_expenses" ->
                            require(
                                row.getLong("amountHalala") > 0 &&
                                    row.getInt("dayOfMonth") in 1..31 &&
                                    row.getString("title").isNotBlank() &&
                                    row.getString("category") in listOf("FUEL", "FOOD", "OTHER")
                            )
                        "fuel" ->
                            require(
                                row.isNull("odometerMeters") ||
                                    row.getDouble("odometerMeters") in 0.0..1e10
                            )
                        "expenses" ->
                            require(
                                row.getLong("amountHalala") > 0 &&
                                    row.getString("category") in listOf("FUEL", "FOOD", "OTHER")
                            )
                        "outings" ->
                            require(
                                row.getLong("startedAt") > 0 &&
                                    (row.isNull("endedAt") ||
                                        row.getLong("endedAt") >= row.getLong("startedAt"))
                            )
                        "points",
                        "events",
                        "places" ->
                            require(
                                row.getDouble("latitude") in -90.0..90.0 &&
                                    row.getDouble("longitude") in -180.0..180.0
                            )
                    }
                    if (table == "outings") {
                        mapOf(
                                "stillDrivingMs" to 0L,
                                "fuelPriceHalala" to 0L,
                                "consumptionMinMl" to 0L,
                                "consumptionMaxMl" to 0L,
                                "estimateSource" to "UNKNOWN",
                                "fuelGrade" to "91",
                            )
                            .forEach { (key, value) ->
                                if (!row.has(key)) row.put(key, value)
                            }
                    }
                    if (table == "points" && !row.has("uncertainGap"))
                        row.put("uncertainGap", row.optInt("breakBefore", 0))
                    if (table == "expenses") {
                        if (!row.has("source")) row.put("source", "MANUAL")
                        if (!row.has("merchant")) row.put("merchant", "")
                        if (!row.has("importKey")) row.put("importKey", JSONObject.NULL)
                    }
                    val keys = row.keys().asSequence().toList()
                    require(keys.isNotEmpty() && keys.all { it in columns })
                    val values = keys.map { key ->
                        row.get(key).let { value ->
                            when (value) {
                                JSONObject.NULL -> null
                                is Number,
                                is String -> value
                                is Boolean -> if (value) 1 else 0
                                else -> error("Invalid backup value")
                            }
                        }
                    }
                    db.execSQL(
                        "INSERT INTO `$table` (${keys.joinToString { "`$it`" }}) VALUES (${keys.joinToString { "?" }})",
                        values.toTypedArray(),
                    )
                }
            }
            db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) }
            database.invalidationTracker.refreshAsync()
            preview
        }
    }
}
