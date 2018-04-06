package eu.zderadicka.audioserve

import eu.zderadicka.audioserve.data.folderIdFromFileId
import eu.zderadicka.audioserve.net.parseContentRange
import org.junit.Assert.*
import org.junit.Test

class UtilsTest {
    @Test
    fun testContentRange() {
        val r = "bytes 28540928-28551305/28551306"
        val range = parseContentRange(r)
        assertNotNull(range)
        range!!
        assertEquals(28540928L, range.start)
        assertEquals(28551305, range.end)
        assertEquals(28551306, range.totalLength)
    }

    @Test
fun testConvMediaId() {
        val fileId = "1/audio/Arbes Jakub/Svaty Xaverius (Jakub Arbes)/001 of 3.opus"
        val folderId ="1/folder/Arbes Jakub/Svaty Xaverius (Jakub Arbes)"
        val fileId2 = "audio/Doyle, Arthur Conan/The Adventures of Sherlock Holmes/04 - 04 - The Boscombe Valley Mystery.mp3"
        val folderId2 = "folder/Doyle, Arthur Conan/The Adventures of Sherlock Holmes"

        assertEquals(folderId, folderIdFromFileId(fileId))
        assertEquals(folderId2, folderIdFromFileId(fileId2))
    }
}