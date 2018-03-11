package eu.zderadicka.audioserve

import android.support.v4.app.Fragment
import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import eu.zderadicka.audioserve.net.ApiClient


// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
const val ARG_FOLDER_PATH = "folder-path"
const val ARG_FOLDER_NAME = "folder-name"
const val ARG_COLLECTION_INDEX = "collections-index"
private const val LOG_TAG = "FolderFragment"

//TODO icon for item type - folder or audio file
// TODO hightlight and icon for currently played icon
// TODO show also :  duration and bitrate and transcoding
class FolderItemViewHolder(itemView: View, val viewType: Int, val clickCB: (Int) -> Unit) : RecyclerView.ViewHolder(itemView) {

    var itemName: TextView = itemView.findViewById(R.id.folderItemName)

    init {
        itemView.setOnClickListener { clickCB(adapterPosition) }
    }
}

class FolderAdapter(val context: Context,
                    private val itemCb: (MediaItem) -> Unit)
    : RecyclerView.Adapter<FolderItemViewHolder>() {

    private var items: List<MediaItem>? = null
    internal var nowPlaying: Int = -1
    private var pendingMediaId: String? = null  // if we got now playing metadata, but list is not loaded yet
    private val idMap: HashMap<String,Int> = HashMap()

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): FolderItemViewHolder {
        val inflater = LayoutInflater.from(parent?.context)
        val view = inflater.inflate(R.layout.folder_item, parent, false)
        return FolderItemViewHolder(view, viewType, this::onItemClicked)
    }

    private fun onItemClicked(index: Int) {

        val item = items?.get(index)
        if (item != null) {
            itemCb(item)
        }

    }

    override fun getItemCount(): Int {
        return items?.size ?: 0
    }

    override fun onBindViewHolder(holder: FolderItemViewHolder?, position: Int) {
        val item = items?.get(position)
        if (item == null) return
        holder!!.itemName.text = item.description.title
        if (position == nowPlaying) {
            holder.itemView.setBackgroundColor(context.resources.getColor(R.color.colorAccent))
        } else {
            holder.itemView.setBackgroundColor(context.resources.getColor(R.color.background_material_light))
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

    fun updateNowPlaying(mediaId: String): Int {

        val idx = idMap.get(mediaId)
        nowPlaying = if (idx == null) -1 else idx
        if (nowPlaying >= 0 ) {
            notifyDataSetChanged()
            pendingMediaId = null
        } else {
            pendingMediaId = mediaId
        }
        return nowPlaying
    }
}


interface OnFolderItemClicked {
    fun onItemClicked(item: MediaItem)
    val mediaBrowser: MediaBrowserCompat
}



class FolderFragment : MediaFragment() {
    private lateinit var folderId: String
    private lateinit var folderName: String
    private var collIndex: Int = 0
    private var listener: OnFolderItemClicked? = null
    private lateinit var adapter: FolderAdapter

    private lateinit var folderView: RecyclerView
// TODO highlight currently played item - save latest meta and on playing find item and highlight
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

    }

    private fun scrollToNowPlaying() {
        if (adapter.nowPlaying >= 0)
            folderView.scrollToPosition(adapter.nowPlaying)
    }

    private val subscribeCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>) {
            Log.d(LOG_TAG, "Received folder listing ${children.size} items")
            super.onChildrenLoaded(parentId, children)
            if (children.size==0) {
                Toast.makeText(this@FolderFragment.context,R.string.empty_folder, Toast.LENGTH_LONG)
            }
            this@FolderFragment.adapter.changeData(children)
            scrollToNowPlaying()
        }

        override fun onError(parentId: String) {
            super.onError(parentId)
            Log.e(LOG_TAG, "Error loading folder ${parentId}")
            Toast.makeText(this@FolderFragment.context,R.string.media_browser_error, Toast.LENGTH_LONG)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            folderId = it.getString(ARG_FOLDER_PATH)
            folderName = it.getString(ARG_FOLDER_NAME)
            collIndex = it.getInt(ARG_COLLECTION_INDEX)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_folder, container, false)
        folderView = view.findViewById(R.id.folderView)
        folderView.layoutManager = LinearLayoutManager(context)

        adapter = FolderAdapter(context!!) {
            listener?.onItemClicked(it)
        }
        folderView.adapter = adapter

//        ApiClient.getInstance(context!!).loadFolder(folderId) {
//           it?.mediaItems?.let {adapter.changeData(it)}
//        }

        // Inflate the layout for this fragment
        return view
    }


    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnFolderItemClicked) {
            listener = context
        } else {
            throw RuntimeException(context.toString() + " must implement OnFolderItemClicked")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }


    private var listenersConnected = false
    override fun onMediaServiceConnected() {
        super.onMediaServiceConnected()
        Log.d(LOG_TAG, "onMediaServiceConnect ${listener?.mediaBrowser}")
        if (listener?.mediaBrowser != null && ! listenersConnected) {
            listener?.mediaBrowser?.subscribe(folderId, subscribeCallback)
            listenersConnected = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (listenersConnected) {
            listener?.mediaBrowser?.unsubscribe(folderId, subscribeCallback)
            listenersConnected = false
        }
    }


    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         */
        @JvmStatic
        fun newInstance(folderPath: String, folderName: String, collectionsIndex: Int = 0) =
                FolderFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_FOLDER_NAME, folderName)
                        putString(ARG_FOLDER_PATH, folderPath)
                        putInt(ARG_COLLECTION_INDEX, 0)
                    }
                }
    }
}
