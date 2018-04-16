package eu.zderadicka.audioserve

import android.support.test.runner.AndroidJUnit4
import eu.zderadicka.audioserve.net.CacheBrowser
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class CacheBrowserTest: BaseCacheAndroidTest() {

    @Test
    fun testRootFolder() {
        val ids = listOf("audio/Verne, Jules/Around the World in Eighty Days/01 - Chapter I.opus",
                "audio/Stevenson, Robert Louis/Treasure Island/01 - 00 - Dedication & Introductory Poem.mp3",
                "1/audio/Adams Douglas/Douglas Adams - Stoparuv pruvodce galaxii (2008)/00.uvod.mp3")
        val cacheBrowser = CacheBrowser(ids, tmpDir)
        val rootFolder = cacheBrowser.rootFolder
        assertEquals(3, rootFolder.size)
        assertEquals("audio/Verne, Jules", rootFolder[2].mediaId)
        assertEquals("1/audio/Adams Douglas", rootFolder[0].mediaId)


    }
}