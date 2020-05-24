package eu.zderadicka.audioserve.net

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import eu.zderadicka.audioserve.data.*
import okhttp3.*
import java.io.File

private const val LOG_TAG = "PositionClient"
private const val NORMAL_CLOSE = 1000
private const val TIMEOUT_DURATION = 2_000L

enum class PositionClientError {
    SocketError,
    CanceledByNext,
    NotConnected,
    Timeout,
    InvalidResponse
}

private typealias PendingReceive = (RemotePositionResponse?, PositionClientError?) -> Unit

class PositionClient(val serverUrl:String, val token:String, val group: String?) {

    private var socket:WebSocket? = null
    private val client = OkHttpClient()
    private val listener = SocketListener()
    private var lastFile:String? = null
    private var pendingQuery: PendingQuery? = null
    private val handler = Handler()
    private var closed = false

    private fun finishPendingQuery(err: PositionClientError) {
        pendingQuery?.pendingReceive?.invoke(null, err)
        pendingQuery = null
    }

    private val timeout = object: Runnable {
        override fun run() {
            pendingQuery?.apply {
               if (errors>0) {
                   finishPendingQuery(PositionClientError.Timeout)
               } else {
                   // try to open the websocketsocket
                   errors+=1;
                   if (! closed) {
                       //extendTimeout for this pending query
                       setTimeout()
                       close(stayClosed = false)
                       open()

                   } else {
                       finishPendingQuery(PositionClientError.Timeout)
                   }
               }
            }

        }

    }

    private fun setTimeout() {
        handler.postDelayed(timeout, TIMEOUT_DURATION)
    }

    private fun cancelTimeout() {
        handler.removeCallbacks(timeout)
    }

    private val reopen = object : Runnable   {
        override fun run() {
            open()
        }
    }

    fun open() {
        closed = false
        if (group.isNullOrBlank()) return
        val parsedUri = Uri.parse(serverUrl)
        val socketUri = parsedUri.buildUpon()
                .scheme(if (parsedUri.scheme == "http") "ws" else "wss")
                .appendPath("position")
        val req = Request.Builder()
                .url(socketUri.toString())
                .header("Authorization", "Bearer $token")
                .build()
        lastFile = null
        socket =  client.newWebSocket(req, listener)
    }

    fun sendPosition(filePath: String?, position: Double) {
        if (filePath == null) {
            Log.w(LOG_TAG, "Null filePath in sendPostion")
            return
        }
        socket?.apply {
            val msg = encodeMessage(filePath,position)
            send(msg)
        }

    }

    fun mediaIdToFolderPath(mediaId:String) :String {
        return File(mediaIdToPositionPath(mediaId, group?:"group")).parent
    }

    fun folderIdToFolderPath(folderId:String) :String? {
        return mediaIdToPositionPath(folderId, group?:"group")
    }

    fun sendQuery(folderPath:String?, cb: PendingReceive) {
        finishPendingQuery(PositionClientError.CanceledByNext)
        cancelTimeout()
        if (socket == null) {
            cb(null, PositionClientError.NotConnected)
        } else {
            socket?.apply {
                pendingQuery = PendingQuery(cb, folderPath, System.currentTimeMillis())
                send(folderPath?: group!!)
                setTimeout()
            }
        }

    }

    private fun resendQuery() {
        cancelTimeout()
        socket?.apply {
            pendingQuery?.apply {
                pendingQuery = PendingQuery(pendingReceive, query, System.currentTimeMillis())
                send(query ?: group!!)
                setTimeout()
            }
        }
    }

    private fun encodeMessage(filePath: String, position: Double):String =
        lastFile.let {
            fun fmt(p:Double) = "%.3f".format(java.util.Locale.US, p)
            return if (it == filePath) {
                "${fmt(position)}|"
            } else {
                lastFile = filePath
                val normedPath = mediaIdToPositionPath(filePath, group!!)
                "${fmt(position)}|${normedPath}"
            }
        }


    fun close(stayClosed:Boolean = true) {
        handler.removeCallbacks(reopen)
        closed = stayClosed
        socket?.close(NORMAL_CLOSE, null)
        socket=null
    }

    inner class SocketListener : WebSocketListener() {
        private val initialErrorRetryInterval: Long = 1_000
        private val maxErrorRetryInterval: Long = 60_000
        private var errorRetryInterval: Long = initialErrorRetryInterval

        private fun resetErrorInterval() {
            errorRetryInterval = initialErrorRetryInterval
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(LOG_TAG, "Socket opened")
            resetErrorInterval()
            //cancelTimeout()
            handler.removeCallbacks(reopen);

            pendingQuery?.also {
                val currentTime = System.currentTimeMillis()
                if (currentTime - it.timeStamp < 2 * TIMEOUT_DURATION)  resendQuery()
                    else finishPendingQuery(PositionClientError.Timeout)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(LOG_TAG, "Socket Error: $t  pending query: $pendingQuery")
            pendingQuery?.also {
                //extend timeout, but only if it is first error
                if (it.errors == 0) {
                    cancelTimeout()
                    setTimeout()
                } else {
                    it.errors+=1
                }
            }
            // try reopen if socket is lost
            if (!closed) {
                handler.postDelayed(reopen, errorRetryInterval)
                if (errorRetryInterval < maxErrorRetryInterval) errorRetryInterval+= initialErrorRetryInterval
            }

        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(LOG_TAG, "Socket closing $code:$reason")
        }



        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(LOG_TAG, "Got message $text")
            resetErrorInterval()
            cancelTimeout()
            try {
                val res = parseRemotePositionResponse(text)
                pendingQuery?.pendingReceive?.invoke(res, null)
            }
            catch (e:Exception) {
                Log.e(LOG_TAG, "Got invalid response, error: $e")
                pendingQuery?.pendingReceive?.invoke(null, PositionClientError.InvalidResponse)
            }
            pendingQuery = null

        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(LOG_TAG, "Socket closed $code:$reason")
            socket = null
        }

    }

    inner class PendingQuery(
            val pendingReceive:PendingReceive,
            val query: String?,
            val timeStamp: Long,
            var errors: Int = 0
    ) {
        override fun toString(): String {
            return "PendigQuery ts = $timeStamp, errors = $errors, query=$query"
        }
    }

}

fun positionToMediaItem(pos: RemotePosition): MediaBrowserCompat.MediaItem {
    val (folder, collection) = splitPositionFolder(pos.folder)
    val name = normTitle(File(pos.file).nameWithoutExtension)
    var mediaId = "audio/$folder/${pos.file}"
    if (collection > 0) mediaId = "$collection/$mediaId"
    val extras = Bundle()
    val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(name)
            .setSubtitle(folder)

    //extras.putLong(METADATA_KEY_DURATION, c.getLong(c.getColumnIndex(RecentEntry.COLUMN_DURATION)))
    extras.putLong(METADATA_KEY_LAST_POSITION, (pos.position * 1000).toLong())
    extras.putLong(METADATA_KEY_LAST_LISTENED_TIMESTAMP, pos.timestamp)
    extras.putBoolean(METADATA_KEY_IS_BOOKMARK, true)
    extras.putBoolean(METADATA_KEY_IS_REMOTE_POSITION, true)

    descBuilder.setExtras(extras)
    val item = MediaBrowserCompat.MediaItem(descBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    return item
}


