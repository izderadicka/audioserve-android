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
import android.support.v4.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import eu.zderadicka.audioserve.ACTION_NAVIGATE_TO_ITEM
import eu.zderadicka.audioserve.AudioService
import eu.zderadicka.audioserve.MainActivity
import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.data.METADATA_KEY_MEDIA_ID

class NotificationsManager(private val mService: AudioService) {

    private val playAction: NotificationCompat.Action
    private val pauseAction: NotificationCompat.Action
    private val nextAction: NotificationCompat.Action
    private val prevAction: NotificationCompat.Action
    private val notificationManager: NotificationManager = mService.getSystemService(Context.NOTIFICATION_SERVICE)!! as NotificationManager

    init {

        playAction = NotificationCompat.Action(
                R.drawable.ic_play_white,
                mService.getString(R.string.label_play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mService,
                        PlaybackStateCompat.ACTION_PLAY))
        pauseAction = NotificationCompat.Action(
                R.drawable.ic_pause_white,
                mService.getString(R.string.label_pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mService,
                        PlaybackStateCompat.ACTION_PAUSE))
        nextAction = NotificationCompat.Action(
                R.drawable.ic_next_white,
                mService.getString(R.string.label_next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mService,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
        prevAction = NotificationCompat.Action(
                R.drawable.ic_previous_white,
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

    fun sendNotification(needStart:Boolean = false) {
        if (mService.isStartedInForeground || ! needStart) {
            notificationManager.notify(NOTIFICATION_ID, getNotification())
        } else {
            mService.startMe(getNotification())
        }


    }

    fun deleteNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun getNotification(): Notification {
        val description = mService.session.controller.metadata.description
        val state = mService.session.controller.playbackState
        val token = mService.sessionToken!!
        val builder = buildNotification(state, token, description)
        return builder.build()
    }


    private fun buildNotification(state: PlaybackStateCompat,
                                  token: MediaSessionCompat.Token,
                                  description: MediaDescriptionCompat): NotificationCompat.Builder {

        // Create the (mandatory) notification channel when running on Android Oreo.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }

        var playIndex = 0
        val builder = NotificationCompat.Builder(mService, CHANNEL_ID)
        // If skip to next action is enabled.
        if (state.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L) {
            builder.addAction(prevAction)
            playIndex+=1
        }

        builder.addAction(if (state.state != PlaybackStateCompat.STATE_PLAYING) playAction else pauseAction)

        // If skip to prev action is enabled.
        if (state.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L) {
            builder.addAction(nextAction)
        }

        builder.setStyle(
                android.support.v4.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(playIndex)
                        // For backwards compatibility with Android L and earlier.
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(
                                MediaButtonReceiver.buildMediaButtonPendingIntent(
                                        mService,
                                        PlaybackStateCompat.ACTION_STOP)))
                .setColor(ContextCompat.getColor(mService, R.color.colorAccent))
                .setSmallIcon(R.drawable.ic_pulse)
                // Pending intent that is fired when user clicks on notification.
                .setContentIntent(createContentIntent(description))
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

        return builder
    }

    // Does nothing on versions of Android earlier than O.
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            // The user-visible name of the channel.
            val name = "Audioserve"
            // The user-visible description of the channel.
            val description = "Audioserve playback notification channel"
            val importance = NotificationManager.IMPORTANCE_LOW
            val mChannel = NotificationChannel(CHANNEL_ID, name, importance)
            // Configure the notification channel.
            mChannel.description = description
            notificationManager.createNotificationChannel(mChannel)
            Log.d(LOG_TAG, "createChannel: New channel created")
        } else {
            Log.d(LOG_TAG, "createChannel: Existing channel reused")
        }
    }

    private fun createContentIntent(description: MediaDescriptionCompat): PendingIntent {
        val openUI = Intent(mService, MainActivity::class.java)
        openUI.action = ACTION_NAVIGATE_TO_ITEM
        openUI.putExtra(METADATA_KEY_MEDIA_ID, description.mediaId)
        openUI.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
                mService, REQUEST_CODE, openUI, PendingIntent.FLAG_CANCEL_CURRENT)
    }

    companion object {

        const val NOTIFICATION_ID = 412

        private val LOG_TAG = NotificationsManager::class.java.simpleName
        private const val CHANNEL_ID = "eu.zderadicka.audioserve.playback.channel"
        private const val REQUEST_CODE = 501
    }

}