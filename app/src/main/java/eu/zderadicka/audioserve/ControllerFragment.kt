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
class ControllerFragment : MediaFragment() {
    var canPlay = false

    // Receive callbacks from the MediaController. Here we update our state such as which queue
    // is being shown, the current title and description and the PlaybackState.
    internal override val mCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
            Log.d(LOG_TAG, "Received playback state change to state ${state.state}")
            if (activity == null) {
                Log.w(LOG_TAG, "onPlaybackStateChanged called when getActivity null," + "this should not happen if the callback was properly unregistered. Ignoring.")
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
    }


    override fun onStart() {
        super.onStart()
        Log.d(LOG_TAG, "ControllerFragment.onStart")
    }

    override fun onStop() {
        super.onStop()
        Log.d(LOG_TAG,"ControllerFragment.onStop")
    }
    //TODO add support for previous and next buttons
    //TODO add support for move forward and back (seek about 1 minute)
    //TODO add support for seek bar and current total time - see MediaSession demo for guidance - need to read duration from meta
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
