package com.music.spotui.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Offline Mode: only downloaded songs and Local Files play.
 *
 * Two inputs:
 *  - a manual switch the user toggles in Settings (persisted), and
 *  - the actual network: when Android reports no validated internet connection, the
 *    app enters Offline Mode on its own.
 *
 * [enabled] is the effective state (manual OR no validated network). Automatic downloads
 * and remote audio-source resolution stay off while it is enabled. Playback never falls
 * back to Spotify or an extension stream.
 */
object OfflineMode {
    private const val PREFS = "myspoti_prefs"
    internal const val KEY = "OfflineMode"

    var enabled by mutableStateOf(false)
        private set

    private var manualEnabled = false
    private var networkAvailable = true

    private var context: Context? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun init(c: Context) {
        if (context != null) return
        val app = c.applicationContext
        context = app
        manualEnabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        networkAvailable = isNetworkAvailable(app)
        enabled = manualEnabled || !networkAvailable
        registerNetworkCallback(app)
    }

    fun set(on: Boolean) {
        if (manualEnabled != on) {
            manualEnabled = on
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY, on)?.apply()
        }
        updateEnabled()
    }

    /** Playback that must not leave the phone (this switch or the data saver applying). */
    val localOnly: Boolean get() = enabled || com.music.spotui.data.DataSaver.active

    private fun registerNetworkCallback(app: Context) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable = isNetworkAvailable(app)
                updateEnabled()
            }

            override fun onLost(network: Network) {
                networkAvailable = isNetworkAvailable(app)
                updateEnabled()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                networkAvailable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                updateEnabled()
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        networkAvailable = isNetworkAvailable(app)
        updateEnabled()
    }

    private fun isNetworkAvailable(app: Context): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun updateEnabled() {
        val newEnabled = manualEnabled || !networkAvailable
        if (newEnabled == enabled) return
        val wasEnabled = enabled
        enabled = newEnabled
        main.post {
            if (newEnabled && !wasEnabled) applied(true)
            else if (!newEnabled && wasEnabled) applied(false)
        }
    }

    private fun applied(on: Boolean) {
        if (on) {
            runCatching { app.myspoti.host.android.MyspotiRuntime.suspendPlayer() }
            OfflineDownloader.holdTransfers()
            PodcastDownloader.holdTransfers()
            com.music.spotui.di.SongPlayer.onOfflineModeOn()
        } else {
            if (!com.music.spotui.data.DataSaver.active) {
                runCatching { app.myspoti.host.android.MyspotiRuntime.resume() }
            }
            if (!com.music.spotui.data.DataSaver.metered) {
                context?.let { OfflineCollections.onUnmetered(it) }
            }
        }
    }
}
