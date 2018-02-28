package eu.zderadicka.audioserve.data

import org.json.JSONException
import org.json.JSONObject
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
            val transcoded = o.getBoolean("trans")
            val metaObject = o.getJSONObject("meta")
            val meta = if (metaObject != null) {
                val duration = metaObject.getInt("duration")
                val bitrate = metaObject.getInt("bitrate")
                MediaMeta(duration,bitrate)
            } else {
                null
            }
            t.add(AudioFile(name,path,meta,mime,transcoded))
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
        var o: JSONObject? = null
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

    return AudioFolder(name,path,subfolders,files,cover,description)
}