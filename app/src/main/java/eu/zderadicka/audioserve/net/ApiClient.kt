package eu.zderadicka.audioserve.net

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.preference.PreferenceManager
import android.text.Html
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import com.android.volley.*
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.ImageRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import eu.zderadicka.audioserve.data.AudioFolder
import eu.zderadicka.audioserve.data.parseCollectionsFromJson
import eu.zderadicka.audioserve.data.parseFolderfromJson
import eu.zderadicka.audioserve.data.parseTranscodingsFromJson
import java.io.File
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom


enum class ApiError {
    Network,
    UnauthorizedAccess,
    Server,
    Unknown;

    companion object {
        fun fromResponseError(err: VolleyError): ApiError =
                if (err is TimeoutError || err is NoConnectionError) {
                    ApiError.Network
                } else if (err is AuthFailureError) {
                    ApiError.UnauthorizedAccess
                } else if (err is NetworkError || err is ServerError || err is ParseError) {
                    ApiError.Server
                } else {
                    ApiError.Unknown
                }
    }
}

private const val LOG_TAG = "ApiClient"

private const val API_REQUEST_TAG = "API"
private const val LOGIN_REQUEST_TAG = "LOGIN"
private const val IMAGE_REQUEST_TAG = "IMAGE"
private const val TEXT_REQUEST_TAG = "TEXT"

private const val CACHE_SOFT_TTL: Long = 5 * 60 * 1000
private const val CACHE_MAX_TTL: Long = 24 * 3600 * 1000

internal fun encodeSecret(secret: String): String {
    val secretBytes = secret.toByteArray(Charset.forName("UTF-8"))
    val randBytes = ByteArray(32)
    val rng = SecureRandom()
    rng.nextBytes(randBytes)
    val concatedBytes = secretBytes + randBytes
    val md = MessageDigest.getInstance("SHA-256")
    md.update(concatedBytes)
    val digest = md.digest()
    val res = Base64.encodeToString(randBytes, Base64.NO_WRAP) + "|" +
            Base64.encodeToString(digest, Base64.NO_WRAP)

    return res

}

data class TranscodingLimits(var low: Int, var medium: Int, var high: Int)


class ApiClient private constructor(val context: Context) {

    lateinit var baseUrl: String
        private set
    var token: String? = null
        private set
    // as login is asynchronous need to handle cases where fists requests are send before login
    // but also consider offline case when login fails always - so store first request here and send
    // after login success or failure
    private var loginDone = false
    private val unsentRequests: ArrayList<Request<*>> = ArrayList()


    // getApplicationContext() is key, it keeps you from leaking the
    // Activity or BroadcastReceiver if someone passes one in.
    val requestQueue: RequestQueue by lazy {
        Volley.newRequestQueue(context.getApplicationContext())

    }

    @Synchronized
    fun loadPreferences(cb: ((ApiError?) -> Unit)? = null) {
        baseUrl = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_server_url", "")
        if (baseUrl.length == 0) {
            Log.w(LOG_TAG, "BaseURL is empty!")
        } else {
            assert(baseUrl.endsWith("/"))
            Log.d(LOG_TAG, "Client base URL is $baseUrl")
        }

        login {
            if (it == null) {
                Log.d(LOG_TAG, "Successfully logged into server")
            }
            if (cb != null) {
                cb(it)
            }
        }
    }

    init {
        loadPreferences()
    }

    fun <T> addToRequestQueue(req: Request<T>) {
        if (!loginDone) {
            Log.w(LOG_TAG, "Client is not authorised yet")
            if (unsentRequests.size < 10) {
                unsentRequests.add(req)
            } else {
                Log.e(LOG_TAG, "Too many requests waiting for login")
                req.deliverError(VolleyError("Waiting for login too long"))
            }

        } else {
            requestQueue.add(req)
        }
    }

    private fun <T> sendRequest(uri: String, convert: (String) -> T, callback: (T?, ApiError?) -> Unit) {
        val request = ConvertingRequest(uri,convert, callback)
        request.setShouldCache(true)
        request.tag = API_REQUEST_TAG
        addToRequestQueue(request)
    }

    fun uriFromMediaId(mediaId: String, transcode: String? = null): Uri {
        return Uri.parse(baseUrl + mediaId + if (transcode != null) "?${TRANSCODE_QUERY}=$transcode" else "")
    }


    fun loadFolder(folder: String = "", collection: Int, callback: (AudioFolder?, ApiError?) -> Unit) {
        var uri = baseUrl
        if (collection > 0) {
            uri += "$collection/"
        }
        uri += folder

        sendRequest(uri, {
            val f = parseFolderfromJson(it, "", folder)
            if (collection > 0) {
                f.collectionIndex = collection
            }
            f
        }, callback)
    }

    fun loadSearch(query: String, collection: Int, callback: (AudioFolder?, ApiError?) -> Unit) {
        var uri = baseUrl
        if (collection > 0) {
            uri += "$collection/"
        }
        uri += "search"

        var queryUri = Uri.parse(uri).buildUpon().appendQueryParameter("q", query).build()
        sendRequest(queryUri.toString(), {
            val f = parseFolderfromJson(it, "search", "")
            if (collection > 0) {
                f.collectionIndex = collection
            }
            f
        }, callback)

    }

    fun loadCollections(callback: (ArrayList<String>?, ApiError?) -> Unit) {
        val uri = baseUrl + "collections"
        sendRequest(uri, ::parseCollectionsFromJson, callback)
    }

    fun loadTranscodings(callback: (TranscodingLimits?, ApiError?) -> Unit) {
        val uri = baseUrl + "transcodings"
        sendRequest(uri, ::parseTranscodingsFromJson, callback)
    }

