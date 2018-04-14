package eu.zderadicka.audioserve.net

import android.app.DownloadManager
import android.app.IntentService
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import eu.zderadicka.audioserve.data.METADATA_KEY_MEDIA_ID
import eu.zderadicka.audioserve.data.folderIdFromFileId
import java.io.File

const val DOWNLOAD_ACTION = "eu.zderadicka.audioserve.DOWNLOAD"
private const val DOWNLOAD_CACHE_DIR = "audioserve_download"
private const val MSG_PREPARE_DOWNLOAD = 1
private const val MSG_PROCESS_DOWNLOAD = 2
private const val MSG_SCHEDULE_DOWNLOAD = 3
private const val LOG_TAG = "DownloadService"

class DownloadService : Service() {

    private lateinit var downloadManager: DownloadManager
    private lateinit var apiClient: ApiClient
    private lateinit var cacheManager: CacheManager
    private val pendingDownloads = HashMap<Long, String>()
    private lateinit var downloadDir: File
    private lateinit var worker: Worker
    private lateinit var workerThread: HandlerThread

    private fun checkStatus(downloadId: Long): Boolean {

        val query = DownloadManager.Query()
        query.setFilterById(downloadId)
        val c = downloadManager.query(query)
        if (c.moveToNext()) {
            val status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) return true
            val reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON))
            Log.e(LOG_TAG, "Download failed for reason $reason")
        }

        return false
    }

    private inner class Worker(looper: Looper) : Handler(looper) {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_PREPARE_DOWNLOAD -> {
                    val mediaId = msg.obj as String
                    onPrepareDownload(mediaId)
                }

                MSG_PROCESS_DOWNLOAD -> {

                    val (downloadId, mediaId) = msg.obj as Pair<Long,String>
                    onProcessDownload(downloadId, mediaId)
                }
                MSG_SCHEDULE_DOWNLOAD -> {
                    val items = msg.obj as List<MediaBrowserCompat.MediaItem>
                    onScheduleDownload(items)
                }
            }
        }
    }

    private val listener = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(LOG_TAG, "Got broadcast ${intent?.action}")
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val mediaId = pendingDownloads.remove(id)

            if (mediaId != null) {
                val msg = worker.obtainMessage(MSG_PROCESS_DOWNLOAD, Pair(id, mediaId))
                worker.sendMessage(msg)
            }


        }

    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("Download Service Worker")
        workerThread.start()
        worker = Worker(workerThread.looper)
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        apiClient = ApiClient.getInstance(this)
        cacheManager = CacheManager.getInstance(this)
        downloadDir = File(this.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_CACHE_DIR)
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        registerReceiver(listener, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))


    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(LOG_TAG, "Intent $intent requesting action ${intent?.action}")
        if (intent?.action == DOWNLOAD_ACTION) {
            val fileId = intent.extras?.getString(METADATA_KEY_MEDIA_ID)!!
            val msg = worker.obtainMessage()
            msg.obj = fileId
            msg.what = MSG_PREPARE_DOWNLOAD
            msg.arg1 = startId
            worker.sendMessage(msg)
        }

        return Service.START_STICKY //TODO reconsider as not sticky? Because after restart anyhow there is nothing to do unless we reconstruct pending downloads
    }

    private fun onProcessDownload(downloadId: Long, mediaId: String) {
        Log.d(LOG_TAG, "Finished download of $mediaId")
        if (checkStatus(downloadId)) {
            val f = File(downloadDir, mediaId)
            if ( f.exists()) {

                Log.d(LOG_TAG, "Successful download of $mediaId")
                val res = cacheManager.injectFile(mediaId, f)
                if (!res) {
                    Log.e(LOG_TAG, "Download injection into cache failed")
                }
            } else {
                Log.e(LOG_TAG, "Downloaded file does not exist")
            }

        }

        downloadManager.remove(downloadId)
        if (pendingDownloads.size == 0) {
            stopSelf()
        }
    }

    private fun onPrepareDownload(fileId: String) {

        val folderId = folderIdFromFileId(fileId)

        apiClient.loadFolder(folderId!!, 0) { data, err ->
            if (err != null) {
                Log.e(LOG_TAG, "Server error $err")
                return@loadFolder
            }
            Log.d(LOG_TAG, "Received data from server on thread ${Thread.currentThread().name}")
            val items = data!!.getPlayableItems(cacheManager)
            val idx = items.indexOfFirst {
                it.mediaId == fileId
            }
            if (idx < 0) return@loadFolder
            val itemsToDownload = items
                    .drop(idx)

            val msg = worker.obtainMessage(MSG_SCHEDULE_DOWNLOAD, itemsToDownload)
            worker.sendMessage(msg)


        }
    }

    private fun onScheduleDownload(itemsToDownload: List<MediaBrowserCompat.MediaItem>) {
        itemsToDownload
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

                pendingDownloads.put(id, it.mediaId!!)
            }
    }


    override fun onDestroy() {
        Log.d(LOG_TAG, "Destroying Download Service")
        super.onDestroy()
        unregisterReceiver(listener)
        workerThread.quit()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}