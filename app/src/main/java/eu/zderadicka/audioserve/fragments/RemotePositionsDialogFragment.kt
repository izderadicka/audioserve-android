package eu.zderadicka.audioserve.fragments

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v4.media.MediaBrowserCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.METADATA_KEY_LAST_LISTENED_TIMESTAMP
import eu.zderadicka.audioserve.data.METADATA_KEY_LAST_POSITION
import java.lang.IllegalStateException

const val REMOTE_POSITIONS_LIST = "eu.zderadicka.audioserve.remote_positions_list"

private const val LOG_TAG ="Remote Positions Dialog"

class PositionViewHolder(itemView: View, viewType: Int, val clickCB: (Int) -> Unit) : RecyclerView.ViewHolder(itemView) {
    val itemName: TextView = itemView.findViewById(R.id.folderItemName)
    val positionView: TextView = itemView.findViewById(R.id.positionView)
    val lastListenedView: TextView = itemView.findViewById(R.id.bookmarkedAtView)
    val folderPathView: TextView = itemView.findViewById(R.id.folderPathView)

    init {
        itemView.findViewById<View>(R.id.positionSeparatorView).visibility = View.GONE
        itemView.findViewById<View>(R.id.durationView).visibility = View.GONE
        itemView.setOnClickListener {
            clickCB(adapterPosition)
        }
    }
}

class PositionsAdapter(val context: Context, private val items: List<MediaBrowserCompat.MediaItem>,
                       private val itemCb: (MediaBrowserCompat.MediaItem) -> Unit)
    : RecyclerView.Adapter<PositionViewHolder>() {


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PositionViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        val view = inflater.inflate(R.layout.bookmark_item_inner, parent, false)
        return PositionViewHolder(view, viewType) {
            index -> itemCb(items[index])

        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PositionViewHolder, position: Int) {

        val item = items[position]
        holder.itemName.text = item.description.title
        holder.folderPathView.text = item.description.subtitle
        var pos = DateUtils.formatElapsedTime((
                item.description.extras?.getLong(METADATA_KEY_LAST_POSITION)?: 0) / 1000L)
        pos = context.getString(R.string.remote_pos, pos)
        holder.positionView.text = pos
        holder.lastListenedView.text = DateUtils.getRelativeTimeSpanString(
                item.description.extras?.getLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP)?:0,
                System.currentTimeMillis(),
                0
        )
    }

}

class RemotePositionsDialogFragment(): DialogFragment() {
    lateinit var items: List<MediaBrowserCompat.MediaItem>
    private var listener: Listener? = null

    fun setListener(l:Listener) {
        listener = l
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.apply {
            items = getParcelableArrayList<MediaBrowserCompat.MediaItem>(REMOTE_POSITIONS_LIST)!!
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {

            fun instructionsVisible(view:View, visible:Boolean) {
                view.findViewById<View>(R.id.instructions_ok).visibility = if (visible) View.VISIBLE else View.GONE
                view.findViewById<View>(R.id.instructions_empty).visibility = if (visible) View.GONE else View.VISIBLE
            }

            val inflater = requireActivity().layoutInflater
            val view = inflater.inflate(R.layout.fragment_remote_positions, null)
            if (items.isNullOrEmpty()) {
                instructionsVisible(view, false)
            } else {
                instructionsVisible(view, true)
                val adapter = PositionsAdapter(it, items) { item ->
                    Log.d(LOG_TAG, "Chosen remote position $item")
                    dismiss()
                    listener?.onItemChosen(item)

                }
                val positionsList = view.findViewById<RecyclerView>(R.id.positionsList)
                positionsList.layoutManager = LinearLayoutManager(it)
                positionsList.adapter = adapter
            }
            val builder = AlertDialog.Builder(it)
            builder
                    .setTitle(getString(R.string.other_playback_positions))
                    .setView(view)
                    .setNegativeButton(getString(R.string.continue_with_current)) {
                _, _ ->
                        listener?.onContinueWithCurrent()
            }

            builder.create()
        }?: throw IllegalStateException("Activity cannot be null for dialog")
    }

    interface Listener {
        fun onItemChosen(item:MediaBrowserCompat.MediaItem)
        fun onContinueWithCurrent()
    }
}