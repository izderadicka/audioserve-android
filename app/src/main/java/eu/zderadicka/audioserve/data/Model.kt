package eu.zderadicka.audioserve.data

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import java.io.File

const val METADATA_KEY_DURATION = MediaMetadataCompat.METADATA_KEY_DURATION
const val METADATA_KEY_BITRATE = "eu.zderadicka.audioserve.bitrate"

const val ITEM_TYPE_FOLDER = "folder"
const val ITEM_TYPE_AUDIOFILE = "audio"

open class Entry(val name: String, val path: String);
data class MediaMeta(val duration: Int, val bitrate: Int)
data class TypedPath(val path:String, val mime:String)

class Subfolder(name: String, path: String): Entry(name,path)
class AudioFile(name: String,  path:String, val meta: MediaMeta?,
                val mime: String, val transcoded: Boolean): Entry(name, path)

class AudioFolder(name: String, path: String, val subfolders: ArrayList<Subfolder>?, val files: ArrayList<AudioFile>?,
             val cover: TypedPath?, val info: TypedPath?): Entry(name,path) {
    var collectionIndex: Int = 0


    init {
        val col = Regex("^(\\d+)/").find(path)
        if (col != null) {
            collectionIndex = col.groups.get(1)?.value?.toInt()?:0
        }

    }
    val numFolders:Int
    get() = this.subfolders?.size?:0

    val numFiles: Int
    get() = this.files?.size?:0

    val mediaItems: ArrayList<MediaItem>
    get() {
        fun prefixPath(p:String, type: String): String {
            if (collectionIndex > 0) {
                return "$collectionIndex/$type/$p"
            } else {
                return "$type/$p"
            }
        }
        val data: ArrayList<MediaItem> = ArrayList()

        if (this.subfolders != null && this.subfolders.size>0) {
            for (s in this.subfolders) {
                val desc = MediaDescriptionCompat.Builder()
                        .setMediaId(prefixPath(s.path, ITEM_TYPE_FOLDER))
                        .setTitle(s.name)
                        .setSubtitle(File(s.path).parent)
                        .build()
                data.add(MediaItem(desc, MediaItem.FLAG_BROWSABLE))
            }
        }

        if (this.files != null && this.files.size > 0) {
            for (f in this.files) {

                val descBuilder = MediaDescriptionCompat.Builder()
                        .setMediaId(prefixPath(f.path, ITEM_TYPE_AUDIOFILE))
                        .setTitle(f.name)
                        .setSubtitle(File(f.path).parent)
                if (f.meta != null) {
                    val extras = Bundle()
                    extras.putLong(METADATA_KEY_DURATION, f.meta.duration.toLong() * 1000) // in miliseconds
                    extras.putInt(METADATA_KEY_BITRATE, f.meta.bitrate)
                    descBuilder.setExtras(extras)
                }
                data.add(MediaItem(descBuilder.build(), MediaItem.FLAG_PLAYABLE))
            }
        }
        return data
    }
}

