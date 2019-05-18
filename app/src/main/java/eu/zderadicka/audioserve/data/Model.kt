package eu.zderadicka.audioserve.data

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import eu.zderadicka.audioserve.net.CacheManager
import eu.zderadicka.audioserve.utils.splitExtension
import java.io.File

const val METADATA_KEY_DURATION = MediaMetadataCompat.METADATA_KEY_DURATION
const val METADATA_KEY_TITLE = MediaMetadataCompat.METADATA_KEY_TITLE
const val METADATA_KEY_ARTIST = MediaMetadataCompat.METADATA_KEY_ARTIST
const val METADATA_KEY_LAST_POSITION = "eu.zderadicka.audioserve.last_position"
const val METADATA_PLAY_AFTER_SEEK = "eu.zderadicka.audioserve.play_after_seek"
const val METADATA_KEY_BITRATE = "eu.zderadicka.audioserve.bitrate"
const val METADATA_KEY_TRANSCODED = "eu.zderadicka.audioserve.transcoded"
const val METADATA_KEY_CACHED = "eu.zderadicka.audioserve.cached"
const val METADATA_KEY_MEDIA_ID = "eu.zderadicka.audioserve.media_id"
const val METADATA_KEY_LAST_LISTENED_TIMESTAMP = "eu.zderadicka.audioserve.last_listened_ts"
const val METADATA_KEY_IS_BOOKMARK = "eu.zderadicka.audioserve.is_bookmark"
const val METADATA_KEY_IS_REMOTE_POSITION = "eu.zderadicka.audioserve.is_remote_position"
const val METADATA_KEY_IS_FOLDER = "eu.zderadicka.audioserve.is_folder"
const val METADATA_KEY_EXTENSION = "eu.zderadicka.audioserve.extension"
const val METADATA_KEY_TOTAL_DURATION = "eu.zderadicka.audioserve.total_duration"
const val METADATA_KEY_FILES_COUNT = "eu.zderadicka.audioserve.files_count"
const val METADATA_KEY_FOLDERS_COUNT = "eu.zderadicka.audioserve.folders_count"
const val METADATA_KEY_FOLDER_PICTURE_PATH = "eu.zderadicka.audioserve.folder_picture_url"
const val METADATA_KEY_FOLDER_TEXT_URL = "eu.zderadicka.audioserve.folder_text_url"
const val METADATA_KEY_FOLDER_DETAILS = "eu.zderadicka.audioserve.folder_details"

const val ITEM_TYPE_FOLDER = "folder"
const val ITEM_TYPE_AUDIOFILE = "audio"
const val PREFIX_COVER = "cover"
const val PREFIX_DESCRIPTION = "desc"

open class Entry(val name: String, val path: String);
data class MediaMeta(val duration: Int, val bitrate: Int)
data class TypedPath(val path:String, val mime:String)

class Subfolder(name: String, path: String): Entry(name,path)
class AudioFile(name: String,  path:String, val meta: MediaMeta?,
                val mime: String): Entry(name, path)

class AudioFolder(name: String, path: String, val subfolders: ArrayList<Subfolder>?,
                  val files: ArrayList<AudioFile>?,
                  val details: Bundle?): Entry(name,path) {
    var collectionIndex: Int = 0


    init {
        val col = Regex("^(\\d+)/").find(path)
        if (col != null) {
            collectionIndex = col.groups.get(1)?.value?.toInt()?:0
        }
        details?.putString(METADATA_KEY_FOLDER_TEXT_URL,
                details.getString(METADATA_KEY_FOLDER_TEXT_URL)?.let{prefixPath(it, PREFIX_DESCRIPTION)})

        details?.putString(METADATA_KEY_FOLDER_PICTURE_PATH,
                details.getString(METADATA_KEY_FOLDER_PICTURE_PATH)?.let {prefixPath(it, PREFIX_COVER)})

    }
    val numFolders:Int
    get() = this.subfolders?.size?:0

    val numFiles: Int
    get() = this.files?.size?:0

    fun getPlayableItems(cache: CacheManager?):List<MediaItem>
    {
        val data: ArrayList<MediaItem> = ArrayList()


        if (this.files != null && this.files.size > 0) {
            for (f in this.files) {
                data.add(fileToItem(f, cache))
            }
        }
        return data
    }

    private fun subfolderToItem(s: Subfolder): MediaItem {
        val desc = MediaDescriptionCompat.Builder()
                .setMediaId(prefixPath(s.path, ITEM_TYPE_FOLDER))
                .setTitle(s.name)
                .setSubtitle(File(s.path).parent)
                .build()
        return MediaItem(desc, MediaItem.FLAG_BROWSABLE)
    }

    private fun fileToItem(f: AudioFile, cache: CacheManager? = null): MediaItem {
        val extras = Bundle()
        val mediaId = prefixPath(f.path, ITEM_TYPE_AUDIOFILE)
        val (name, ext) = splitExtension(f.name)
        val album = File(f.path).parent
        val descBuilder = MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(name)
                .setSubtitle(album)
        if (ext != null) {
            extras.putString(METADATA_KEY_EXTENSION, ext)
        }
        if (f.meta != null) {
            extras.putLong(METADATA_KEY_DURATION, f.meta.duration.toLong() * 1000) // in miliseconds
            extras.putInt(METADATA_KEY_BITRATE, f.meta.bitrate)
        }
        if (cache != null && cache.isCached(mediaId)) {
            extras.putBoolean(METADATA_KEY_CACHED, true)
        }
        //this is needed for proper presentation to other MediaSession clients as Lock screen ...
        extras.putString(METADATA_KEY_TITLE, name)
        extras.putString(METADATA_KEY_ARTIST, album)

        descBuilder.setExtras(extras)
        val item =  MediaItem(descBuilder.build(), MediaItem.FLAG_PLAYABLE)
        if (cache?.shouldTranscode(item) != null) {
            item.description.extras?.putBoolean(METADATA_KEY_TRANSCODED, true)
        }
        return item
    }

    private fun prefixPath(p:String, type: String): String {
        if (collectionIndex > 0) {
            return "$collectionIndex/$type/$p"
        } else {
            return "$type/$p"
        }
    }

    fun getMediaItems(cache: CacheManager?): ArrayList<MediaItem>
    {

        val data: ArrayList<MediaItem> = ArrayList()


        if (this.subfolders != null && this.subfolders.size>0) {
            for (s in this.subfolders) {
                data.add(subfolderToItem(s))
            }
        }

        if (this.files != null && this.files.size > 0) {
            for (f in this.files) {
                data.add(fileToItem(f, cache))
            }
        }

        if (data.size>0 && details!= null) {

            val item = duplicateMediaItemWithExtrasAssured(data[0])
            item.description.extras?.putBundle(METADATA_KEY_FOLDER_DETAILS,details)
            data[0] = item

        }
        return data
    }
}

data class RemotePosition(val folder:String, val file:String, val timestamp: Long, val position:Double)
data class RemotePositionResponse(val folder: RemotePosition?, val last: RemotePosition?)


