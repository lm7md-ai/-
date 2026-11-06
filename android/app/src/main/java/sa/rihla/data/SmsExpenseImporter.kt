package sa.rihla.data

import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sa.rihla.core.PurchaseSmsParser
import sa.rihla.core.distance
import sa.rihla.core.normalizeSms

class SmsExpenseImporter(private val db: JourneyDatabase) {
    private class ChannelRaceCache {
        val mutex = Mutex()
        val lru =
            object : LinkedHashMap<String, Long>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                    size > 50
            }
    }

    companion object {
        const val FUZZY_DEDUP_WINDOW_MS = 10 * 60_000L // ±10 minutes
        private const val BUCKET_5_MIN_MS = 5 * 60_000L
        private val caches = ConcurrentHashMap<Int, ChannelRaceCache>()

        private fun cacheFor(db: JourneyDatabase): ChannelRaceCache =
            caches.computeIfAbsent(System.identityHashCode(db)) { ChannelRaceCache() }

        fun normalizeMerchantPrefix(merchant: String): String =
            merchant
                .trim()
                .uppercase(java.util.Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]"), "")
                .take(10)

        fun raceKey(amountHalala: Long, merchant: String, timestampMs: Long): String =
            "$amountHalala:${normalizeMerchantPrefix(merchant)}:${timestampMs / BUCKET_5_MIN_MS}"

        fun clearProcessCachesForTests() {
            caches.clear()
        }

        fun clearRecentFingerprintsForTest() = clearProcessCachesForTests()
    }

    suspend fun import(packageName: String, body: String, receivedAt: Long): Boolean {
        if (receivedAt <= 0) return false
        val purchase = PurchaseSmsParser.parse(body) ?: return false
        val messageTime = sa.rihla.core.purchaseMessageTime(body, receivedAt)
        fun fingerprint(time: Long) =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    "$packageName\n$time\n${normalizeSms(body).replace(Regex("\\s+"), " ").trim()}"
                        .toByteArray(Charsets.UTF_8)
                )
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        val key = fingerprint(messageTime)
        val gate = cacheFor(db)
        val rKey = raceKey(purchase.amountHalala, purchase.merchant, messageTime)
        return gate.mutex.withLock {
            val nowWall = System.currentTimeMillis()
            val cachedAt = gate.lru[rKey]
            // Fast in-memory race block if another channel (SMS broadcast vs NotificationListener)
            // just processed the exact same amount + merchant prefix + 5-min bucket.
            if (cachedAt != null && nowWall - cachedAt in 0..FUZZY_DEDUP_WINDOW_MS) {
                db.withTransaction { db.dao().claimSms(ImportedSms(key, messageTime)) }
                return@withLock false
            }

            val inserted =
                db.withTransaction {
                    // 1. Retain 1.3–3.0 receipt identities, including deliberately deleted expenses.
                    if (
                        messageTime != receivedAt &&
                            db.dao()
                                .smsReceipts(minOf(messageTime, receivedAt) - 120_000, receivedAt)
                                .any { it.key == fingerprint(it.receivedAt) }
                    ) {
                        db.dao().claimSms(ImportedSms(key, messageTime))
                        return@withTransaction false
                    }
                    // 2. Exact SMS fingerprint check (blocks deleted or already-imported messages).
                    if (db.dao().smsReceipts(messageTime, messageTime).any { it.key == key }) {
                        return@withTransaction false
                    }

                    val rules = db.dao().rules()
                    val matchedRule =
                        rules.firstOrNull {
                            it.pattern.isNotBlank() &&
                                purchase.merchant.contains(it.pattern, ignoreCase = true)
                        }
                    val displayMerchant =
                        matchedRule?.customName?.takeIf { it.isNotBlank() } ?: purchase.merchant
                    val outingId = db.dao().outingAt(messageTime)
                    val geo = resolveGeoContext(outingId, messageTime, matchedRule)

                    // 3. Fuzzy Deduplication Gate (±10 minutes for exact amountHalala across MANUAL & SMS_NOTIFICATION).
                    val nearbyMatches =
                        db.dao().expensesInAmountWindow(
                            amountHalala = purchase.amountHalala,
                            start = messageTime - FUZZY_DEDUP_WINDOW_MS,
                            end = messageTime + FUZZY_DEDUP_WINDOW_MS,
                            center = messageTime,
                        )
                    if (nearbyMatches.isNotEmpty()) {
                        val manualMatch = nearbyMatches.firstOrNull { it.source == "MANUAL" }
                        if (manualMatch != null) {
                            // Smart Merge into existing MANUAL expense without creating a 2nd row!
                            val mergedMerchant =
                                manualMatch.merchant.ifBlank { displayMerchant }
                            val mergedKey = manualMatch.importKey ?: key
                            db.dao().updateExpense(
                                manualMatch.copy(
                                    merchant = mergedMerchant,
                                    importKey = mergedKey,
                                    latitude = manualMatch.latitude ?: geo.first,
                                    longitude = manualMatch.longitude ?: geo.second,
                                    placeLabel = manualMatch.placeLabel.ifBlank { geo.third },
                                )
                            )
                        }
                        db.dao().claimSms(ImportedSms(key, messageTime))
                        return@withTransaction false
                    }

                    if (db.dao().claimSms(ImportedSms(key, messageTime)) == -1L) {
                        return@withTransaction false
                    }

                    // Remember merchant geo coordinates in merchant_rules if rule exists without location
                    if (matchedRule != null && matchedRule.latitude == null && geo.first != null && geo.second != null) {
                        db.dao().rule(
                            matchedRule.copy(
                                latitude = geo.first,
                                longitude = geo.second,
                                placeLabel = matchedRule.placeLabel.ifBlank { geo.third },
                            )
                        )
                    }

                    db.dao()
                        .importExpense(
                            Expense(
                                outingId = outingId,
                                category = sa.rihla.core.merchantCategory(purchase.merchant, rules),
                                amountHalala = purchase.amountHalala,
                                createdAt = messageTime,
                                source = "SMS_NOTIFICATION",
                                merchant = displayMerchant,
                                importKey = key,
                                latitude = geo.first,
                                longitude = geo.second,
                                placeLabel = geo.third,
                            )
                        ) != -1L
                }
            if (inserted) {
                gate.lru[rKey] = nowWall
            }
            inserted
        }
    }

    private suspend fun resolveGeoContext(
        outingId: Long?,
        messageTime: Long,
        matchedRule: MerchantRule?,
    ): Triple<Double?, Double?, String> {
        val places = db.dao().places()
        val home = places.find { it.type == "HOME" }
        val uni = places.find { it.type == "UNIVERSITY" }
        fun classifyPlace(lat: Double, lon: Double, isStopped: Boolean): String {
            if (uni != null && distance(uni.lat(), uni.lon(), lat, lon) <= uni.radius + 250.0)
                return "UNIVERSITY"
            if (home != null && distance(home.latitude, home.longitude, lat, lon) <= home.radius + 60.0)
                return "HOME"
            return if (isStopped) "STOP" else "ROUTE"
        }

        val active = db.dao().active()
        if (active != null && (outingId == active.id || kotlin.math.abs(active.state.lastTime - messageTime) <= 15 * 60_000L)) {
            val st = active.state
            if (st.stopStart > 0 && st.stillLat != 0.0 && st.stillLon != 0.0) {
                return Triple(st.stillLat, st.stillLon, classifyPlace(st.stillLat, st.stillLon, true))
            }
            if (st.lastLat != null && st.lastLon != null) {
                return Triple(st.lastLat, st.lastLon, classifyPlace(st.lastLat, st.lastLon, false))
            }
        }
        if (outingId != null) {
            val pts = db.dao().pointsForOutings(listOf(outingId))
            val nearest =
                pts.minByOrNull { kotlin.math.abs(it.recordedAt - messageTime) }
                    ?.takeIf { kotlin.math.abs(it.recordedAt - messageTime) <= 20 * 60_000L }
            if (nearest != null) {
                return Triple(
                    nearest.latitude,
                    nearest.longitude,
                    classifyPlace(nearest.latitude, nearest.longitude, nearest.speed < 1.2),
                )
            }
        }
        if (matchedRule?.latitude != null && matchedRule.longitude != null) {
            return Triple(matchedRule.latitude, matchedRule.longitude, matchedRule.placeLabel)
        }
        return Triple(null, null, "")
    }

    private fun SavedPlace.lat(): Double = latitude
    private fun SavedPlace.lon(): Double = longitude
}
