package eu.zderadicka.audioserve.ui

import android.content.Context
import android.preference.PreferenceManager
import android.widget.SeekBar
import android.widget.TextView

import kotlin.math.roundToInt

data class SeekBarRange(val steps:Int, val left: Float, val right: Float)

val SPEED_RANGE = SeekBarRange(10, 0.75F, 1.25F)
val PITCH_RANGE = SeekBarRange(10, 0.9F, 1.1F)

class DiscreteSeekbarListener(
        private val context: Context,
        private val range: SeekBarRange,
        private val prefName: String? = null,
        private val valueView: TextView? = null):
        SeekBar.OnSeekBarChangeListener {

    var startingProggres: Int? = null
    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        updateText(valueView, progress)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        startingProggres = seekBar.progress
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        if (startingProggres == seekBar.progress) return
        prefName?.also {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putFloat(it, progressToValue(seekBar.progress))
                    .apply()
        }
    }


    fun valueToProgress(v: Float): Int {
        val res = (v- range.left) * (range.steps /(range.right - range.left))
        return res.roundToInt()
    }

    fun progressToValue(p: Int): Float {
        val res = range.left + (p.toFloat() / range.steps) * (range.right - range.left)
        return res
    }

    fun updateText(tv: TextView?, progress: Int ) {
        tv?.text = "%.2f".format(progressToValue(progress))
    }

    fun updateSeekBar(bar: SeekBar, value: Float) {
        bar.progress = valueToProgress(value)
        updateText(valueView, bar.progress)
    }

}