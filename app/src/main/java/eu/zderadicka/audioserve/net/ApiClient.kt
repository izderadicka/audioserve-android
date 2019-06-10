package eu.zderadicka.audioserve.net

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.support.v4.media.MediaBrowserCompat
import android.text.Html
import android.text.format.DateUtils
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import com.android.volley.*
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.ImageRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import eu.zderadicka.audioserve.data.*
import eu.zderadicka.audioserve.utils.encodeUri
import eu.zderadicka.audioserve.utils.fromMarkdown
import java.io.File
import java.io.UnsupportedEncodingException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import kotlin.collections.ArrayList


enum class ApiError {
    Network,
    UnauthorizedAccess,
    Server,
    Unknown;

    companion object {
        fun fromResponseError(err: VolleyError): ApiError =
                if (err is TimeoutError || err is NoConnectionError) {
                    Network
                } else if (err is AuthFailureError) {
                    UnauthorizedAccess
                } else if (err is NetworkError || err is ServerError || err is ParseError) {
                    Server
                } else {
                    Unknown
                }
    }
}

private const val LOG_TAG = "ApiClient"

private const val API_REQUEST_TAG = "API"
private const val LOGIN_REQUEST_TAG = "LOGIN"
private const val IMAGE_REQUEST_TAG = "IMAGE"
private const val TEXT_REQUEST_TAG = "TEXT"

private const val RELOGIN_TAG = "RELOGIN"

private const val CACHE_SOFT_TTL: Long = 24 * DateUtils.HOUR_IN_MILLIS
private const val CACHE_MAX_TTL: Long = 7 * DateUtils.DAY_IN_MILLIS

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

internal fun tokenValidityEpoch(token: String): Long {

    val bytes = Base64.decode(token, Base64.DEFAULT)
    val buf = ByteBuffer.allocate(8)
    buf.put(bytes, 32, 8)
    buf.flip()
    val epoch = buf.long
    return epoch
}


