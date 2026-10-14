package sa.rihla.audio

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioLibraryTest {
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),AudioDatabase::class.java).build()
    private fun track(uri: String="content://music/1") = AudioTrack(AudioRepository.id(uri),uri,"مقطع اختبار","فنان","ألبوم",120000)

    @Test
    fun rescanningRetainsFavoritePositionPlaylistAndBookmarks() = runBlocking {
        val db=database(); val repo=AudioRepository(RuntimeEnvironment.getApplication(),db)
        try {
            val track=track(); assertTrue(repo.add(track))
            repo.dao.favorite(track.id); repo.dao.progress(track.id,40000,1000)
            val list=repo.createPlaylist("طريق الجامعة")
            repo.addToPlaylist(list,track.id); repo.addToPlaylist(list,track.id)
            repo.dao.bookmark(AudioBookmark(trackId=track.id,positionMs=30000,label="موضع"))
            assertFalse(repo.add(track.copy(title="عنوان محدث")))
            val updated=repo.dao.track(track.id)!!
            assertTrue(updated.favorite); assertEquals(40000L,updated.positionMs); assertEquals("عنوان محدث",updated.title)
            assertEquals(1,repo.dao.members().first().size); assertEquals(1,repo.dao.bookmarks().first().size)
            repo.dao.progress(track.id,41000,0)
            assertEquals(1000L,repo.dao.track(track.id)!!.lastPlayedAt)
        } finally { db.close() }
    }

    @Test
    fun reorderAndDeleteCascadeOnlyLibraryReferences() = runBlocking {
        val db=database();val repo=AudioRepository(RuntimeEnvironment.getApplication(),db)
        try {
            val a=track();val b=track("content://music/2");repo.add(a);repo.add(b)
            val list=repo.createPlaylist("قائمة")
            repo.addToPlaylist(list,a.id);repo.addToPlaylist(list,b.id)
            val members=repo.dao.members().first();repo.swap(list,members[0],members[1])
            assertEquals(b.id,repo.dao.members().first().first().trackId)
            repo.dao.bookmark(AudioBookmark(trackId=a.id,positionMs=5000,label="موضع"))
            repo.dao.remove(a.id)
            assertEquals(listOf(b.id),repo.dao.members().first().map{it.trackId});assertTrue(repo.dao.bookmarks().first().isEmpty())
            repo.dao.removePlaylist(list);assertTrue(repo.dao.members().first().isEmpty());assertNotNull(repo.dao.track(b.id))
        } finally { db.close() }
    }

    @Test
    fun backupRestoresAllRecordsAndRejectsOverwriteAndMalformedReferences() = runBlocking {
        val source=database();val target=database();val malformedTarget=database()
        val repo=AudioRepository(RuntimeEnvironment.getApplication(),source)
        try {
            val t=track();repo.add(t);repo.dao.favorite(t.id);repo.dao.progress(t.id,20000,1000)
            val list=repo.createPlaylist("صوتيات");repo.addToPlaylist(list,t.id)
            repo.dao.bookmark(AudioBookmark(trackId=t.id,positionMs=10000,label="عودة"))
            val text=AudioBackup(source).export()
            assertEquals(1,AudioBackup(target).restore(text))
            assertEquals(repo.dao.tracks().first(),target.dao().tracks().first())
            assertEquals(repo.dao.members().first(),target.dao().members().first())
            assertEquals(repo.dao.bookmarks().first(),target.dao().bookmarks().first())
            assertThrows(IllegalArgumentException::class.java){runBlocking{AudioBackup(target).restore(text)}}
            val malformed=JSONObject(text).apply{getJSONArray("members").getJSONObject(0).put("track","missing")}
            assertThrows(Exception::class.java){runBlocking{AudioBackup(malformedTarget).restore(malformed.toString())}}
            assertTrue(malformedTarget.dao().tracks().first().isEmpty())
        } finally { source.close();target.close();malformedTarget.close() }
    }

    @Test
    fun savedPositionAndPlaylistsSurviveDatabaseReopen() = runBlocking {
        val context=RuntimeEnvironment.getApplication(); val name="audio-reopen.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,AudioDatabase::class.java,name).build()
        try {
            val t=track();db.dao().insert(t);db.dao().progress(t.id,45000,1000)
            db.close();db=Room.databaseBuilder(context,AudioDatabase::class.java,name).build()
            assertEquals(45000L,db.dao().track(t.id)!!.positionMs)
        } finally { db.close();context.deleteDatabase(name) }
    }

    @Test
    fun completedAndInvalidPositionsRestartAndFormattingIncludesHours() {
        assertEquals(0L,audioResume(119000,120000));assertEquals(50000L,audioResume(50000,120000));assertEquals(0L,audioResume(-1,120000))
        assertEquals("1:01:01",audioTime(3661000));assertEquals("0:00",audioTime(-1))
    }
}
