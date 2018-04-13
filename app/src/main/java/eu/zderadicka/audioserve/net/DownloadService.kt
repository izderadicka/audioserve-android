package eu.zderadicka.audioserve.net

import android.app.DownloadManager
import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.os.ConditionVariable
import android.util.Log
import eu.zderadicka.audioserve.data.METADATA_KEY_MEDIA_ID

const val DOWNLOAD_ACTION = "eu.zderadicka.audioserve.DOWNLOAD"

private const val LOG_TAG ="DownloadService"

class DownloadService :IntentService("Download Service") {

    private lateinit var downloadManager: DownloadManager
    private lateinit var apiClient: ApiClient
    private val waitForEnqueue = ConditionVariable()

    override fun onCreate() {
        super.onCreate()

        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        apiClient = ApiClient.getInstance(this)
    }
    override fun onHandleIntent(intent: Intent?) {
        Log.d(LOG_TAG, "Requesting action ${intent?.action}")
        if (intent?.action == DOWNLOAD_ACTION) {
            val folderId = intent.extras?.getString(METADATA_KEY_MEDIA_ID)

            apiClient.loadFolder(folderId!!,0){data,err ->
                if (err != null) {
                    Log.d(LOG_TAG, "Server error $err")
                    return@loadFolder
                }
                //for (item in data.getPlayableItems())
            }

        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}