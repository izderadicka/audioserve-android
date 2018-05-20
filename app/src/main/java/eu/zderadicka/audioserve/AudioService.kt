package eu.zderadicka.audioserve

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.support.v4.content.ContextCompat
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaBrowserServiceCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.YEAR_IN_MILLIS
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.DefaultPlaybackController
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.DynamicConcatenatingMediaSource
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import eu.zderadicka.audioserve.data.*
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.net.ApiError
import eu.zderadicka.audioserve.net.CacheManager
import eu.zderadicka.audioserve.net.FileCache
import eu.zderadicka.audioserve.notifications.NotificationsManager
import eu.zderadicka.audioserve.utils.cancelSleepTimer
import java.io.File
import kotlin.math.min

private const val LOG_TAG = "audioserve-service"
private const val TIME_AFTER_WHICH_NOT_RESUMING = 20 * 60 * 1000
private const val FF_MS = 30 * 1000L
private const val REWIND_MS = 15 * 1000L
const val MEDIA_FULLY_CACHED = "eu.zderadicka.audioserve.FULLY_CACHED"
const val MEDIA_CACHE_DELETED = "eu.zderadicka.audioserve.CACHE_DELETED"
const val PLAYER_NOT_READY = "eu.zderadicka.audioserve.PLAYER_NOT_READY"

const val AUDIOSERVICE_ACTION_PAUSE = "eu.zderadicka.audioserve.ACTION_PAUSE"
const val AUDIOSERVICE_FORCE_RELOAD = "eu.zderadicka.audioserve.FORCE_RELOAD"
const val AUDIOSERVICE_DONT_PRELOAD_LATEST = "eu.zderadicka.audioserve.NO_PRELOAD_LATEST"
private const val AUDIOSERVICE_ACTION_SELF_START = "eu.zderadicka.audioserve.SELF_START"
private const val PAUSE_DELAYED_TASKS = "pause_tasks"


private class ResultWrapper(val result: MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>) {
    private var resultSent = false

    fun detach() {
        result.detach()
    }

    @Synchronized
    fun sendResult(res: List<MediaBrowserCompat.MediaItem>?) {
        if (!resultSent) {
            result.sendResult(res)
            resultSent = true
        } else {
            Log.w(LOG_TAG, "Second attempt to send result - probably from cache refresh")
        }
    }

    val isDone: Boolean
        get() {
            return resultSent
        }
}


class AudioService : MediaBrowserServiceCompat() {
    lateinit var session: MediaSessionCompat
    private lateinit var connector: MediaSessionConnector
    lateinit var player: ExoPlayer
    lateinit var notifManager: NotificationsManager
    private var currentFolder : List<MediaItem> = ArrayList<MediaItem>()
    private var currentMediaItem: MediaItem? = null
    private var lastKnownPosition = 0L
    private var lastPositionUpdateTime = 0L
    private var previousPositionUpdateTime = 0L
    private var playQueue: MutableList<MediaItem> = ArrayList<MediaItem>()
    private lateinit var apiClient: ApiClient
    private var preloadFiles: Int = 2
    private var seekAfterPrepare: Long? = null
    private var deletePreviousQueueItem: Int = -1 // delete previous queue Item
    private var isOffline: Boolean = false
    private val scheduler = Handler()
    private var enableAutoRewind = false

