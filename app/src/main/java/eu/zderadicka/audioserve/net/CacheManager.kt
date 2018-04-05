package eu.zderadicka.audioserve.net

import android.content.Context
import android.net.Uri
import android.os.ConditionVariable
import android.preference.PreferenceManager
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.math.min

private const val LOG_TAG: String = "CacheManager"
private const val MAX_CACHED_FILE_SIZE: Long =  250*1024*1024
private const val DEFAULT_CACHE_SIZE_MB: Int = 500
private const val DEFAULT_CACHE_DIR = "exoplayer"
private const val TIME_TO_WAIT_FOR_CACHE:Long = 10000

class CachedFileDataSourceFactory(val cache: FileCache) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return CachedFileDataSource(cache)
    }
}


class CachedFileDataSource(val cache: FileCache) : DataSource, CacheItem.Listener {


    private var item: CacheItem? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private val cacheReadyCondition = ConditionVariable()


    class CachedFileException(cause: IOException) : IOException(cause)

    @Throws(CachedFileException::class)
    override fun open(dataSpec: DataSpec): Long {
        try {
            uri = dataSpec.uri
            val item = cache.getOrAddAndSchedule(cache.pathFromUri(dataSpec.uri))
            this.item = item
            item.addListener(this)

            // Wait for cache item to get filled
            if (item.state == CacheItem.State.Empty) {
                cacheReadyCondition.block(TIME_TO_WAIT_FOR_CACHE)
                if (item.state == CacheItem.State.Empty) {
                    throw IOException("Empty cache")
                }
            }

            if (dataSpec.position> item.cachedLength) {
                throw EOFException()
            }

            item.openForRead(dataSpec.position)

//            The number of bytes that can be read from the opened source.
//            For unbounded requests (i.e. requests where DataSpec.length equals C.LENGTH_UNSET)
//            this value is the resolved length of the request, or C.LENGTH_UNSET if the length is still unresolved.
//            For all other requests, the value returned will be equal to the request's DataSpec.length.
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                if (item.totalLength == UNKNOWN_LENGTH) {
                UNKNOWN_LENGTH
                } else {
                    item.totalLength - dataSpec.position
                }
            } else {
                dataSpec.length
            }

        } catch (e: IOException) {
            Log.e(LOG_TAG,"Error opening CachedFileDataSource: $e")
            throw CachedFileException(e)
        }

        return bytesRemaining
    }

    override fun onItemChange(path: String, state: CacheItem.State) {
            if (state != CacheItem.State.Empty) cacheReadyCondition.open()
    }

    @Throws(CachedFileException::class)
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        } else {
            val bytesRead: Int
            try {
                val toRead = if (bytesRemaining == UNKNOWN_LENGTH) readLength.toLong()
                    else min(bytesRemaining, readLength.toLong())
                bytesRead = item!!.read(buffer, offset, toRead.toInt())
            } catch (e: IOException) {
                throw CachedFileException(e)
            }

            if (bytesRemaining != UNKNOWN_LENGTH && bytesRead > 0) {
                bytesRemaining -= bytesRead.toLong()
            }

            return bytesRead
        }
    }

    override fun getUri(): Uri? {
        return uri
    }

    @Throws(CachedFileException::class)
    override fun close() {
        uri = null
        try {
            if (item != null) {
                item!!.removeListener(this)
                item!!.closeForRead()
            }
        } catch (e: IOException) {
            throw CachedFileException(e)
        } finally {
            item = null
        }
    }

}

class CacheManager(val context: Context) {

    val cacheDir = File(context.applicationContext.cacheDir, DEFAULT_CACHE_DIR)
    val cache:FileCache

    init {
        val dir = context.applicationContext.cacheDir
        val cacheSize: Long = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_cache_size",
                DEFAULT_CACHE_SIZE_MB.toString()).toLong() * 1024 * 1024
        val baseUrl:String = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_server_url","")
        cache =  FileCache(cacheDir,cacheSize, baseUrl)
        Log.d(LOG_TAG, "Cache initialized in directory ${dir.absolutePath}")
    }

    fun startCacheLoader(token: String) {
        cache.startLoader(token)
    }

    fun stopCacheLoader() {
        cache.stopLoader()
    }

    fun resetLoading(vararg keepLoading:String) {
        cache.stopAllLoading(*keepLoading)
    }

    fun isCached(mediaId: String): Boolean {
        return cache.checkCache(mediaId) == FileCache.Status.FullyCached
    }

    val sourceFactory: DataSource.Factory by lazy {
        CachedFileDataSourceFactory(cache)
    }

    fun addListener(l: FileCache.Listener) {
        cache.addListener(l)
    }

    fun removeLister(l: FileCache.Listener) {
        cache.removeListener(l)
    }

    fun ensureCaching(mediaId: String) {
        cache.getOrAddAndSchedule(mediaId)
    }

    companion object {

        @Synchronized fun clearCache(context: Context) {
            try {
                File(context.applicationContext.cacheDir, DEFAULT_CACHE_DIR).deleteRecursively()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Cannot delete cache due  error $e")
            }
        }
    }


}