package eu.zderadicka.audioserve.net

import android.net.Uri
import android.os.Handler
import android.util.Log
import eu.zderadicka.audioserve.data.RemotePositionResponse
import eu.zderadicka.audioserve.data.mediaIdToPositionPath
import eu.zderadicka.audioserve.data.parseRemotePositionResponse
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
                send(if (folderPath.isNullOrBlank()) "?" else folderPath)
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


