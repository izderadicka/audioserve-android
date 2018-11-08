package eu.zderadicka.audioserve

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import eu.zderadicka.audioserve.data.BookmarkContract
import eu.zderadicka.audioserve.data.BookmarksDbHelper
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkTest {

    val ctx: Context = InstrumentationRegistry.getTargetContext()

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

    @After
    fun cleanUp() {
        val db = BookmarksDbHelper(ctx).writableDatabase
        db.delete(BookmarkContract.BookmarkEntry.TABLE_NAME, "1",null)

    }

}