package eu.zderadicka.audioserve.data

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import eu.zderadicka.audioserve.net.ApiClient
import java.io.File
import kotlin.math.absoluteValue

private const val LOG_TAG = "RecentAdapter"
private const val MIN_TIME_DIFFERENCE_FOR_POSITION_SHARING = 20_000L
private const val IN_PAST_OFFSET = 60_000L


private fun MediaItem.updateMediaItemTime(updateTime: Long, positionTime: Long) {
    description.extras?.apply {
        putLong(METADATA_KEY_LAST_POSITION, positionTime)
        putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP, updateTime)
    }
}

class RecentAdapter(private val ctx: Context) {

    private var currentItem: MediaItem? = null
    private var lastUpdateTime: Long? = null
    private var itemPosition: Long? = null


    fun updateRecent(item: MediaItem?, position: Long) {
        if (item == null) return
        Log.d(LOG_TAG, "Updating ")
        if (position == 0L) Log.d(LOG_TAG, "Updating ${item.mediaId} with zero position!")
        else Log.d(LOG_TAG, "Updating ${item.mediaId} to position $position")
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
            currentItem?.let {
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
            list.addAll(getRecents(ctx, path))
            ApiClient.getInstance(ctx).queryPosition(null) { res, err ->
                if (res != null && res.last != null) {
                    val remoteItem = positionToMediaItem(res.last)
                    val addRemote = list.firstOrNull()?.let {
                        it.mediaId != remoteItem.mediaId ||
                                (it.description.extras?.getLong(METADATA_KEY_DURATION) ?: 0
                                - (remoteItem.description.extras?.getLong(METADATA_KEY_DURATION)
                                        ?: 0L))
                                        .absoluteValue > MIN_TIME_DIFFERENCE_FOR_POSITION_SHARING

                    } ?: true
                    if (addRemote) list.add(0, remoteItem)
                }
                cb(list)
            }

        }.start()


    }

    private fun positionToMediaItem(pos: RemotePosition): MediaItem {
        val (folder, collection) = splitPositionFolder(pos.folder)
        val name = normTitle(File(pos.file).nameWithoutExtension)
        var mediaId = "audio/$folder/${pos.file}"
        if (collection > 0) mediaId = "$collection/$mediaId"
        val extras = Bundle()
        val descBuilder = MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(name)
                .setSubtitle(folder)

        //extras.putLong(METADATA_KEY_DURATION, c.getLong(c.getColumnIndex(RecentEntry.COLUMN_DURATION)))
        extras.putLong(METADATA_KEY_LAST_POSITION, (pos.position * 1000).toLong())
        extras.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP, pos.timestamp)
        extras.putBoolean(METADATA_KEY_IS_BOOKMARK, true)
        extras.putBoolean(METADATA_KEY_IS_REMOTE_POSITION, true)

        descBuilder.setExtras(extras)
        val item = MediaItem(descBuilder.build(), MediaItem.FLAG_PLAYABLE)
        return item
    }

    fun lastForFolder(parentId: String, cb: (MediaItem) -> Unit) {

        val lastItems = getRecents(ctx, onlyLatest = true)
        if (lastItems.isNotEmpty()) {
            val lastItem = lastItems[0]
            val lastFolderId = folderIdFromFileId(lastItem.mediaId!!)
            if (lastFolderId == parentId) cb(lastItem) //TODO: Should be async

        }
    }


}