package dev.owntv.launcher.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.owntv.launcher.ui.render.NetworkState

/** The device's current connection, following the system's default network. */
@Composable
fun rememberNetworkState(): NetworkState {
    val context = LocalContext.current
    val connectivity = remember(context) { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var state by remember { mutableStateOf(connectivity.currentState()) }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                state = capabilities.toState()
            }

            override fun onLost(network: Network) {
                state = NetworkState.Offline
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    return state
}

private fun ConnectivityManager.currentState(): NetworkState =
    activeNetwork?.let(::getNetworkCapabilities)?.toState() ?: NetworkState.Offline

private fun NetworkCapabilities.toState(): NetworkState = when {
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.Ethernet
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.Wifi
    else -> NetworkState.Offline
}
