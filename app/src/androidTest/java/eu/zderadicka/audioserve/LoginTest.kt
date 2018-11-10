package eu.zderadicka.audioserve

import androidx.test.runner.AndroidJUnit4
import eu.zderadicka.audioserve.net.encodeSecret
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class LoginTest {

    @Test
    fun testSecretEncoding() {
        val r = encodeSecret("usak")
        assert(r.length> 80)
    }
}