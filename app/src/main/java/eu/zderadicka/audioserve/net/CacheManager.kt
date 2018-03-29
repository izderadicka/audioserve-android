package eu.zderadicka.audioserve.net

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.DataSink
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.FileDataSourceFactory
import com.google.android.exoplayer2.upstream.cache.*
import java.io.File

private const val LOG_TAG: String = "CacheManager"
private const val MAX_CACHED_FILE_SIZE: Long =  250*1024*1024
private const val DEFAULT_CACHE_SIZE_MB: Int = 500
private const val DEFAULT_CACHE_DIR = "exoplayer"


class MyCacheDataSink(val cacheDataSink: CacheDataSink) : DataSink by cacheDataSink {

    override fun open(ds: DataSpec?) {
        ds!!
        val dataSpec = DataSpec(ds.uri, ds.postBody, ds.absoluteStreamPosition, ds.position, ds.length, ds.key,
                ds.flags or DataSpec.FLAG_ALLOW_CACHING_UNKNOWN_LENGTH)
        cacheDataSink.open(dataSpec)
    }

}

class MyCacheDataSinkFactory(val cache: Cache, val maxFileSize: Long): DataSink.Factory {
    override fun createDataSink(): DataSink {
        return MyCacheDataSink(CacheDataSink(cache, maxFileSize))
    }

}

class FileBackedDataSource(val upstreamDataSource: DataSource, val cache: CacheManager):DataSource {
    lateinit var currentDataSource: DataSource
    lateinit var currentUri: Uri
    override fun open(dataSpec: DataSpec?): Long {
        Log.d(LOG_TAG, "Opening this data spec $dataSpec on thread ${Thread.currentThread().name}")
        currentDataSource = upstreamDataSource
        return currentDataSource.open(dataSpec)
    }

    override fun getUri(): Uri {
        return currentDataSource.uri
    }

    override fun close() {
        currentDataSource.close()
    }

    override fun read(buffer: ByteArray?, offset: Int, readLength: Int): Int {
        return currentDataSource.read(buffer, offset,readLength)
    }

}

class DsFactory(val upstreamFactory: DataSource.Factory, val cache: CacheManager) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return FileBackedDataSource(upstreamFactory.createDataSource(), cache)
    }

}


class CacheManager(val context: Context) {
    val cache:Cache
    val cacheDir = File(context.applicationContext.cacheDir, DEFAULT_CACHE_DIR)


    init {
        val dir = context.applicationContext.cacheDir
        val cacheSize: Long = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_cache_size",
                DEFAULT_CACHE_SIZE_MB.toString()).toLong() * 1024 * 1024
        cache =  SimpleCache(cacheDir,
                LeastRecentlyUsedCacheEvictor(cacheSize))
        Log.d(LOG_TAG, "Cache initialized in directory ${dir.absolutePath}")


    }

    var upstreamFactory: DataSource.Factory? = null
    val sourceFactory: DataSource.Factory by lazy {
        val us = CacheDataSourceFactory(this.cache, upstreamFactory!!,
                FileDataSourceFactory(),
                MyCacheDataSinkFactory(cache, MAX_CACHED_FILE_SIZE),
                0,
                null)
        DsFactory(us, this@CacheManager)
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