    private val playerController = object : DefaultPlaybackController(REWIND_MS, FF_MS, MediaSessionConnector.DEFAULT_REPEAT_TOGGLE_MODES) {

        private val am: AudioManager by lazy {
            this@AudioService.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        private var needResume = false
        private var timeInterrupted = 0L
        private var focusCallback: AudioManager.OnAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.d(LOG_TAG, "Audio Focus changed to $focusChange")
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (needResume && System.currentTimeMillis() - timeInterrupted <= TIME_AFTER_WHICH_NOT_RESUMING) {
                        onPlay(player)
                    }
                    needResume = false
                }
                else -> {
                    // resume only if it was playing
                    needResume = session.controller.playbackState.state == PlaybackStateCompat.STATE_PLAYING
                    super.onPause(player)
                    timeInterrupted = System.currentTimeMillis()
                }
            }
        }

        init {
            Log.d(LOG_TAG, "Initializing PlayerController")

        }

        override fun onPlay(player: Player) {
            Log.d(LOG_TAG, "Playback started")
            val autoRewind = calcAutoRewind()
            if (autoRewind>100) {
                super.onSeekTo(player, player.currentPosition - autoRewind)
            }
            if (requestAudioFocus()) {
                super.onPlay(player)
            }
        }

        private var audioRequest: AudioFocusRequest? = null

        fun requestAudioFocus(): Boolean {
            val result = if (Build.VERSION.SDK_INT >= 26) {
                audioRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setOnAudioFocusChangeListener(focusCallback).build()
                am.requestAudioFocus(audioRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(focusCallback, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        override fun onPause(player: Player) {
            super.onPause(player)
            pauseMe()
            needResume = false
        }

        override fun onStop(player: Player) {
            Log.d(LOG_TAG, "Stoping play")
            deletePreviousQueueItem = -1
            seekAfterPrepare = null
            needResume = false
            super.onStop(player)
            session.isActive = false
            stopMe()
            if (Build.VERSION.SDK_INT >= 26) {
                audioRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusCallback)
            }
        }
    }

    private fun calcAutoRewind():Int {
        if (!enableAutoRewind) return 0
        val prevPos = if (previousPositionUpdateTime >0) previousPositionUpdateTime else lastPositionUpdateTime
        previousPositionUpdateTime = 0
        val updatedBefore = System.currentTimeMillis() - prevPos
        Log.d(LOG_TAG,"Determine autorewind for item ${currentMediaItem}, updated before${updatedBefore}")
        return if  (updatedBefore < 5* MINUTE_IN_MILLIS) 2000
            else if (updatedBefore < 30 * MINUTE_IN_MILLIS) 15_000
            else if (updatedBefore < YEAR_IN_MILLIS) 30_000
            else 0
    }

    private fun findIndexInFolder(mediaId: String): Int {
        return currentFolder.indexOfFirst { it.mediaId == mediaId }
    }

    private fun findIndexInQueue(mediaId: String?): Int {
        if (mediaId == null) return -1
        return playQueue.indexOfFirst { it.mediaId == mediaId }
    }



    private val preparer = object : MediaSessionConnector.PlaybackPreparer {
        override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onCommand(player: Player?, command: String?, extras: Bundle?, cb: ResultReceiver?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun getSupportedPrepareActions(): Long {
            return ACTION_PREPARE_FROM_MEDIA_ID or ACTION_PLAY_FROM_MEDIA_ID
        }

        override fun getCommands(): Array<String>? {
            return null
        }

        var sourceFactory: ExtractorMediaSource.Factory? = null
        var currentSourcesList: DynamicConcatenatingMediaSource? = null

        fun initSourceFactory(cm: CacheManager, token: String? = null) {
            if (sourceFactory!= null) return

            if (!isOffline && token == null) {
                Log.e(LOG_TAG, "Invalid state - Api client is not initialized")
                return
            }
            if (token != null) {
                cm.startCacheLoader(token)
            }
            sourceFactory = ExtractorMediaSource.Factory(cm.sourceFactory)

        }

        override fun onPrepareFromMediaId(mediaId: String, extras: Bundle?) {
            if (!session.isActive) {
                session.isActive = true
            }
            Log.d(LOG_TAG, "Preparing mediaId $mediaId")
            initSourceFactory(cacheManager, apiClient.token)

            val folderPosition = findIndexInFolder(mediaId)


            deletePreviousQueueItem = -1
            if (folderPosition >= 0 && sourceFactory!=null) {
                val factory = sourceFactory?: throw IllegalStateException("Session not ready")

                playQueue = currentFolder.slice(folderPosition until currentFolder.size).toMutableList()
                cacheManager.resetLoading(* playQueue.slice(0 until min(preloadFiles+1, playQueue.size))
                        .map{it.mediaId!!}.toTypedArray())
                cacheManager.ensureCaching(currentFolder[folderPosition])
                val ms = playQueue.map {
                    val transcode = cacheManager.shouldTranscode(it)
                    factory.createMediaSource(apiClient.uriFromMediaId(it.mediaId!!, transcode))
                }
                val source  = DynamicConcatenatingMediaSource()
                source.addMediaSources(ms)
                currentSourcesList =source

                player.prepare(source)
                if (player.playWhenReady) {
                    playerController.requestAudioFocus()
                }
                val seekTo = extras?.getLong(METADATA_KEY_LAST_POSITION)
                if (seekTo != null && seekTo > 1000) {
                    seekAfterPrepare = seekTo
                }

                previousPositionUpdateTime = extras?.getLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP)?:0


            } else {
                //source = factory.createMediaSource(apiClient.uriFromMediaId(mediaId))
                Log.e(LOG_TAG, "Folder is not synched or offline- cannot played")
                playQueue.clear()
                currentSourcesList = null

                session.sendSessionEvent(PLAYER_NOT_READY, null)
                stopMe()
            }

        }

        fun duplicateInQueue(mediaId: String, cb: (queueIndex:Int, playerPosition:Long) -> Unit){
            if (currentSourcesList != null && currentMediaItem != null && currentMediaItem?.mediaId == mediaId) {
                val mi = currentMediaItem!!
                val playerPos = player.currentWindowIndex
                val queuePos = findIndexInQueue(mi.mediaId!!)
                if (playerPos == queuePos) {
                    val duration = mi.description.extras?.getLong(METADATA_KEY_DURATION)?:0L
                    val pos = player.currentPosition
                    if (duration -pos > 5000) {
                        playQueue.add(queuePos+1, mi)
                        currentSourcesList!!.addMediaSource(playerPos+1,
                                sourceFactory!!.createMediaSource(apiClient.uriFromMediaId(mi.mediaId!!)))
                        {cb(queuePos, pos)}
                    }
                }
            }


        }

        fun deleteInQueue() {
            val pos = deletePreviousQueueItem
            deletePreviousQueueItem = -1
            if (currentSourcesList != null && pos >=0 && pos == player.currentWindowIndex-1 && pos < playQueue.size -1) {
                //Double check that we have same mediaIs on both positions
                val prev = playQueue[pos]
                val curr = playQueue[pos+1]
                if (curr.mediaId == prev.mediaId) {
                    Log.d(LOG_TAG, "Deleting previous item in queue at $pos")
                    playQueue.removeAt(pos)
                    currentSourcesList?.removeMediaSource(pos)
                }
            }

        }

        override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onPrepare() {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

    }

    private val cacheListener: FileCache.Listener = object: FileCache.Listener {
        override fun onCacheChange(path: String, status: FileCache.Status) {

            fun send_event(cached: Boolean) {
                val b =Bundle()
                b.putString(METADATA_KEY_MEDIA_ID, path)
                session.sendSessionEvent(if (cached) MEDIA_FULLY_CACHED else MEDIA_CACHE_DELETED, b)
                val folderPosition = findIndexInFolder(path)
                if (folderPosition>=0) {
                    currentFolder[folderPosition].description.extras?.putBoolean(METADATA_KEY_CACHED, cached)
                }
            }
            Log.d(LOG_TAG,"Cache change on $path to ${status.name}")
            if (status == FileCache.Status.FullyCached) {
                send_event(true)
                preparer.duplicateInQueue(path){ idx, pos ->
                    Log.d(LOG_TAG, "Duplicted $idx,$pos, current player pos ${player.currentPosition}")
                    player.seekTo(idx+1, pos) //TODO find best way for gapless seek +200 looked like better when emulated
                    deletePreviousQueueItem = idx

                }


            } else if (status == FileCache.Status.NotCached) {
                send_event(false)
            }
        }

    }

    private val sessionCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
            super.onPlaybackStateChanged(state)
            Log.d(LOG_TAG, "Playback state changed in service ${state}")
            when (state.state) {
                PlaybackStateCompat.STATE_PLAYING -> {
                    notifManager.sendNotification(true)
                }
                PlaybackStateCompat.STATE_PAUSED -> {
                    notifManager.sendNotification()
                }
            }
            if (seekAfterPrepare == null) {
                lastKnownPosition = state.position
                lastPositionUpdateTime = System.currentTimeMillis() //state.lastPositionUpdateTime
                if (currentMediaItem!= null && session.controller?.metadata?.description?.mediaId == currentMediaItem?.mediaId) {
                    updateCurrentMediaItemTime()
                }
            }

            if ((state.state == PlaybackStateCompat.STATE_PAUSED || state.state == PlaybackStateCompat.STATE_PLAYING)
                    && seekAfterPrepare!=null) {
                val seekTo = seekAfterPrepare!!
                seekAfterPrepare = null
                Log.d(LOG_TAG, "Seeking to previous position $seekTo")
                session.controller.transportControls.seekTo(seekTo)

            } else if ((state.state == PlaybackStateCompat.STATE_ERROR)
                    && seekAfterPrepare!=null) {
                seekAfterPrepare= null
            }

            if ((state.state == PlaybackStateCompat.STATE_PAUSED || state.state == PlaybackStateCompat.STATE_PLAYING)
                    && deletePreviousQueueItem>=0 && player.currentWindowIndex == deletePreviousQueueItem+1) {
                preparer.deleteInQueue()
            }

            if (state.state != PlaybackStateCompat.STATE_PAUSED) {
                scheduler.removeCallbacksAndMessages(PAUSE_DELAYED_TASKS)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            super.onMetadataChanged(metadata)
            if (metadata == null || metadata.description ==null || metadata.description.mediaId ==null) return
            val idx = findIndexInQueue(metadata.description.mediaId!!)
            if (idx>=0) {

                //initiate preload of next x items
                for (i in idx until min(idx+preloadFiles+1, playQueue.size)) {
                    if (! (playQueue[i].description.extras?.getBoolean(METADATA_KEY_CACHED)?:false)) {
                        cacheManager.ensureCaching(playQueue[i])
                    }
                }

                //set current item
                val item = playQueue[idx]
                if (item != currentMediaItem) {
                    val oldItem = currentMediaItem
                    currentMediaItem = item


                    if (oldItem!= null) {
                        val oldPath = File(oldItem.mediaId).parent
                        val newPath = File(item.mediaId).path

                        if (oldPath != newPath) {
                            saveRecent(oldItem, applicationContext)
                        }
                    }
                }
            }

        }
    }

    inner class QueueManager(session: MediaSessionCompat) : TimelineQueueNavigator(session) {
        override fun getMediaDescription(windowIndex: Int): MediaDescriptionCompat? {
            if (windowIndex >= 0 && windowIndex < playQueue.size) {
                return playQueue.get(windowIndex).description
            } else {
                val builder = MediaDescriptionCompat.Builder()
                builder.setMediaId("__Unknown")
                builder.setTitle("Unknown")
                return builder.build()
                //throw IllegalArgumentException("windowIndex is $windowIndex, but queue size is ${playQueue.size}")
            }
        }

        override fun onSkipToNext(player: Player?) {
            val idx = findIndexInQueue(currentMediaItem?.mediaId)
            if (idx>=0 && idx+1< playQueue.size) {
                val nextItem = playQueue[idx+1]
                if (!cacheManager.isCached(nextItem.mediaId!!)) {
                    cacheManager.resetLoading(nextItem.mediaId!!)
                }
            }
            super.onSkipToNext(player)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener {
        sharedPreferences, key ->
        when (key) {
            "pref_server_url", "pref_shared_secret" -> apiClient.loadPreferences()
            "pref_preload" -> {
                preloadFiles = sharedPreferences.getString("pref_preload","2").toInt()
            }
            "pref_cache_location" -> {
                preparer.sourceFactory = null
            }
            "pref_offline" -> {
                isOffline = sharedPreferences.getBoolean("pref_offline", false)
                preparer.sourceFactory = null
            }
            "pref_autorewind" -> {
                enableAutoRewind = sharedPreferences.getBoolean("pref_autorewind", false)
            }
        }
    }

    private val loginListener = object:ApiClient.LoginListener {
        override fun loginSuccess(token: String) {
            cacheManager.updateToken(token)
        }
    }

    private lateinit var queueManager: QueueManager
    private lateinit var cacheManager: CacheManager

    companion object {
        const val MEDIA_ROOT_TAG = "__AUDIOSERVE_ROOT__"
        const val EMPTY_ROOT_TAG = "__AUDIOSERVE_EMPTY__"
        const val RECENTLY_LISTENED_TAG = "__AUDIOSERVE_RECENT"
        const val COLLECTION_PREFIX = "__COLLECTION_"
        const val SEARCH_PREFIX = "__AUDIOSERVE_SEARCH_"
        const val ITEM_IS_COLLECTION = "is-collection"
    }

    override fun onCreate() {
        super.onCreate()
        preloadFiles = PreferenceManager.getDefaultSharedPreferences(this).getString("pref_preload","2").toInt()
        isOffline = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_offline",false)
        enableAutoRewind = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_autorewind", false)
        session = MediaSessionCompat(this, LOG_TAG)
        session.controller.registerCallback(sessionCallback)
        player = ExoPlayerFactory.newSimpleInstance(this, DefaultTrackSelector())

        queueManager = QueueManager(session)

        sessionToken = session.sessionToken
        notifManager = NotificationsManager(this)
        connector = MediaSessionConnector(session, playerController)
        cacheManager = CacheManager.getInstance(this)
        cacheManager.addListener(cacheListener)

        connector.setPlayer(player, preparer)
        connector.setQueueNavigator(queueManager)

        //TODO - implement error messages as per
        /*
        messageProvider = new MediaSessionConnector.ErrorMessageProvider() {
  @Override
  public Pair<Integer, String> getErrorMessage(
      ExoPlaybackException playbackException) {
    return getHumanReadableError(playbackException);
  }
}

mediaSessionConnector.setErrorMessageProvider(messageProvider);
         */

        apiClient = ApiClient.getInstance(this)
        apiClient.addLoginListener(loginListener)

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(prefsListener)


        Log.d(LOG_TAG, "Audioservice created")

    }


    var isStartedInForeground = false
    private var isStarted = false

    fun startMe(notification: Notification) {

        if (!isStarted) {
            val intent = Intent(this, AudioService::class.java)
            intent.action = AUDIOSERVICE_ACTION_SELF_START
            ContextCompat.startForegroundService(this, intent)
            isStarted = true
        }
        startForeground(NotificationsManager.NOTIFICATION_ID, notification)
        isStartedInForeground = true
    }

    fun stopMe() {
        cancelSleepTimer(this)
        saveCurrentlyListened()
        if (isStartedInForeground) {
            stopForeground(true)
        } else {
            notifManager.deleteNotification()
        }
        isStartedInForeground = false
        stopSelf()
        isStarted = false

    }

    fun pauseMe() {
        cancelSleepTimer(this)
        scheduler.postAtTime({
            saveCurrentlyListened()
        },
                PAUSE_DELAYED_TASKS,
                SystemClock.uptimeMillis()+ 10_000)
        Log.d(LOG_TAG, "Pausing service - stopForeground")
        //TODO - consider if we really want to stop foreground - as playback service might get recycled
        stopForeground(false)
        isStartedInForeground = false

    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(LOG_TAG, "Started Audio Service with action ${intent?.action}")
        if (intent == null) {
            Log.w(LOG_TAG, "Intent, null but always should start with intent")

        } else {
            when (intent.action) {
                AUDIOSERVICE_ACTION_PAUSE -> {
                    if (isStarted) {
                        player.playWhenReady = false
                        pauseMe()
                    } else {
                        stopSelf(startId)
                    }
                }
            }
        }

        return Service.START_NOT_STICKY
    }

    private fun saveCurrentlyListened() {
        if (currentMediaItem != null && lastPositionUpdateTime>0) {
            //update it with last known possition
            currentMediaItem?.description?.extras?.putLong(METADATA_KEY_LAST_POSITION, lastKnownPosition)
            currentMediaItem?.description?.extras?.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP, lastPositionUpdateTime)
            saveRecent(currentMediaItem!!, applicationContext)
            Log.d(LOG_TAG, "Save lastly listened item ${currentMediaItem?.mediaId} pos ${lastKnownPosition} time ${lastPositionUpdateTime}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {
            saveCurrentlyListened()
            session.isActive = false
            session.release()
            player.release()

            cacheManager.removeLister(cacheListener)
            cacheManager.stopCacheLoader()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error while destroying AudioService")
        }

        Log.d(LOG_TAG, "Audioservice destroyed")
    }

    private fun updateCurrentMediaItemTime(){
        if (currentMediaItem!=null) {
            val mi = currentMediaItem!!
            mi.description.extras?.putLong(METADATA_KEY_LAST_POSITION, lastKnownPosition)
            mi.description.extras?.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP, lastPositionUpdateTime)
        }
    }

    private val SEARCH_RE = Regex("""^(\d+)_(.*)""")

    override fun onLoadChildren(parentId: String, result: Result<List<MediaItem>>) {
        val options = Bundle()
        onLoadChildren(parentId, result, options)
    }

    override fun onLoadChildren(parentId: String, result:Result<List<MediaItem>>, options: Bundle) {
        if (parentId == RECENTLY_LISTENED_TAG) {
            result.detach()
            Log.d(LOG_TAG, "Requesting list of recently listened items")

            val list = ArrayList<MediaItem>()
            if (currentMediaItem != null) {
                val mi = currentMediaItem!!
                mi.description.extras?.putBoolean(METADATA_KEY_IS_BOOKMARK, true)
                updateCurrentMediaItemTime()
                list.add(mi)
            }

            Thread({

                var path: String? = null
                if (currentMediaItem != null) {
                    path = File(currentMediaItem!!.mediaId).parent
                }
                    list.addAll(getRecents(applicationContext,path ))
                    result.sendResult(list)
                }).start()






        } else {
            if (isOffline) {
                onLoadChildrenOffline(parentId, result, options)
            } else {
                onLoadChildrenOnline(parentId, result, options)
            }
        }
    }

    private fun onLoadChildrenOffline(parentId: String, result: Result<List<MediaItem>>, options: Bundle) {
        when (parentId) {
            EMPTY_ROOT_TAG -> result.sendResult(ArrayList())
            MEDIA_ROOT_TAG -> {
                Log.d(LOG_TAG, "Requesting offline root")
                result.sendResult(cacheManager.cacheBrowser.rootFolder)
            }
            else -> {
                result.detach()
                val t = Thread({
                    val res =  cacheManager.cacheBrowser.listFolder(parentId, options.getBoolean(AUDIOSERVICE_FORCE_RELOAD))
                    currentFolder = res.filter{it.isPlayable}
                    result.sendResult(res)
                    prepareLatestItem(folderIdFromOfflinePath(parentId), options)
                }, "Retrieve folder")
                t.start()
            }
        }
    }



    private fun onLoadChildrenOnline(parentId: String, result: Result<List<MediaItem>>, options: Bundle) {

        @Suppress("NAME_SHADOWING")
        val result = ResultWrapper(result)

        fun <T> checkError(res: T?, err: ApiError?, cb: (T) -> Unit) {
            if (!result.isDone) {
                if (err != null) {
                    Log.e(LOG_TAG, "Api returned error: ${err.name}")
                    //TODO - handle unauthorised access??
                    result.sendResult(null)
                } else {
                    cb(res!!)
                }
            }
        }

        if (parentId == EMPTY_ROOT_TAG) {
            result.sendResult(ArrayList())
        } else if (parentId == MEDIA_ROOT_TAG) {
            Log.d(LOG_TAG, "Requesting listing of root of media")
            result.detach()
            apiClient.loadCollections(options.getBoolean(AUDIOSERVICE_FORCE_RELOAD)) { cols, err ->
                checkError(cols, err) {
                    val list = it.mapIndexed { idx, coll ->
                        val b = Bundle()
                        b.putBoolean(ITEM_IS_COLLECTION, true)
                        val meta = MediaDescriptionCompat.Builder()
                                .setMediaId(COLLECTION_PREFIX + idx)
                                .setTitle(coll)
                                .build()
                        MediaItem(meta, MediaItem.FLAG_BROWSABLE)
                    }
                    result.sendResult(list)
                }

            }

        }  else if (parentId.startsWith(SEARCH_PREFIX)) {
            val s = parentId.substring(SEARCH_PREFIX.length)
            val m = SEARCH_RE.matchEntire(s)
            if (m == null) {
                result.sendResult(ArrayList())
                Log.e(LOG_TAG, "Invalid search tag $s")
            } else {
                val collection = m.groups.get(1)?.value?.toInt()?:0
                val query = m.groups.get(2)?.value?:""

                result.detach()
                apiClient.loadSearch(query, collection, options.getBoolean(AUDIOSERVICE_FORCE_RELOAD))
                {it, err->
                    if (it != null) {
                        result.sendResult(it.getMediaItems(cacheManager))
                        currentFolder = ArrayList()//it.getPlayableItems(cacheManager)

                    } else {
                        Log.e(LOG_TAG, "Search failed with $err")
                    }
                }
            }

        } else {
            var index = 0
            var folder = parentId
            if (parentId.startsWith(COLLECTION_PREFIX)) {
                index = parentId.substring(COLLECTION_PREFIX.length).toInt()
                folder = ITEM_TYPE_FOLDER + "/"
                Log.d(LOG_TAG, "Requesting listing of collection ${index}")
            } else {
                Log.d(LOG_TAG, "Requesting listing of folder ${parentId}")
            }

            result.detach()
            apiClient.loadFolder(folder, index, options.getBoolean(AUDIOSERVICE_FORCE_RELOAD))
            { it, err ->
                checkError(it, err)
                {
                    result.sendResult(it.getMediaItems(cacheManager))
                    currentFolder = it.getPlayableItems(cacheManager)
                    prepareLatestItem(parentId, options)
                }

            }
        }


    }

    private fun prepareLatestItem(parentId: String, options: Bundle) {
        if (!options.getBoolean(AUDIOSERVICE_DONT_PRELOAD_LATEST) &&
                (session.controller.playbackState.state == STATE_NONE ||
                        session.controller.playbackState.state == STATE_STOPPED)) {
            // If player is stopped we can prepare latest bookmark

            val lastItems = getRecents(this, onlyLatest = true)
            if (lastItems.size > 0) {
                val lastItem = lastItems[0]
                val lastFolderId = folderIdFromFileId(lastItem.mediaId!!)
                 if (lastFolderId == parentId) {
                    val idx = findIndexInFolder(lastItem.mediaId!!)
                    if (idx >= 0) {
                        Log.d(LOG_TAG, "This folder can resume last played item ${lastItem.mediaId}")
                        player.playWhenReady = false
                        val extras = Bundle()
                        extras.putLong(METADATA_KEY_LAST_POSITION,
                                lastItem.description.extras?.getLong(METADATA_KEY_LAST_POSITION)
                                        ?: 0L)
                        extras.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP,
                                lastItem.description.extras?.getLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP)
                                        ?: 0L)
                        preparer.onPrepareFromMediaId(lastItem.mediaId!!, extras)
                    }
                }
            }
        }
    }


    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserServiceCompat.BrowserRoot? {
        Log.d(LOG_TAG, "Requesting root mediaId")
        // TODO For now allow browser access from same application - latter consider Package manager from Universal player
        return if (Process.SYSTEM_UID == clientUid || Process.myUid() == clientUid) {
            BrowserRoot(MEDIA_ROOT_TAG, null)
        } else {
            BrowserRoot(EMPTY_ROOT_TAG, null)
        }
    }

}