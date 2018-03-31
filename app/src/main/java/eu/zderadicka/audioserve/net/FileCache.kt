package eu.zderadicka.audioserve.net

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingDeque
import kotlin.math.max

private const val LOG_TAG = "FileCache"
private const val MAX_CACHE_FILES = 1000
private const val MIN_FILE_SIZE: Long = 5 * 1024 * 1024 // If we do not know file size assume at least 5MB

class CacheIndex(val maxCacheSize: Long, private val changeListener: CacheItem.Listener, lru: Boolean = false) {
    var cacheSize: Long = 0
    val map = LinkedHashMap<String, CacheItem>(MAX_CACHE_FILES / 5, 0.75f, lru)

    val size: Int
        get() {
            return map.size
        }

    fun contains(path: String): Boolean {
        return map.containsKey(path)
    }

    fun get(path: String): CacheItem? {
        return map.get(path)
    }

    fun put(item: CacheItem) {
        synchronized(this) {
            if (map.containsKey(item.path)) return
            makeSpaceFor(item)
            map.put(item.path, item)
            cacheSize+= item.knownLength
        }

    }

    fun clear() = synchronized(this) {
        for (e in map) {
            e.value.destroy()
        }
        map.clear()
        cacheSize = 0
    }

    private fun makeSpaceFor(item: CacheItem) {
        val sz = if (item.totalLength > 0) {
            item.totalLength
        } else {
            max(item.cachedLength, MIN_FILE_SIZE)
        }
        makeSpace(sz)
    }

    private fun makeSpace(sz: Long) {

        //TODO consider if there is better way then to recaclculate - but since cache items can change asynchronoulsy
        cacheSize = map.values.map { it.knownLength }.sum()

        val toRemote = ArrayList<MutableMap.MutableEntry<String, CacheItem>>()
        var removedSize: Long = 0
        for (e in map.iterator()) {
            if (map.size - toRemote.size > MAX_CACHE_FILES || cacheSize + sz - removedSize > maxCacheSize) {
                if (e.value.unused) {
                    toRemote.add(e)
                    removedSize += e.value.knownLength
                }
            } else {
                break
            }
        }

        for (e in toRemote) {
            e.value.destroy()
            map.remove(e.key)
        }
        cacheSize-=removedSize

    }

    internal fun loadFromDir(cacheDir: File) {
        val pathPrefixLength = cacheDir.path!!.length
        val itemsToAdd = ArrayList<CacheItem>()
        fun load(currDir: File) {
            for (f in currDir.listFiles()) {
                if (f.isDirectory()) {
                    load(f)
                } else {
                    var path = f.absolutePath.substring(pathPrefixLength)
                    if (path.endsWith(TMP_FILE_SUFFIX)) {
                        path = path.substring(0, path.length - TMP_FILE_SUFFIX.length)
                    }
                    val item = CacheItem(path, cacheDir, changeListener)
                    itemsToAdd.add(item)


                }
            }
        }
        load(cacheDir)
        itemsToAdd.sortBy { it.lastUsed }
        for (i in itemsToAdd) map.put(i.path, i)
        // now assure that it consistent with limits
        makeSpace(0)

    }


}

class FileCache(val cacheDir: File, val maxCacheSize: Long, val baseUrl: String) : CacheItem.Listener {

    enum class Status {
        NotCached,
        PartiallyCached,
        FullyCached
    }

    private val index = CacheIndex(maxCacheSize, this, true)
    private val listeners = HashSet<Listener>()
    private val queue = ArrayBlockingQueue<CacheItem>(MAX_CACHE_FILES)

    init {
        index.loadFromDir(cacheDir)
    }

    val numberOfFiles: Int
    get() = index.size

    val cacheSize: Long
    get() = index.cacheSize

    fun checkCache(path: String): Status =
            if (!index.contains(path)) {
                Status.NotCached
            } else {
                itemStateConv(index.get(path)?.state)
            }

    fun addToCache(path: String) {
        val item = CacheItem(path, cacheDir, this)
        index.put(item)
        if (item.state == CacheItem.State.Complete) return
        try {
            queue.add(item)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Cannot queue $path for loading")
        }
    }

    private fun itemStateConv(state: CacheItem.State?) =
            when (state) {
                CacheItem.State.Empty -> Status.NotCached
                CacheItem.State.Exists,
                CacheItem.State.Filling -> Status.PartiallyCached
                CacheItem.State.Complete -> Status.FullyCached
                else -> Status.NotCached
            }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    override fun onItemChange(path: String, state: CacheItem.State) {
        for (l in listeners) {
            l.onCacheChange(path, itemStateConv(state))
        }
    }

    interface Listener {
        fun onCacheChange(path: String, status: Status)
    }


}

private const val LOADER_BUFFER_SIZE = 10 * 1024

class FileLoader(private val queue: BlockingDeque<CacheItem>, private val baseUrl: String, private val token: String) : Runnable {
    override fun run() {
        while (true) {
            try {

                val item = queue.take()
                val url = URL(baseUrl + item.path)
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    val responseCode = conn.responseCode
                    if (responseCode == 200 || responseCode == 206) {

                        item.openForAppend()
                        var complete = false
                        try {
                            val contentType = conn.contentType
                            val contentLength = conn.contentLength
                            val buf = ByteArray(LOADER_BUFFER_SIZE)
                            conn.inputStream.use {
                                while (true) {
                                    val read = it.read(buf)
                                    if (read < 0) break
                                    item.write(buf, 0, read)
                                }
                            }
                            complete = true
                        } finally {
                            item.closeForAppend(complete)
                        }

                    } else {
                        Log.e(LOG_TAG, "Http error $responseCode ${conn.responseMessage}")
                    }

                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Network error", e)

                }

            } catch (e: InterruptedException) {
                Log.d(LOG_TAG, "Load interrupted")
            }
        }
    }

}