package eu.zderadicka.audioserve.utils

import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import java.io.File

private const val LOG_TAG = "Utils"

fun isStoppedOrDead(state: PlaybackStateCompat): Boolean {
    return state.state == PlaybackStateCompat.STATE_NONE
            || state.state == PlaybackStateCompat.STATE_STOPPED
}

fun ifStoppedOrDead(state: PlaybackStateCompat?, then: ()-> Unit, else_: (() -> Unit)? = null) {
    if (state == null) return
    if (isStoppedOrDead(state)) {
        then()
    } else if (else_ != null) {
        else_()
    }
}


