package sa.rihla.data

import android.database.Cursor
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

enum class RestoreMode {
    SMART_MERGE,
    FULL_OVERWRITE,
}

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
                "university_schedule",
                "campus_parking",
            )
        const val maxBytes = 100 * 1024 * 1024

        fun preview(text: String): BackupPreview {
            val json = JSONObject(text)
            require(json.getString("format") == "RIHLA_BACKUP" && json.getInt("formatVersion") == 1)
            require(json.getInt("schemaVersion") in 1..8)
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
            .put("schemaVersion", 8)
            .put("exportedAt", System.currentTimeMillis())
            .put("tables", data)
            .toString()
    }

    /**
     * Restores into the database using either SMART_MERGE (adds missing records without duplicating)
     * or FULL_OVERWRITE (atomically replaces all tables inside a single SQLite transaction).
     * Malformed files always roll back cleanly without losing existing data.
     */
    suspend fun restore(
        text: String,
        mode: RestoreMode = RestoreMode.SMART_MERGE,
    ): BackupPreview {
        val preview = preview(text)
        val source = JSONObject(text).getJSONObject("tables")
        require(source.keys().asSequence().all { it in tables })
        return database.withTransaction {
            val db = database.openHelper.writableDatabase
            val isTargetEmpty =
                tables.all { table ->
                    db.query("SELECT COUNT(*) FROM `$table`").use {
                        it.moveToFirst()
                        it.getLong(0) == 0L
                    }
                }
            if (mode == RestoreMode.FULL_OVERWRITE && !isTargetEmpty) {
                listOf(
                        "active_slot",
                        "points",
                        "events",
                        "trip_details",
                        "campus_parking",
                        "fuel",
                        "expenses",
                        "outings",
                        "places",
                        "imported_sms",
                        "merchant_rules",
                        "recurring_expenses",
                        "university_schedule",
                    )
                    .forEach { tbl -> db.execSQL("DELETE FROM `$tbl`") }
            }

            val outingIdMap = HashMap<Long, Long>()
            val expenseIdMap = HashMap<Long, Long>()
            val targetHasActiveTrip =
                !isTargetEmpty &&
                    mode == RestoreMode.SMART_MERGE &&
                    db.query("SELECT COUNT(*) FROM active_slot").use {
                        it.moveToFirst() && it.getLong(0) > 0L
                    }

            for (table in tables) {
                val columns =
                    db.query("PRAGMA table_info(`$table`)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    }
                val rows = if (source.has(table)) source.getJSONArray(table) else JSONArray()
                for (i in 0 until rows.length()) {
                    val row = JSONObject(rows.getJSONObject(i).toString())
                    validateAndNormalizeRow(table, row)

                    if (isTargetEmpty || mode == RestoreMode.FULL_OVERWRITE) {
                        val keys = row.keys().asSequence().toList()
                        require(keys.isNotEmpty() && keys.all { it in columns })
                        val values = keys.map { key -> jsonSqlValue(row.get(key)) }
                        db.execSQL(
                            "INSERT INTO `$table` (${keys.joinToString { "`$it`" }}) VALUES (${keys.joinToString { "?" }})",
                            values.toTypedArray(),
                        )
                    } else {
                        smartMergeRow(db, table, columns, row, outingIdMap, expenseIdMap, targetHasActiveTrip)
                    }
                }
            }
            db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) }
            db.query("SELECT COUNT(*) FROM outings WHERE endedAt IS NULL").use {
                require(it.moveToFirst() && it.getLong(0) <= 1)
            }
            db.query(
                    "SELECT outings.id FROM outings LEFT JOIN active_slot ON outings.id=active_slot.outingId WHERE (outings.endedAt IS NULL AND active_slot.outingId IS NULL) OR (outings.endedAt IS NOT NULL AND active_slot.outingId IS NOT NULL)"
                )
                .use {
                    require(!it.moveToFirst()) { "BACKUP_INVALID_ACTIVE_TRIP" }
                }
            database.invalidationTracker.refreshAsync()
            preview
        }
    }

    private fun validateAndNormalizeRow(table: String, row: JSONObject) {
        when (table) {
            "merchant_rules" -> {
                require(
                    row.getString("pattern").isNotBlank() &&
                        row.getString("category") in listOf("FUEL", "FOOD", "OTHER")
                )
                if (!row.has("customName")) row.put("customName", "")
                if (!row.has("latitude")) row.put("latitude", JSONObject.NULL)
                if (!row.has("longitude")) row.put("longitude", JSONObject.NULL)
                if (!row.has("placeLabel")) row.put("placeLabel", "")
            }
            "recurring_expenses" ->
                require(
                    row.getLong("amountHalala") > 0 &&
                        row.getInt("dayOfMonth") in 1..31 &&
                        row.getString("title").isNotBlank() &&
                        row.getString("category") in listOf("FUEL", "FOOD", "OTHER")
                )
            "university_schedule" ->
                require(
                    row.getInt("dayOfWeek") in 1..7 &&
                        row.getString("courseTitle").isNotBlank() &&
                        row.getInt("startMinuteOfDay") in 0..1439 &&
                        row.getInt("endMinuteOfDay") in 0..1439
                )
            "campus_parking" ->
                require(
                    row.getDouble("latitude") in -90.0..90.0 &&
                        row.getDouble("longitude") in -180.0..180.0 &&
                        row.getDouble("accuracy") > 0 &&
                        row.getLong("savedAt") > 0
                )
            "fuel" ->
                require(
                    row.getLong("litersMl") > 0 &&
                        row.getLong("priceHalala") > 0 &&
                        row.getDouble("trackedMeters").isFinite() &&
                        row.getDouble("trackedMeters") >= 0 &&
                        (row.isNull("odometerMeters") ||
                            row.getDouble("odometerMeters") in 0.0..1e10)
                )
            "expenses" -> {
                require(
                    row.getLong("amountHalala") > 0 &&
                        row.getString("category") in listOf("FUEL", "FOOD", "OTHER")
                )
                if (!row.has("source")) row.put("source", "MANUAL")
                if (!row.has("merchant")) row.put("merchant", "")
                if (!row.has("importKey")) row.put("importKey", JSONObject.NULL)
                if (!row.has("latitude")) row.put("latitude", JSONObject.NULL)
                if (!row.has("longitude")) row.put("longitude", JSONObject.NULL)
                if (!row.has("placeLabel")) row.put("placeLabel", "")
                if (!row.has("receiptImageUri")) row.put("receiptImageUri", JSONObject.NULL)
                if (!row.has("warrantyExpiryAt")) row.put("warrantyExpiryAt", JSONObject.NULL)
            }
            "outings" -> {
                require(
                    row.getLong("startedAt") > 0 &&
                        (row.isNull("endedAt") ||
                            row.getLong("endedAt") >= row.getLong("startedAt"))
                )
                val latitude = row.opt("distanceLat")
                val longitude = row.opt("distanceLon")
                val hasLatitude = latitude != null && latitude != JSONObject.NULL
                val hasLongitude = longitude != null && longitude != JSONObject.NULL
                require(hasLatitude == hasLongitude) { "BACKUP_INVALID_DISTANCE_ANCHOR" }
                if (hasLatitude)
                    require(
                        row.getDouble("distanceLat") in -90.0..90.0 &&
                            row.getDouble("distanceLon") in -180.0..180.0 &&
                            row.getLong("distanceTime") in 1..row.getLong("lastTime") &&
                            row.getDouble("distanceAccuracy") in 0.1..60.0
                    ) {
                        "BACKUP_INVALID_DISTANCE_ANCHOR"
                    }
                else
                    require(
                        row.optLong("distanceTime", 0) == 0L &&
                            row.optDouble("distanceAccuracy", 0.0) == 0.0
                    )
                mapOf(
                        "stillDrivingMs" to 0L,
                        "distanceLat" to JSONObject.NULL,
                        "distanceLon" to JSONObject.NULL,
                        "distanceTime" to 0L,
                        "distanceAccuracy" to 0.0,
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
            "points",
            "events",
            "places" -> {
                require(
                    row.getDouble("latitude") in -90.0..90.0 &&
                        row.getDouble("longitude") in -180.0..180.0
                )
                if (table == "points" && !row.has("uncertainGap"))
                    row.put("uncertainGap", row.optInt("breakBefore", 0))
            }
        }
    }

    private fun smartMergeRow(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        columns: Set<String>,
        row: JSONObject,
        outingIdMap: MutableMap<Long, Long>,
        expenseIdMap: MutableMap<Long, Long>,
        targetHasActiveTrip: Boolean,
    ) {
        when (table) {
            "outings" -> {
                val oldId = row.getLong("id")
                val startedAt = row.getLong("startedAt")
                val existingId =
                    db.query("SELECT id FROM outings WHERE startedAt=?", arrayOf(startedAt)).use {
                        if (it.moveToFirst()) it.getLong(0) else null
                    }
                if (existingId != null) {
                    outingIdMap[oldId] = existingId
                    return
                }
                if (row.isNull("endedAt") && targetHasActiveTrip) {
                    // Never create a second active trip when target already has one running
                    row.put("endedAt", maxOf(startedAt, row.optLong("lastTime", startedAt)))
                    row.put("phase", "ENDED")
                }
                row.remove("id")
                val newId = insertReturningId(db, table, columns, row)
                outingIdMap[oldId] = newId
            }
            "active_slot" -> {
                if (targetHasActiveTrip) return
                val mapped = outingIdMap[row.getLong("outingId")] ?: return
                val isUnended =
                    db.query("SELECT endedAt FROM outings WHERE id=?", arrayOf(mapped)).use {
                        it.moveToFirst() && it.isNull(0)
                    }
                if (isUnended) {
                    row.put("outingId", mapped)
                    insertOrIgnore(db, table, columns, row)
                }
            }
            "points",
            "events",
            "trip_details" -> {
                val oldOuting = row.getLong("outingId")
                val mapped = outingIdMap[oldOuting] ?: error("Missing outing reference $oldOuting")
                row.put("outingId", mapped)
                if (table != "trip_details") row.remove("id")
                insertOrIgnore(db, table, columns, row)
            }
            "expenses" -> {
                val oldId = row.getLong("id")
                if (!row.isNull("outingId")) {
                    val oldOuting = row.getLong("outingId")
                    val mapped = outingIdMap[oldOuting]
                    row.put("outingId", mapped ?: JSONObject.NULL)
                }
                val importKey = if (row.isNull("importKey")) null else row.getString("importKey")
                val createdAt = row.getLong("createdAt")
                val amount = row.getLong("amountHalala")
                val existingId =
                    if (importKey != null) {
                        db.query("SELECT id FROM expenses WHERE importKey=?", arrayOf(importKey))
                            .use { if (it.moveToFirst()) it.getLong(0) else null }
                    } else {
                        db.query(
                                "SELECT id FROM expenses WHERE amountHalala=? AND createdAt=?",
                                arrayOf(amount, createdAt),
                            )
                            .use { if (it.moveToFirst()) it.getLong(0) else null }
                    }
                if (existingId != null) {
                    expenseIdMap[oldId] = existingId
                    return
                }
                row.remove("id")
                val newId = insertReturningId(db, table, columns, row)
                expenseIdMap[oldId] = newId
            }
            "fuel" -> {
                val oldExp = row.getLong("expenseId")
                val mappedExp = expenseIdMap[oldExp] ?: error("Missing expense reference $oldExp")
                val alreadyExists =
                    db.query("SELECT id FROM fuel WHERE expenseId=?", arrayOf(mappedExp)).use {
                        it.moveToFirst()
                    }
                if (alreadyExists) return
                row.remove("id")
                row.put("expenseId", mappedExp)
                insertOrIgnore(db, table, columns, row)
            }
            "places",
            "imported_sms" -> insertOrIgnore(db, table, columns, row)
            "merchant_rules" -> {
                row.remove("id")
                insertOrIgnore(db, table, columns, row)
            }
            "recurring_expenses" -> {
                val title = row.getString("title")
                val day = row.getInt("dayOfMonth")
                val exists =
                    db.query(
                            "SELECT id FROM recurring_expenses WHERE title=? AND dayOfMonth=?",
                            arrayOf(title, day),
                        )
                        .use { it.moveToFirst() }
                if (!exists) {
                    row.remove("id")
                    insertOrIgnore(db, table, columns, row)
                }
            }
            "university_schedule" -> {
                val day = row.getInt("dayOfWeek")
                val startMin = row.getInt("startMinuteOfDay")
                val title = row.getString("courseTitle")
                val exists =
                    db.query(
                            "SELECT id FROM university_schedule WHERE dayOfWeek=? AND startMinuteOfDay=? AND courseTitle=?",
                            arrayOf(day, startMin, title),
                        )
                        .use { it.moveToFirst() }
                if (!exists) {
                    row.remove("id")
                    insertOrIgnore(db, table, columns, row)
                }
            }
            "campus_parking" -> {
                val savedAt = row.getLong("savedAt")
                val exists =
                    db.query("SELECT id FROM campus_parking WHERE savedAt=?", arrayOf(savedAt))
                        .use { it.moveToFirst() }
                if (!exists) {
                    if (!row.isNull("outingId")) {
                        val mapped = outingIdMap[row.getLong("outingId")]
                        row.put("outingId", mapped ?: JSONObject.NULL)
                    }
                    // Preserve target's active pin if target already has a newer active pin
                    val newerActive =
                        db.query(
                                "SELECT id FROM campus_parking WHERE active=1 AND savedAt>?",
                                arrayOf(savedAt),
                            )
                            .use { it.moveToFirst() }
                    if (newerActive) row.put("active", 0)
                    else if (row.optInt("active", 0) == 1) {
                        db.execSQL("UPDATE campus_parking SET active=0 WHERE active=1")
                    }
                    row.remove("id")
                    insertOrIgnore(db, table, columns, row)
                }
            }
        }
    }

    private fun insertReturningId(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        columns: Set<String>,
        row: JSONObject,
    ): Long {
        val keys = row.keys().asSequence().toList()
        require(keys.isNotEmpty() && keys.all { it in columns })
        val values = keys.map { jsonSqlValue(row.get(it)) }
        db.execSQL(
            "INSERT INTO `$table` (${keys.joinToString { "`$it`" }}) VALUES (${keys.joinToString { "?" }})",
            values.toTypedArray(),
        )
        return db.query("SELECT last_insert_rowid()").use {
            check(it.moveToFirst())
            it.getLong(0)
        }
    }

    private fun insertOrIgnore(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        columns: Set<String>,
        row: JSONObject,
    ) {
        val keys = row.keys().asSequence().toList()
        require(keys.isNotEmpty() && keys.all { it in columns })
        val values = keys.map { jsonSqlValue(row.get(it)) }
        db.execSQL(
            "INSERT OR IGNORE INTO `$table` (${keys.joinToString { "`$it`" }}) VALUES (${keys.joinToString { "?" }})",
            values.toTypedArray(),
        )
    }

    private fun jsonSqlValue(value: Any?): Any? =
        when (value) {
            null,
            JSONObject.NULL -> null
            is Number,
            is String -> value
            is Boolean -> if (value) 1 else 0
            else -> error("Invalid backup value")
        }
}
