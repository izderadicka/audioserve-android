package eu.zderadicka.audioserve

import androidx.test.runner.AndroidJUnit4
import eu.zderadicka.audioserve.net.tokenValidityDays
import eu.zderadicka.audioserve.net.tokenValidityEpoch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*

@RunWith(AndroidJUnit4::class)
class TokenTest {
    @Test
    fun testToken() {
        val v = tokenValidityEpoch("v0Ra9B58tBabK1bm22f37a8hXMxTThMVWFVHAySmivUAAAAAXN61+eGeG5fWYUqU22Ns/R8OTrsHapqw9Qd9fQm1sGOwSScp")

        val c = Calendar.getInstance()
        c.timeInMillis = v*1000
        assertEquals(2019, c.get( Calendar.YEAR))
        println("date ${c}")
    }

    @Test
    fun testToken2() {
        val v = tokenValidityDays("v0Ra9B58tBabK1bm22f37a8hXMxTThMVWFVHAySmivUAAAAAXN61+eGeG5fWYUqU22Ns/R8OTrsHapqw9Qd9fQm1sGOwSScp")
        println("valid days {v}")
    }
}