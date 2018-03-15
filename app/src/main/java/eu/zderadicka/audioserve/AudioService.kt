package eu.zderadicka.audioserve

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
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
import com.google.android.exoplayer2.C
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
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import eu.zderadicka.audioserve.data.ITEM_TYPE_FOLDER
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.notifications.NotificationsManager


private const val LOG_TAG = "audioserve-service"
private const val TIME_AFTER_WHICH_NOT_RESUMING = 20 * 60 * 1000
private const val FF_MS = 30 * 1000L
private const val REWIND_MS = 15 * 1000L

class PlayerController(private val service: AudioService)
    : DefaultPlaybackController(REWIND_MS, FF_MS, MediaSessionConnector.DEFAULT_REPEAT_TOGGLE_MODES) {

    private val am: AudioManager =  service.getSystemService(Context.AUDIO_SERVICE) as AudioManager;
    private var needResume = false
    private var timeInterrupted = 0L
    private val notifManager: NotificationsManager = service.notifManager
    private val currentPlayer = service.player
    private var focusCallback: AudioManager.OnAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResume && System.currentTimeMillis() - timeInterrupted <= TIME_AFTER_WHICH_NOT_RESUMING) {
                    onPlay(currentPlayer)
                }
                needResume = false
            }
            else -> {
                // resume only if it was playing
                needResume = service.session.controller.playbackState.state == PlaybackStateCompat.STATE_PLAYING
                super.onPause(currentPlayer)
                timeInterrupted = System.currentTimeMillis()
            }
        }
    }

    init {

    }

    override fun onPlay(player: Player) {
        Log.d(LOG_TAG, "Playback started")
        val result = am.requestAudioFocus(focusCallback, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            super.onPlay(player)
        }
    }

    override fun onPause(player: Player) {
        super.onPause(player)
        service.pauseMe()
        needResume = false
    }

    override fun onStop(player: Player) {
        Log.d(LOG_TAG, "Stoping play")
        needResume = false
        super.onStop(player)
        service.stopMe()
        am.abandonAudioFocus(focusCallback)
    }

    override fun onFastForward(player: Player?) {
        super.onFastForward(player)
    }

}

class AudioService : MediaBrowserServiceCompat() {
    lateinit var session: MediaSessionCompat
    private lateinit var connector: MediaSessionConnector
    lateinit var player: ExoPlayer
    lateinit var notifManager:NotificationsManager
    private var playQueue: List<MediaItem> = ArrayList<MediaItem>()
    private var skipToQueueItem = -1
    private lateinit var apiClient: ApiClient

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

        private fun findIndexInQueue(mediaId: String): Int {
            return playQueue.indexOfFirst { it.mediaId == mediaId }
        }

        override fun onPrepareFromMediaId(mediaId: String, extras: Bundle?) {
            Log.d(LOG_TAG, "Preparing mediaId $mediaId")
            mediaId ?: return
            val sourceFactory = ExtractorMediaSource.Factory(DefaultDataSourceFactory(applicationContext,
                    "audioserve"))
            skipToQueueItem = findIndexInQueue(mediaId)

            var source: MediaSource = sourceFactory.createMediaSource(apiClient.uriFromMediaId(mediaId))
            if (skipToQueueItem >= 0) {
                val ms = playQueue.map { sourceFactory.createMediaSource(apiClient.uriFromMediaId(it.mediaId!!)) }.toTypedArray()
                source = ConcatenatingMediaSource(*ms)
                player.currentTimeline
            }

            player.prepare(source)

        }

        override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onPrepare() {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

    }

    private val sessionCallback = object: MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
            super.onPlaybackStateChanged(state)
            Log.d(LOG_TAG, "Playback state changed in service ${state}")
            when(state.state) {
                PlaybackStateCompat.STATE_PLAYING -> notifManager.sendNotification(true)
                PlaybackStateCompat.STATE_PAUSED -> notifManager.sendNotification()
                PlaybackStateCompat.STATE_BUFFERING -> {

                    if (skipToQueueItem >= 0) {
                        val timeline = player.currentTimeline
                        if (!timeline.isEmpty) {
                            //No need to skip to 0, as this is default start
                            if (0 < skipToQueueItem && skipToQueueItem < timeline.windowCount) {
                                player.seekTo(skipToQueueItem, C.TIME_UNSET)
                            }
                            skipToQueueItem=-1
                        }
                    }

                }
            }
        }
    }

    inner class QueueManager(val session: MediaSessionCompat) :TimelineQueueNavigator(session) {
        override fun getMediaDescription(windowIndex: Int): MediaDescriptionCompat {
            if (windowIndex>=0 && windowIndex<playQueue.size) {
                return playQueue.get(windowIndex).description
            } else {
                throw IllegalArgumentException("windowIndex is $windowIndex, but queue size is ${playQueue.size}")
            }
        }
    }

    private lateinit var queueManager:QueueManager

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
        notifManager  = NotificationsManager(this)
        connector = MediaSessionConnector(session, PlayerController(this))
        connector.setPlayer(player, preparer)
        connector.setQueueNavigator(queueManager)

        apiClient = ApiClient.getInstance(this)


        Log.d(LOG_TAG, "Audioservice created")

    }
    var isStartedInForeground = false
    var isStarted = false

    fun startMe(notification: Notification) {

        if (!isStarted) {
            val intent = Intent(this,AudioService::class.java)
            ContextCompat.startForegroundService(this,intent)
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
        player.release()
        session.release()

        Log.d(LOG_TAG, "Audioservice destroyed")
    }

    override fun onLoadChildren(parentId: String, result: MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>) {
        if (parentId == EMPTY_ROOT_TAG) {
            result.sendResult(ArrayList())
        } else if (parentId == MEDIA_ROOT_TAG) {
            Log.d(LOG_TAG, "Requesting listing of root of media")
            result.detach()
            apiClient.loadCollections {
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

        } else  {
            var index = 0
            var folder = parentId
            if (parentId.startsWith(COLLECTION_PREFIX)) {
                index = parentId.substring(COLLECTION_PREFIX.length).toInt()
                folder = ITEM_TYPE_FOLDER + "/"
                Log.d(LOG_TAG, "Requesting listing of collection ${index}")
            } else  {
                Log.d(LOG_TAG, "Requesting listing of folder ${parentId}")
            }

            result.detach()
            apiClient.loadFolder(folder, index) {
                if (it != null) {
                    result.sendResult(it.mediaItems)
                    playQueue = it.playableItems
                } else {
                    Log.e(LOG_TAG, "Null audiofolder $folder")
                    result.sendResult(null)
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