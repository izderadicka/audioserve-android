package eu.zderadicka.audioserve

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
import android.preference.PreferenceManager
import android.support.v4.content.ContextCompat
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaBrowserServiceCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
import android.support.v4.media.session.PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.DefaultPlaybackController
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory
import eu.zderadicka.audioserve.data.ITEM_TYPE_FOLDER
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.net.ApiError
import eu.zderadicka.audioserve.net.CacheManager
import eu.zderadicka.audioserve.notifications.NotificationsManager

private const val LOG_TAG = "audioserve-service"
private const val TIME_AFTER_WHICH_NOT_RESUMING = 20 * 60 * 1000
private const val FF_MS = 30 * 1000L
private const val REWIND_MS = 15 * 1000L


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
    private var playQueue: List<MediaItem> = ArrayList<MediaItem>()
    private lateinit var apiClient: ApiClient

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
            if (requestAudioFocus()) {
                super.onPlay(player)
            }
        }

        fun requestAudioFocus(): Boolean {
            val result = am.requestAudioFocus(focusCallback, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        override fun onPause(player: Player) {
            super.onPause(player)
            pauseMe()
            needResume = false
        }

        override fun onStop(player: Player) {
            Log.d(LOG_TAG, "Stoping play")
            needResume = false
            super.onStop(player)
            session.isActive = false
            stopMe()
            am.abandonAudioFocus(focusCallback)
        }
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

        private fun findIndexInFolder(mediaId: String): Int {
            return currentFolder.indexOfFirst { it.mediaId == mediaId }
        }

        lateinit var dsFactory: DefaultHttpDataSourceFactory
        lateinit var sourceFactory: ExtractorMediaSource.Factory

        fun initSourceFactory(cm: CacheManager) {

            dsFactory = DefaultHttpDataSourceFactory("audioserve")
            cm.upstreamFactory = dsFactory
            sourceFactory = ExtractorMediaSource.Factory(cm.sourceFactory)

        }

        override fun onPrepareFromMediaId(mediaId: String, extras: Bundle?) {
            if (!session.isActive) {
                session.isActive = true
            }
            Log.d(LOG_TAG, "Preparing mediaId $mediaId")
            if (apiClient.token != null) {
                dsFactory.defaultRequestProperties.set("Authorization", "Bearer ${apiClient.token}")
            }
            val folderPosition = findIndexInFolder(mediaId)

            var source: MediaSource
            if (folderPosition >= 0) {
                playQueue = currentFolder.slice(folderPosition until currentFolder.size)
                val ms = playQueue.map { sourceFactory.createMediaSource(apiClient.uriFromMediaId(it.mediaId!!)) }.toTypedArray()
                source = ConcatenatingMediaSource(*ms)
                player.currentTimeline
            } else {
                source = sourceFactory.createMediaSource(apiClient.uriFromMediaId(mediaId))
            }

            player.prepare(source)
            if (player.playWhenReady) {
                playerController.requestAudioFocus()
            }
        }

        override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onPrepare() {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

    }

    private val sessionCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
            super.onPlaybackStateChanged(state)
            Log.d(LOG_TAG, "Playback state changed in service ${state}")
            when (state.state) {
                PlaybackStateCompat.STATE_PLAYING -> notifManager.sendNotification(true)
                PlaybackStateCompat.STATE_PAUSED -> notifManager.sendNotification()
            }
        }
    }

    inner class QueueManager(session: MediaSessionCompat) : TimelineQueueNavigator(session) {
        override fun getMediaDescription(windowIndex: Int): MediaDescriptionCompat {
            if (windowIndex >= 0 && windowIndex < playQueue.size) {
                return playQueue.get(windowIndex).description
            } else {
                throw IllegalArgumentException("windowIndex is $windowIndex, but queue size is ${playQueue.size}")
            }
        }
    }

    private val prefsListener = object : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            apiClient.loadPreferences()
        }

    }

    private lateinit var queueManager: QueueManager
    private lateinit var cacheManager: CacheManager

    companion object {
        const val MEDIA_ROOT_TAG = "__AUDIOSERVE_ROOT__"
        const val EMPTY_ROOT_TAG = "__AUDIOSERVE_EMPTY__"
        private const val COLLECTION_PREFIX = "__COLLECTION_"
        const val ITEM_IS_COLLECTION = "is-collection"
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, LOG_TAG)
        session.controller.registerCallback(sessionCallback)
        player = ExoPlayerFactory.newSimpleInstance(this, DefaultTrackSelector())
        queueManager = QueueManager(session)

        sessionToken = session.sessionToken
        notifManager = NotificationsManager(this)
        connector = MediaSessionConnector(session, playerController)
        cacheManager = CacheManager(this)
        preparer.initSourceFactory(cacheManager)
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

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(prefsListener)


        Log.d(LOG_TAG, "Audioservice created")

    }

    var isStartedInForeground = false
    var isStarted = false

    fun startMe(notification: Notification) {

        if (!isStarted) {
            val intent = Intent(this, AudioService::class.java)
            ContextCompat.startForegroundService(this, intent)
            isStarted = true
        }
        startForeground(NotificationsManager.NOTIFICATION_ID, notification)
        isStartedInForeground = true
    }

    fun stopMe() {
        stopForeground(true)
        isStartedInForeground = false
        stopSelf()
        isStarted = false

    }

    fun pauseMe() {
        Log.d(LOG_TAG, "Pausing service - stopForeground")
        stopForeground(false)
        isStartedInForeground = false

    }


    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {

            session.isActive = false
            session.release()
            player.release()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error while destroying AudioService")
        }

        Log.d(LOG_TAG, "Audioservice destroyed")
    }


    override fun onLoadChildren(parentId: String, result: MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>) {

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
            apiClient.loadCollections { cols, err ->
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
            apiClient.loadFolder(folder, index) { it, err ->
                checkError(it, err)
                {
                    result.sendResult(it.mediaItems)
                    currentFolder = it.playableItems
                }

            }
        }


    }


    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserServiceCompat.BrowserRoot? {
        Log.d(LOG_TAG, "Requesting root mediaId")
        // TODO For now allow browser access from same application - latter consider Package manager from Universal player
        if (Process.SYSTEM_UID == clientUid || Process.myUid() == clientUid) {
            return BrowserRoot(MEDIA_ROOT_TAG, null)
        } else {
            return BrowserRoot(EMPTY_ROOT_TAG, null)
        }
    }

}