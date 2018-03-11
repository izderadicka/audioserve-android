package eu.zderadicka.audioserve.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.support.annotation.RequiresApi
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import eu.zderadicka.audioserve.AudioService
import eu.zderadicka.audioserve.MainActivity
import eu.zderadicka.audioserve.R

class NotificationsManager(private val mService: AudioService) {

    private val mPlayAction: NotificationCompat.Action
    private val mPauseAction: NotificationCompat.Action
    private val mNextAction: NotificationCompat.Action
    private val mPrevAction: NotificationCompat.Action
    private val notificationManager: NotificationManager = mService.getSystemService(Context.NOTIFICATION_SERVICE)!! as NotificationManager

    init {

        mPlayAction = NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                mService.getString(R.string.label_play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mService,
                        PlaybackStateCompat.ACTION_PLAY))
        mPauseAction = NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                mService.getString(R.string.label_pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mService,
                        PlaybackStateCompat.ACTION_PAUSE))
        mNextAction = NotificationCompat.Action(
                android.R.drawable.ic_media_next,
                mService.getString(R.string.label_next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mService,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
        mPrevAction = NotificationCompat.Action(
                android.R.drawable.ic_media_previous,
                mService.getString(R.string.label_previous),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mService,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))

        // Cancel all notifications to handle the case where the Service was killed and
        // restarted by the system.
        notificationManager.cancelAll()
    }

    fun onDestroy() {
        Log.d(LOG_TAG, "onDestroy: ")
    }

    fun sendNotification() {
        if(mService.isStartedInForeground) {
            notificationManager.notify(NOTIFICATION_ID, getNotification())
        } else {
            mService.startMe(getNotification())
        }



    }
    private fun getNotification(): Notification {
        val description = getMeta().description
        val state = mService.session.controller.playbackState
        val token = mService.sessionToken!!
        val builder = buildNotification(state, token, description)
        return builder.build()
    }

    private fun getMeta(): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Dummy for now")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, " Lorem i psum ...")
                .build()
    }


    private fun buildNotification(state: PlaybackStateCompat,
                                  token: MediaSessionCompat.Token,
                                  description: MediaDescriptionCompat): NotificationCompat.Builder {

        // Create the (mandatory) notification channel when running on Android Oreo.

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createChannel()
            }


        val builder = NotificationCompat.Builder(mService, CHANNEL_ID)
        builder.setStyle(
                android.support.v4.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0)
                        // For backwards compatibility with Android L and earlier.
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(
                                MediaButtonReceiver.buildMediaButtonPendingIntent(
                                        mService,
                                        PlaybackStateCompat.ACTION_STOP)))
                .setColor(ContextCompat.getColor(mService, R.color.primary_material_dark))
                .setSmallIcon(R.mipmap.ic_launcher)
                // Pending intent that is fired when user clicks on notification.
                .setContentIntent(createContentIntent())
                // Title - Usually Song name.
                .setContentTitle(description.title)
                // Subtitle - Usually Artist name.
                .setContentText(description.subtitle)
                //.setLargeIcon(MusicLibrary.getAlbumBitmap(mService, description.mediaId))
                // When notification is deleted (when playback is paused and notification can be
                // deleted) fire MediaButtonPendingIntent with ACTION_STOP.
                .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mService, PlaybackStateCompat.ACTION_STOP))
                // Show controls on lock screen even when user hides sensitive content.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // If skip to next action is enabled.
        if (state.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L) {
            builder.addAction(mPrevAction)
        }

        builder.addAction(if (state.state != PlaybackStateCompat.STATE_PLAYING )  mPlayAction else mPauseAction)

        // If skip to prev action is enabled.
        if (state.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L) {
            builder.addAction(mNextAction)
        }

//TODO Make notification to be able to swipe away and add stop playback

        return builder
    }

    // Does nothing on versions of Android earlier than O.
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            // The user-visible name of the channel.
            val name = "MediaSession"
            // The user-visible description of the channel.
            val description = "MediaSession and MediaPlayer"
            val importance = NotificationManager.IMPORTANCE_LOW
            val mChannel = NotificationChannel(CHANNEL_ID, name, importance)
            // Configure the notification channel.
            mChannel.description = description
            mChannel.enableLights(true)
            // Sets the notification light color for notifications posted to this
            // channel, if the device supports this feature.
            mChannel.lightColor = Color.RED
            mChannel.enableVibration(true)
            mChannel.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
            notificationManager.createNotificationChannel(mChannel)
            Log.d(LOG_TAG, "createChannel: New channel created")
        } else {
            Log.d(LOG_TAG, "createChannel: Existing channel reused")
        }
    }

    private fun createContentIntent(): PendingIntent {
        val openUI = Intent(mService, MainActivity::class.java)
        openUI.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
                mService, REQUEST_CODE, openUI, PendingIntent.FLAG_CANCEL_CURRENT)
    }

    companion object {

        const val NOTIFICATION_ID = 412

        private val LOG_TAG = NotificationsManager::class.java.simpleName
        private const val CHANNEL_ID = "com.example.android.musicplayer.channel"
        private const val REQUEST_CODE = 501
    }

}