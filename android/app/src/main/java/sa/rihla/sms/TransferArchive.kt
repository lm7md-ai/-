package sa.rihla.sms

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.provider.Telephony
import kotlinx.coroutines.*
import org.xmlpull.v1.XmlPullParser
import sa.rihla.data.TransferStore

object TransferArchive {
    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun scan(
        context: Context,
        store: TransferStore,
        progress: (Int, Int) -> Unit,
    ): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            check(allowed(context)) { "READ_SMS_REQUIRED" }
            var scanned = 0
            var added = 0
            context.contentResolver
                .query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf("_id", "body", "date", "date_sent"),
                    null,
                    null,
                    "date DESC",
                )
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        scanned++
                        val body = cursor.getString(1).orEmpty()
                        val time = cursor.getLong(3).takeIf { it > 0 } ?: cursor.getLong(2)
                        if (store.import(body, time, "inbox:${cursor.getLong(0)}")) added++
                        if (scanned % 100 == 0) progress(scanned, added)
                    }
                } ?: error("SMS_ARCHIVE_UNAVAILABLE")
            progress(scanned, added)
            scanned to added
        }

    suspend fun xml(input: java.io.InputStream, store: TransferStore): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            var bytes = 0L
            val bounded =
                object : java.io.FilterInputStream(input) {
                    override fun read(): Int {
                        val result = super.read()
                        if (result >= 0) require(++bytes <= 50 * 1024 * 1024)
                        return result
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val result = `in`.read(b, off, len)
                        if (result > 0) {
                            bytes += result
                            require(bytes <= 50 * 1024 * 1024)
                        }
                        return result
                    }
                }
            val parser = android.util.Xml.newPullParser()
            parser.setInput(bounded, null)
            var scanned = 0
            var added = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                currentCoroutineContext().ensureActive()
                require(event != XmlPullParser.DOCDECL) { "DTD_NOT_ALLOWED" }
                if (
                    event == XmlPullParser.START_TAG &&
                        parser.name == "sms" &&
                        parser.getAttributeValue(null, "type") == "1"
                ) {
                    scanned++
                    val body = parser.getAttributeValue(null, "body").orEmpty()
                    val time =
                        parser.getAttributeValue(null, "date_sent")?.toLongOrNull()?.takeIf {
                            it > 0
                        } ?: parser.getAttributeValue(null, "date")?.toLongOrNull()
                    if (store.import(body, time, "xml:$scanned:${time?:0}")) added++
                }
                event = parser.nextToken()
            }
            scanned to added
        }
}
