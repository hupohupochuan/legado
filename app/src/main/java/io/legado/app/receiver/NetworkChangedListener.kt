package io.legado.app.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import splitties.systemservices.connectivityManager

/**
 * 监测网络变化
 */
class NetworkChangedListener(private val context: Context) {

    var onNetworkChanged: (() -> Unit)? = null

    private val networkCallback: ConnectivityManager.NetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged?.invoke()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun register() {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    fun unRegister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

}