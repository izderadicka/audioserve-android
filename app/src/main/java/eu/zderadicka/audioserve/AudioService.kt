package eu.zderadicka.audioserve

import android.app.Notification
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaBrowserServiceCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
import android.support.v4.media.session.PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
import android.util.Log
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.DefaultPlaybackController
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import eu.zderadicka.audioserve.data.ITEM_TYPE_FOLDER
import eu.zderadicka.audioserve.net.ApiClient
import eu.zderadicka.audioserve.net.UriFromMediaId
import eu.zderadicka.audioserve.notifications.NotificationsManager


private const val LOG_TAG = "audioserve-service"

class PlayerController(private val service: AudioService)
    : DefaultPlaybackController() {

    private val notifManager: NotificationsManager = service.notifManager

    override fun onPlay(player: Player) {
        Log.d(LOG_TAG, "Playback started")
        super.onPlay(player)
        notifManager.sendNotification()

    }

    override fun onPause(player: Player) {
        super.onPause(player)
        assert(service.isStartedInForeground)
        notifManager.sendNotification()

    }


    override fun onStop(player: Player) {
        Log.d(LOG_TAG, "Stoping play")
        super.onStop(player)
        service.stopForeground(true)
        service.stopSelf()
    }

}

class AudioService : MediaBrowserServiceCompat() {
    lateinit var session: MediaSessionCompat
    private lateinit var connector: MediaSessionConnector
    private lateinit var player: ExoPlayer
    lateinit var notifManager:NotificationsManager

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

        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(LOG_TAG, "Preparing mediaId $mediaId")
            mediaId ?: return
            val sourceFactory = ExtractorMediaSource.Factory(DefaultDataSourceFactory(applicationContext,
                    "audioserve"))
            player.prepare(sourceFactory.createMediaSource(UriFromMediaId(mediaId)))
            if (player.playWhenReady) {
                notifManager.sendNotification()
            }
        }

        override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onPrepare() {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

    }

    companion object {
        const val MEDIA_ROOT_TAG = "__AUDIOSERVE_ROOT__"
        const val EMPTY_ROOT_TAG = "__AUDIOSERVE_EMPTY__"
        private const val COLLECTION_PREFIX = "__COLLECTION_"
        const val ITEM_IS_COLLECTION = "is-collection"
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, LOG_TAG)
        // TODO I think its not needed as its done by connector
//        session.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
//        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
//        session.isActive =true

        sessionToken = session.sessionToken
        notifManager  = NotificationsManager(this)
        connector = MediaSessionConnector(session, PlayerController(this))
        player = ExoPlayerFactory.newSimpleInstance(this, DefaultTrackSelector())
        connector.setPlayer(player, preparer)




        Log.d(LOG_TAG, "Audioservice created")

    }
    var isStartedInForeground = false

    fun startMe(notification: Notification) {
        startForeground(NotificationsManager.NOTIFICATION_ID, notification)
        isStartedInForeground = true
    }

    fun stopMe() {
        stopForeground(true)
        stopSelf()
        isStartedInForeground = false
    }


    override fun onDestroy() {
        super.onDestroy()
        player.release()
        session.release()

        Log.d(LOG_TAG, "Audioservice destroyed")
    }

    override fun onLoadChildren(parentId: String, result: MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>>) {

        if (parentId == MEDIA_ROOT_TAG) {
            Log.d(LOG_TAG, "Requesting listing of root of media")
            result.detach()
            ApiClient.getInstance(this).loadCollections {
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

        } else if (parentId.startsWith(COLLECTION_PREFIX)) {
            val index = parentId.substring(COLLECTION_PREFIX.length).toInt()
            Log.d(LOG_TAG, "Requesting listing of collection ${index}")
            result.detach()
            ApiClient.getInstance(this).loadFolder(ITEM_TYPE_FOLDER + "/", index) {
                result.sendResult(it?.mediaItems)
            }

        } else {
            result.detach()
            Log.d(LOG_TAG, "Requesting listing of folder ${parentId}")
            ApiClient.getInstance(this).loadFolder(parentId, 0) {
                result.sendResult(it?.mediaItems)
            }
        }


    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserServiceCompat.BrowserRoot? {
        Log.d(LOG_TAG, "Requesting root mediaId")
        return BrowserRoot(MEDIA_ROOT_TAG, null)
    }

}