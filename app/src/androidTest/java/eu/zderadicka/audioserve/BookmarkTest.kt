package eu.zderadicka.audioserve

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.test.ProviderTestCase2
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.AndroidJUnit4
import eu.zderadicka.audioserve.data.BookmarkContract
import eu.zderadicka.audioserve.data.BookmarksDbHelper
import eu.zderadicka.audioserve.data.BookmarksProvider
import eu.zderadicka.audioserve.data.saveBookmark
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkTest: ProviderTestCase2<BookmarksProvider>(BookmarksProvider::class.java, "eu.zderadicka.audioserve"){

    lateinit var ctx: Context

    @Before
    public override fun setUp() {
        super.setUp()
        ctx = ApplicationProvider.getApplicationContext<Context>()

    }

    @Test
    fun testInsertAndDelete() {
        val i = 1
        val v = ContentValues()
        v.put(BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP, System.currentTimeMillis())
        v.put(BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID, "audio/author/book/chapter_$i")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_FOLDER_PATH, "audio/author/Some book")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_NAME, "chapter_$i")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_DESCRIPTION, "audio/author/book")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_POSITION, i*1000L)
        v.put(BookmarkContract.BookmarkEntry.COLUMN_CATEGORY, "To Read")

        val uri = ctx.contentResolver.insert(BookmarkContract.BookmarkEntry.CONTENT_URI, v)
        Assert.assertNotNull(uri)

        val c = ctx.contentResolver.query(BookmarkContract.BookmarkEntry.CONTENT_URI,
                null,null,null,null)
        Assert.assertEquals("Should have 1 rows", 1, c.count)
        c.close()

        val res = ctx.contentResolver.delete(uri!!,null,null)

        val c2 = ctx.contentResolver.query(BookmarkContract.BookmarkEntry.CONTENT_URI,
                null,null,null,null)
        Assert.assertEquals("Should have 0 rows after delete", 0, c2.count)
        c2.close()

    }

    @Test
    fun testInsertSame() {
        val i = 1
        val v = ContentValues()
        v.put(BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP, System.currentTimeMillis())
        v.put(BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID, "audio/author/book/chapter_$i")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_FOLDER_PATH, "audio/author/Some book")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_NAME, "chapter_$i")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_DESCRIPTION, "audio/author/book")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_POSITION, i*1000L)
        v.put(BookmarkContract.BookmarkEntry.COLUMN_CATEGORY, "To Read")

        val uri = ctx.contentResolver.insert(BookmarkContract.BookmarkEntry.CONTENT_URI, v)
        Assert.assertNotNull(uri)

        val uri2 = ctx.contentResolver.insert(BookmarkContract.BookmarkEntry.CONTENT_URI, v)
        Assert.assertNotNull(uri)

        Assert.assertEquals("If media id already exists should return same uri", uri, uri2)

        val c = ctx.contentResolver.query(BookmarkContract.BookmarkEntry.CONTENT_URI,
                null,null,null,null)
        Assert.assertEquals("Should have 1 rows", 1, c.count)
        c.close()


    }

    @Test
    fun testThatSameIsUpdated() {
        fun createVals(i:Long): ContentValues {
        val v = ContentValues()
        v.put(BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP, i)
        v.put(BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID, "audio/author/book/chapter_0")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_FOLDER_PATH, "audio/author/Some book")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_NAME, "chapter_0")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_DESCRIPTION, "audio/author/book")
        v.put(BookmarkContract.BookmarkEntry.COLUMN_POSITION, 1000L)
        v.put(BookmarkContract.BookmarkEntry.COLUMN_CATEGORY, "audio")
        return v
        }

        val v1 = createVals(1)
        ctx.contentResolver.insert(BookmarkContract.BookmarkEntry.CONTENT_URI, v1)
        val v2 = createVals(2)
        ctx.contentResolver.insert(BookmarkContract.BookmarkEntry.CONTENT_URI, v2)

        val c = ctx.contentResolver.query(BookmarkContract.BookmarkEntry.CONTENT_URI,null,null,null,null)
        Assert.assertEquals(1, c.count)
        Assert.assertTrue(c.moveToNext())
        val ts = c?.run{ getLong(getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP))}
        Assert.assertEquals(2L,ts)
    }

    @After
    public override fun tearDown() {
        val db = BookmarksDbHelper(ctx).writableDatabase
        db.delete(BookmarkContract.BookmarkEntry.TABLE_NAME, "1",null)

    }

}