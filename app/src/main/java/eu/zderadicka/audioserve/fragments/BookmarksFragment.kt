package eu.zderadicka.audioserve.fragments

import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.LoaderManager
import android.support.v4.content.CursorLoader
import android.support.v4.content.Loader
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.BookmarkContract
import eu.zderadicka.audioserve.data.BookmarkDeleteTask
import eu.zderadicka.audioserve.data.METADATA_KEY_IS_BOOKMARK
import eu.zderadicka.audioserve.data.METADATA_KEY_LAST_POSITION

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

class BookmarksAdapter(val ctx: Context,
                       val onClickAction: (MediaBrowserCompat.MediaItem) -> Unit,
                       val requery: ()->Unit
    ) : RecyclerView.Adapter<BookmarkViewHolder>() {
    var cursor: Cursor? = null


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.bookmark2_item, parent, false)
        return BookmarkViewHolder(view, this::onPositionClick)
    }

    private fun onPositionClick(pos: Int) {
        if (cursor?.moveToPosition(pos) == true) {
            val mediaItem = cursor?.run {
                val bundle = Bundle()
                val mediaId = getString(getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_MEDIA_ID))
                val cat = getString(getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_CATEGORY))
                val pos = getLong(getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_POSITION))
                val name = getString(getColumnIndex(BookmarkContract.BookmarkEntry.COLUMN_NAME))

                bundle.putBoolean(METADATA_KEY_IS_BOOKMARK,true)
                if (cat == "audio") {
                    bundle.putLong(METADATA_KEY_LAST_POSITION, pos)
                }

                val descBuilder = MediaDescriptionCompat.Builder()
                        .setMediaId(mediaId)
                        .setTitle(name)
                        .setExtras(bundle)

                MediaBrowserCompat.MediaItem(descBuilder.build(),
                        if (cat == "audio") MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)

            }

            mediaItem?.let(onClickAction)
        }

    }

    override fun getItemCount(): Int =
            cursor?.count ?: 0

    fun swapCursor(c:Cursor?) {
        val old = cursor
        cursor = c
        old?.close()
        notifyDataSetChanged()
    }

    fun deleteAtPosition(pos: Int) {
        if (cursor?.moveToPosition(pos) == true) {
            cursor?.run {
                val id = getLong(getColumnIndex(BookmarkContract.BookmarkEntry._ID))
                BookmarkDeleteTask(ctx){
                    if (it) {
                        // we have to requery
                        this@BookmarksAdapter.requery()
                    }
                    else {
                        Toast.makeText(ctx, "Delete of bookmark failed!", Toast.LENGTH_SHORT).show()
                        notifyDataSetChanged()
                    }
                }.execute(id)


            }
        }
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

class SwipeToDeleteCallback(val adapter: BookmarksAdapter): ItemTouchHelper.SimpleCallback(0,
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    override fun onMove(p0: RecyclerView, p1: RecyclerView.ViewHolder, p2: RecyclerView.ViewHolder): Boolean {
       // do nothing
        return false
    }

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
       //delete at current pos
        adapter.deleteAtPosition(vh.adapterPosition)

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

        adapter = BookmarksAdapter(activity!!.application, {item ->
            mediaActivity.onItemClicked(item, ItemAction.Open)
            },
                {
                   loaderManager.restartLoader(0,null, this)
                }
        )
        folderView.adapter = adapter
        ItemTouchHelper(SwipeToDeleteCallback(adapter)).attachToRecyclerView(folderView)

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
        adapter.swapCursor(cursor)
    }

    override fun onLoaderReset(p0: Loader<Cursor>) {
        adapter.swapCursor(null)
    }
}