package eu.zderadicka.audioserve.net

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.support.annotation.RequiresApi
import android.support.v4.app.NotificationCompat
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.METADATA_KEY_MEDIA_ID
import eu.zderadicka.audioserve.data.folderIdFromFileId
import java.util.concurrent.LinkedBlockingDeque

const val DOWNLOAD_ACTION = "eu.zderadicka.audioserve.DOWNLOAD"
const val CANCEL_DOWNLOAD_ACTION = "eu.zderadicka.audioserve.CANCEL_DOWNLOAD"
private const val MSG_PREPARE_DOWNLOAD = 1
private const val MSG_PROCESS_DOWNLOAD = 2
private const val MSG_SCHEDULE_DOWNLOAD = 3
private const val MSG_CANCEL_DOWNLOAD = 4

private const val LOG_TAG = "DownloadService"
private const val CHANNEL_ID = "eu.zderadicka.audioserve.downloads.channel"
private const val NOTIFICATION_ID = 8432


enum class DownloadStatus(val details: Any? = null) {
    Pending,
    Downloading,
    Done,
    Error;

    companion object {
        fun fromCacheStatus(state: FileCache.Status) =
                when (state) {
                    FileCache.Status.NotCached -> Pending
                    FileCache.Status.PartiallyCached -> Downloading
                    FileCache.Status.FullyCached -> Done
                    FileCache.Status.Error -> Error

                }

    }
}

class DownloadService : Service() {


    private lateinit var apiClient: ApiClient
    private lateinit var cacheManager: CacheManager
    private val pendingDownloads = HashMap<String, DownloadStatus>()

    private lateinit var worker: Worker
    private lateinit var workerThread: HandlerThread

    private var pendingCount: Int = 0
    private var doneCount: Int = 0
    private var failedCount: Int = 0


    private val queue = LinkedBlockingDeque<CacheItem>(MAX_CACHE_FILES)
    private val downloadThreads = ArrayList<Thread>()
    private val downloadLoaders = ArrayList<FileLoader>()


