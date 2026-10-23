package sa.rihla.data

import android.content.*
import android.database.sqlite.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.*
import sa.rihla.core.*

data class TransferRow(
    val id: String,
    val amount: Long,
    val sender: String,
    val time: Long,
    val bodyHash: String,
    val decision: Int,
    val duplicate: Boolean,
    val origin: String = "",
    val receivedAt: Long = 0,
)

data class TransferTotals(
    val all: Long,
    val old: Long,
    val fresh: Long,
    val count: Int,
    val last: TransferRow?,
)

fun isFatherTransfer(row: TransferRow, aliases: Set<String>): Boolean =
    row.decision == 1 ||
        (row.decision == 0 &&
            !row.duplicate &&
            row.sender.isNotBlank() &&
            transferName(row.sender) in aliases.map(::transferName))

fun transferTotals(rows: List<TransferRow>, aliases: Set<String>, cutoff: Long): TransferTotals {
    val normalized = aliases.map(::transferName).toSet()
    val selected = rows.filter {
        it.decision == 1 ||
            (it.decision == 0 &&
                !it.duplicate &&
                it.sender.isNotBlank() &&
                transferName(it.sender) in normalized)
    }
    val old =
        selected
            .filter { (it.receivedAt.takeIf { t -> t > 0 } ?: it.time) < cutoff }
            .sumOf { it.amount }
    val fresh =
        selected
            .filter { (it.receivedAt.takeIf { t -> t > 0 } ?: it.time) >= cutoff }
            .sumOf { it.amount }
    return TransferTotals(
        old + fresh,
        old,
        fresh,
        selected.size,
        selected.maxWithOrNull(compareBy<TransferRow> { it.time }.thenBy { it.receivedAt }),
    )
}

