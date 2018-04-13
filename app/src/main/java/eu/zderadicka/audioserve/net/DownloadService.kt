package eu.zderadicka.audioserve.net

import android.app.DownloadManager
import android.app.IntentService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.ConditionVariable
import android.os.Environment
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import eu.zderadicka.audioserve.data.METADATA_KEY_MEDIA_ID
import eu.zderadicka.audioserve.data.folderIdFromFileId
import java.io.File

const val DOWNLOAD_ACTION = "eu.zderadicka.audioserve.DOWNLOAD"
private const val DOWNLOAD_CACHE_DIR = "audioserve_download"

private const val LOG_TAG = "DownloadService"

class DownloadService : IntentService("Download Service") {

    private lateinit var downloadManager: DownloadManager
    private lateinit var apiClient: ApiClient
    private lateinit var cacheManager: CacheManager
    private val waitForEnqueue = ConditionVariable()
    private val pendingDownloads = HashMap<Long,String>()
    private lateinit var downloadDir: File

    private val listener = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(LOG_TAG, "Got broadcast ${intent?.action}")
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val mediaId = pendingDownloads.remove(id)

            if (mediaId != null) {
                Log.d(LOG_TAG, "Downloaded $mediaId")
            }
            
            if (pendingDownloads.size ==0)  {
                waitForEnqueue.open()
            }
        }

    }

    override fun onCreate() {
        super.onCreate()

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        apiClient = ApiClient.getInstance(this)
        cacheManager = CacheManager.getInstance(this)
        downloadDir = File(this.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_CACHE_DIR)
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        registerReceiver(listener, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    override fun onHandleIntent(intent: Intent?) {
        Log.d(LOG_TAG, "Requesting action ${intent?.action}")
        if (intent?.action == DOWNLOAD_ACTION) {
            val fileId = intent.extras?.getString(METADATA_KEY_MEDIA_ID)!!
            val folderId = folderIdFromFileId(fileId)

            apiClient.loadFolder(folderId!!, 0) { data, err ->
                if (err != null) {
                    Log.d(LOG_TAG, "Server error $err")
                    return@loadFolder
                }
                val items = data!!.getPlayableItems(cacheManager)
                val idx = items.indexOfFirst {
                    it.mediaId == fileId
                }
                if (idx < 0) return@loadFolder
                val itemsToDownload = items
                        .drop(idx)
                        .filter { !cacheManager.isCached(it.mediaId!!) }
                        .forEach {
                            val transcode = cacheManager.shouldTranscode(it)
                            val transcodeQuery = if (transcode != null) {
                                "?$TRANSCODE_QUERY=$transcode"
                            } else {
                                ""
                            }

                            val url = apiClient.baseURL + it.mediaId + transcodeQuery
                            val destination = File(downloadDir, it.mediaId)
                            Log.d(LOG_TAG, "Will download $url")
                            val request = DownloadManager.Request(Uri.parse(url))
                            request.setDestinationUri(Uri.parse(destination.toURI().toString()))
                            request.addRequestHeader("Authorization", "Bearer ${apiClient.token}")
                            request.setVisibleInDownloadsUi(true);
                            request.setTitle(destination.name)
                            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                            request.setAllowedOverRoaming(false)

                            val id = downloadManager.enqueue(request)

                            pendingDownloads.put(id,it.mediaId!!)
                        }
                waitForEnqueue.close()
            }
            waitForEnqueue.block()
        }
    }

    override fun onDestroy() {
        Log.d(LOG_TAG, "Destroying Download Service")
        super.onDestroy()
        unregisterReceiver(listener)
    }
}