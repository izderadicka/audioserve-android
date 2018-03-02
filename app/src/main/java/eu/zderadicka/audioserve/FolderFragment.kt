package eu.zderadicka.audioserve

import android.support.v4.app.Fragment
import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.session.MediaControllerCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import eu.zderadicka.audioserve.net.ApiClient


// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
const val ARG_FOLDER_PATH = "folder-path"
const val ARG_FOLDER_NAME = "folder-name"
const val ARG_COLLECTION_INDEX = "collections-index"
private const val LOG_TAG = "FolderFragment"

class FolderItemViewHolder(itemView: View, val viewType: Int, val clickCB: (Int) -> Unit) : RecyclerView.ViewHolder(itemView) {

    lateinit var itemName: TextView

    init {
        itemName = itemView.findViewById(R.id.folderItemName)
        itemView.setOnClickListener { clickCB(adapterPosition) }
    }
}

class FolderAdapter(val context: Context,
                    private val itemCb: (MediaItem) -> Unit)
    : RecyclerView.Adapter<FolderItemViewHolder>() {

    private var items: MutableList<MediaItem>? = null

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
    }

    fun changeData(newData: MutableList<MediaItem>) {
        items = newData
        notifyDataSetChanged()
    }
}


interface OnFolderItemClicked {
    fun onItemClicked(item: MediaItem)
    val mediaBrowser: MediaBrowserCompat
}


/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [FolderFragment.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [FolderFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 */
class FolderFragment : Fragment() {
    private lateinit var folderId: String
    private lateinit var folderName: String
    private var collIndex: Int = 0
    private var listener: OnFolderItemClicked? = null
    private lateinit var adapter: FolderAdapter

    private lateinit var folderView: RecyclerView

    private val subscribeCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>) {
            Log.d(LOG_TAG, "Received folder listing ${children.size} items")
            super.onChildrenLoaded(parentId, children)
            this@FolderFragment.adapter.changeData(children)
        }

        override fun onError(parentId: String) {
            super.onError(parentId)
            Log.e(LOG_TAG, "Error loading folder ${parentId}")
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

    override fun onStart() {
        super.onStart()
        onMediaServiceConnect()
    }

    private var listenersConnected = false
    fun onMediaServiceConnect() {
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
