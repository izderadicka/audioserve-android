package eu.zderadicka.audioserve.fragments


import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
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
import eu.zderadicka.audioserve.*
import eu.zderadicka.audioserve.ui.*

//import kotlinx.android.synthetic.main.fragment_controller.*


private const val LOG_TAG = "ControllerFragment"

interface ControllerHolder {
    fun onControllerClick()
}

class ControllerFragment : MediaFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

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
                PlaybackStateCompat.STATE_BUFFERING,
                PlaybackStateCompat.STATE_STOPPED -> enablePlay = true
                PlaybackStateCompat.STATE_ERROR -> {
                    val msg = state.errorMessage ?: "Playback error"
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
                progressAnimator = ValueAnimator.ofInt(seekBar.progress, seekBar.max).apply {
                    setDuration(timeToEnd.toLong())
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        seekBar.progress = it.animatedValue as Int
                    }
                    start()
                }

            }

            //enable seekbar only if can seek
            seekBar.isEnabled = state.actions and PlaybackStateCompat.ACTION_SEEK_TO > 0
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
        Log.d(LOG_TAG, "ControllerFragment.onStop")
    }

    lateinit var playPauseButton: ImageView
    lateinit var seekBar: SeekBar
    lateinit var currentTimeView: TextView
    lateinit var trackTimeView: TextView
    lateinit var skipPreviousButton: ImageView
    lateinit var skipNextButton: ImageView
    lateinit var fastForwardButton: LongPressButton
    lateinit var rewindButton: LongPressButton
    lateinit var mainView: View
    lateinit var speedBar: SeekBar
    lateinit var speedBarListener: DiscreteSeekbarListener
    lateinit var pitchBar: SeekBar
    lateinit var pitchBarListener: DiscreteSeekbarListener
    lateinit var speedView: TextView
    lateinit var pitchView: TextView
    lateinit var silenceSwitch: Switch
    lateinit var volumeBoostSwitch: Switch

    var progressAnimator: ValueAnimator? = null

    lateinit var holder: ControllerHolder

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_controller, container, false)
        mainView = view
        playPauseButton = view.findViewById(R.id.playPauseButton)
        seekBar = view.findViewById(R.id.seekBar)
        pitchBar = view.findViewById(R.id.pitchBar)
        currentTimeView = view.findViewById(R.id.currentTimeView)
        trackTimeView = view.findViewById(R.id.trackTimeView)
        skipNextButton = view.findViewById(R.id.skipNextButton)
        skipPreviousButton = view.findViewById(R.id.skipPreviousButton)
        rewindButton = view.findViewById(R.id.rewindButton)
        fastForwardButton = view.findViewById(R.id.fastForwardButton)
        speedBar = view.findViewById(R.id.speedBar)
        silenceSwitch = view.findViewById(R.id.silenceSwitch)
        volumeBoostSwitch = view.findViewById(R.id.volumeBoostSwitch)
        speedView = view.findViewById(R.id.speedView)
        pitchView = view.findViewById(R.id.pitchView)
        speedBarListener = DiscreteSeekbarListener(
                context!!,
                SPEED_RANGE,
                "pref_playback_speed",
                speedView
        )
        pitchBarListener = DiscreteSeekbarListener(
                context!!,
                PITCH_RANGE,
                "pref_playback_pitch",
                pitchView
        )


        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentTimeView.text = DateUtils.formatElapsedTime(progress.toLong() / 1000)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                cancelProgressAnimator()

            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                //TODO - transcoded media seek - longer term solution -  use extra data to seek on server - e.g start new media with offset
                mediaController?.transportControls?.seekTo(seekBar?.progress!!.toLong())
            }

        })

        playPauseButton.setOnClickListener {

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

        rewindButton.setOnClickListener {
            mediaController?.transportControls?.rewind()
        }

        rewindButton.setLongPressListener(object: LongPressListener{
            override fun onStart() {
                mediaController?.sendCommand(CUSTOM_COMMAND_REWIND_PLAY_START, null, null)
            }

            override fun onEnd() {
                mediaController?.sendCommand(CUSTOM_COMMAND_REWIND_PLAY_END, null, null)
            }

        })

        fastForwardButton.setOnClickListener {
            mediaController?.transportControls?.fastForward()
        }

        fastForwardButton.setLongPressListener(object : LongPressListener {
            override fun onStart() {
                mediaController?.sendCommand(CUSTOM_COMMAND_FAST_PLAY_START, null, null)
            }

            override fun onEnd() {
                mediaController?.sendCommand(CUSTOM_COMMAND_FAST_PLAY_END, null, null)
            }

        })

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

        speedBar.max = SPEED_RANGE.steps
        speedBar.setOnSeekBarChangeListener(speedBarListener)

        pitchBar.max = PITCH_RANGE.steps
        pitchBar.setOnSeekBarChangeListener(pitchBarListener)

        silenceSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean("pref_skip_silence", isChecked)
                    .apply()
        }

        volumeBoostSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean("pref_volume_boost", isChecked)
                    .apply()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(this)
        // and synch
        val currentSpeed = PreferenceManager.getDefaultSharedPreferences(context)
                .getFloat("pref_playback_speed", 1.0f)
        val currentPitch = PreferenceManager.getDefaultSharedPreferences(context)
                .getFloat("pref_playback_pitch", 1.0f)
        val currentSilence = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("pref_skip_silence", false)
        val currentVolumeBoost = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("pref_volume_boost", false)

        speedBarListener.updateSeekBar(speedBar, currentSpeed)
        pitchBarListener.updateSeekBar(pitchBar, currentPitch)

        silenceSwitch.isChecked = currentSilence
        volumeBoostSwitch.isChecked = currentVolumeBoost
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(context).unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
        when (key) {
            "pref_playback_speed" -> {
                val v = sp.getFloat(key, 1.0f)
                speedBarListener.updateSeekBar(speedBar,v)
            }

            "pref_playback_pitch" -> {
                val v = sp.getFloat(key, 1.0f)
                pitchBarListener.updateSeekBar(pitchBar,v)
            }

            "pref_skip_silence" -> {
                silenceSwitch.isChecked = sp.getBoolean(key, false)
            }

            "pref_volume_boost" -> {
                volumeBoostSwitch.isChecked = sp.getBoolean(key, false)
            }
        }
    }


    private fun updateTrackTime(meta: MediaMetadataCompat) {

        val duration = meta.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
        Log.d(LOG_TAG, "Track time is $duration")
        seekBar.max = duration.toInt()
        trackTimeView.text = DateUtils.formatElapsedTime(duration / 1000L)
    }


    val currentPlayTime: Long
        get() {
            return seekBar.progress.toLong()
        }


}
