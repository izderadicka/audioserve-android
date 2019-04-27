package eu.zderadicka.audioserve.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val LOG_TAG  = "ConnectivityListener"

typealias ConnectivityChangeListener = (Boolean) -> Unit

class ConnectivityMonitor private constructor(context: Context) {



    private var isConnected = false
    private var nets = HashSet<Network>()
    private val listeners = HashSet<ConnectivityChangeListener>()
    private val handler = Handler(Looper.getMainLooper())
    private val updateStatus = object: Runnable {
        override fun run() {
            for (l in listeners) l(isConnected)
        }

    }

    private fun delayedUpdate(isConnected: Boolean) {
        handler.removeCallbacks(updateStatus)
        this.isConnected = isConnected
        handler.postDelayed(updateStatus, 300)
    }

    private val networkListener = object: ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            super.onLost(network)
            nets.remove(network)
            Log.d(LOG_TAG, "network is lost $network - remaining nets - ${nets.size}")
            delayedUpdate(!nets.isEmpty())

        }

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            nets.add    (network)
            Log.d(LOG_TAG, "Some network is available $network - remaining nets - ${nets.size}")
            delayedUpdate(true)
        }

    }

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        cm.registerNetworkCallback(net, networkListener)
    }
    
    fun addListener(l:ConnectivityChangeListener) {
        listeners.add(l)
    }
    
    fun removeListener(l: ConnectivityChangeListener) {
        listeners.remove(l)
    }

    companion object {
        var instance: ConnectivityMonitor? = null
        @JvmStatic
        fun getInstance(context: Context): ConnectivityMonitor =
                synchronized(this) {
                    if (instance == null) instance = ConnectivityMonitor(context.applicationContext)
                    instance!!

                }
    }
}