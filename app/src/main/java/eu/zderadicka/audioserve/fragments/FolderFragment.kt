package eu.zderadicka.audioserve.fragments

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.format.DateUtils
import android.util.Log
import eu.zderadicka.audioserve.utils.ifStoppedOrDead
import android.os.Parcelable
import android.support.v4.content.ContextCompat
import android.view.*
import android.widget.*
import eu.zderadicka.audioserve.*
import eu.zderadicka.audioserve.data.*
import eu.zderadicka.audioserve.ui.SwipeRevealLayout


// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
const val ARG_FOLDER_PATH = "folder-path"
const val ARG_FOLDER_NAME = "folder-name"
const val ARG_PREPARE = "folder-prepare"

const val FOLDER_VIEW_STATE_KEY = "eu.zderadicka.audiserve.folderViewKey"

const val ITEM_TYPE_FOLDER = 0
const val ITEM_TYPE_FILE = 1
const val ITEM_TYPE_BOOKMARK = 2
const val ITEM_TYPE_SEARCH_FOLDER = 3

private const val MIN_BORDER_DISTANCE: Float = 0.07f

enum class ItemAction {
    Open,
    Download,
    Bookmark
}

private const val LOG_TAG = "FolderFragment"

//TODO icon for item type - folder or audio file
// TODO icon for currently played icon - that nice equlizer bar from Universal player
class FolderItemViewHolder(itemView: View, viewType: Int, val clickCB: (Int, ItemAction) -> Unit) : RecyclerView.ViewHolder(itemView) {

    var itemName: TextView = itemView.findViewById(R.id.folderItemName)
    var durationView: TextView? = null
    var bitRateView: TextView? = null
    var transcodedIcon: ImageView? = null
    var cachedIcon: ImageView? = null
    var positionView: TextView? = null
    var lastListenedView: TextView? = null
    var folderPathView: TextView? = null
    var contentView: View? = null
    var itemContainer: View? = null
    var downloadButton: ImageButton? = null
    var bookmarkButton: ImageButton? = null
    var extensionView: TextView? = null
    var isFile = false
    private set
    var isBookmark = false
    private set
    var isSearch = false
    private set

    private val clickDetector: GestureDetector

    init {
        fun setButton(btnId: Int, action: ItemAction): ImageButton {
            val btn: ImageButton = itemView.findViewById(btnId)
            btn.setOnClickListener {
                //val animator = ObjectAnimator.ofInt(itemContainer, "left", 0)
                //animator.start()
                (itemView as SwipeRevealLayout).close(true)
                clickCB(adapterPosition, action)
            }
            return btn
        }

        when (viewType) {
            ITEM_TYPE_FILE -> {
                contentView = itemView.findViewById(R.id.contentView)
                itemContainer = itemView.findViewById(R.id.itemContainer)
                downloadButton = setButton(R.id.downloadButton, ItemAction.Download)
                bookmarkButton = setButton(R.id.bookmarkButton, ItemAction.Bookmark)
                durationView = itemView.findViewById(R.id.durationView)
                bitRateView = itemView.findViewById(R.id.bookmarkedAtView)
                transcodedIcon = itemView.findViewById(R.id.transcodedIcon)
                cachedIcon = itemView.findViewById(R.id.cachedIcon)
                extensionView = itemView.findViewById(R.id.extesionView)
                isFile = true
            }
            ITEM_TYPE_BOOKMARK -> {
                durationView = itemView.findViewById(R.id.durationView)
                positionView = itemView.findViewById(R.id.positionView)
                lastListenedView = itemView.findViewById(R.id.bookmarkedAtView)
                folderPathView = itemView.findViewById(R.id.folderPathView)
                isBookmark = true
                bookmarkButton = setButton(R.id.bookmarkButton, ItemAction.Bookmark)
                contentView = itemView.findViewById(R.id.contentView)
            }
            ITEM_TYPE_SEARCH_FOLDER -> {
                folderPathView = itemView.findViewById(R.id.folderPathView)
                bookmarkButton = setButton(R.id.bookmarkButton, ItemAction.Bookmark)
                contentView = itemView.findViewById(R.id.contentView)
                isSearch = true

            }
            ITEM_TYPE_FOLDER -> {
                contentView = itemView.findViewById(R.id.contentView)
                bookmarkButton = setButton(R.id.bookmarkButton, ItemAction.Bookmark)
            }

            else -> {
                throw IllegalArgumentException("Unknown ViewType")
            }

        }

        contentView?.setOnClickListener {
            clickCB(adapterPosition, ItemAction.Open)
        }

        // disable clicks on very right and left borders of the screen to limit accidental touches
        val xdpi = itemView.resources.displayMetrics.xdpi
        clickDetector = GestureDetector(itemView.context, object: GestureDetector.SimpleOnGestureListener(){
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val borderDistance = Math.min(e.x, (contentView?.right?.toFloat()?: Float.MAX_VALUE) - e.x) / xdpi
                if (borderDistance> MIN_BORDER_DISTANCE) {
                    Log.d(LOG_TAG, "Click perform - border distance ${borderDistance}")
                    contentView?.performClick()
                } else {
                    Log.d(LOG_TAG, "No Click too close to border distance ${borderDistance}")
                }
                return super.onSingleTapUp(e)

            }
        })
        contentView?.setOnTouchListener{
            _, event ->
            clickDetector.onTouchEvent(event)
            true
        }

    }
}


