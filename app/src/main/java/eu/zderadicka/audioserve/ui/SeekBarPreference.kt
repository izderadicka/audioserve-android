package eu.zderadicka.audioserve.ui

import android.content.Context
import android.content.res.TypedArray
import android.os.Parcel
import android.os.Parcelable
import android.preference.Preference
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView

import eu.zderadicka.audioserve.R
import eu.zderadicka.audioserve.utils.SpeedHelper
import kotlin.math.roundToInt

class SeekBarPreference//    public SeekBarPreference(
//            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
//        super(context, attrs, defStyleAttr, defStyleRes);
//        TypedArray a = context.obtainStyledAttributes(
//                attrs, com.android.internal.R.styleable.ProgressBar, defStyleAttr, defStyleRes);
//        setMax(a.getInt(com.android.internal.R.styleable.ProgressBar_max, mMax));
//        a.recycle();
//        a = context.obtainStyledAttributes(attrs,
//                com.android.internal.R.styleable.SeekBarPreference, defStyleAttr, defStyleRes);
//        final int layoutResId = a.getResourceId(
//                com.android.internal.R.styleable.SeekBarPreference_layout,
//                com.android.internal.R.layout.preference_widget_seekbar);
//        a.recycle();
//        setLayoutResource(layoutResId);
//    }
//    public SeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
//        this(context, attrs, defStyleAttr, 0);
//    }


@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs), OnSeekBarChangeListener {
    private var mProgress: Int = 0

    private var mTrackingTouch: Boolean = false

    var progress: Int
        get() = mProgress
        set(progress) = setProgress(progress, true)

        init {
            layoutResource = R.layout.preference_seekbar;
        }

    var valueView: TextView? = null

    override fun onBindView(view: View) {
        super.onBindView(view)
        val seekBar = view.findViewById<View>(
                R.id.seekbar) as SeekBar
        seekBar.setOnSeekBarChangeListener(this)
        seekBar.max = SpeedHelper.max
        seekBar.progress = mProgress
        seekBar.isEnabled = isEnabled

        valueView = view.findViewById(R.id.position)
        SpeedHelper.updateText(valueView, progress)
    }

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        progress = SpeedHelper.valueToProgress(if (restoreValue)
            getPersistedFloat(  1.0F)
        else
        defaultValue?.toString()?.toFloat() ?: 1.0F)



    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, 0)
    }

    fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        val seekBar = v.findViewById<View>(R.id.seekbar) as SeekBar ?: return false
        return seekBar.onKeyDown(keyCode, event)
    }



    private fun setProgress(progress: Int, notifyChanged: Boolean) {
        var progress = progress
        if (progress > SpeedHelper.max) {
            progress = SpeedHelper.max
        }
        if (progress < 0) {
            progress = 0
        }
        if (progress != mProgress) {
            mProgress = progress
            persistFloat(SpeedHelper.progressToValue(progress))
            if (notifyChanged) {
                notifyChanged()
            }
        }
    }

    /**
     * Persist the seekBar's progress value if callChangeListener
     * returns true, otherwise set the seekBar's progress to the stored value
     */
    internal fun syncProgress(seekBar: SeekBar) {
        val progress = seekBar.progress
        if (progress != mProgress) {
            if (callChangeListener(progress)) {
                setProgress(progress, false)
            } else {
                seekBar.progress = mProgress
            }
        }
    }

    override fun onProgressChanged(
            seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        SpeedHelper.updateText(valueView, progress)
        if (fromUser && !mTrackingTouch) {
            syncProgress(seekBar)
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        mTrackingTouch = true
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        mTrackingTouch = false
        if (seekBar.progress != mProgress) {
            syncProgress(seekBar)
        }
    }

    override fun onSaveInstanceState(): Parcelable {
        /*
         * Suppose a client uses this preference type without persisting. We
         * must save the instance state so it is able to, for example, survive
         * orientation changes.
         */
        val superState = super.onSaveInstanceState()
        if (isPersistent) {
            // No need to save instance state since it's persistent
            return superState
        }
        // Save the instance state
        val myState = SavedState(superState)
        myState.progress = mProgress
        return myState
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state.javaClass != SavedState::class.java) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state)
            return
        }
        // Restore the instance state
        val myState = state as SavedState
        super.onRestoreInstanceState(myState.superState)
        mProgress = myState.progress
        notifyChanged()
    }

    /**
     * SavedState, a subclass of [Preference.BaseSavedState], will store the state
     * of MyPreference, a subclass of Preference.
     *
     *
     * It is important to always call through to super methods.
     */
    private class SavedState : Preference.BaseSavedState {
        internal var progress: Int = 0

        constructor(source: Parcel) : super(source) {
            // Restore the click counter
            progress = source.readInt()
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            // Save the click counter
            dest.writeInt(progress)
        }

        constructor(superState: Parcelable) : super(superState) {}

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(`in`: Parcel): SavedState {
                    return SavedState(`in`)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }
}
