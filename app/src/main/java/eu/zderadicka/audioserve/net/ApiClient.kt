package eu.zderadicka.audioserve.net

import android.content.Context
import com.android.volley.toolbox.Volley
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Base64
import android.util.Log
import com.android.volley.*
import com.android.volley.toolbox.StringRequest
import eu.zderadicka.audioserve.data.AudioFolder
import eu.zderadicka.audioserve.data.parseCollectionsFromJson
import eu.zderadicka.audioserve.data.parseFolderfromJson
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

//internal fun audioUri(path: String) : Uri {
//    val segments = path.split("/")
//    val builder =  Uri.parse(BASE_URI).buildUpon()
//    builder.appendPath("audio")
//    for (s in segments) builder.appendPath(s)
//    return builder.build()
//}

class ApiClient private constructor(val context: Context) {

    lateinit var baseURL: String
    var token:String? = null


    // getApplicationContext() is key, it keeps you from leaking the
    // Activity or BroadcastReceiver if someone passes one in.
    val requestQueue: RequestQueue by lazy{
            Volley.newRequestQueue(context.getApplicationContext())

        }

    @Synchronized fun loadPreferences() {
        baseURL = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_server_url", "")
        if (baseURL.length == 0) {
            Log.w(LOG_TAG, "BaseURL is empty!")
        } else {
            assert(baseURL.endsWith("/"))
            Log.d(LOG_TAG, "Client base URL is $baseURL")
        }

        login {
            Log.d(LOG_TAG, "Successfully logged into server")
        }
    }

    init {
       loadPreferences()
    }

    fun <T> addToRequestQueue(req: Request<T>) {
        requestQueue.add(req)
    }

    private fun <T>sendRequest(uri:String, convert: (String) -> T, callback: (T?, ApiError?) -> Unit) {
        val request = object:StringRequest(uri,
                {val v = convert(it)
                    callback(v,null)
                },
                {Log.e(LOG_TAG, "Network Error $it")
                    callback(null, ApiError.fromResponseError(it))
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
        }

        addToRequestQueue(request)
    }

    fun uriFromMediaId(mediaId: String): Uri {
        return Uri.parse(baseURL+mediaId)
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

    fun loadCollections(callback: (ArrayList<String>?, ApiError?) -> Unit) {
        val uri = baseURL + "collections"
        sendRequest(uri, ::parseCollectionsFromJson, callback)
    }

    fun login(cb: (String) -> Unit) {
        val request = object: StringRequest(Request.Method.POST,baseURL+"authenticate",
                {
                    cb(it)
                    token = it
                },
                {
                    Log.e(LOG_TAG, "Login error: $it")
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
        addToRequestQueue(request)

    }

    companion object {
        @Volatile private var instance: ApiClient? = null

        @Synchronized @JvmStatic
        fun getInstance(context: Context): ApiClient =
            instance?: synchronized(this) {
                instance = ApiClient(context)
                instance!!
            }

    }
}