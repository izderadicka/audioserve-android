package eu.zderadicka.audioserve

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkInfo
import eu.zderadicka.audioserve.net.CacheItem
import eu.zderadicka.audioserve.net.FileCache
import eu.zderadicka.audioserve.utils.copyFile
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito
import java.io.File
import org.mockito.ArgumentMatchers.*

class FileCacheTest:BaseCacheTest() {


    @Test
    fun testInit() {


        val prefs: SharedPreferences = Mockito.mock(SharedPreferences::class.java)
        val context: Context = Mockito.mock(Context::class.java)
        val cm: ConnectivityManager = Mockito.mock(ConnectivityManager::class.java)
        val netInfo:NetworkInfo = Mockito.mock(NetworkInfo::class.java)

        Mockito.`when`(context.getSharedPreferences(anyString(),anyInt())).thenReturn(prefs)
        Mockito.`when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)
        Mockito.`when`(cm.activeNetworkInfo).thenReturn(netInfo)
        Mockito.`when`(netInfo.isConnectedOrConnecting).thenReturn(true)

        var statusesUpdates = 0

        val counter = object : FileCache.Listener {
            override fun onCacheChange(path: String, status: FileCache.Status) {
                println("Status change for $path to ${status}")
                statusesUpdates+=1
            }

        }

        for (i in 1..10) {
            copyFile(testFile, File(tmpDir, "audio/author/book/chapter$i.mp3"))
            if (i==1) {
                Thread.sleep(1100)
            }
        }
        val maxCacheSize: Long = 586270
        val cache = FileCache(tmpDir!!, maxCacheSize, context, dontObserveDir = true)
        cache.addListener(counter)
        assertEquals(10, cache.numberOfFiles)
        assertEquals(10L * testFile.length(), cache.cacheSize)
        assertEquals(maxCacheSize, cache.maxCacheSize)

        assertEquals(FileCache.Status.FullyCached, cache.checkCache("audio/author/book/chapter8.mp3"))

        println("Now cache has ${cache.cacheSize} bytes")

        val newItemPath = "audio/author/book/chapter11.mp3"
        copyFile(testFile, File(tmpDir, newItemPath))

        cache.addToCache(newItemPath)

        assertEquals(FileCache.Status.FullyCached, cache.checkCache(newItemPath))
        assertEquals(10, cache.numberOfFiles)
        assertEquals(10L * testFile.length(), cache.cacheSize)

        assertEquals(FileCache.Status.NotCached, cache.checkCache("audio/author/book/chapter1.mp3"))

        assertEquals(2, statusesUpdates)

    }
}