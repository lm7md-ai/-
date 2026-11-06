package sa.rihla.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.store by preferencesDataStore("settings")

data class Preferences(
    val theme: String = "SYSTEM",
    val auto: Boolean = false,
    val onboarded: Boolean = false,
    val offlineAttribution: String = "",
    val onlineMaps: Boolean = false,
    val fuelGrade: String = "91",
    val fuelPriceHalala: Long = 218,
    val engine: String = "LANCER_16_AT",
    val customConsumptionMl: Long = 8000,
    val useMeasured: Boolean = true,
    val smsImport: Boolean = false,
    val smsImportSince: Long = 0,
    val monthlyBudgetHalala: Long = 0,
    val bluetoothAddress: String = "",
    val preciseTracking: Boolean = false,
    val fuelBudget: Long = 0,
    val foodBudget: Long = 0,
    val otherBudget: Long = 0,
    val turnDetails: Boolean = true,
    // v7.0 personal power-user preferences
    val zeroConfirmationVoice: Boolean = true,
    val speedAdaptiveAudio: Boolean = true,
    val autoStopBookmark: Boolean = true,
    val autoOpenCockpitOnBluetooth: Boolean = false,
    val autoMorningBriefing: Boolean = false,
    val autoDailyBackup: Boolean = true,
    val unifiedBackupFolderUri: String = "",
    val stationaryDeepSleep: Boolean = true,
)

class Settings(private val context: Context) {
    val flow =
        context.store.data.map {
            Preferences(
                theme = it[stringPreferencesKey("theme")] ?: "SYSTEM",
                auto = it[booleanPreferencesKey("auto")] ?: false,
                onboarded = it[booleanPreferencesKey("onboarded")] ?: false,
                offlineAttribution = it[stringPreferencesKey("attribution")] ?: "",
                onlineMaps = it[booleanPreferencesKey("online_maps")] ?: false,
                fuelGrade = it[stringPreferencesKey("fuel_grade")] ?: "91",
                fuelPriceHalala = it[longPreferencesKey("fuel_price")] ?: 218,
                engine = if (it[stringPreferencesKey("engine")] == "CUSTOM") "CUSTOM" else "LANCER_16_AT",
                customConsumptionMl = it[longPreferencesKey("custom_consumption")] ?: 8000,
                useMeasured = it[booleanPreferencesKey("use_measured")] ?: true,
                smsImport = it[booleanPreferencesKey("sms_import")] ?: false,
                smsImportSince = it[longPreferencesKey("sms_import_since")] ?: 0,
                monthlyBudgetHalala = it[longPreferencesKey("monthly_budget")] ?: 0,
                bluetoothAddress = it[stringPreferencesKey("bluetooth_address")] ?: "",
                preciseTracking = it[booleanPreferencesKey("precise_tracking")] ?: false,
                fuelBudget = it[longPreferencesKey("fuel_budget")] ?: 0,
                foodBudget = it[longPreferencesKey("food_budget")] ?: 0,
                otherBudget = it[longPreferencesKey("other_budget")] ?: 0,
                turnDetails = it[booleanPreferencesKey("turn_detail")] ?: true,
                zeroConfirmationVoice = it[booleanPreferencesKey("zero_confirm_voice")] ?: true,
                speedAdaptiveAudio = it[booleanPreferencesKey("speed_adaptive_audio")] ?: true,
                autoStopBookmark = it[booleanPreferencesKey("auto_stop_bookmark")] ?: true,
                autoOpenCockpitOnBluetooth = it[booleanPreferencesKey("auto_open_cockpit_bt")] ?: false,
                autoMorningBriefing = it[booleanPreferencesKey("auto_morning_briefing")] ?: false,
                autoDailyBackup = it[booleanPreferencesKey("auto_daily_backup")] ?: true,
                unifiedBackupFolderUri = it[stringPreferencesKey("unified_backup_folder")] ?: "",
                stationaryDeepSleep = it[booleanPreferencesKey("stationary_deep_sleep")] ?: true,
            )
        }

    suspend fun theme(value: String) {
        context.store.edit { it[stringPreferencesKey("theme")] = value }
    }

    suspend fun automatic(value: Boolean) {
        context.store.edit { it[booleanPreferencesKey("auto")] = value }
    }

    suspend fun onboard() {
        context.store.edit { it[booleanPreferencesKey("onboarded")] = true }
    }

    suspend fun attribution(value: String) {
        context.store.edit { it[stringPreferencesKey("attribution")] = value }
    }

    suspend fun onlineMaps(value: Boolean) {
        context.store.edit { it[booleanPreferencesKey("online_maps")] = value }
    }

