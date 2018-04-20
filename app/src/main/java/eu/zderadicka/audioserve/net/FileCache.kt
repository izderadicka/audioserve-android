package eu.zderadicka.audioserve.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Uri
import android.os.ConditionVariable
import android.os.FileObserver
import android.preference.PreferenceManager
import android.util.Log
import eu.zderadicka.audioserve.utils.defPrefs
import eu.zderadicka.audioserve.utils.isNetworkConnected
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.BlockingDeque
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.max

private const val LOG_TAG = "FileCache"
internal const val MAX_CACHE_FILES = 1000
private const val MIN_FILE_SIZE: Long = 5 * 1024 * 1024 // If we do not know file size assume at least 5MB
private const val MAX_DOWNLOAD_RETRIES = 3
private const val NOT_CONNECTED_WAIT = 10000L // wait ms if phone is not connected
private const val MAX_CACHED_FILE_SIZE: Long = 250 * 1024 * 1024


private val CONTENT_RANGE_RE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)\s*""")

data class ContentRange(val start: Long, val end: Long, val totalLength: Long)

fun parseContentRange(range: String): ContentRange? {
    val m = CONTENT_RANGE_RE.matchEntire(range)
    if (m == null) return null
    try {
        val groupAsLong = { n: Int -> m.groups.get(n)!!.value.toLong() }
        return ContentRange(groupAsLong(1), groupAsLong(2), groupAsLong(3))

    } catch (e: NumberFormatException) {
    } catch (e: NullPointerException) {

    }
    return null
}

class CacheIndex(val maxCacheSize: Long, private val changeListener: CacheItem.Listener, lru: Boolean = false) {
    var cacheSize: Long = 0
    val map = LinkedHashMap<String, CacheItem>(MAX_CACHE_FILES / 5, 0.75f, lru)

    val size: Int
        get() {
            return map.size
        }

    val keys: Set<String>
        get() = map.keys

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
            cacheSize += item.knownLength
        }

    }

    fun clear() = synchronized(this) {
        for (e in map) {
            e.value.destroy()
        }
        map.clear()
        cacheSize = 0
    }

    fun fastClear() {
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
        cacheSize -= removedSize

    }

    internal fun loadFromDir(cacheDir: File) {
        val pathPrefixLength = cacheDir.path!!.length
        val itemsToAdd = ArrayList<CacheItem>()
        fun load(currDir: File) {
            for (f in currDir.listFiles()) {
                if (f.isDirectory()) {
                    load(f)
                } else {
                    var path = f.absolutePath.substring(pathPrefixLength + 1)
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

class FileCache(val cacheDir: File,
                val maxCacheSize: Long,
                val context: Context,
                token: String? = null,
                dontObserveDir: Boolean = false) : CacheItem.Listener {

    enum class Status {
        NotCached,
        PartiallyCached,
        FullyCached,
        Error
    }

    private val index = CacheIndex(maxCacheSize, this, true)
    internal val listeners = HashSet<Listener>()
    private val queue = LinkedBlockingDeque<CacheItem>(MAX_CACHE_FILES)
    private var loaderThread: Thread? = null
    private var loader: FileLoader? = null
    private val baseUrlPath: String
        get() {
            val baseUrl = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_server_url", null)!!
            return URL(baseUrl).path
        }

    private var dirObserver: FileObserver? = null;

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdir()
        }
        if (!dontObserveDir) {
            dirObserver = object : FileObserver(cacheDir.absolutePath, FileObserver.DELETE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    Log.d(LOG_TAG, "Dir event is $event, $path")
                    if (event and FileObserver.DELETE_SELF > 0) resetIndex()
                }

            }
            dirObserver?.startWatching()
        }

        index.loadFromDir(cacheDir)
        if (token != null) startLoader(token)
    }

    internal fun resetIndex(deleteDir: Boolean = false) {
        Log.i(LOG_TAG, "Reseting cache")
        synchronized(this) {
            dirObserver?.stopWatching()
            if (deleteDir) {
                val res = cacheDir.deleteRecursively()
                if (!res) {
                    Log.e(LOG_TAG, "Cannot delete media cache dir")
                }
            }
            if (!cacheDir.exists()) {
                cacheDir.mkdir()
            }
            stopAllLoading()
            index.clear()
            dirObserver = object : FileObserver(cacheDir.absolutePath, FileObserver.DELETE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    Log.d(LOG_TAG, "Dir event is $event, $path")
                    if (event and FileObserver.DELETE_SELF > 0) resetIndex()
                }
            }
            dirObserver?.startWatching()
        }
    }

    val numberOfFiles: Int
        get() = index.size

    val cacheSize: Long
        get() = index.cacheSize

    val cacheKeys: List<String>
        get() {
            val keys = HashSet(index.keys)
            return keys.filter { checkCache(it) == Status.FullyCached }
        }

    fun pathFromUri(uri: Uri): String {
        val p = uri.path
        if (p.startsWith(baseUrlPath)) {
            return p.substring(baseUrlPath.length)
        } else {
            return p
        }
    }

    fun startLoader(token: String) {
        if (loaderThread != null) {
            throw IllegalStateException("Loader already started")
        }

        loader = FileLoader(queue = queue, token = token, context = context)
        val loaderThread = Thread(loader, "Loader Thread")
        loaderThread.isDaemon = true
        loaderThread.start()
        this.loaderThread = loaderThread
    }

    fun stopLoader() {
        this.loader?.stop()
        stopAllLoading()
        this.loaderThread = null
        this.loader = null
    }

    fun getOrAdd(path: String): CacheItem = synchronized(this) {
        if (index.contains(path)) {
            index.get(path)!!
        } else {
            addToCache(path)
        }
    }

    fun getOrAddTranscoded(path: String, transcode: String?): CacheItem {
        val item = getOrAdd(path)
        // set transcode only for item that is not partly loaded
        if (item.state == CacheItem.State.Empty) {
            item.transcode = transcode
        }
        return item
    }

    fun injectFile(path: String, f: File): CacheItem {
        val item = getOrAdd(path)
        item.injectFile(f)
        return item
    }

    fun getOrAddAndSchedule(path: String, transcode: String?): CacheItem {
        val item = getOrAdd(path)
        scheduleDownload(item, transcode)
        return item
    }

    fun checkCache(path: String): Status = synchronized(this) {
        if (!index.contains(path)) {
            Status.NotCached
        } else {
            val item = index.get(path)
            itemStateConv(item?.state, item?.hasError ?: false)
        }
    }

    private fun scheduleDownload(item: CacheItem, transcode: String?) {
        if (item.state != CacheItem.State.Complete) {
            try {
                // set transcode only for item that is not partly loaded
                if (item.state == CacheItem.State.Empty) {
                    item.transcode = transcode
                }
                queue.add(item)
            } catch (e: IllegalStateException) {
                Log.e(LOG_TAG, "Cannot queue ${item.path} for loading, cache is full")
                throw IOException("Cache is full")
            }
        }
    }

    fun addToCache(path: String): CacheItem = synchronized(this) {
        val item = CacheItem(path, cacheDir, this)
        index.put(item)

        item
    }

    fun stopAllLoading(vararg keepLoading: String) = synchronized(this) {
        queue.clear()
        val shouldInterrupt: Boolean = if (keepLoading.size == 0) {
            true
        } else if (keepLoading[0] == loader?.currentPath) {
            false
        } else if (keepLoading.size > 1) {
            val firstItem = index.get(keepLoading[0])
            if (firstItem == null || firstItem.state != CacheItem.State.Complete) {
                true
            } else {
                !(loader?.currentPath in keepLoading.slice(1..keepLoading.size))
            }
        } else {
            true
        }
        if (shouldInterrupt) loaderThread?.interrupt()

    }

    private fun itemStateConv(state: CacheItem.State?, hasError: Boolean) =
            if (hasError) {
                Status.Error
            } else {
                when (state) {
                    CacheItem.State.Empty -> Status.NotCached
                    CacheItem.State.Exists,
                    CacheItem.State.Filling -> Status.PartiallyCached
                    CacheItem.State.Complete -> Status.FullyCached
                    else -> Status.NotCached
                }
            }

    fun addListener(l: Listener) = synchronized(this) { listeners.add(l) }
    fun removeListener(l: Listener) = synchronized(this) { listeners.remove(l) }
    fun removeAllListeners() = synchronized(this) { listeners.clear() }

    override fun onItemChange(path: String, state: CacheItem.State, hasError: Boolean) = synchronized(this) {
        for (l in listeners) {
            l.onCacheChange(path, itemStateConv(state, hasError))
        }
    }

    interface Listener {
        fun onCacheChange(path: String, status: Status)
    }
}


private const val LOADER_BUFFER_SIZE = 10 * 1024

class FileLoader(private val queue: BlockingDeque<CacheItem>,
                 private val token: String,
                 private val context: Context) : Runnable {
    @Volatile
    private var stopFlag = false
    fun stop() {
        stopFlag = true
        context.unregisterReceiver(connectivityStateReceiver)
    }

    var currentPath: String? = null
        private set

    private var putBack = false
    private var myThread: Thread? = null

    private var isConnected = true
        set(v) {
            field = v
            if (field) {
                Log.d(LOG_TAG, "Network is connected")
                connectedCondition.open()
            } else {
                Log.d(LOG_TAG, "Network is disconnected")
                connectedCondition.close()
                myThread?.let {
                    putBack = true
                    Log.d(LOG_TAG, "Trying to interrupt thread ${it.name}")
                    it.interrupt()
                }
            }
        }
    private val connectedCondition = ConditionVariable()
    private val connectivityStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION && context != null) {
                isConnected = isNetworkConnected(context)

            }
        }

    }

    init {

        context.registerReceiver(connectivityStateReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        isConnected = isNetworkConnected(context)


    }
    private fun putItemBack(item:CacheItem?) {
        if (putBack && item != null) {
            Log.d(LOG_TAG, "Putting item back as loader was interrupted by network disconnect ")
            returnToQueue(item)
            putBack = false
        }
    }

    private fun returnToQueue(item: CacheItem) {
        try {
            queue.addFirst(item)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Cannot add for retry")
        }
    }

    private fun retry(item: CacheItem) {
        item.retries++
        if (item.retries <= MAX_DOWNLOAD_RETRIES) {
            returnToQueue(item)
        } else {
            item.hasError = true
        }
    }


    override fun run() {
        myThread = Thread.currentThread()
        main@ while (true) {
            var item: CacheItem? = null
            var url: URL? = null
            try {

                // wait if not connected
                if (!isConnected) {
                    Log.d(LOG_TAG, "Network is not connected cannot load cache")
                    connectedCondition.block(NOT_CONNECTED_WAIT)
                }
                if (stopFlag) break
                item = queue.take()

                // return to queue and try again if not connected
                if (!isConnected) {
                    returnToQueue(item)
                    continue
                }

                currentPath = item.path
                if (stopFlag) break
                if (Thread.interrupted()) continue
                // Check that item is not complete or filling elsewhere
                if (item.state == CacheItem.State.Complete ||
                        item.state == CacheItem.State.Filling) continue
                val baseUrl = defPrefs(context).getString("pref_server_url", null)!!
                var urlStr = baseUrl + if (item.path.startsWith("/")) item.path.substring(1) else item.path
                if (item.transcode != null) {
                    urlStr += "?${TRANSCODE_QUERY}=${item.transcode}"
                }
                url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                Log.d(LOG_TAG, "Started download of $url")

                if (item.cachedLength > 0) {
                    conn.setRequestProperty("Range", "bytes=${item.cachedLength}-")
                }
                conn.setRequestProperty("Authorization", "Bearer $token")
                val responseCode = conn.responseCode
                if (responseCode == 200 || responseCode == 206) {
                    val startAt: Long = if (responseCode == 206) {
                        parseContentRange(conn.getHeaderField("Content-Range"))?.start ?: 0L
                    } else {
                        0L
                    }
                    val startNew = startAt == 0L && item.cachedLength > 0L
                    assert(if (!startNew) item.cachedLength == startAt else true)
                    item.openForAppend(startNew)
                    var complete = false
                    try {
                        //val contentType = conn.contentType
                        val contentLength = conn.contentLength
                        if (contentLength > MAX_CACHED_FILE_SIZE) {
                            Log.e(LOG_TAG, "File is bigger then max limit")
                            complete = true
                            break
                        }
                        val buf = ByteArray(LOADER_BUFFER_SIZE)
                        conn.inputStream.use {
                            while (true) {
                                val read = it.read(buf)
                                if (read < 0) {
                                    complete = true
                                    break
                                } else if (read + item.cachedLength > MAX_CACHED_FILE_SIZE) {
                                    complete = true
                                    break
                                }
                                item.write(buf, 0, read)
                                if (Thread.interrupted()) {
                                    Log.d(LOG_TAG, "Load interrupted in read")
                                    putItemBack(item)
                                    break
                                }
                            }
                        }

                    } finally {
                        Log.d(LOG_TAG, "Finished download of $url complete $complete")
                        item.closeForAppend(complete)
                    }

                } else {
                    Log.e(LOG_TAG, "Http error $responseCode ${conn.responseMessage} for url $url")
                    // Can retry on server error
                    if (responseCode >= 500) retry(item) else item.hasError = true
                }

            } catch (e: InterruptedException) {
                putItemBack(item)
                Log.d(LOG_TAG, "Load interrupted in wait")
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Network error for url $url", e)
                item?.let { retry(it) }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Other error for url $url", e)
                item?.hasError = true
            } finally {
                currentPath = null
                putBack = false
            }
        }

        Log.d(LOG_TAG, "Thread ${Thread.currentThread().name} finished")
    }

}