class FolderAdapter(val context: Context, val isSearch:Boolean,
                    private val itemCb: (MediaItem, ItemAction, Boolean) -> Unit)
    : RecyclerView.Adapter<FolderItemViewHolder>() {

    private var items: List<MediaItem>? = null
    internal var nowPlaying: Int = -1
    private var pendingMediaId: String? = null  // if we got now playing metadata, but list is not loaded yet
    private val idMap: HashMap<String,Int> = HashMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderItemViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        var viewId = R.layout.folder_item
        if (viewType == ITEM_TYPE_FILE) {
            viewId = R.layout.file_item
        } else if (viewType == ITEM_TYPE_BOOKMARK) {
            viewId = R.layout.bookmark_item
        } else if (viewType == ITEM_TYPE_SEARCH_FOLDER) {
            viewId = R.layout.search_folder_item
        }
        val view = inflater.inflate(viewId, parent, false)
        return FolderItemViewHolder(view, viewType, this::onItemClicked)

    }

    private fun onItemClicked(index: Int, action: ItemAction) {

        val item = items?.get(index)
        if (item != null) {
            val currentlyPlaying = index == nowPlaying
            itemCb(item, action, currentlyPlaying)
        }

    }

    override fun getItemCount(): Int {
        return items?.size ?: 0
    }

    override fun getItemViewType(position: Int): Int {
        val item = items?.get(position)
        if (item == null) {
            return ITEM_TYPE_FOLDER
        }
        return if ( item.isPlayable ) {
            if (item.description.extras?.getBoolean(METADATA_KEY_IS_BOOKMARK) == true) ITEM_TYPE_BOOKMARK
                else ITEM_TYPE_FILE
        } else if (isSearch) {
            ITEM_TYPE_SEARCH_FOLDER
        } else {
            ITEM_TYPE_FOLDER
        }
    }

    override fun onBindViewHolder(holder: FolderItemViewHolder, position: Int) {
        val item = items?.get(position)
        if (item == null) return
        holder.itemName.text = item.description.title

        if ((holder.isFile || holder.isBookmark) && item.isPlayable) {
            holder.durationView?.text =
                    DateUtils.formatElapsedTime((item.description.extras?.getLong(METADATA_KEY_DURATION)
                            ?: 0) / 1000L)
        }

        if (holder.isFile) {

            (holder.itemView as SwipeRevealLayout).close(false)
            if (position == nowPlaying) {
                holder.contentView?.setBackgroundColor(ContextCompat.getColor(context,R.color.colorAccentLight))
            } else {
                holder.contentView?.setBackgroundColor(ContextCompat.getColor(context, R.color.colorListBackground))
            }

            holder.extensionView?.text = item.description.extras?.getString(METADATA_KEY_EXTENSION,"")

            holder.bitRateView?.text =
                    item.description.extras?.getInt(METADATA_KEY_BITRATE)?.toString()?:"?"

            if (item.description.extras?.getBoolean(METADATA_KEY_TRANSCODED)?: false) {
                holder.transcodedIcon?.visibility = View.VISIBLE
            } else {
                holder.transcodedIcon?.visibility = View.INVISIBLE
            }

            if (item.description.extras?.getBoolean(METADATA_KEY_CACHED)?: false) {
                holder.cachedIcon?.visibility = View.VISIBLE
            } else {
                holder.cachedIcon?.visibility = View.INVISIBLE
            }
        } else if (holder.isBookmark) {
            holder.positionView?.text = DateUtils.formatElapsedTime((
                    item.description.extras?.getLong(METADATA_KEY_LAST_POSITION)?: 0) / 1000L)

            holder.lastListenedView?.text = DateUtils.getRelativeTimeSpanString(
                    item.description.extras?.getLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP)?:0,
                    System.currentTimeMillis(),
                    0
            )

            holder.folderPathView?.text = item.description.subtitle

        }

        if (holder.isSearch) {
            holder.folderPathView?.text = pathFromFolderId(item.mediaId!!)
        }
    }

    fun changeData(newData: List<MediaItem>) {
        items = newData
        idMap.clear()
        for (i in 0 until newData.size) {
            idMap.put(newData.get(i).mediaId!!, i)
        }
        notifyDataSetChanged()
        if (pendingMediaId != null) updateNowPlaying(pendingMediaId!!)
    }

    fun updatedCached(mediaId: String, cached: Boolean) {
        val idx = idMap.get(mediaId)
        if (idx != null) {
            items?.get(idx)?.description?.extras?.putBoolean(METADATA_KEY_CACHED, cached)
            notifyItemChanged(idx)
        }
    }

    fun updateNowPlaying(mediaId: String): Int {
        val oldPlaying = nowPlaying
        val idx = idMap.get(mediaId)
        nowPlaying = if (idx == null) -1 else idx
        if (nowPlaying >= 0 && nowPlaying != oldPlaying) {
            notifyItemChanged(nowPlaying)
            if (oldPlaying >= 0) notifyItemChanged(oldPlaying)
            pendingMediaId = null
        } else if (nowPlaying < 0){
            pendingMediaId = mediaId
        }
        return nowPlaying
    }

    fun resetNowPlaying() {
        if (nowPlaying >= 0) {
            nowPlaying = -1
            notifyDataSetChanged()
        }
    }
}


