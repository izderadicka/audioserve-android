package eu.zderadicka.audioserve.ui

import android.content.Context
import android.os.Handler
import androidx.appcompat.widget.AppCompatImageView
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import kotlin.math.abs

private const val LOG_TAG = "LongPressBUtton"

interface LongPressListener {
    fun onStart()
    fun onEnd()
}

class LongPressButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
                                               defStyleAttr: Int = 0):
        AppCompatImageView(context, attrs, defStyleAttr) {
    private var timer = Handler()
    private var longPress = false
    private var hasMoved = false
    private var startX = 0.0f
    private var startY = 0.0f
    private var listener: LongPressListener? = null
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    init {
        setOnTouchListener {_, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPress = false
                    hasMoved = false
                    startX = event.rawX
                    startY = event.rawY
                    cancelTimer()
                    timer.postDelayed({
                        longPress = true
                        startLongPress()
                    },
                            ViewConfiguration.getLongPressTimeout().toLong())

                    true

                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(startX - event.rawX) > touchSlop ||
                            abs(startY - event.rawY) > touchSlop) {
                          hasMoved = true
                        cancelTimer()
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    if (longPress) endLongPress() else if (!hasMoved) endShortPress()

                    cancelTimer()

                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (longPress) endLongPress()
                    cancelTimer()

                    true
                }

                else -> {
                    false
                }
            }


        }
    }

    private fun cancelTimer() {
        timer.removeCallbacksAndMessages(null)
    }

    private fun startLongPress() {
        listener?.onStart()
    }

    private fun endShortPress() {
        performClick()
    }
    private fun endLongPress() {
        listener?.onEnd()
    }

    fun setLongPressListener(l: LongPressListener) {
        listener = l
    }

    fun removeLongPressListener() {
        listener = null
    }
}