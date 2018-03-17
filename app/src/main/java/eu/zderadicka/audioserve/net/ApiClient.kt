package eu.zderadicka.audioserve.net

import android.content.Context
import com.android.volley.toolbox.ImageLoader
import com.android.volley.toolbox.Volley
import android.util.LruCache
import android.graphics.Bitmap
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Log
import com.android.volley.*
import com.android.volley.toolbox.StringRequest
import eu.zderadicka.audioserve.data.AudioFolder
import eu.zderadicka.audioserve.data.parseCollectionsFromJson
import eu.zderadicka.audioserve.data.parseFolderfromJson
import java.io.File
import java.nio.file.Path


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

//internal fun audioUri(path: String) : Uri {
//    val segments = path.split("/")
//    val builder =  Uri.parse(BASE_URI).buildUpon()
//    builder.appendPath("audio")
//    for (s in segments) builder.appendPath(s)
//    return builder.build()
//}

class ApiClient private constructor(val context: Context) {
    private var mRequestQueue: RequestQueue? = null
    val imageLoader: ImageLoader
    lateinit var baseURL: String

    // getApplicationContext() is key, it keeps you from leaking the
    // Activity or BroadcastReceiver if someone passes one in.
    val requestQueue: RequestQueue
        get() {
            if (mRequestQueue == null) {
                mRequestQueue = Volley.newRequestQueue(context.getApplicationContext())
            }
            return mRequestQueue!!
        }

    @Synchronized fun loadPreferences() {
        baseURL = PreferenceManager.getDefaultSharedPreferences(context).getString("pref_server_url", null)
        if (baseURL == null || baseURL.length == 0) {
            Log.w(LOG_TAG, "BaseURL is empty!")
        } else {
            Log.d(LOG_TAG, "Client base URL is $baseURL")
        }
    }

    init {

       loadPreferences()

        imageLoader = ImageLoader(mRequestQueue,
                object : ImageLoader.ImageCache {
                    private val cache = LruCache<String, Bitmap>(20)

                    override fun getBitmap(url: String): Bitmap {
                        return cache.get(url)
                    }

                    override fun putBitmap(url: String, bitmap: Bitmap) {
                        cache.put(url, bitmap)
                    }
                })
    }

    fun <T> addToRequestQueue(req: Request<T>) {
        requestQueue.add(req)
    }

    private fun <T>sendRequest(uri:String, convert: (String) -> T, callback: (T?, ApiError?) -> Unit) {
        val request = StringRequest(uri,
                {val v = convert(it)
                    callback(v,null)
                },
                {Log.e(LOG_TAG, "Network Error $it")
                    callback(null, ApiError.fromResponseError(it))
                }
                )

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

    companion object {
        private var mInstance: ApiClient? = null


        @Synchronized @JvmStatic
        fun getInstance(context: Context): ApiClient {
            if (mInstance == null) {
                mInstance = ApiClient(context)
            }
            return mInstance!!
        }
    }
}