package eu.zderadicka.audioserve.fragments


import android.animation.ValueAnimator
import android.os.Bundle
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
import eu.zderadicka.audioserve.R

//import kotlinx.android.synthetic.main.fragment_controller.*


private const val LOG_TAG = "ControllerFragment"

interface ControllerHolder {
    fun onControllerClick()
}
class ControllerFragment : MediaFragment() {
    var canPlay = false

    // Receive callbacks from the MediaController. Here we update our state such as which queue
    // is being shown, the current title and description and the PlaybackState.
    override val mCallback = object : MediaControllerCompat.Callback() {
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
                    enablePlay = false
                }
                PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.STATE_STOPPED-> enablePlay = true
                PlaybackStateCompat.STATE_ERROR -> {
                    val msg = state.errorMessage?:"Playback error"
                    Log.e(LOG_TAG, "error playbackstate:  $msg")
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    enablePlay = true
                }
            }

            if (enablePlay) {
                playPauseButton.setImageDrawable(

                        ContextCompat.getDrawable(activity!!, R.drawable.ic_play_white))
            } else {
                playPauseButton.setImageDrawable(
                        ContextCompat.getDrawable(activity!!, R.drawable.ic_pause_white))
            }

            this@ControllerFragment.canPlay = enablePlay

            // control buttons

            fun enableButton(btn: View, action: Long) {
                if (state.actions and action == action) {
                    btn.visibility = View.VISIBLE
                } else {
                    btn.visibility = View.INVISIBLE
                }
            }

            enableButton(skipNextButton, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            enableButton(skipPreviousButton, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            enableButton(fastForwardButton, PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_SEEK_TO)
            enableButton(rewindButton, PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_SEEK_TO)



            // seekBar animation

            //reset current
            cancelProgressAnimator()

            val progress = state.position.toInt()
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

            //enable seekbar only if can seek
            if (state.actions and PlaybackStateCompat.ACTION_SEEK_TO > 0) {
                seekBar.isEnabled = true
            } else {
                seekBar.isEnabled = false
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
    lateinit var playPauseButton: ImageView
    lateinit var seekBar: SeekBar
    lateinit var currentTimeView: TextView
    lateinit var trackTimeView: TextView
    lateinit var skipPreviousButton: ImageView
    lateinit var skipNextButton: ImageView
    lateinit var fastForwardButton: ImageView
    lateinit var rewindButton: ImageView

    var progressAnimator: ValueAnimator? = null

    lateinit var holder: ControllerHolder

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
                //TODO - transcoded media seek - longer term solution -  use extra data to seek on server - e.g start new media with offset
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

        if (context is ControllerHolder) {
            holder = context as ControllerHolder
        } else {
            throw IllegalStateException("Containing activity must implement ControllerHolder")
        }

        currentTimeView.setOnClickListener({
            holder.onControllerClick()
        })

        trackTimeView.setOnClickListener {
            holder.onControllerClick()
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