interface MediaActivity {
    fun onItemClicked(item: MediaItem, action: ItemAction, currentlyPlaying: Boolean)
    fun onFolderLoaded(folderId: String, folderDetails: Bundle?, error: Boolean, empty: Boolean)
    val mediaBrowser: MediaBrowserCompat
}

interface TopActivity {
    fun setFolderTitle(title: String)
}



class FolderFragment : MediaFragment(), BaseFolderFragment {
    override lateinit var folderId: String
    private set

    override lateinit var folderName: String
    private set

    private var willPrepare = false

    private var mediaActivity: MediaActivity? = null
    private lateinit var adapter: FolderAdapter

    private lateinit var folderView: RecyclerView
    private lateinit var loadingProgress: ProgressBar
    private val handler = Handler()

    override val mCallback = object: MediaControllerCompat.Callback() {

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (metadata == null) {
                return
            }
            Log.d(LOG_TAG, "Received metadata state change to mediaId=${metadata.description.mediaId} song=${metadata.description.title}")
            if (metadata.description.mediaId != null) {
                adapter.updateNowPlaying(metadata.description.mediaId!!)
                scrollToNowPlaying()
            } else {
                Log.w(LOG_TAG,"Metadata should always contain mediaID :  ${metadata.description}")
            }
        }

