package eu.zderadicka.audioserve.utils

import android.content.Context
import android.support.v4.media.session.PlaybackStateCompat
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel


private const val LOG_TAG = "Utils"

// this is actually same as PreferenceManager.getDefaultSharedPreferences, but shorter and testing friendly as context can be mocked
fun defPrefs(context: Context) =
        context.getSharedPreferences(
context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE)

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

@Throws(IOException::class)
fun copyFile(sourceFile: File, destFile: File) {
    if (!destFile.exists()) {
        destFile.parentFile.mkdirs()
        destFile.createNewFile()
    }

    var source: FileChannel? = null
    var destination: FileChannel? = null

    try {
        source = FileInputStream(sourceFile).getChannel()
        destination = FileOutputStream(destFile).getChannel()
        destination!!.transferFrom(source, 0, source!!.size())
    } finally {
        if (source != null) {
            source!!.close()
        }
        if (destination != null) {
            destination!!.close()
        }
    }
}

fun splitExtension(name:String): Pair<String,String?> {
    val dotIndex = name.lastIndexOf(".")
    if (dotIndex>0) {
        return Pair(name.substring(0 until dotIndex),
                name.substring(dotIndex+1).let {
                    if (it.length>0) it else null
                })
    } else {
        return Pair(name, null)
    }
}

fun encodeUri(uri:String):String {
    //encoding of uri path is only needed for API level <= 23
    if (Build.VERSION.SDK_INT > 23) {
        return uri
    } else {
        val u = Uri.parse(uri)
        val p = u.encodedPath
        return u.buildUpon().path(p).build().toString()
    }
}