    private inner class Worker(looper: Looper) : Handler(looper) {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_PREPARE_DOWNLOAD -> {
                    val mediaId = msg.obj as String
                    onPrepareDownload(mediaId)
                }

                MSG_PROCESS_DOWNLOAD -> {

                    val (downloadId, mediaId) = msg.obj as Pair<String, FileCache.Status>
                    onProcessDownload(downloadId, mediaId)
                }
                MSG_SCHEDULE_DOWNLOAD -> {
                    val items = msg.obj as List<MediaBrowserCompat.MediaItem>
                    onScheduleDownload(items)
                }
                MSG_CANCEL_DOWNLOAD -> {
                    onCancelDownloads()
                }
            }
        }
    }

    private val cacheListener = object : FileCache.Listener {
        override fun onCacheChange(path: String, status: FileCache.Status) {
            val msg = worker.obtainMessage(MSG_PROCESS_DOWNLOAD, Pair(path, status))
            worker.sendMessage(msg)
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("Download Service Worker")
        workerThread.start()
        worker = Worker(workerThread.looper)

        apiClient = ApiClient.getInstance(this)
        cacheManager = CacheManager.getInstance(this)
        cacheManager.addListener(cacheListener)

        pendingCount = 0
        doneCount = 0
        failedCount = 0

        startLoaders()
    }

    private fun startLoaders() {
        if (downloadThreads.size > 0) {
            throw IllegalStateException("Loaders already started")
        }
        val numLoaders = 2
        for (i in 1..numLoaders) {

            val loader = FileLoader(queue = queue, context = this, token = apiClient.token!!)
            val loaderThread = Thread(loader, "Downloader Loader Thread $i")
            loaderThread.isDaemon = true
            loaderThread.start()
            downloadLoaders.add(loader)
            downloadThreads.add(loaderThread)
        }
    }

    private fun stopLoaders() {
        queue.clear()
        downloadLoaders.forEach { it.stop() }
        downloadThreads.forEach { it.interrupt() }
        downloadThreads.forEach{
            it.join(1000)
            if (it.isAlive) {
                Log.w(LOG_TAG, "Download thread is not finished")

            }
        }
        downloadLoaders.clear()
        downloadThreads.clear()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(LOG_TAG, "Intent $intent requesting action ${intent?.action}")
        when (intent?.action) {
            DOWNLOAD_ACTION -> {
                val fileId = intent.extras?.getString(METADATA_KEY_MEDIA_ID)!!
                val msg = worker.obtainMessage()
                msg.obj = fileId
                msg.what = MSG_PREPARE_DOWNLOAD
                msg.arg1 = startId
                worker.sendMessage(msg)
            }
            CANCEL_DOWNLOAD_ACTION -> {
                worker.sendMessage(worker.obtainMessage(MSG_CANCEL_DOWNLOAD))
            }
        }

        return Service.START_STICKY //TODO reconsider as not sticky? Because after restart anyhow there is nothing to do unless we reconstruct pending downloads
    }

    private fun onProcessDownload(mediaId: String, status: FileCache.Status) {
        if (mediaId in pendingDownloads) {
            if (status == FileCache.Status.FullyCached) {
                Log.d(LOG_TAG, "Finished download of $mediaId")
                pendingDownloads.remove(mediaId)
                doneCount+=1
                pendingCount-=1
                fireChange()

            } else if (status == FileCache.Status.Error) {
                failedCount +=1
                pendingCount-=1
                pendingDownloads.remove(mediaId)
                fireChange()
            }else {
                pendingDownloads.put(mediaId, DownloadStatus.fromCacheStatus(status))
                fireChange()
            }

        }

        if (pendingDownloads.size == 0) {
            stopForeground(false)
            stopSelf()
        }
    }

    private fun onCancelDownloads() {
        pendingDownloads.clear()
        queue.clear()
        stopForeground(true)
        stopSelf()
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

    private val notificationManager: NotificationManager by lazy {
        this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val name = "Audioserve Downloads"
            val description = "Audioserve pending downloads"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)

        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }
        val cancelIntent = Intent(this, DownloadService::class.java)
        cancelIntent.action = CANCEL_DOWNLOAD_ACTION
        val cancelPendingIntent = PendingIntent.getService(this,0,cancelIntent,0)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        builder.setContentTitle(getString(R.string.downloading_notification_title, pendingCount))
        builder.setContentText(getString(R.string.download_notification_details, doneCount, failedCount))
        builder.setSmallIcon(R.drawable.ic_download)
        builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
        builder.addAction(R.drawable.ic_cancel,getString(R.string.action_cancel_all),cancelPendingIntent)

        return builder.build()


    }

    private fun onScheduleDownload(itemsToDownload: List<MediaBrowserCompat.MediaItem>) {
        itemsToDownload
                .filter { !cacheManager.isCached(it.mediaId!!) }
                .filter { !(it.mediaId in pendingDownloads)}
                .forEach {

                    val cacheItem = cacheManager.getCacheItemFor(it)
                    try {
                        queue.add(cacheItem)
                        pendingCount+=1
                        pendingDownloads.put(it.mediaId!!, DownloadStatus.Pending)
                    } catch (e: IllegalStateException) {
                        Log.e(LOG_TAG, "Cannot add to queue - it's full")
                    }

                }

                if (pendingCount> 0 ) {
                    fireChange()
                } else {
                    stopSelf()
                }
    }


    override fun onDestroy() {
        Log.d(LOG_TAG, "Destroying Download Service")
        super.onDestroy()
        cacheManager.removeLister(cacheListener)
        workerThread.quit()
        stopLoaders()

        Log.d(LOG_TAG, "Destroyed Download Service")
    }


    inner class LocalBinder: Binder() {
        val service: DownloadService
        get (){
           return  this@DownloadService
        }
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    interface ChangeListener {
        fun onChange()
    }

    private val listeners = HashSet<ChangeListener>()
    fun addListener(l: ChangeListener) = listeners.add(l)
    fun removeListener(l:ChangeListener) = listeners.remove(l)
    fun clearListeners() = listeners.clear()

    fun fireChange() {
        startForeground(NOTIFICATION_ID, createNotification())
        listeners.forEach { it.onChange() }
    }
}
