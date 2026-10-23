package sa.rihla

import android.app.Application
import androidx.room.Room
import sa.rihla.data.*

class RihlaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val jobs = getSharedPreferences("studio_jobs", MODE_PRIVATE)
        if (jobs.getBoolean("running", false)) {
            sa.rihla.audio.studio.StudioTasks.state.value =
                sa.rihla.audio.studio.StudioState(
                    "توقفت العملية السابقة؛ أعد طلبها. النقل قد يكون اكتمل جزئيًا."
                )
            jobs.edit().putBoolean("running", false).apply()
        }
        // Only abandoned temporary studio data; never user recordings or completed output.
        cacheDir
            .listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("studio-[0-9a-f-]{36}")) }
            ?.forEach { it.deleteRecursively() }
        java.io
            .File(filesDir, "processed_audio")
            .listFiles()
            ?.filter { it.extension == "part" }
            ?.forEach { it.delete() }
    }

    val transfers by lazy { TransferStore(this) }

    val audioDatabase by lazy {
        Room.databaseBuilder(this, sa.rihla.audio.AudioDatabase::class.java, "audio_library.db")
            .addMigrations(sa.rihla.audio.AudioDatabase.MIGRATION_1_2)
            .build()
    }
    val audio by lazy { sa.rihla.audio.AudioRepository(this, audioDatabase) }
    val database by lazy {
        Room.databaseBuilder(this, JourneyDatabase::class.java, "journeys.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()
    }
    val repository by lazy { JourneyRepository(database, settings) }
    val settings by lazy { Settings(this) }
}