internal fun tokenValidityDays(token: String): Int {
    val now = System.currentTimeMillis() / 1000
    val secs = tokenValidityEpoch(token) - now
    if (secs < 0) return 0
    val days = secs / 3600 / 24
    return days.toInt()
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
    private var positionClient: PositionClient? = null
    private var group: String? = null
    private var isOffline = false


    // getApplicationContext() is key, it keeps you from leaking the
    // Activity or BroadcastReceiver if someone passes one in.
    val requestQueue: RequestQueue by lazy {
        Volley.newRequestQueue(context.applicationContext)

    }

    private val handler = Handler(Looper.getMainLooper())

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            "pref_server_url", "pref_shared_secret", "pref_group" -> loadPreferences()
            "pref_offline" -> {
                isOffline = sharedPreferences.getBoolean("pref_offline", false)
                if (isOffline) {
                    positionClient?.close()
                    positionClient = null
                } else {
                    loadPreferences()
                }
            }


        }
    }


    @Synchronized
    fun loadPreferences(cb: ((ApiError?) -> Unit)? = null) {
        val sps = PreferenceManager.getDefaultSharedPreferences(context)
        baseUrl = sps.getString("pref_server_url", "")!!
        group = sps.getString("pref_group", null)
        isOffline = sps.getBoolean("pref_offline", false)
        if (baseUrl.length == 0) {
            Log.w(LOG_TAG, "BaseURL is empty!")
        } else {
            assert(baseUrl.endsWith("/"))
            Log.d(LOG_TAG, "Client base URL is $baseUrl")
        }

        login {
            if (it == null) {
                Log.d(LOG_TAG, "Successfully logged into server")
                initPositionClient(true)
            }
            if (cb != null) {
                cb(it)
            }
        }
    }

    private fun initPositionClient(connected: Boolean) {
        if (token == null) return // do nothing until we have token
        positionClient?.close()
        if (group.isNullOrBlank() || !connected || isOffline) {
            positionClient = null
        } else {
            positionClient = PositionClient(baseUrl, token!!, group)
            positionClient?.open()
        }

    }


    init {
        loadPreferences()
        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(prefsListener)
        ConnectivityMonitor.getInstance(context).addListener {
            Log.d(LOG_TAG, "network connectivity is $it")
            initPositionClient(it)
        }
    }


    val isPositionEnabled: Boolean
        get() = positionClient != null


    fun sendPosition(filePath: String?, position: Double) {
        positionClient?.sendPosition(filePath, position)
    }

    fun queryLastPosition(cb: (MediaBrowserCompat.MediaItem?, PositionClientError?) -> Unit) {
        positionClient.apply {
            if (this == null) {
                cb(null, PositionClientError.NotConnected)
            } else {
                sendQuery(null) { res, err ->
                    cb(if (res?.last != null) positionToMediaItem(res.last) else null, err)
                }
            }
        }
    }

    fun queryPositionForFolderOrMediaId(
            folderId: String?,
            mediaId: String?,
            position: Long?,
            cb: (ArrayList<MediaBrowserCompat.MediaItem>?, PositionClientError?) -> Unit) {
        positionClient.apply {
            if (this == null) {
                cb(ArrayList(), PositionClientError.NotConnected)
            } else {
                val folderPath = mediaId?.let { mediaIdToFolderPath(it) }
                        ?: folderId?.let { folderIdToFolderPath(folderId) }
                sendQuery(folderPath) { res, err ->

                    if (res != null) {

                        getLastRecent(context) {lastRecentLocal ->

                            val list = ArrayList<MediaBrowserCompat.MediaItem>()
                            fun addToList(pos: RemotePosition) {
                                val item = positionToMediaItem(pos)
                                if (item.isNotablyDifferentFrom(mediaId, position) &&
                                        (lastRecentLocal == null || item.isNotablyDifferentFrom(lastRecentLocal))) {
                                    list.add(item)
                                }
                            }

                            res.folder?.also { addToList(it) }
                            res.last?.also { addToList(it) }
                            cb(list, null)


                        }
                    } else {
                        cb(null, err)
                    }

                }
            }
        }
    }

    fun queryPositionForMediaId(mediaId: String,
                                position: Long?,
                                cb: (ArrayList<MediaBrowserCompat.MediaItem>?, PositionClientError?) -> Unit) {
        queryPositionForFolderOrMediaId(null, mediaId, position, cb)
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

    private fun <T> sendRequest(uri: String, forceReload: Boolean, convert: (String) -> T, callback: (T?, ApiError?) -> Unit) {
        val request = ConvertingRequest(uri, convert, callback)
        request.setShouldCache(true)
        if (forceReload) {
            requestQueue.cache.remove(uri)
        }
        request.tag = API_REQUEST_TAG
        addToRequestQueue(request)
    }

    fun uriFromMediaId(mediaId: String, transcode: String? = null): Uri {
        return Uri.parse(baseUrl + mediaId + if (transcode != null) "?${TRANSCODE_QUERY}=$transcode" else "")
    }

    fun loadFolder(folder: String = "", collection: Int, callback: (AudioFolder?, ApiError?) -> Unit) {
        loadFolder(folder, collection, false, null, callback)
    }

    fun loadFolder(folder: String = "", collection: Int, forceReload: Boolean,
                   ordering: String?,
                   callback: (AudioFolder?, ApiError?) -> Unit) {
        var uri = baseUrl
        if (collection > 0) {
            uri += "$collection/"
        }
        uri += folder

        val builder = Uri.parse(uri).buildUpon()
        addOrdering(builder, ordering)
        val folderUri = builder.build().toString()

        sendRequest(folderUri, forceReload, {
            val f = parseFolderfromJson(it, "", folder)
            if (collection > 0) {
                f.collectionIndex = collection
            }
            f
        }, callback)
    }

    fun loadSearch(query: String, collection: Int, callback: (AudioFolder?, ApiError?) -> Unit) {
        loadSearch(query, collection, false, null, callback)
    }

    private fun addOrdering(builder: Uri.Builder, ordering: String?) {
        ordering?.let {
            if (it != "a") {
                builder.appendQueryParameter("ord", it)
            }
        }
    }

    fun loadSearch(query: String, collection: Int, forceReload: Boolean,
                   ordering: String?,
                   callback: (AudioFolder?, ApiError?) -> Unit) {
        var uri = baseUrl
        if (collection > 0) {
            uri += "$collection/"
        }
        uri += "search"

        val queryBuilder = Uri.parse(uri).buildUpon().appendQueryParameter("q", query)
        addOrdering(queryBuilder, ordering)
        val queryUri = queryBuilder.build().toString()
        sendRequest(queryUri, forceReload, {
            val f = parseFolderfromJson(it, "search", "")
            if (collection > 0) {
                f.collectionIndex = collection
            }
            f
        }, callback)

    }

    fun loadRecent(collection: Int, forceReload: Boolean, callback: (AudioFolder?, ApiError?) -> Unit) {
        var uri = baseUrl
        if (collection > 0) {
            uri += "$collection/"
        }
        uri += "recent"
        sendRequest(uri, forceReload, {
            val f = parseFolderfromJson(it, "recent", "")
            if (collection > 0) {
                f.collectionIndex = collection
            }
            f
        }, callback)

    }

    fun loadCollections(callback: (ArrayList<String>?, ApiError?) -> Unit) {
        loadCollections(false, callback)
    }

    fun loadCollections(forceReload: Boolean, callback: (ArrayList<String>?, ApiError?) -> Unit) {
        val uri = baseUrl + "collections"
        sendRequest(uri, forceReload, ::parseCollectionsFromJson, callback)
    }

    fun loadTranscodings(callback: (TranscodingLimits?, ApiError?) -> Unit) {
        loadTranscodings(false, callback)
    }

    fun loadTranscodings(forceReload: Boolean, callback: (TranscodingLimits?, ApiError?) -> Unit) {
        val uri = baseUrl + "transcodings"
        sendRequest(uri, forceReload, ::parseTranscodingsFromJson, callback)
    }

    fun loadPicture(path: String, callback: (Bitmap?, ApiError?) -> Unit) {

        val url = baseUrl + path

        val request = object : ImageRequest(url,
                { callback(it, null) },
                1024,
                1024,
                ImageView.ScaleType.CENTER,
                Bitmap.Config.ARGB_8888,
                {
                    val err = ApiError.fromResponseError(it)
                    callback(null, err)
                }) {
            override fun getHeaders(): MutableMap<String, String> {
                return authorizationHeaders
            }
        }

        request.setShouldCache(true)
        request.tag = IMAGE_REQUEST_TAG
        addToRequestQueue(request)

    }

    fun loadText(path: String, callback: (CharSequence?, ApiError?) -> Unit) {
        val url = baseUrl + path
        val req = object : MyRequest<CharSequence>(url, callback) {
            override fun parseNetworkResponse(response: NetworkResponse?): Response<CharSequence> {

                var parsed = ""
                val contentType = response?.headers?.get("Content-Type") ?: "text/plain"
                val semi = contentType.indexOf(';')
                if (semi > 0) {
                    contentType.substring(0, semi).trim()
                }

                if (response != null) {
                    try {
                        val charset = Charset.forName("UTF-8")
                        parsed = String(response.data, charset)
                    } catch (e: UnsupportedEncodingException) {
                        parsed = String(response.data)
                    }
                }

                if (contentType == "text/html") {
                    @Suppress("DEPRECATION")
                    val html = if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(parsed, Html.FROM_HTML_MODE_LEGACY)
                    else Html.fromHtml(parsed)
                    return Response.success(html, HttpHeaderParser.parseCacheHeaders(response))
                }

                if (contentType == "text/x-markdown") {
                    val md = fromMarkdown(context, parsed)
                    return Response.success(md, HttpHeaderParser.parseCacheHeaders(response))
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

        fun renewToken(token: String) {
            val days = tokenValidityDays(token)
            val dur: Long = if (days > 1) (days - 1) * DAY_IN_MILLIS else 5 * MINUTE_IN_MILLIS
            val renewAt = SystemClock.uptimeMillis() + dur
            handler.postAtTime({ login({}) }, RELOGIN_TAG, renewAt)

        }

        handler.removeCallbacksAndMessages(RELOGIN_TAG)
        val request = object : StringRequest(Method.POST, baseUrl + "authenticate",
                {
                    token = it
                    val pref = PreferenceManager.getDefaultSharedPreferences(context)
                    pref.edit().putString("pref_token", token).apply()
                    Log.d(LOG_TAG, "Login success")
                    cb(null)
                    afterLogin()
                    fireLoginSuccess(it)
                    renewToken(it)
                    loadTranscodings { tr, err ->
                        if (tr != null) {
                            transcodingBitrates = tr
                        }
                    }
                },
                {
                    Log.e(LOG_TAG, "Login error: $it")
                    val pref = PreferenceManager.getDefaultSharedPreferences(context)
                    val savedToken = pref.getString("pref_token", null)
                    if (savedToken != null && tokenValidityDays(savedToken) > 1 && token == null) {
                        Log.i(LOG_TAG, "Reusing saved token as it's still valid")
                        token = savedToken
                    }
                    val err = ApiError.fromResponseError(it)
                    // renew only if not Unauthorized access, because in that case we have probably wrong shared secret
                    if (err != ApiError.UnauthorizedAccess) token?.let { renewToken(it) }
                    cb(err)
                    afterLogin()
                    fireLoginError(err)
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
                return Priority.HIGH
            }
        }

        request.setShouldCache(false)
        request.tag = LOGIN_REQUEST_TAG
        requestQueue.add(request)

    }

    interface LoginListener {
        fun loginSuccess(token: String) {}

        fun loginError(error: ApiError) {}
    }

    private val loginListeners: ArrayList<LoginListener> = ArrayList()
    private fun fireLoginError(error: ApiError) = synchronized(this) {
        loginListeners.forEach { it.loginError(error) }
    }

    private fun fireLoginSuccess(token: String) = synchronized(this) {
        loginListeners.forEach { it.loginSuccess(token) }
    }

    fun addLoginListener(l: LoginListener) = loginListeners.add(l)
    fun removeLoginListener(l: LoginListener) = loginListeners.remove(l)


    private val authorizationHeaders: HashMap<String, String>
        get() {
            val headers = HashMap<String, String>(1)
            if (token != null) {
                headers.put("Authorization", "Bearer $token")
            }
            return headers
        }

    inner abstract class MyRequest<T>(val uri: String, private val callback: (T?, ApiError?) -> Unit)
        : Request<T>(Method.GET, encodeUri(uri),
            {
                Log.e(LOG_TAG, "Network Error $it")
                val err = ApiError.fromResponseError(it)
                if (err == ApiError.UnauthorizedAccess && token == null) {
                    // started offline - try again to log in
                    login {
                        if (it == null && ! group.isNullOrEmpty()) {
                            //we logged in reinit positionClient
                            initPositionClient(true)
                        }
                    }
                }
                callback(null, err)

            }) {

        private var cancelled: Boolean = false
        private val canceledLock = Object()


        override fun getHeaders(): MutableMap<String, String> {
            return authorizationHeaders
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