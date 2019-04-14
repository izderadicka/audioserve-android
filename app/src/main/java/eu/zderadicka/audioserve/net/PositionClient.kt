package eu.zderadicka.audioserve.net

import android.net.Uri
import android.util.Log
import eu.zderadicka.audioserve.data.mediaIdToPositionPath
import okhttp3.*

private const val LOG_TAG = "PositionClient"
private const val NORMAL_CLOSE = 1000

class PositionClient(val serverUrl:String, val token:String, val group: String?) {

    private var socket:WebSocket? = null
    private val client = OkHttpClient()
    private val listener = SocketListener()
    private var lastFile:String? = null

    fun open() {
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
        socket?.close(NORMAL_CLOSE, null)
    }

    inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(LOG_TAG, "Socket opened")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(LOG_TAG, "Socket Error: $t")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(LOG_TAG, "Socket closing $code:$reason")
        }



        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(LOG_TAG, "Got message $text")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(LOG_TAG, "Socket closed $code:$reason")
            socket = null
        }

    }

}


