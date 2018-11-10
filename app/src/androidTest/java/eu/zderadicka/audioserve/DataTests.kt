package eu.zderadicka.audioserve

import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import eu.zderadicka.audioserve.data.readCollectionsFromJson
import eu.zderadicka.audioserve.data.readFolderFromJson

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class DataTests {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getTargetContext()
        assertEquals("eu.zderadicka.audioserve", appContext.packageName)
    }

    @Test
    fun readFileFromAssets() {
        val ctx = InstrumentationRegistry.getContext()
        val stream = ctx.resources.assets.open("sample_text.txt")
        val s = stream.bufferedReader().readLine()
        assertEquals("Hello world", s)
        stream.close()
    }

    @Test
    fun parseJsonFolderData() {
        val ctx = InstrumentationRegistry.getContext()
        val stream = ctx.resources.assets.open("test_folder_data.json")
        val data = readFolderFromJson(stream, "test", "test")

        assertEquals(5,data.numFiles)
        assertEquals(5,data.numFolders)
        assertNotNull(data.details)

    }

    @Test
fun parseJsonCollectionsData() {
        val ctx = InstrumentationRegistry.getContext()
        val stream = ctx.resources.assets.open("test_collections_data.json")
        val data = readCollectionsFromJson(stream)
        assertEquals(2, data.size)
    }
}