    override  fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
        super.onPlaybackStateChanged(state)
        ifStoppedOrDead(state,
                {
            adapter.resetNowPlaying()
        })
    }

        override fun onSessionEvent(event: String?, extras: Bundle?) {
            super.onSessionEvent(event, extras)
            when (event) {
            MEDIA_FULLY_CACHED, MEDIA_CACHE_DELETED -> {
                val cached = event == MEDIA_FULLY_CACHED
                val mediaId = extras?.getString(METADATA_KEY_MEDIA_ID)
                if (mediaId != null) {
                    adapter.updatedCached(mediaId, cached)
                }
            }
                PLAYER_NOT_READY -> {
                    Toast.makeText(context,getString(R.string.player_not_ready),
                            Toast.LENGTH_LONG).show()
                }
            }
        }

    }

    override fun scrollToNowPlaying() {
        if (adapter.nowPlaying >= 0)
            folderView.scrollToPosition(adapter.nowPlaying)
    }

    private val subscribeCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>, options: Bundle) {
            onChildrenLoaded(parentId, children)
        }

        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>) {
            Log.d(LOG_TAG, "Received folder listing ${children.size} items")
            super.onChildrenLoaded(parentId, children)
            var empty = false
            var folderDetail: Bundle? = null
            if (children.size==0) {
                Toast.makeText(this@FolderFragment.context, R.string.empty_folder, Toast.LENGTH_LONG).show()
                empty = true
            } else {
                folderDetail = children[0].description.extras?.getBundle(METADATA_KEY_FOLDER_DETAILS)
            }
            this@FolderFragment.adapter.changeData(children)
            scrollToNowPlaying()
            doneLoading(folderDetail, false, empty)
        }

        override fun onError(parentId: String, options: Bundle) {
            onError(parentId)
        }

        override fun onError(parentId: String) {
            super.onError(parentId)
            Log.e(LOG_TAG, "Error loading folder ${parentId}")
            Toast.makeText(this@FolderFragment.context, R.string.media_browser_error, Toast.LENGTH_LONG).show()
            doneLoading(null, true, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            folderId = it.getString(ARG_FOLDER_PATH)
            folderName = it.getString(ARG_FOLDER_NAME)
            willPrepare = it.getBoolean(ARG_PREPARE)
            it.remove(ARG_PREPARE)
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_folder, container, false)
        folderView = view.findViewById(R.id.folderView)
        folderView.layoutManager = LinearLayoutManager(context)
        val isSearch = folderId.startsWith(AudioService.SEARCH_PREFIX) || folderId.startsWith(AudioService.RECENTLY_MODIFIED_PREFIX)
        adapter = FolderAdapter(context!!, isSearch) {item, action, currentlyPlaying ->
            mediaActivity?.onItemClicked(item, action, currentlyPlaying)
        }
        folderView.adapter = adapter

        if (context is MediaActivity && context is TopActivity) {
            mediaActivity = context as MediaActivity
            (context as TopActivity).setFolderTitle(folderName)
        } else {
            throw RuntimeException(context.toString() + " must implement MediaActivity, TopActivity")
        }

        loadingProgress = view.findViewById(R.id.progressBar)
        return view
    }




    override fun onDestroyView() {
        Log.d(LOG_TAG, "OnDestroyView")
        super.onDestroyView()
        mediaActivity = null
        // save folderView scrolling state for immediate back
        folderViewState = getFolderViewState()
    }

    private val showProgress = object: Runnable {
        override fun run() {
            loadingProgress.visibility = View.VISIBLE
        }

    }

    private fun startLoading(forceReload:Boolean=false) {
        val options = Bundle()
        if (willPrepare) {
            willPrepare = false
            options.putBoolean(AUDIOSERVICE_DONT_PRELOAD_LATEST, true)
        }
        if (forceReload) {
            options.putBoolean(AUDIOSERVICE_FORCE_RELOAD, true)
        }
        mediaActivity?.mediaBrowser?.subscribe(folderId, options, subscribeCallback)
        loadingProgress.visibility = View.INVISIBLE
        // do not show loading immediatelly for cached and quick responces
        handler.postDelayed(showProgress, 500)
        folderView.visibility = View.VISIBLE
    }

    private fun doneLoading(folderDetails: Bundle?, error: Boolean = false, empty: Boolean = false) {
        handler.removeCallbacks(showProgress)
        loadingProgress.visibility = View.INVISIBLE
        folderView.visibility = View.VISIBLE

        if (!error && folderViewState!= null) {
            folderView.getLayoutManager()?.onRestoreInstanceState(folderViewState)
            folderViewState = null
        }



        mediaActivity?.onFolderLoaded(folderId, folderDetails, error, empty)
    }

    private var folderViewState: Parcelable? = null

    override fun onSaveInstanceState(outState: Bundle) {
        Log.d(LOG_TAG, "onSaveInstanceState")
        super.onSaveInstanceState(outState)
        //fragment is going to be destroyed - save state
        Log.d(LOG_TAG, "Have folderViewState")
        outState.putParcelable(FOLDER_VIEW_STATE_KEY, getFolderViewState())

    }

    private fun getFolderViewState(): Parcelable? =
        folderView.getLayoutManager()?.onSaveInstanceState()


    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // This is not just return from backstack - we're recreating instance so should restore state
        if (folderViewState == null) {
            folderViewState = savedInstanceState?.getParcelable(FOLDER_VIEW_STATE_KEY)
        }
    }


    private var listenersConnected = false
    override fun onMediaServiceConnected() {
        super.onMediaServiceConnected()
        Log.d(LOG_TAG, "onMediaServiceConnect ${mediaActivity?.mediaBrowser}")
        if (mediaActivity?.mediaBrowser != null && ! listenersConnected) {
            startLoading()
            listenersConnected = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (listenersConnected) {
            mediaActivity?.mediaBrowser?.unsubscribe(folderId, subscribeCallback)
            listenersConnected = false
        }
    }


    override fun reload() {
        mediaActivity?.mediaBrowser?.unsubscribe(folderId, subscribeCallback)
        startLoading(forceReload = true)
    }


    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         */
        @JvmStatic
        fun newInstance(folderPath: String, folderName: String, preparePlay:Boolean = false) =
                FolderFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_FOLDER_NAME, folderName)
                        putString(ARG_FOLDER_PATH, folderPath)
                        if (preparePlay) {
                            putBoolean(ARG_PREPARE, true)
                        }

                    }
                }
    }
}
