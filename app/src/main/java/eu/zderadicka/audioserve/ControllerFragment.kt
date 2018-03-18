package eu.zderadicka.audioserve


import android.animation.ValueAnimator
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.*

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
                PlaybackStateCompat.STATE_NONE -> {
                    Log.d(LOG_TAG, "AudioService is stopped")
                    // TODO - do something about it -  it means audioservice is stopped - to start playing again need to prepare media again
                }
                PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.STATE_STOPPED-> enablePlay = true
                PlaybackStateCompat.STATE_ERROR -> {
                    val msg = state.errorMessage?:"Playback error"
                    Log.e(LOG_TAG, "error playbackstate:  $msg")
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
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

            // control buttons

            fun enableButton(btn: View, action: Long) {
                if (state.actions and action > 0) {
                    btn.visibility = View.VISIBLE
                } else {
                    btn.visibility = View.INVISIBLE
                }
            }

            enableButton(skipNextButton, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            enableButton(skipPreviousButton, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            enableButton(fastForwardButton, PlaybackStateCompat.ACTION_FAST_FORWARD)
            enableButton(rewindButton, PlaybackStateCompat.ACTION_REWIND)



            // seekBar animation

            //reset current
            cancelProgressAnimator()

            val progress = state.position.toInt() ?: 0
            seekBar.progress = progress
            if (state.state == PlaybackStateCompat.STATE_PLAYING) {
                var timeToEnd = (seekBar.max - progress) / state.playbackSpeed
                if (timeToEnd < 0) timeToEnd = 0F
                Log.d(LOG_TAG, "Setting Animator to ${seekBar.progress} to ${seekBar.max} timeToEnd $timeToEnd")
                progressAnimator = ValueAnimator.ofInt(seekBar.progress, seekBar.max).setDuration(timeToEnd.toLong())
                progressAnimator!!.interpolator = LinearInterpolator()
                progressAnimator!!.addUpdateListener {
                    seekBar.progress = it.animatedValue as Int
                }
                progressAnimator!!.start()

            }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (metadata != null) {
                updateTrackTime(metadata)
            }

        }
    }


    private fun cancelProgressAnimator() {
        if (progressAnimator != null) {
            progressAnimator!!.cancel()
            progressAnimator = null
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
    lateinit var seekBar: SeekBar
    lateinit var currentTimeView: TextView
    lateinit var trackTimeView: TextView
    lateinit var skipPreviousButton: ImageView
    lateinit var skipNextButton: ImageView
    lateinit var fastForwardButton: ImageView
    lateinit var rewindButton: ImageView

    var progressAnimator: ValueAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_controller, container, false)
        playPauseButton = view.findViewById(R.id.playPauseButton)
        seekBar = view.findViewById(R.id.seekBar)
        currentTimeView = view.findViewById(R.id.currentTimeView)
        trackTimeView = view.findViewById(R.id.trackTimeView)
        skipNextButton = view.findViewById(R.id.skipNextButton)
        skipPreviousButton = view.findViewById(R.id.skipPreviousButton)
        rewindButton = view.findViewById(R.id.rewindButton)
        fastForwardButton = view.findViewById(R.id.fastForwardButton)

        seekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentTimeView.text = DateUtils.formatElapsedTime(progress.toLong()/ 1000)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelProgressAnimator()

            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                //TODO - disable seek for transcoded media
                //TODO - longer term solution -  use extra data to seek on server - e.g start new media with offset
                mediaController?.transportControls?.seekTo(seekBar?.progress!!.toLong())
            }

        })

        playPauseButton.setOnClickListener{

            if (canPlay) {
                mediaController?.transportControls?.play()
            } else {
                mediaController?.transportControls?.pause()
            }

        }

        skipNextButton.setOnClickListener {
            mediaController?.transportControls?.skipToNext()
        }

        skipPreviousButton.setOnClickListener {
            mediaController?.transportControls?.skipToPrevious()
        }

        rewindButton.setOnClickListener{
            mediaController?.transportControls?.rewind()
        }

        fastForwardButton.setOnClickListener {
            mediaController?.transportControls?.fastForward()
        }

        return view


    }


    private fun updateTrackTime(meta: MediaMetadataCompat) {

        val duration = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
        Log.d(LOG_TAG, "Track time is $duration")
        seekBar.max = duration.toInt()
        trackTimeView.text = DateUtils.formatElapsedTime(duration / 1000L)
    }



}
