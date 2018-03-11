package eu.zderadicka.audioserve

import android.support.v4.app.Fragment
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log

private const val LOG_TAG = "MediaFragment"
abstract class MediaFragment: Fragment() {


    internal var controllerRegistered = false
    internal abstract  val mCallback: MediaControllerCompat.Callback

    internal val mediaController: MediaControllerCompat?
        get() {
            return MediaControllerCompat.getMediaController(activity!!)
        }

    override fun onStart() {
        super.onStart()
        onMediaServiceConnected()

    }

    open fun onMediaServiceConnected() {

        if (activity != null && mediaController != null && !controllerRegistered) {
            val controller = mediaController !!
            Log.d(LOG_TAG, "Mediacontroller is present")
            mCallback.onMetadataChanged(controller.metadata)
            mCallback.onPlaybackStateChanged(controller.playbackState)
            controller.registerCallback(mCallback)
            controllerRegistered = true

        }
    }

    override fun onStop() {
        super.onStop()
        if (controllerRegistered) {
            mediaController?.unregisterCallback(mCallback)
            controllerRegistered = false
        }

    }




}