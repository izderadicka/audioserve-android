package eu.zderadicka.audioserve


import android.app.Activity
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
//import kotlinx.android.synthetic.main.fragment_controller.*


private const val LOG_TAG = "ControllerFragment"

/**
 * A simple [Fragment] subclass.
 *
 */
class ControllerFragment : Fragment() {
    var canPlay = false

    // Receive callbacks from the MediaController. Here we update our state such as which queue
    // is being shown, the current title and description and the PlaybackState.
    private val mCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
            Log.d(LOG_TAG, "Received playback state change to state ${state.state}")
            this@ControllerFragment.onPlaybackStateChanged(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (metadata == null) {
                return
            }
            Log.d(LOG_TAG, "Received metadata state change to mediaId=${metadata.description.mediaId} song=${metadata.description.title}")
            this@ControllerFragment.onMetadataChanged(metadata)
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadataCompat) {

    }

    private fun onPlaybackStateChanged(state: PlaybackStateCompat) {
        if (activity == null) {
            Log.w(LOG_TAG, "onPlaybackStateChanged called when getActivity null," + "this should not happen if the callback was properly unregistered. Ignoring.")
            return
        }
        if (state == null) {
            return
        }
        var enablePlay = false
        when (state.state) {
            PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.STATE_STOPPED -> enablePlay = true
            PlaybackStateCompat.STATE_ERROR -> {
                Log.e(LOG_TAG, "error playbackstate:  ${state.errorMessage}")
                Toast.makeText(activity, state.errorMessage, Toast.LENGTH_LONG).show()
            }
        }

        if (enablePlay) {
            playPauseButton.setImageDrawable(

                    ContextCompat.getDrawable(activity!!, android.R.drawable.ic_media_play))
        } else {
            playPauseButton.setImageDrawable(
                    ContextCompat.getDrawable(activity!!, android.R.drawable.ic_media_pause))
        }

        this@ControllerFragment.canPlay = enablePlay
    }

    private val mediaController: MediaControllerCompat?
    get() {
        return MediaControllerCompat.getMediaController(activity!!)
    }

    override fun onStart() {
        super.onStart()
        Log.d(LOG_TAG, "ControllerFragment.onStart")
        onMediaServiceConnected()

    }

    private var controllerRegistered = false
    fun onMediaServiceConnected() {

        if (activity != null && mediaController != null && !controllerRegistered) {
            val controller = mediaController !!
            Log.d(LOG_TAG, "Mediacontroller is present")
            onMetadataChanged(controller.metadata)
            onPlaybackStateChanged(controller.playbackState)
            controller.registerCallback(mCallback)
            controllerRegistered = true

        }
    }


    override fun onStop() {
        super.onStop()
        Log.d(LOG_TAG,"ControllerFragment.onStop")
        if (controllerRegistered) {
            mediaController?.unregisterCallback(mCallback)
            controllerRegistered = false
        }

    }

    lateinit var playPauseButton: ImageView
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_controller, container, false)
        playPauseButton = view.findViewById(R.id.playPauseButton)

        playPauseButton.setOnClickListener{

            if (canPlay) {
                mediaController?.transportControls?.play()
            } else {
                mediaController?.transportControls?.pause()
            }

        }

        return view


    }



}
