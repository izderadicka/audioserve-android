package eu.zderadicka.audioserve.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import eu.zderadicka.audioserve.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val LOG_TAG = "ExpandableFrameLayout"

class ExpandableFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
                                                      defStyleAttr: Int = 0, defStyleRes: Int = 0)
    : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
    var minHeight: Int = 0
    var maxHeight: Int = 0
    val gestureDetector: GestureDetector
    var animator: ValueAnimator? = null
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    var midHeight: Int = 0

    fun animate(toValue: Int) {
        animator?.cancel()
        animator = ValueAnimator.ofInt(height, toValue).apply {
            this.addUpdateListener {
                layoutParams.height = it.animatedValue as Int
                requestLayout()
            }
            start()

        }
    }

    inner class MyGestureListener: GestureDetector.SimpleOnGestureListener() {
        var isDragging = false
        var startHeight = 0
        override fun onDown(e: MotionEvent?): Boolean {
            startHeight = layoutParams.height
            isDragging = false
            return true
        }

//        override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
//            Log.d(LOG_TAG, "Fling with velocity $velocityY")
//            isDragging = true
//            if (velocityY < 0 && height < maxHeight) {
//                animate(maxHeight)
//
//            } else if (velocityY > 0 && height > minHeight) {
//                animate(minHeight)
//            }
//
//            return true
//        }

        override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val distY = e1.rawY - e2.rawY
            val distX = e1.rawX - e2.rawX
            Log.d(LOG_TAG, "Scroll in Y $distY")
            isDragging = true
            if (abs(distY) > abs(distX)) {
                if (distY > touchSlop && height < maxHeight) {
                    layoutParams.height = min(startHeight + distY.roundToInt(), maxHeight)
                    requestLayout()
                } else if (distY < -touchSlop && height > minHeight) {
                    layoutParams.height = max(startHeight + distY.roundToInt(), minHeight)
                    requestLayout()
                }
                return true
            } else {
                return false
            }
        }

        fun dragging() = isDragging

    }

    val gestureListener = MyGestureListener()

    init {

        val customAttrs = context.theme.obtainStyledAttributes(attrs, R.styleable.ExpandableFrameLayout, 0, 0)
        minHeight = customAttrs.getLayoutDimension(R.styleable.ExpandableFrameLayout_minExpandableHeight, 0)
        maxHeight = customAttrs.getLayoutDimension(R.styleable.ExpandableFrameLayout_maxExpandableHeight, 1024)
        midHeight = minHeight+ (maxHeight - minHeight) / 2
        gestureDetector = GestureDetector(context, this.gestureListener)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d(LOG_TAG, "Player touch $event")

        if (event.actionMasked == MotionEvent.ACTION_UP) {

                if (layoutParams.height >= midHeight && layoutParams.height < maxHeight)
                    animate(maxHeight)
                else if (layoutParams.height < midHeight && layoutParams.height > minHeight)
                    animate(minHeight)

        }

        return gestureDetector.onTouchEvent(event)

    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        //listen also to all child events in upper part
        if (event.y <= minHeight) {
            onTouchEvent(event)
            // need to block up event, which were dragging
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                return gestureListener.isDragging
            }

        }
        return super.onInterceptTouchEvent(event)
    }

    fun onPause() {
        if (layoutParams.height >= minHeight) {
            layoutParams?.height = minHeight
        }

    }


}