    fun loadPicture(path: String, callback: (Bitmap?, ApiError?) -> Unit) {

        val url = baseUrl + path

        val request = object: ImageRequest(url,
                {callback(it,null)},
                1024,
                1024,
                ImageView.ScaleType.CENTER,
                Bitmap.Config.ARGB_8888,
                {
                    val err = ApiError.fromResponseError(it)
                    callback(null,err)
                })
        {
            override fun getHeaders(): MutableMap<String, String> {
                return auhorizationHeaders
            }
        }

        request.setShouldCache(true)
        request.tag = IMAGE_REQUEST_TAG
        addToRequestQueue(request)

    }

    fun loadText(path:String, callback: (CharSequence?, ApiError?)-> Unit) {
        val url = baseUrl + path
        val req = object: MyRequest<CharSequence>(url, callback)

        {
            override fun parseNetworkResponse(response: NetworkResponse?): Response<CharSequence> {

                var parsed: String = ""
                var contentType = response?.headers?.get("Content-Type")?:"text/plain"
                val semi = contentType.indexOf(';')
                if (semi>0) {
                    contentType.substring(0, semi).trim()
                }

                if (response!= null) {
                    try {
                        val charset = Charset.forName("UTF-8")
                        parsed = String(response.data, charset)
                    } catch (e: UnsupportedEncodingException) {
                        parsed = String(response.data)
                    }
                }

                if (contentType == "text/html") {
                    val html = Html.fromHtml(parsed)
                    return Response.success(html, HttpHeaderParser.parseCacheHeaders(response))
                }

                return Response.success(parsed, HttpHeaderParser.parseCacheHeaders(response))
            }

        }

        req.setShouldCache(true)
        req.tag = TEXT_REQUEST_TAG
        addToRequestQueue(req)

    }

    fun login(cb: (ApiError?) -> Unit) {
        fun afterLogin() {
            synchronized(this@ApiClient) {
                loginDone = true
                for (r in unsentRequests) {
                    requestQueue.add(r)
                }
                unsentRequests.clear()
            }
        }

        val request = object : StringRequest(Request.Method.POST, baseUrl + "authenticate",
                {
                    cb(null)
                    token = it
                    afterLogin()
                    loadTranscodings { tr, err ->
                        if (tr != null) {
                            transcodingBitrates = tr
                        }
                    }
                },
                {
                    Log.e(LOG_TAG, "Login error: $it")
                    cb(ApiError.fromResponseError(it))
                    afterLogin()
                }) {
            override fun getParams(): MutableMap<String, String>? {
                val p = HashMap<String, String>()
                val sharedSecret = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_shared_secret", null)
                if (sharedSecret == null) {
                    Log.w(LOG_TAG, "Shared secret is not set! Assuming open server")
                    return null
                }
                val secret = encodeSecret(sharedSecret)
                p.put("secret", secret)
                return p
            }

            override fun getPriority(): Priority {
                return Request.Priority.HIGH
            }
        }

        request.setShouldCache(false)
        request.tag = LOGIN_REQUEST_TAG
        requestQueue.add(request)

    }

    private val auhorizationHeaders: HashMap<String, String>
    get() {
        val headers = HashMap<String, String>(1)
        if (token != null) {
            headers.put("Authorization", "Bearer $token")
        }
        return headers
    }

    inner abstract class MyRequest<T>(val uri: String, private val callback: (T?, ApiError?) -> Unit)
        :Request<T>( Request.Method.GET, uri,
            {
                Log.e(LOG_TAG, "Network Error $it")
                var err = ApiError.fromResponseError(it)
                if (err == ApiError.UnauthorizedAccess && token == null) {
                    // started offline - try again to log in
                    login {}
                }
                callback(null, err)

            }) {

        private var cancelled: Boolean = false
        private val canceledLock = java.lang.Object()


        override fun getHeaders(): MutableMap<String, String> {
            return auhorizationHeaders
        }

        override fun cancel() {
            super.cancel()
            synchronized(canceledLock) {
                cancelled = true
            }
        }

        override fun deliverResponse(response: T) {
            synchronized(canceledLock) {
                if (!cancelled) callback(response, null)
            }

        }
    }


        inner class ConvertingRequest<T>(uri: String, val convert: (String) -> T,
                                     callback: (T?, ApiError?) -> Unit)
        : MyRequest<T>(
            uri, callback
            ) {

        override fun parseNetworkResponse(response: NetworkResponse?): Response<T> {
            var parsed: String
            if (response?.data == null || response.data.isEmpty()) {
                return Response.error(VolleyError("Empty response"))
            }
            try {
                val charset = Charset.forName("UTF-8")
                parsed = String(response.data, charset)
            } catch (e: UnsupportedEncodingException) {
                parsed = String(response.data)
            }
            val cacheEntry = HttpHeaderParser.parseCacheHeaders(response)
            val now = System.currentTimeMillis()
            cacheEntry.softTtl = now + CACHE_SOFT_TTL
            cacheEntry.ttl = now + CACHE_MAX_TTL

            val result = convert(parsed)
            return Response.success(result, cacheEntry)
        }
    }

    companion object {
        @Volatile
        private var instance: ApiClient? = null

        @Synchronized
        fun clearCache(context: Context) {
            //TODO - although this is working consider better way
            try {
                for (f in File(context.applicationContext.cacheDir, "volley").listFiles()) {
                    f.deleteRecursively()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Cannot delete API cache due error $e")
            }
        }

        @JvmStatic
        fun getInstance(context: Context): ApiClient =
                synchronized(this) {
                    if (instance == null) instance = ApiClient(context.applicationContext)
                    instance!!

                }

        var transcodingBitrates = TranscodingLimits(32, 48, 64)
            private set
    }
}