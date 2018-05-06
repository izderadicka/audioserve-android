package eu.zderadicka.audioserve.net
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.text.format.DateUtils
import android.util.Log
import android.util.LruCache
import eu.zderadicka.audioserve.data.*
import eu.zderadicka.audioserve.utils.splitExtension
import java.io.File


private const val SEP = "/"
private const val LOG_TAG = "CacheBrowser"

private fun subfolderToItem(path:String): MediaBrowserCompat.MediaItem {
    val desc = MediaDescriptionCompat.Builder()
            .setMediaId(path)
            .setTitle(File(path).name)
            .setSubtitle(File(path).parent)
            .build()
    return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
}

private fun fileToItem(mediaId: String, cacheDir: File): MediaBrowserCompat.MediaItem? {
    val fullFile = File(cacheDir,mediaId)
    if (! fullFile.isFile()) return null

    val extras = Bundle()
    val f = File(mediaId)
    val (name, ext) = splitExtension(f.name)
    val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(name)
            .setSubtitle(f.parent)
    if (ext != null) {
        extras.putString(METADATA_KEY_EXTENSION, ext)
    }
    val metaExtractor = MediaMetadataRetriever()
    metaExtractor.setDataSource(fullFile.path)
    try {
        val duration = metaExtractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        extras.putLong(METADATA_KEY_DURATION, duration!!) // in miliseconds
    } catch (e: NumberFormatException) {
        Log.e(LOG_TAG, "Wrong duration value")
    }
    catch (e: NullPointerException) {
        Log.e(LOG_TAG, "Cannot retrieve duration value")
    }

    try {
        val bitrate = metaExtractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toInt()
        val kbBitrate = bitrate!! / 1024
        extras.putInt(METADATA_KEY_BITRATE, kbBitrate)
    } catch (e: NumberFormatException) {
        Log.e(LOG_TAG, "Wrong bitrate value")
    }
    catch (e: NullPointerException) {
        Log.e(LOG_TAG, "Cannot retrieve bitrate value")
    }
    metaExtractor.release()




    extras.putBoolean(METADATA_KEY_CACHED, true)
    descBuilder.setExtras(extras)
    val item = MediaBrowserCompat.MediaItem(descBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)

    return item
}

private const val MAX_CACHE_LIVE = 24 * DateUtils.HOUR_IN_MILLIS
class CacheBrowser(val ids: List<String>, val cacheDir:File) {
    private val cache = LruCache<String, CacheItem>(16)

    val rootFolder :List<MediaBrowserCompat.MediaItem> by lazy() {
        listRootFolder()
    }

    private fun listRootFolder():List<MediaBrowserCompat.MediaItem>  {
        val rootFolders = HashSet<String>()
        for (id in ids) {
            val segments = id.split(SEP)
            val nameIdx = if (segments[0] == "audio") 1 else if (segments[1] == "audio")  2 else continue
            rootFolders.add(segments.take(nameIdx+1).joinToString(SEP))
        }

        return rootFolders.toList().sorted().map{ subfolderToItem(it)}



    }
    val FOLDER_RE = Regex("""^(\d+/)?folder/""")
    fun listFolder(folderId: String, noCache:Boolean = false):List<MediaBrowserCompat.MediaItem> {
        val folderMatch = FOLDER_RE.find(folderId)
        @Suppress("NAME_SHADOWING")
        val folderId = if (folderMatch!= null) {
            val c = folderMatch.groups.get(1)?.value?:""
            FOLDER_RE.replace(folderId, "${c}audio/")
        } else {
            folderId
        }

        if (!noCache) {
            val cachedEntry = cache.get(folderId)
            if (cachedEntry != null && System.currentTimeMillis() - cachedEntry.timestamp < MAX_CACHE_LIVE) {
                return cachedEntry.data
            }
        }

        val folders = HashSet<String>()
        val files = ArrayList<String>()
        val items = ArrayList<MediaBrowserCompat.MediaItem>()
        val folderSegmentsNo = folderId.split(SEP).size
        for (id in ids) {
            if (id.startsWith(folderId) && id != folderId) {
                val segments = id.split(SEP)
                if (segments.size == folderSegmentsNo+1) {
                    //In this folder, should be file
                    files.add(id)
                } else {
                    //subfolder
                    folders.add(segments.take(folderSegmentsNo+1).joinToString(SEP))
                }
            }
        }

        items.addAll(folders.toList().sorted().map{subfolderToItem(it)})
        files.sort()
        for (f in files) {
            val item = fileToItem(f, cacheDir)
            if (item == null) continue
            items.add(item)
        }
        cache.put(folderId, CacheItem(System.currentTimeMillis(), items))
        return items
    }

    fun clearCache() {
        cache.evictAll()
    }
    private data class  CacheItem(val timestamp: Long, val data: List<MediaBrowserCompat.MediaItem>)
}

