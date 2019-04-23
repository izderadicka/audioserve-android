package eu.zderadicka.audioserve.net

import android.net.Uri
import android.os.Bundle
import android.os.Handler
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
    Socket,
    CanceledByNext,
    NotReady,
    Timeout,
    InvalidResponse
}

class PositionClient(val serverUrl:String, val token:String, val group: String?) {

    private var socket:WebSocket? = null
    private val client = OkHttpClient()
    private val listener = SocketListener()
    private var lastFile:String? = null
    private var pendingReceive: ((RemotePositionResponse?, PositionClientError?) -> Unit)? = null
    private val handler = Handler()
    private var closed = false

    private fun finishPendingReceive(err: PositionClientError) {
        pendingReceive?.invoke(null, err)
        pendingReceive = null
    }

    private val timeout = object: Runnable {
        override fun run() {
            finishPendingReceive(PositionClientError.Timeout)
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

    fun sendQuery(folderPath:String?, cb: (RemotePositionResponse?, PositionClientError?) -> Unit) {
        finishPendingReceive(PositionClientError.CanceledByNext)
        handler.removeCallbacks(timeout)
        if (socket == null) {
            cb(null, PositionClientError.NotReady)
        } else {
            socket?.apply {
                pendingReceive = cb
                send(folderPath?: group!!)
                handler.postDelayed(timeout, TIMEOUT_DURATION)
            }
        }

    }

    private fun encodeMessage(filePath: String, position: Double):String =
        lastFile.let {
            fun fmt(p:Double) = "%.3f".format(p)
            return if (it == filePath) {
                "${fmt(position)}|"
            } else {
                lastFile = filePath
                val normedPath = mediaIdToPositionPath(filePath, group!!)
                "${fmt(position)}|${normedPath}"
            }
        }


    fun close() {
        closed = true
        socket?.close(NORMAL_CLOSE, null)
    }

    inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(LOG_TAG, "Socket opened")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(LOG_TAG, "Socket Error: $t")
            // try reopen if socket is lost
            if (!closed) {
                handler.postDelayed({
                    open()
                }, 1000)
            }

        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(LOG_TAG, "Socket closing $code:$reason")
        }



        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(LOG_TAG, "Got message $text")
            handler.removeCallbacks(timeout)
            try {
                val res = parseRemotePositionResponse(text)
                pendingReceive?.invoke(res, null)
            }
            catch (e:Exception) {
                Log.e(LOG_TAG, "Got invalid response, error: $e")
                pendingReceive?.invoke(null, PositionClientError.InvalidResponse)
            }
            pendingReceive = null

        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(LOG_TAG, "Socket closed $code:$reason")
            socket = null
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


