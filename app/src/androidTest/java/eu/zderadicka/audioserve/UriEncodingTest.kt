package eu.zderadicka.audioserve

import android.os.Build
import androidx.test.runner.AndroidJUnit4
import eu.zderadicka.audioserve.utils.encodeUri
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UriEncodingTest {
    @Test
    fun testEncoding() {
        val uriEncoded = "http://localhost:3000/folder/Doyle%2C%20Arthur%20Conan"
        val uriWithSpace = "http://localhost:3000/folder/Doyle, Arthur Conan"
        val res = encodeUri(uriWithSpace)
        if (Build.VERSION.SDK_INT<= 23) {

            assertEquals(uriEncoded, res)
        } else {
           assertEquals(uriWithSpace, res)
        }
    }
}