    suspend fun costSettings(
        grade: String,
        price: Long,
        engine: String,
        consumption: Long,
        measured: Boolean,
    ) {
        require(grade in listOf("91", "95") && price in 1..10000 && consumption in 1000..30000)
        require(engine in listOf("LANCER_16_AT", "CUSTOM"))
        context.store.edit {
            it[stringPreferencesKey("fuel_grade")] = grade
            it[longPreferencesKey("fuel_price")] = price
            it[stringPreferencesKey("engine")] = engine
            it[longPreferencesKey("custom_consumption")] = consumption
            it[booleanPreferencesKey("use_measured")] = measured
        }
    }

    suspend fun clear() {
        context.store.edit { it.clear() }
    }

    suspend fun smsImport(enabled: Boolean) {
        context.store.edit {
            if (enabled && it[booleanPreferencesKey("sms_import")] != true)
                it[longPreferencesKey("sms_import_since")] = System.currentTimeMillis()
            it[booleanPreferencesKey("sms_import")] = enabled
        }
    }

    suspend fun budget(value: Long) {
        require(value in 0..100_000_000)
        context.store.edit { it[longPreferencesKey("monthly_budget")] = value }
    }

    suspend fun bluetooth(address: String) {
        require(
            address.isEmpty() || android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)
        )
        context.store.edit { it[stringPreferencesKey("bluetooth_address")] = address }
    }

    suspend fun turnDetails(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("turn_detail")] = enabled }
    }

    suspend fun precise(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("precise_tracking")] = enabled }
    }

    suspend fun categoryBudget(category: String, value: Long) {
        require(category in listOf("FUEL", "FOOD", "OTHER") && value in 0..100_000_000)
        context.store.edit { it[longPreferencesKey(category.lowercase() + "_budget")] = value }
    }

    suspend fun zeroConfirmationVoice(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("zero_confirm_voice")] = enabled }
    }

    suspend fun speedAdaptiveAudio(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("speed_adaptive_audio")] = enabled }
    }

    suspend fun autoStopBookmark(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("auto_stop_bookmark")] = enabled }
    }

    suspend fun autoOpenCockpitOnBluetooth(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("auto_open_cockpit_bt")] = enabled }
    }

    suspend fun autoMorningBriefing(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("auto_morning_briefing")] = enabled }
    }

    suspend fun autoDailyBackup(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("auto_daily_backup")] = enabled }
    }

    suspend fun unifiedBackupFolderUri(uri: String) {
        context.store.edit { it[stringPreferencesKey("unified_backup_folder")] = uri }
    }

    suspend fun stationaryDeepSleep(enabled: Boolean) {
        context.store.edit { it[booleanPreferencesKey("stationary_deep_sleep")] = enabled }
    }

    suspend fun update(transform: (Preferences) -> Preferences) {
        val next = transform(flow.first())
        context.store.edit {
            it[stringPreferencesKey("theme")] = next.theme
            it[booleanPreferencesKey("auto")] = next.auto
            it[booleanPreferencesKey("onboarded")] = next.onboarded
            it[stringPreferencesKey("attribution")] = next.offlineAttribution
            it[booleanPreferencesKey("online_maps")] = next.onlineMaps
            it[stringPreferencesKey("fuel_grade")] = next.fuelGrade
            it[longPreferencesKey("fuel_price")] = next.fuelPriceHalala
            it[stringPreferencesKey("engine")] = next.engine
            it[longPreferencesKey("custom_consumption")] = next.customConsumptionMl
            it[booleanPreferencesKey("use_measured")] = next.useMeasured
            it[booleanPreferencesKey("sms_import")] = next.smsImport
            it[longPreferencesKey("sms_import_since")] = next.smsImportSince
            it[longPreferencesKey("monthly_budget")] = next.monthlyBudgetHalala
            it[stringPreferencesKey("bluetooth_address")] = next.bluetoothAddress
            it[booleanPreferencesKey("precise_tracking")] = next.preciseTracking
            it[longPreferencesKey("fuel_budget")] = next.fuelBudget
            it[longPreferencesKey("food_budget")] = next.foodBudget
            it[longPreferencesKey("other_budget")] = next.otherBudget
            it[booleanPreferencesKey("turn_detail")] = next.turnDetails
            it[booleanPreferencesKey("zero_confirm_voice")] = next.zeroConfirmationVoice
            it[booleanPreferencesKey("speed_adaptive_audio")] = next.speedAdaptiveAudio
            it[booleanPreferencesKey("auto_stop_bookmark")] = next.autoStopBookmark
            it[booleanPreferencesKey("auto_open_cockpit_bt")] = next.autoOpenCockpitOnBluetooth
            it[booleanPreferencesKey("auto_morning_briefing")] = next.autoMorningBriefing
            it[booleanPreferencesKey("auto_daily_backup")] = next.autoDailyBackup
            it[stringPreferencesKey("unified_backup_folder")] = next.unifiedBackupFolderUri
            it[booleanPreferencesKey("stationary_deep_sleep")] = next.stationaryDeepSleep
        }
    }

    suspend fun exportJson(): String {
        val p = flow.first()
        return JSONObject()
            .put("theme", p.theme)
            .put("auto", p.auto)
            .put("onboarded", p.onboarded)
            .put("offlineAttribution", p.offlineAttribution)
            .put("onlineMaps", p.onlineMaps)
            .put("fuelGrade", p.fuelGrade)
            .put("fuelPriceHalala", p.fuelPriceHalala)
            .put("engine", p.engine)
            .put("customConsumptionMl", p.customConsumptionMl)
            .put("useMeasured", p.useMeasured)
            .put("smsImport", p.smsImport)
            .put("smsImportSince", p.smsImportSince)
            .put("monthlyBudgetHalala", p.monthlyBudgetHalala)
            .put("bluetoothAddress", p.bluetoothAddress)
            .put("preciseTracking", p.preciseTracking)
            .put("fuelBudget", p.fuelBudget)
            .put("foodBudget", p.foodBudget)
            .put("otherBudget", p.otherBudget)
            .put("turnDetails", p.turnDetails)
            .put("zeroConfirmationVoice", p.zeroConfirmationVoice)
            .put("speedAdaptiveAudio", p.speedAdaptiveAudio)
            .put("autoStopBookmark", p.autoStopBookmark)
            .put("autoOpenCockpitOnBluetooth", p.autoOpenCockpitOnBluetooth)
            .put("autoMorningBriefing", p.autoMorningBriefing)
            .put("autoDailyBackup", p.autoDailyBackup)
            .put("unifiedBackupFolderUri", p.unifiedBackupFolderUri)
            .put("stationaryDeepSleep", p.stationaryDeepSleep)
            .toString()
    }

    suspend fun restoreJson(raw: String, overwrite: Boolean) {
        val json = JSONObject(raw)
        context.store.edit { prefs ->
            if (overwrite) prefs.clear()
            fun putStringIfNeeded(key: String, jsonKey: String) {
                val k = stringPreferencesKey(key)
                if (json.has(jsonKey) && (overwrite || prefs[k].isNullOrEmpty())) {
                    prefs[k] = json.optString(jsonKey, "")
                }
            }
            fun putLongIfNeeded(key: String, jsonKey: String) {
                val k = longPreferencesKey(key)
                if (json.has(jsonKey) && (overwrite || (prefs[k] ?: 0L) == 0L)) {
                    prefs[k] = json.optLong(jsonKey, 0L)
                }
            }
            fun putBoolIfNeeded(key: String, jsonKey: String) {
                val k = booleanPreferencesKey(key)
                if (json.has(jsonKey) && (overwrite || prefs[k] == null)) {
                    prefs[k] = json.optBoolean(jsonKey)
                }
            }
            putStringIfNeeded("theme", "theme")
            putBoolIfNeeded("auto", "auto")
            putBoolIfNeeded("onboarded", "onboarded")
            putStringIfNeeded("attribution", "offlineAttribution")
            putBoolIfNeeded("online_maps", "onlineMaps")
            putStringIfNeeded("fuel_grade", "fuelGrade")
            putLongIfNeeded("fuel_price", "fuelPriceHalala")
            putStringIfNeeded("engine", "engine")
            putLongIfNeeded("custom_consumption", "customConsumptionMl")
            putBoolIfNeeded("use_measured", "useMeasured")
            putBoolIfNeeded("sms_import", "smsImport")
            putLongIfNeeded("sms_import_since", "smsImportSince")
            putLongIfNeeded("monthly_budget", "monthlyBudgetHalala")
            val bt = json.optString("bluetoothAddress", "")
            if (bt.isEmpty() || android.bluetooth.BluetoothAdapter.checkBluetoothAddress(bt)) {
                val k = stringPreferencesKey("bluetooth_address")
                if (overwrite || prefs[k].isNullOrEmpty()) prefs[k] = bt
            }
            putBoolIfNeeded("precise_tracking", "preciseTracking")
            putLongIfNeeded("fuel_budget", "fuelBudget")
            putLongIfNeeded("food_budget", "foodBudget")
            putLongIfNeeded("other_budget", "otherBudget")
            putBoolIfNeeded("turn_detail", "turnDetails")
            putBoolIfNeeded("zero_confirm_voice", "zeroConfirmationVoice")
            putBoolIfNeeded("speed_adaptive_audio", "speedAdaptiveAudio")
            putBoolIfNeeded("auto_stop_bookmark", "autoStopBookmark")
            putBoolIfNeeded("auto_open_cockpit_bt", "autoOpenCockpitOnBluetooth")
            putBoolIfNeeded("auto_morning_briefing", "autoMorningBriefing")
            putBoolIfNeeded("auto_daily_backup", "autoDailyBackup")
            putStringIfNeeded("unified_backup_folder", "unifiedBackupFolderUri")
            putBoolIfNeeded("stationary_deep_sleep", "stationaryDeepSleep")
        }
    }
}
