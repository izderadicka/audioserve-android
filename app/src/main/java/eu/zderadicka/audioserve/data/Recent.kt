package eu.zderadicka.audioserve.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import eu.zderadicka.audioserve.data.RecentContract.RecentEntry
import java.io.File

object RecentContract {
    const val RECENT_PATH = "recent"
    const val CONTENT_AUTHORITY = "eu.zderadicka.audioserve"
    object RecentEntry {
        val CONTENT_URI = Uri.parse("content://$CONTENT_AUTHORITY/$RECENT_PATH")
        const val TABLE_NAME = "recent"
        const val _ID = BaseColumns._ID
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_MEDIA_ID = "media_id"
        const val COLUMN_FOLDER_PATH = "folder_path"
        const val COLUMN_NAME = "name"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_POSITION = "position"
        const val COLUMN_DURATION = "duration"

        val DEFAULT_PROJECTION = arrayOf(_ID, COLUMN_TIMESTAMP, COLUMN_MEDIA_ID, COLUMN_FOLDER_PATH, COLUMN_NAME,
                COLUMN_DESCRIPTION, COLUMN_POSITION, COLUMN_DURATION)

    }
}
internal const val RECENT_DATABASE_NAME = "recent.db"
private const val DATABASE_VERSION = 1
class RecentDbHelper(context: Context): SQLiteOpenHelper(context, RECENT_DATABASE_NAME,null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        val query = """CREATE TABLE ${RecentEntry.TABLE_NAME} (
            ${RecentEntry._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${RecentEntry.COLUMN_TIMESTAMP} INTEGER NOT NULL,
            ${RecentEntry.COLUMN_MEDIA_ID} TEXT NOT NULL UNIQUE,
            ${RecentEntry.COLUMN_FOLDER_PATH} TEXT NOT NULL,
            ${RecentEntry.COLUMN_NAME} TEXT NOT NULL,
            ${RecentEntry.COLUMN_DESCRIPTION} TEXT,
            ${RecentEntry.COLUMN_POSITION} INTEGER,
            ${RecentEntry.COLUMN_DURATION} INTEGER
            )
        """
        db.execSQL(query)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {

    }

}

internal const val MAX_RECENT_RECORDS = 100
class RecentProvider: ContentProvider() {
    lateinit var  dbHelper: RecentDbHelper
    override fun insert(uri: Uri?, values: ContentValues?): Uri {
        if (uri != RecentEntry.CONTENT_URI) throw UnsupportedOperationException("Invalid content URI")
        val db = dbHelper.writableDatabase
        val path = values?.getAsString(RecentEntry.COLUMN_FOLDER_PATH)
        db.beginTransaction()
        try {
            val deleted = db.delete(RecentEntry.TABLE_NAME, "${RecentEntry.COLUMN_FOLDER_PATH}=?",
                    arrayOf(path))
            val c = db.query(RecentEntry.TABLE_NAME, arrayOf(RecentEntry._ID), null, null,
                    null, null, "${RecentEntry.COLUMN_TIMESTAMP} DESC", "${MAX_RECENT_RECORDS-1},$MAX_RECENT_RECORDS")
            c.use {
                while (c.moveToNext()) {
                    db.delete(RecentEntry.TABLE_NAME, "${RecentEntry._ID}=?", arrayOf(c.getString(0)))
                }
            }

            val id = db.insert(RecentEntry.TABLE_NAME, null, values)
            db.setTransactionSuccessful()
            return uri!!.buildUpon().appendPath(id.toString()).build()
        } finally {
            db.endTransaction()
        }



    }

    override fun query(uri: Uri?, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (uri != RecentEntry.CONTENT_URI) throw UnsupportedOperationException("Invalid content URI")

        val db = dbHelper.readableDatabase
        val c =db.query(RecentEntry.TABLE_NAME, projection?: RecentEntry.DEFAULT_PROJECTION, selection, selectionArgs,
                null,null, RecentEntry.COLUMN_TIMESTAMP + " DESC")
        c.setNotificationUri(context.contentResolver, uri)
        return c
    }

    override fun onCreate(): Boolean {
        dbHelper = RecentDbHelper(context)
        return true
    }

    override fun update(uri: Uri?, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun delete(uri: Uri?, selection: String?, selectionArgs: Array<out String>?): Int {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getType(uri: Uri?): String {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}

fun saveRecent(item:MediaBrowserCompat.MediaItem, context: Context) {
    val v = ContentValues()
    v.put(RecentContract.RecentEntry.COLUMN_TIMESTAMP, item.description.extras?.getLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP))
    v.put(RecentContract.RecentEntry.COLUMN_MEDIA_ID, item.mediaId)
    val path = File(item.mediaId).parent
    v.put(RecentContract.RecentEntry.COLUMN_FOLDER_PATH, path)
    v.put(RecentContract.RecentEntry.COLUMN_NAME, item.description.title.toString())
    v.put(RecentContract.RecentEntry.COLUMN_DESCRIPTION, item.description.subtitle.toString())
    v.put(RecentContract.RecentEntry.COLUMN_POSITION, item.description.extras?.getLong(METADATA_KEY_LAST_POSITION))
    v.put(RecentContract.RecentEntry.COLUMN_DURATION, item.description.extras?.getLong(METADATA_KEY_DURATION))

    context.contentResolver.insert(RecentEntry.CONTENT_URI, v)
}

fun getRecents(context: Context, exceptPath: String? = null): List<MediaBrowserCompat.MediaItem> {
    var selection:String? = null
    var selectionArgs:Array<String?>? = null
    if (exceptPath != null) {
        selection = "${RecentEntry.COLUMN_FOLDER_PATH} != ?"
        selectionArgs = arrayOf(exceptPath)
    }
    val l = ArrayList<MediaBrowserCompat.MediaItem>()
    val c = context.contentResolver.query(RecentEntry.CONTENT_URI, null, selection,selectionArgs,null)
    c.use {
        while (c.moveToNext()) {
            val extras = Bundle()
            val descBuilder = MediaDescriptionCompat.Builder()
                    .setMediaId(c.getString(c.getColumnIndex(RecentEntry.COLUMN_MEDIA_ID)))
                    .setTitle(c.getString(c.getColumnIndex(RecentEntry.COLUMN_NAME)))
                    .setSubtitle(c.getString(c.getColumnIndex(RecentEntry.COLUMN_DESCRIPTION)))

            extras.putLong(METADATA_KEY_DURATION, c.getLong(c.getColumnIndex(RecentEntry.COLUMN_DURATION)))
            extras.putLong(METADATA_KEY_LAST_POSITION, c.getLong(c.getColumnIndex(RecentEntry.COLUMN_POSITION)))
            extras.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP, c.getLong(c.getColumnIndex(RecentEntry.COLUMN_TIMESTAMP)))
            extras.putBoolean(METADATA_KEY_IS_BOOKMARK, true)

            descBuilder.setExtras(extras)
            val item = MediaBrowserCompat.MediaItem(descBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)

            l.add(item)
        }
    }

    return l
}