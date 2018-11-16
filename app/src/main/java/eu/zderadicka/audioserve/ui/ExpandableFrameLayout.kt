package eu.zderadicka.audioserve.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import eu.zderadicka.audioserve.R
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

    val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent?): Boolean {
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
            Log.d(LOG_TAG, "Fling with velocity $velocityY")

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

            if (velocityY < 0 && height < maxHeight) {
                animate(maxHeight)

            } else if (velocityY > 0 && height > minHeight) {
                animate(minHeight)
            }

            return true
        }

//        override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
//            val dist = e1.y - e2.y
//            Log.d(LOG_TAG, "Scroll in Y $dist")
//
//            if (dist>0 && height < maxHeight) {
//                layoutParams.height = minHeight+dist.roundToInt()
//                requestLayout()
//            } else if (dist < 0 && height> minHeight) {
//                layoutParams.height = maxHeight + dist.roundToInt()
//                requestLayout()
//            }
//            return true
//        }





    }

    init {

        val customAttrs = context.theme.obtainStyledAttributes(attrs, R.styleable.ExpandableFrameLayout, 0, 0)
        minHeight = customAttrs.getLayoutDimension(R.styleable.ExpandableFrameLayout_minExpandableHeight, 0)
        maxHeight = customAttrs.getLayoutDimension(R.styleable.ExpandableFrameLayout_maxExpandableHeight, 1024)
        gestureDetector = GestureDetector(context, this.gestureListener)




    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d(LOG_TAG, "Player touch $event")

        return gestureDetector.onTouchEvent(event)

    }


}


