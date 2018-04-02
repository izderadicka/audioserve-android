package eu.zderadicka.audioserve

import eu.zderadicka.audioserve.net.CacheItem
import eu.zderadicka.audioserve.net.FileCache
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class FileCacheTest:BaseCacheTest() {


    @Test
    fun testInit() {

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
        val cache = FileCache(tmpDir!!, maxCacheSize, "http://localhost:3000", "abcd")
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