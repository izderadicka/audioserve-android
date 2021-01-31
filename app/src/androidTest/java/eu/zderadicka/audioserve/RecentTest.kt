package eu.zderadicka.audioserve

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.test.ProviderTestCase2
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.AndroidJUnit4
import eu.zderadicka.audioserve.data.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class RecentTest: ProviderTestCase2<BookmarksProvider>(BookmarksProvider::class.java,
        RecentContract.CONTENT_AUTHORITY) {

   lateinit var ctx: Context


    @Before
    public override fun setUp() {
        super.setUp()
        ctx = ApplicationProvider.getApplicationContext<Context>()

    }

    @Test
    fun testRegistration() {
        val cls = BookmarksProvider::class.java.name
        val component = ComponentName(ctx, cls)
        try {
            val providerInfo = ctx.packageManager?.getProviderInfo(component,0)
            assertEquals("Incorrect Authority", RecentContract.CONTENT_AUTHORITY, providerInfo?.authority)
        } catch (e: PackageManager.NameNotFoundException) {
            fail("RecentProvider is not registered")
        }
    }

    @Test
    fun testDifferent() {
        testInsertAndQuery()
    }

    @Test
    fun testSame() {
        testInsertAndQuery(same = true)
    }

    @Test
    fun testLimit() {
        val values = ArrayList<ContentValues>()
        for (i in 1..(MAX_RECENT_RECORDS+10)) {
            val v = ContentValues()
            v.put(RecentContract.RecentEntry.COLUMN_TIMESTAMP, System.currentTimeMillis())
            v.put(RecentContract.RecentEntry.COLUMN_MEDIA_ID, "audio/author/book/chapter_$i")
            v.put(RecentContract.RecentEntry.COLUMN_FOLDER_PATH, "audio/author/book$i")
            v.put(RecentContract.RecentEntry.COLUMN_NAME, "chapter_$i")
            v.put(RecentContract.RecentEntry.COLUMN_DESCRIPTION, "audio/author/book")
            v.put(RecentContract.RecentEntry.COLUMN_POSITION, i*1000L)
            v.put(RecentContract.RecentEntry.COLUMN_DURATION, i* 10000L)

            values.add(v)
        }

        for (v in values) {
            val uri = ctx.contentResolver.insert(RecentContract.RecentEntry.CONTENT_URI, v)
        }

        val c = ctx.contentResolver.query(RecentContract.RecentEntry.CONTENT_URI, null,null,null,null)
        assertEquals("Should have $MAX_RECENT_RECORDS rows", MAX_RECENT_RECORDS, c!!.count)
        c.close()
    }

    fun testInsertAndQuery(same:Boolean=false) {
        val values = ArrayList<ContentValues>()
        for (i in 1..10) {
            val v = ContentValues()
            v.put(RecentContract.RecentEntry.COLUMN_TIMESTAMP, System.currentTimeMillis())
            v.put(RecentContract.RecentEntry.COLUMN_MEDIA_ID, "audio/author/book/chapter_$i")
            v.put(RecentContract.RecentEntry.COLUMN_FOLDER_PATH, "audio/author/book" +if (same) "" else "$i")
            v.put(RecentContract.RecentEntry.COLUMN_NAME, "chapter_$i")
            v.put(RecentContract.RecentEntry.COLUMN_DESCRIPTION, "audio/author/book")
            v.put(RecentContract.RecentEntry.COLUMN_POSITION, i*1000L)
            v.put(RecentContract.RecentEntry.COLUMN_DURATION, i* 10000L)

            values.add(v)
        }
        for (v in values) {
            val uri = ctx.contentResolver.insert(RecentContract.RecentEntry.CONTENT_URI, v)
        }

        val c = ctx.contentResolver.query(RecentContract.RecentEntry.CONTENT_URI, null,null,null,null)
        assertEquals("Should have 10 rows", if (same) 1 else 10, c!!.count)
        c.close()

    }

    @Test
    fun testMedia() {
        fun createMediaItem(i: Int): MediaBrowserCompat.MediaItem {
            val extras = Bundle()
            val descBuilder = MediaDescriptionCompat.Builder()
                    .setMediaId("audio/author/book$i/chapter_$i")
                    .setTitle("chapter_$i")
                    .setSubtitle("audio/author/book$i")

            extras.putLong(METADATA_KEY_DURATION, i*10000L)
            extras.putLong(METADATA_KEY_LAST_POSITION, i* 1000L)
            extras.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP,System.currentTimeMillis())
            descBuilder.setExtras(extras)
            return MediaBrowserCompat.MediaItem(descBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }

        saveRecent(createMediaItem(1), ctx)
        val l = getRecents(ctx)

        assertEquals("One recent", 1, l.size)
        assertEquals("has same mediaId",l[0].mediaId,"audio/author/book1/chapter_1" )
    }

    @After
    public override fun tearDown() {
        super.tearDown()
        //ctx.deleteDatabase(RECENT_DATABASE_NAME)
        val db = BookmarksDbHelper(ctx).writableDatabase
        db.delete(RecentContract.RecentEntry.TABLE_NAME, "1",null)
    }
}