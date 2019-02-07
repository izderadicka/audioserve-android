package eu.zderadicka.audioserve.utils

import android.widget.TextView
import kotlin.math.roundToInt


private const val MAX: Int = 10
private const val LEFT: Float = 0.9F
private const val RIGHT:Float = 1.1F

object PitchHelper {

    val max = MAX

    fun valueToProgress(v: Float): Int {
        val res = (v- LEFT) * (MAX /(RIGHT - LEFT))
        return res.roundToInt()
    }

    fun progressToValue(p: Int): Float {
        val res = LEFT + (p.toFloat() / MAX) * (RIGHT - LEFT)
        return res
    }

    fun updateText(tv: TextView?, progress: Int ) {
        tv?.text = "%.2f".format(progressToValue(progress))
    }

}