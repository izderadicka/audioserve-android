package eu.zderadicka.audioserve.fragments

import android.database.Cursor
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.LoaderManager
import android.support.v4.content.CursorLoader
import android.support.v4.content.Loader
import android.support.v4.media.MediaBrowserCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.BookmarkContract

class BookmarkViewHolder(itemView: View, clickCallback: (Int) -> Unit): RecyclerView.ViewHolder(itemView) {
    var itemNameView: TextView = itemView.findViewById(R.id.folderItemName)
    var positionView: TextView = itemView.findViewById(R.id.positionView)
    var bookmarkedAtView: TextView = itemView.findViewById(R.id.bookmarkedAtView)
    var folderPathView: TextView = itemView.findViewById(R.id.folderPathView)

    init {
        itemView.setOnClickListener{
            clickCallback(adapterPosition)
        }
    }

}

class BookmarksAdapter(val onClickAction: (MediaBrowserCompat.MediaItem) -> Unit) : RecyclerView.Adapter<BookmarkViewHolder>() {
    var cursor: Cursor? = null


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.bookmark2_item, parent, false)
        return BookmarkViewHolder(view, this::onPositionClick)
    }

    private fun onPositionClick(pos: Int) {

    }

    override fun getItemCount(): Int =
            cursor?.count ?: 0

    fun swapCursor(c:Cursor) {
        val old = cursor
        cursor = c
        old?.close()
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(viewHolder: BookmarkViewHolder, pos: Int) {
        cursor?.let {cursor ->
            if (cursor.moveToPosition(pos)) {
                val name = cursor.getString(cursor.getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_NAME))
                val path = cursor.getString(cursor.getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_FOLDER_PATH))
                val ts = cursor.getLong(cursor.getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP))
                val position = cursor.getLong(cursor.getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_POSITION))

                viewHolder.itemNameView.text = name
                viewHolder.folderPathView.text = path
                viewHolder.positionView.text = DateUtils.formatElapsedTime(
                        position / 1000L)
                viewHolder.bookmarkedAtView.text =  DateUtils.getRelativeTimeSpanString(
                        ts,
                        System.currentTimeMillis(),
                        0
                )

            }

        }
    }

}

class BookmarksFragment: Fragment(), BaseFolderFragment, LoaderManager.LoaderCallbacks<Cursor> {

    lateinit var adapter: BookmarksAdapter
    lateinit var mediaActivity: MediaActivity
    lateinit var folderView: RecyclerView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderName = getString(R.string.folder_bookmarks)
    }


    override fun onStart() {
        super.onStart()
        //this is a hack to update search and info menu in main activity
        mediaActivity.onFolderLoaded(folderId, null,true, false)
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_folder, container, false)
        folderView = view.findViewById(R.id.folderView)
        folderView.layoutManager = LinearLayoutManager(context)

        adapter = BookmarksAdapter {item ->
            mediaActivity.onItemClicked(item, ItemAction.Open)
        }
        folderView.adapter = adapter

        if (context is MediaActivity && context is TopActivity) {
            mediaActivity = context as MediaActivity
            (context as TopActivity).setFolderTitle(folderName)
        } else {
            throw RuntimeException(context.toString() + " must implement MediaActivity, TopActivity")
        }

        //loadingProgress = view.findViewById(R.id.progressBar)
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        loaderManager.initLoader(0, null, this)
    }

    override fun onMediaServiceConnected() {
    }

    override val folderId: String = "__BOOKMARKS"
    override lateinit var folderName: String
    private set

    override fun scrollToNowPlaying() {
        //this is no action for bookmarks - at least now
    }

    override fun reload() {
        loaderManager.restartLoader(0, null, this)
    }

    override fun onCreateLoader(p0: Int, p1: Bundle?): Loader<Cursor> =
        activity?.let { ctx ->
            CursorLoader(ctx, BookmarkContract.BookmarkEntry.CONTENT_URI,
                    BookmarkContract.BookmarkEntry.DEFAULT_PROJECTION,
                    null, null,
                    "${BookmarkContract.BookmarkEntry.COLUMN_TIMESTAMP} DESC")
        }?: throw Exception("Activity cannot be null")


    override fun onLoadFinished(loader: Loader<Cursor>, cursor: Cursor?) {
        adapter.swapCursor(cursor!!)
    }

    override fun onLoaderReset(p0: Loader<Cursor>) {
       //TODO - do we need to reset?
    }
}