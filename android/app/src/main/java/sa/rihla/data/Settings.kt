package sa.rihla.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

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
)

class Settings(private val context: Context) {
    val flow =
        context.store.data.map {
            Preferences(
                it[stringPreferencesKey("theme")] ?: "SYSTEM",
                it[booleanPreferencesKey("auto")] ?: false,
                it[booleanPreferencesKey("onboarded")] ?: false,
                it[stringPreferencesKey("attribution")] ?: "",
                it[booleanPreferencesKey("online_maps")] ?: false,
                it[stringPreferencesKey("fuel_grade")] ?: "91",
                it[longPreferencesKey("fuel_price")] ?: 218,
                if (it[stringPreferencesKey("engine")] == "CUSTOM") "CUSTOM" else "LANCER_16_AT",
                it[longPreferencesKey("custom_consumption")] ?: 8000,
                it[booleanPreferencesKey("use_measured")] ?: true,
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
}