/** Independent additive store: no changes to journeys, purchases, or their backup schemas. */
class TransferStore(context: Context) :
    SQLiteOpenHelper(context, "incoming_transfers.db", null, 1) {
    private val prefs = context.getSharedPreferences("father_transfers", Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    val enabled
        get() = prefs.getBoolean("enabled", false)

    val cutoff
        get() = prefs.getLong("cutoff", 0)

    val aliases
        get() = prefs.getStringSet("aliases", emptySet()).orEmpty().toSet()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE incoming (id TEXT PRIMARY KEY NOT NULL, amount INTEGER NOT NULL CHECK(amount>0), sender TEXT NOT NULL, time INTEGER NOT NULL, bodyHash TEXT NOT NULL, decision INTEGER NOT NULL DEFAULT 0, duplicate INTEGER NOT NULL DEFAULT 0, origin TEXT NOT NULL, receivedAt INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX incoming_time ON incoming(time)")
        db.execSQL("CREATE INDEX incoming_hash ON incoming(bodyHash,time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported forward migration")
    }

    fun configure(names: Set<String>, time: Long, active: Boolean) {
        require(
            names.size <= 30 &&
                names.all { it.trim().length in 2..120 && it.any(Char::isLetter) } &&
                time > 0
        )
        prefs
            .edit()
            .putStringSet("aliases", names.map { it.trim() }.toSet())
            .putLong("cutoff", time)
            .putBoolean("enabled", active)
            .apply()
        changed()
    }

    @Synchronized
    fun import(body: String, receivedAt: Long?, source: String? = null): Boolean {
        val parsed = IncomingTransferParser.parse(body) ?: return false
        val time = parsed.time ?: receivedAt ?: return false
        if (
            time <= 0 ||
                time > System.currentTimeMillis() + 120000 ||
                (receivedAt != null && receivedAt !in 1..System.currentTimeMillis() + 120000)
        )
            return false
        val hash = transferHash(normalizeSms(body).replace(Regex("\\s+"), " ").trim())
        val origin = source ?: "paste:${receivedAt?:time}"
        val id = transferHash("$hash\n$origin")
        val db = writableDatabase
        val possible =
            db.rawQuery(
                    "SELECT amount,sender,bodyHash FROM incoming WHERE id!=? AND time BETWEEN ? AND ?",
                    arrayOf(id, (time - 120000).toString(), (time + 120000).toString()),
                )
                .use { cursor ->
                    var match = false
                    while (cursor.moveToNext()) if (
                        cursor.getString(2) == hash ||
                            (parsed.sender.isNotBlank() &&
                                cursor.getLong(0) == parsed.amount &&
                                transferName(cursor.getString(1)) == transferName(parsed.sender))
                    ) {
                        match = true
                        break
                    }
                    match
                }
        val added =
            db.insertWithOnConflict(
                "incoming",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("amount", parsed.amount)
                    put("sender", parsed.sender)
                    put("time", time)
                    put("bodyHash", hash)
                    put("decision", 0)
                    put("duplicate", if (possible) 1 else 0)
                    put("origin", origin)
                    put("receivedAt", receivedAt ?: time)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        if (added) changed()
        return added
    }

    @Synchronized
    fun rows(): List<TransferRow> =
        readableDatabase
            .rawQuery(
                "SELECT id,amount,sender,time,bodyHash,decision,duplicate,origin,receivedAt FROM incoming ORDER BY time DESC,id",
                null,
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        TransferRow(
                            c.getString(0),
                            c.getLong(1),
                            c.getString(2),
                            c.getLong(3),
                            c.getString(4),
                            c.getInt(5),
                            c.getInt(6) != 0,
                            c.getString(7),
                            c.getLong(8),
                        )
                    )
                }
            }

    @Synchronized
    fun decide(id: String, decision: Int) {
        require(decision in -1..1)
        writableDatabase.update(
            "incoming",
            ContentValues().apply { put("decision", decision) },
            "id=?",
            arrayOf(id),
        )
        changed()
    }

    fun export(): String =
        JSONObject()
            .put("format", "RIHLA_INCOMING_V1")
            .put("cutoff", cutoff)
            .put("aliases", JSONArray(aliases.toList()))
            .put(
                "items",
                JSONArray().apply {
                    rows().forEach { row ->
                        put(
                            JSONObject()
                                .put("id", row.id)
                                .put("amount", row.amount)
                                .put("sender", row.sender)
                                .put("time", row.time)
                                .put("bodyHash", row.bodyHash)
                                .put("decision", row.decision)
                                .put("duplicate", row.duplicate)
                                .put("origin", row.origin)
                                .put("receivedAt", row.receivedAt)
                        )
                    }
                },
            )
            .toString()

    @Synchronized
    fun restore(text: String): Int {
        require(text.toByteArray().size <= 32 * 1024 * 1024)
        val json = JSONObject(text)
        require(json.getString("format") == "RIHLA_INCOMING_V1")
        val names =
            json.getJSONArray("aliases").let { a ->
                (0 until a.length()).map { a.getString(it) }.toSet()
            }
        val time = json.getLong("cutoff")
        require(names.size <= 30 && names.all { it.length in 2..120 } && time > 0)
        val items = json.getJSONArray("items")
        require(items.length() <= 100000)
        val data =
            (0 until items.length()).map { index ->
                val row = items.getJSONObject(index)
                TransferRow(
                        row.getString("id"),
                        row.getLong("amount"),
                        row.getString("sender"),
                        row.getLong("time"),
                        row.getString("bodyHash"),
                        row.getInt("decision"),
                        row.getBoolean("duplicate"),
                        row.getString("origin"),
                        row.getLong("receivedAt"),
                    )
                    .also {
                        require(
                            it.id.matches(Regex("[a-f0-9]{64}")) &&
                                it.bodyHash.matches(Regex("[a-f0-9]{64}")) &&
                                it.id == transferHash("${it.bodyHash}\n${it.origin}") &&
                                it.origin.length in 1..120 &&
                                it.receivedAt > 0 &&
                                it.amount in 1..1_000_000_000 &&
                                it.sender.length <= 120 &&
                                it.time in 1..System.currentTimeMillis() + 120000 &&
                                it.decision in -1..1
                        )
                    }
            }
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            data.forEach { row ->
                if (
                    db.insertWithOnConflict(
                        "incoming",
                        null,
                        ContentValues().apply {
                            put("id", row.id)
                            put("amount", row.amount)
                            put("sender", row.sender)
                            put("time", row.time)
                            put("bodyHash", row.bodyHash)
                            put("decision", row.decision)
                            put("duplicate", if (row.duplicate) 1 else 0)
                            put("origin", row.origin)
                            put("receivedAt", row.receivedAt)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                )
                    added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (cutoff == 0L) configure(names, time, false)
        changed()
        return added
    }

    private fun changed() {
        revision.value = revision.value + 1
    }
}

fun readTransferBackup(input: java.io.InputStream, limit: Int = 32 * 1024 * 1024): String {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        total += size
        require(total <= limit)
        out.write(buffer, 0, size)
    }
    return out.toString("UTF-8")
}
