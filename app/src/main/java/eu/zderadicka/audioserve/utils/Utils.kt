package eu.zderadicka.audioserve.utils

import android.content.Context
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.net.NetworkInfo
import android.net.ConnectivityManager



private const val LOG_TAG = "Utils"

enum class ConnectionState {
    Unknown,
    Disconnected,
    Wifi,
    Cellular,
    Other;

    companion object {
        fun fromNetworkInfo(info: NetworkInfo?): ConnectionState =
                if (info == null || !info.isConnectedOrConnecting)   ConnectionState.Disconnected
                else when(info.type) {
                    ConnectivityManager.TYPE_WIFI -> Wifi
                    ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_MOBILE_DUN -> Cellular
                    else  -> Other
                }
    }
}

fun connectionState(context: Context):ConnectionState {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    try {
        val activeNetwork = cm.activeNetworkInfo
        return ConnectionState.fromNetworkInfo(activeNetwork)
    } catch(e: Exception) {
        return ConnectionState.Unknown
    }
}

fun isNetworkConnected(context:Context):Boolean {

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    try {
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting
    } catch(e: Exception) {
        return true
    }
}



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


