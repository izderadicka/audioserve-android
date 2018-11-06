package eu.zderadicka.audioserve

import android.content.Context
import android.os.ConditionVariable
import android.preference.PreferenceManager
import android.support.test.InstrumentationRegistry
import android.support.test.filters.Suppress
import android.support.test.runner.AndroidJUnit4
import android.util.Log
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.net.FileCache
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import java.net.HttpURLConnection
import java.net.URL

private const val LOG_TAG = "CacheLoadTest"

open class BaseCacheAndroidTest {
    lateinit var tmpDir:File
    lateinit var ctx: Context


    @Before
    fun prepare() {
        ctx = InstrumentationRegistry.getTargetContext()
        tmpDir = File(ctx.cacheDir, "test-tmp-cache")
        tmpDir.mkdir()


    }

    @After
    fun cleanUp() {
        tmpDir.deleteRecursively()

    }
}
fun checkConnection(uri: String): Boolean {
    val url = URL(uri)
    val conn = url.openConnection() as HttpURLConnection
    try {
        val code = conn.responseCode
        if (code >=200 && code < 400) return true
    } catch (e: Exception) {
        println("Got exception $e")
    }
    return false
}


@RunWith(AndroidJUnit4::class)
@Suppress
class CacheLoadTest: BaseCacheAndroidTest() {
    val cond: ConditionVariable = ConditionVariable()

    @Test
    fun testLoad() {

        Log.i(LOG_TAG,"Test of cache load")
        assertTrue(tmpDir.exists() && tmpDir.isDirectory())
        val baseUrl = PreferenceManager.getDefaultSharedPreferences(ctx).getString("pref_server_url", null)
        assumeTrue(baseUrl != null && checkConnection(baseUrl))
        baseUrl!!
        val client = ApiClient.getInstance(ctx)


        client.login {
            cond.open()
            if (it != null) Log.e(LOG_TAG, "Login error $it")
            assertNull(it)
        }

        cond.block(8000)

        assertNotNull(client.token)
        val cacheMaxSize: Long = 100 * 1024 * 1024
        val cache = FileCache(tmpDir, cacheMaxSize, ctx, client.token!!)

        // size is 2,591,233 bytes
        val ps = arrayListOf("audio/Verne, Jules/Around the World in Eighty Days/01 - Chapter I.opus",
                "audio/Stevenson, Robert Louis/Treasure Island/01 - 00 - Dedication & Introductory Poem.mp3",
        "1/audio/Adams Douglas/Douglas Adams - Stoparuv pruvodce galaxii (2008)/00.uvod.mp3")
        var counter = ps.size
        val listener = object: FileCache.Listener {
            override fun onCacheChange(path: String, status: FileCache.Status) {
                Log.i(LOG_TAG, "Cache $path -- ${status.name}")
                if (status == FileCache.Status.FullyCached) {
                    counter--
                    if (counter == 0) cond.open()
                }
            }
            
        }
        cache.addListener(listener)
        cond.close()
        for (p in ps) {
            cache.getOrAddAndSchedule(p, null)
        }

        cond.block(6000)

        for ((i,p) in ps.withIndex()) {
            assertEquals(FileCache.Status.FullyCached, cache.checkCache(p))
            val f1 = File(tmpDir, p)
            assertTrue(f1.exists() && f1.isFile)
            if (i==0) assertEquals(2591233, f1.length())

        }

        assertEquals(0, counter)

        cache.stopLoader()

    }
}