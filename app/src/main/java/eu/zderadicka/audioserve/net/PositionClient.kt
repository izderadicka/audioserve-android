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
    SocketError,
    CanceledByNext,
    NotConnected,
    Timeout,
    InvalidResponse
}

private enum class ClientState {
    Opening,
    Ready,
    Closing,
    Closed
}

private typealias PendingReceive = (RemotePositionResponse?, PositionClientError?) -> Unit

class PositionClient(private val serverUrl: String, private val token: String, private val group: String?) {

    private var socket: WebSocket? = null
    private val client = OkHttpClient()
    private val listener = SocketListener()
    private var lastFile: String? = null
    private var pendingQuery: PendingQuery? = null
    private var pendingPosition: PendingPosition? = null
    private val handler = Handler()
    private var state = ClientState.Closed

    private fun finishPendingQuery(err: PositionClientError) {
        pendingQuery?.pendingReceive?.invoke(null, err)
        pendingQuery = null
    }

    private val timeout = Runnable {
        pendingQuery?.apply {
            finishPendingQuery(PositionClientError.Timeout)
        }
    }

    private fun setTimeout() {
        handler.postDelayed(timeout, TIMEOUT_DURATION)
    }

    private fun cancelTimeout() {
        handler.removeCallbacks(timeout)
    }

    private fun resetTimeout() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_DURATION)

    }

    val ready: Boolean
        get() = state == ClientState.Ready

    private val reopen = Runnable {
        if (state != ClientState.Opening) {
            if (state == ClientState.Closing) {
                // terminate immediately old socket
                socket?.apply{
                    cancel()
                }
            }
            open()
        }
    }

    fun open() {
        if (group.isNullOrBlank()) return
        state = ClientState.Opening
        val parsedUri = Uri.parse(serverUrl)
        val socketUri = parsedUri.buildUpon()
                .scheme(if (parsedUri.scheme == "http") "ws" else "wss")
                .appendPath("position")
        val req = Request.Builder()
                .url(socketUri.toString())
                .header("Authorization", "Bearer $token")
                .build()
        lastFile = null
        socket = client.newWebSocket(req, listener)
    }

    fun sendPosition(filePath: String?, position: Double) {
        if (!ready) {
            pendingPosition = PendingPosition(filePath, position)
            handler.post(reopen)
            Log.d(LOG_TAG, "Web socket is not ready, requesting reopen")
            return
        }
        if (filePath == null) {
            Log.w(LOG_TAG, "Null filePath in sendPosition")
            return
        }
        socket?.apply {
            val msg = encodeMessage(filePath, position)
            send(msg)
        }
    }

    fun mediaIdToFolderPath(mediaId: String): String {
        return File(mediaIdToPositionPath(mediaId, group ?: "group")!!).parent!!
    }

    fun folderIdToFolderPath(folderId: String): String? {
        return mediaIdToPositionPath(folderId, group ?: "group")
    }

    fun sendQuery(folderPath: String?, cb: PendingReceive) {
        finishPendingQuery(PositionClientError.CanceledByNext)
        cancelTimeout()
        pendingQuery = PendingQuery(cb, folderPath, System.currentTimeMillis())
        setTimeout()
        if (!ready) {
            handler.post(reopen)
        } else {
            socket?.apply {
                send(folderPath ?: group!!)
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

    private fun encodeMessage(filePath: String, position: Double): String =
            lastFile.let {
                fun fmt(p: Double) = "%.3f".format(java.util.Locale.US, p)
                return if (it == filePath) {
                    "${fmt(position)}|"
                } else {
                    lastFile = filePath
                    val normedPath = mediaIdToPositionPath(filePath, group!!)
                    "${fmt(position)}|${normedPath}"
                }
            }


    fun close(): Boolean {
        handler.removeCallbacks(reopen)
        return socket?.close(NORMAL_CLOSE, null)?: false

    }

    inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(LOG_TAG, "Socket opened")
            handler.removeCallbacks(reopen)
            state = ClientState.Ready

            pendingPosition?.apply {
                // TODO what will be best expiration time?
                if ( (System.currentTimeMillis() - timestamp) < 5*60*1000) { // only send if it is resent
                    sendPosition(filePath, position)
                }
                pendingPosition = null
            }

            pendingQuery?.apply{
                resendQuery()
            }

        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(LOG_TAG, "Socket Error: $t  pending query: $pendingQuery")
            state = ClientState.Closed
            socket = null
            pendingQuery?.also {
                //try reopen socket if there is pending query, but only on first error
                if (it.errors == 0) {
                    it.errors += 1
                    resetTimeout()
                    handler.postDelayed(reopen, 1000)

                } else {
                    // just gave up - report back error
                    Log.e(LOG_TAG, "Query failed with $t")
                        finishPendingQuery(PositionClientError.SocketError)

                }
            }

        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(LOG_TAG, "Socket closing $code:$reason")
            state = ClientState.Closing
        }


        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(LOG_TAG, "Got message $text")
            cancelTimeout()
            try {
                val res = parseRemotePositionResponse(text)
                pendingQuery?.pendingReceive?.invoke(res, null)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Got invalid response, error: $e")
                pendingQuery?.pendingReceive?.invoke(null, PositionClientError.InvalidResponse)
            }
            pendingQuery = null

        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(LOG_TAG, "Socket closed $code:$reason")
            state = ClientState.Closed
            socket = null
        }

    }

    inner class PendingPosition(val filePath: String?, val position: Double) {
        val timestamp: Long = System.currentTimeMillis()
        override fun toString(): String {
            return "PendingPosition filePath = $filePath, position = $position"
        }
    }

    inner class PendingQuery(
            val pendingReceive: PendingReceive,
            val query: String?,
            val timeStamp: Long,
            var errors: Int = 0
    ) {
        override fun toString(): String {
            return "PendingQuery ts = $timeStamp, errors = $errors, query=$query"
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
    return MediaBrowserCompat.MediaItem(descBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
}


