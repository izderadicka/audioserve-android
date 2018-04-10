package eu.zderadicka.audioserve.net

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Base64
import android.util.Log
import com.android.volley.*
import com.android.volley.toolbox.HttpHeaderParser
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
        fun fromResponseError(err: VolleyError):ApiError =
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
private const val CACHE_SOFT_TTL: Long = 5 * 60 * 1000
private const val CACHE_MAX_TTL: Long = 24 * 3600 * 1000

internal fun encodeSecret(secret: String):String {
    val secretBytes = secret.toByteArray(Charset.forName("UTF-8"))
    val randBytes = ByteArray(32)
    val rng = SecureRandom()
    rng.nextBytes(randBytes)
    val concatedBytes = secretBytes + randBytes
    val md = MessageDigest.getInstance("SHA-256")
    md.update(concatedBytes)
    val digest = md.digest()
    val res = Base64.encodeToString(randBytes,Base64.NO_WRAP) + "|" +
            Base64.encodeToString(digest, Base64.NO_WRAP)

    return res

}

data class TranscodingLimits(var low:Int, var medium:Int, var high: Int)

class ApiClient private constructor(val context: Context) {

    lateinit var baseURL: String
    var token:String? = null
    // as login is asynchronous need to handle cases where fists requests are send before login
    // but also consider offline case when login fails always - so store first request here and send
    // after login success or failure
    var loginDone = false
    val unsentRequests: ArrayList<Request<*>> = ArrayList()



    // getApplicationContext() is key, it keeps you from leaking the
    // Activity or BroadcastReceiver if someone passes one in.
    val requestQueue: RequestQueue by lazy{
            Volley.newRequestQueue(context.getApplicationContext())

        }

    @Synchronized fun loadPreferences(cb: ((ApiError?) -> Unit)? = null) {
        baseURL = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_server_url", "")
        if (baseURL.length == 0) {
            Log.w(LOG_TAG, "BaseURL is empty!")
        } else {
            assert(baseURL.endsWith("/"))
            Log.d(LOG_TAG, "Client base URL is $baseURL")
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

    private fun <T>sendRequest(uri:String, convert: (String) -> T, callback: (T?, ApiError?) -> Unit) {
        val request = object:StringRequest(uri,
                {val v = convert(it)
                    callback(v,null)
                },
                {Log.e(LOG_TAG, "Network Error $it")
                    var err = ApiError.fromResponseError(it)
                    if (err == ApiError.UnauthorizedAccess && token==null) {
                        // started offline - try again to log in
                        login{}
                    }
                    callback(null, err)
                }
                )
        {
            override fun getHeaders(): MutableMap<String, String> {
                val headers = HashMap<String, String>(1)
                if (token != null) {
                    headers.put("Authorization", "Bearer $token")
                }
                return headers
            }

            override  fun parseNetworkResponse(response: NetworkResponse?): Response<String> {
                var parsed: String
                if (response == null || response.data == null || response.data.size == 0) {
                    return Response.error(VolleyError("Empty response"))
                }
                try {
                    val charset = Charset.forName(HttpHeaderParser.parseCharset(response.headers))
                    parsed = String(response.data, charset)
                } catch (e: UnsupportedEncodingException) {
                    parsed = String(response.data)
                }
                val cacheEntry = HttpHeaderParser.parseCacheHeaders(response)
                val now = System.currentTimeMillis()
                cacheEntry.softTtl = now + CACHE_SOFT_TTL
                cacheEntry.ttl = now + CACHE_MAX_TTL

                return Response.success(parsed, cacheEntry)
            }
        }

        request.setShouldCache(true)
        request.tag = API_REQUEST_TAG
        addToRequestQueue(request)
    }

    fun uriFromMediaId(mediaId: String, transcode: String? = null): Uri {
        return Uri.parse(baseURL+mediaId+ if (transcode != null) "?${TRANSCODE_QUERY}=$transcode" else "")
    }


    fun loadFolder(folder: String = "", collection: Int, callback: (AudioFolder?, ApiError?) -> Unit) {
        var uri = baseURL
        if (collection>0) {
            uri+="$collection/"
        }
        uri+= folder

        sendRequest(uri, {
            val f = parseFolderfromJson(it,"",folder)
            if (collection>0) {
                f.collectionIndex = collection
            }
            f
        }, callback)
    }

    fun loadSearch(query: String, collection: Int, callback: (AudioFolder?, ApiError?) -> Unit) {
        var uri = baseURL
        if (collection>0) {
            uri+="$collection/"
        }
        uri+= "search"

        var queryUri = Uri.parse(uri).buildUpon().appendQueryParameter("q", query).build()
        sendRequest(queryUri.toString(), {
            val f = parseFolderfromJson(it,"search","")
            if (collection>0) {
                f.collectionIndex = collection
            }
            f
        }, callback)

    }

    fun loadCollections(callback: (ArrayList<String>?, ApiError?) -> Unit) {
        val uri = baseURL + "collections"
        sendRequest(uri, ::parseCollectionsFromJson, callback)
    }

    fun loadTranscodings(callback: (TranscodingLimits?, ApiError?) -> Unit) {
        val uri = baseURL + "transcodings"
        sendRequest(uri, ::parseTranscodingsFromJson, callback)
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
        val request = object: StringRequest(Request.Method.POST,baseURL+"authenticate",
                {
                    cb(null)
                    token = it
                    afterLogin()
                    loadTranscodings{ tr, err ->
                        if (tr!=null) {
                            transcodingBitrates=tr
                        }
                    }
                },
                {
                    Log.e(LOG_TAG, "Login error: $it")
                    cb(ApiError.fromResponseError(it))
                    afterLogin()
                })
        {
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

    companion object {
        @Volatile private var instance: ApiClient? = null

        @Synchronized fun clearCache(context: Context) {
            //FIXME - this is a complete adhoc hack per now
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
                if (instance ==null) instance = ApiClient(context)
                    instance!!

            }

        var transcodingBitrates = TranscodingLimits(32, 48, 64)
        private set
    }
}