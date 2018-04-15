package eu.zderadicka.audioserve.net
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import eu.zderadicka.audioserve.data.ITEM_TYPE_FOLDER
import eu.zderadicka.audioserve.data.Subfolder
import java.io.File

private fun subfolderToItem(path:String): MediaBrowserCompat.MediaItem {
    val desc = MediaDescriptionCompat.Builder()
            .setMediaId(path)
            .setTitle(File(path).name)
            .setSubtitle(File(path).parent)
            .build()
    return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
}

class CacheBrowser(val ids: Set<String>, val cacheDir:File) {
    val rootFolder :List<MediaBrowserCompat.MediaItem> by lazy() {
        listRootFolder()
    }

    private fun listRootFolder():List<MediaBrowserCompat.MediaItem>  {
        val rootFolders = HashSet<String>()
        for (id in ids) {
            val segments = id.split("/")
            val name_idx = if (segments[0] == "audio") 1 else if (segments[1] == "audio")  2 else continue
            rootFolders.add(segments.take(name_idx+1).joinToString("/"))
        }

        return rootFolders.toList().sorted().map{ subfolderToItem(it)}



    }

    fun listFolder(folderId: String) {

    }
}