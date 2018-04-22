package eu.zderadicka.audioserve.data

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import eu.zderadicka.audioserve.AudioService
import eu.zderadicka.audioserve.net.TranscodingLimits
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStream

fun readAsString(stream: InputStream): String {
    val sb=StringBuilder()
    val reader = stream.bufferedReader()

    while(true) {
        var line = reader.readLine()
        if (line == null) break
        sb.append(line)
    }

    return sb.toString()
}

fun readCollectionsFromJson(stream: InputStream): ArrayList<String> {
    val data = readAsString(stream)
    return parseCollectionsFromJson(data)
}

fun parseCollectionsFromJson(data: String): ArrayList<String> {
    val json = JSONObject(data);

    val count = json.getInt("count")
    val names = json.getJSONArray("names")
    assert(names.length() == count)
    val out = ArrayList<String>()
    for (i in 0 until names.length()) {
        out.add(names.getString(i))
    }
    return out
}

fun parseTranscodingsFromJson(data: String): TranscodingLimits {
    val json = JSONObject(data)
    val low = json.getJSONObject("low").getInt("bitrate")
    val medium = json.getJSONObject("medium").getInt("bitrate")
    val high = json.getJSONObject("high").getInt("bitrate")

    return TranscodingLimits(low, medium, high)
}




fun readFolderFromJson(stream: InputStream, name: String, path: String): AudioFolder {
    val data = readAsString(stream)
    return parseFolderfromJson(data, name, path)
}

fun parseFolderfromJson(data: String, name: String, path: String) :AudioFolder{
    val json = JSONObject(data);
    val fs = json.getJSONArray("files")
    val files = if (fs != null && fs.length()> 0) {
        val t = ArrayList<AudioFile>()
        for (i in 0 until fs.length()) {
            val o = fs.getJSONObject(i)
            val name = o.getString("name")!!
            val path = o.getString("path")!!
            val mime = o.getString("mime")!!

            val metaObject = o.getJSONObject("meta")
            val meta = if (metaObject != null) {
                val duration = metaObject.getInt("duration")
                val bitrate = metaObject.getInt("bitrate")
                MediaMeta(duration,bitrate)
            } else {
                null
            }
            t.add(AudioFile(name,path,meta,mime))
        }
        t
    } else {
        null
    }

    val subs = json.getJSONArray("subfolders")
    val subfolders = if (subs != null && subs.length()> 0) {
        val t = ArrayList<Subfolder>()
        for (i in 0 until subs.length()) {
            val o = subs.getJSONObject(i)
            val name = o.getString("name")!!
            val path = o.getString("path")!!
            t.add(Subfolder(name, path))
        }
        t
    } else {
        null
    }


    fun getTypedPath(key:String): TypedPath? {
        val o: JSONObject?
        try {
            o = json.getJSONObject(key)
        } catch (e: JSONException) {
            return null
        }
        return if (o != null) {
            val path = o.getString("path")!!
            val mime = o.getString("mime")!!
            TypedPath(path, mime)
        } else {
            null
        }
    }

    val cover = getTypedPath("cover")
    val description = getTypedPath("description")

    val details = Bundle()
    details.putInt(METADATA_KEY_FOLDERS_COUNT, subfolders?.size?:0)
    details.putInt(METADATA_KEY_FILES_COUNT, files?.size?:0)
    details.putLong(METADATA_KEY_TOTAL_DURATION, files?.map{it?.meta?.duration?.toLong()?:0L}?.sum()?:0L)
    if (cover!=null )
        details.putString(METADATA_KEY_FOLDER_PICTURE_PATH, cover.path)
    if (description!= null)
        details.putString(METADATA_KEY_FOLDER_TEXT_URL, description.path)

    return AudioFolder(name,path,subfolders,files,details)
}

private val AUDIO_START_RE = Regex("""^(\d+)?/?audio/(.+)""")
private val FOLDER_START_RE = Regex("""^(\d+)?/?folder/(.+)""")

fun folderIdFromFileId(fileId: String): String {
    val re = AUDIO_START_RE.matchEntire(fileId)
    if (re != null) {
        val collectionNo = re.groups.get(1)?.value
        val prefix = if (collectionNo != null && collectionNo.length>0) {
            "$collectionNo/"
        } else ""
        val path = File(re.groups.get(2)?.value).parent
        return "${prefix}folder/$path"
    } else {
        throw IllegalArgumentException("Agrument is not audio file mediaId")
    }
}

fun pathFromFolderId(folderId:String): String {
    if (folderId.startsWith(AudioService.COLLECTION_PREFIX)) return ""
    val m = FOLDER_START_RE.matchEntire(folderId)
    if (m != null) {
        val p = m.groups.get(2)?.value
        if (p!= null) {
            return File(p).parent?:""
        }
    }
    return ""
}

fun collectionFromFolderId(folderId:String): Int? {
    if (folderId.startsWith(AudioService.COLLECTION_PREFIX)) {
        return folderId.substring(AudioService.COLLECTION_PREFIX.length).toInt()
    }
    val m = FOLDER_START_RE.matchEntire(folderId)
    if (m == null) return null
    val n = m.groups.get(1)?.value
    if (n == null || n.length == 0 ) return 0
    return n.toInt()
}

fun duplicateMediaItemWithExtrasAssured(item: MediaBrowserCompat.MediaItem):MediaBrowserCompat.MediaItem {
    val desc = MediaDescriptionCompat.Builder()
            .setMediaId(item.description.mediaId)
            .setTitle(item.description.title)
            .setSubtitle(item.description.subtitle)
            .setExtras(item.description.extras?: Bundle())
            .build()
    return MediaBrowserCompat.MediaItem(desc, if (item.isBrowsable) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        else if (item.isPlayable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else 0)

}