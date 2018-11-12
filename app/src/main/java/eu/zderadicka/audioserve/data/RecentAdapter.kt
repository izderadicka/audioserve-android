package eu.zderadicka.audioserve.data

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.util.Log
import java.io.File

private const val LOG_TAG = "RecentAdapter"


private fun MediaItem.updateMediaItemTime(updateTime:Long, positionTime: Long){
    description.extras?.apply {
        putLong(METADATA_KEY_LAST_POSITION, positionTime)
        putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP, updateTime)
    }
}

class RecentAdapter(private val ctx: Context) {

    private var currentItem: MediaItem? = null
    private var lastUpdateTime: Long? = null
    private var itemPosition: Long? = null


    fun updateRecent(item: MediaItem, position: Long) {
        Log.d(LOG_TAG, "Updating ")
        if (position == 0L) Log.d(LOG_TAG,"Updating ${item.mediaId} with zero position!")
        else  Log.d(LOG_TAG, "Updating ${item.mediaId} to position $position")
        currentItem?.let {
            if (it.mediaId != item.mediaId) {
                val oldItem = it  //TODO: maybe should rather clone?
                oldItem.updateMediaItemTime(lastUpdateTime!!, itemPosition!!)
                //TODO: should be async
                saveRecent(oldItem, ctx)
            }
        }

        currentItem = item
        lastUpdateTime = System.currentTimeMillis()
        itemPosition = position
    }

    fun reset() {
        currentItem = null
        lastUpdateTime = null
        itemPosition = null
    }

    fun flush() =
        currentItem?.let{
            it.updateMediaItemTime(lastUpdateTime!!, itemPosition!!)
            saveRecent(it, ctx)
        }


    fun list(cb: (ArrayList<MediaItem>) -> Unit) {

        Log.d(LOG_TAG, "Requesting list of recently listened items")

        val list = ArrayList<MediaItem>()
        currentItem?.apply {
            description.extras?.putBoolean(METADATA_KEY_IS_BOOKMARK, true)
            updateMediaItemTime(lastUpdateTime!!, itemPosition!!)
            list.add(this)
        }
        //TODO:: use own Looper + thread for all async tasks
        Thread {
            val path =
                    currentItem?.run({
                        File(mediaId).parent
                    })
            list.addAll(getRecents(ctx,path ))
            cb(list)
        }.start()


    }

    fun lastForFolder(parentId: String, cb: (MediaItem)-> Unit) {

        val lastItems = getRecents(ctx, onlyLatest = true)
        if (lastItems.isNotEmpty()) {
            val lastItem = lastItems[0]
            val lastFolderId = folderIdFromFileId(lastItem.mediaId!!)
            if (lastFolderId == parentId) cb(lastItem) //TODO: Should be async

        }
    }


}