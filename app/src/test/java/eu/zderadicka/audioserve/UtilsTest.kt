package eu.zderadicka.audioserve

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
}